package app.snipnet.desktop.court

import androidx.compose.ui.graphics.ImageBitmap
import app.snipnet.desktop.state.StateHolder
import app.snipnet.desktop.video.VideoEngine
import app.snipnet.desktop.video.VideoPlayer
import app.snipnet.shared.api.ApiError
import app.snipnet.shared.model.Court
import app.snipnet.shared.model.CourtSuggestion
import app.snipnet.shared.model.Point
import app.snipnet.shared.model.Roi
import app.snipnet.shared.store.ProjectStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/**
 * @property durationMs length of the video, the range of the frame scrubber.
 * @property positionMs media time of the frame shown.
 * @property netPoint the marked net, null until the user clicks.
 * @property roi the court rectangle; set together with the net point and adjustable afterwards.
 * @property loadError why the frame preview is unavailable (unknown project, unreadable file).
 * @property saveError why the last save failed; the court is still stored locally in that case.
 * @property prefilledFromDetection whether [netPoint] and [roi] come from automatic net detection and still await the
 *   user's confirmation; cleared as soon as the user changes either.
 */
data class CourtSelectionState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val frame: ImageBitmap? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val netPoint: Point? = null,
    val roi: Roi? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    val prefilledFromDetection: Boolean = false,
) {
    val canSave: Boolean get() = netPoint != null && roi != null && !saving
}

/**
 * Drives the court and net selection screen for one local project: shows a scrubbable frame of the video, keeps the
 * net point and the ROI, and saves the result locally and to the server.
 *
 * The court is written to the local [projectStore] first, so it survives a failing network. It is sent to the server
 * through [uploadCourt] only when the project already has a remote video id; otherwise the upload that registers the
 * video is expected to send it later.
 *
 * @param projectId the local project id carried by `Screen.CourtSelection`.
 * @param uploadCourt `PUT /v1/videos/:id/court`, called with the remote video id.
 * @param loadSuggestion fetches the server's automatic court suggestion for a remote video id (`Video.courtSuggestion`
 *   in `docs/api.md`). It is used only for projects without a saved court and only applied at high confidence; any
 *   [ApiError] is ignored because a missing suggestion just means the user marks the court by hand.
 * @param onSaved called after a successful save.
 */
class CourtSelectionStateHolder(
    private val projectId: String,
    private val projectStore: ProjectStore,
    private val engine: VideoEngine,
    private val uploadCourt: suspend (remoteVideoId: String, court: Court) -> Unit,
    private val onSaved: () -> Unit,
    private val loadSuggestion: suspend (remoteVideoId: String) -> CourtSuggestion? = { null },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder<CourtSelectionState>(CourtSelectionState(), dispatcher) {
    private var player: VideoPlayer? = null
    private var closed = false

    /** Once the user has resized or moved the ROI, later net clicks must not throw that work away. */
    private var roiAdjusted = false

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val project = projectStore.get(projectId)
        if (project == null) {
            update { it.copy(loading = false, loadError = "This project no longer exists.") }
            return
        }
        val file = pickSource(project.proxyPath, project.originalPath)
        try {
            val opened = engine.open(file)
            if (closed) {
                opened.close()
                return
            }
            player = opened
            val existing = project.court
            roiAdjusted = existing != null
            update {
                it.copy(
                    loading = false,
                    durationMs = opened.info.durationMs,
                    netPoint = existing?.netPoint,
                    roi = existing?.roi,
                )
            }
            scope.launch { opened.frames.collect { frame -> update { it.copy(frame = frame) } } }
            scrubTo(opened.info.durationMs / INITIAL_POSITION_DIVISOR, exact = true)
            val remoteId = project.remoteVideoId
            if (existing == null && remoteId != null) scope.launch { prefill(remoteId) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            update { it.copy(loading = false, loadError = "Could not open the video: ${e.message}") }
        }
    }

    /**
     * Pre-fills net and ROI from the server's suggestion when it is confident enough. The user may already have
     * clicked the net while the request was in flight; that choice always wins.
     */
    private suspend fun prefill(remoteVideoId: String) {
        val suggestion =
            try {
                loadSuggestion(remoteVideoId)
            } catch (e: ApiError) {
                null
            }
        if (suggestion == null || !suggestion.isHighConfidence) return
        update {
            if (it.netPoint != null || it.roi != null) {
                it
            } else {
                it.copy(
                    netPoint = suggestion.court.netPoint,
                    roi = suggestion.court.roi,
                    prefilledFromDetection = true,
                )
            }
        }
    }

    /**
     * Shows the frame at [positionMs]. While the scrubber is being dragged the nearest keyframe is enough and keeps it
     * responsive; on release the caller asks for the [exact] frame.
     */
    fun scrubTo(
        positionMs: Long,
        exact: Boolean,
    ) {
        val clamped = positionMs.coerceIn(0, state.value.durationMs)
        update { it.copy(positionMs = clamped) }
        player?.seek(clamped, exact)
    }

    /** Marks the net at [point]; the ROI follows with its default size unless the user has already adjusted it. */
    fun setNetPoint(point: Point) =
        update {
            val roi = if (roiAdjusted && it.roi != null) it.roi else CourtGeometry.defaultRoi(point)
            // A pre-filled ROI is not the user's own work, so a new net click may replace it like the default one.
            it.copy(netPoint = point, roi = roi, saveError = null, prefilledFromDetection = false)
        }

    fun setRoi(roi: Roi) {
        roiAdjusted = true
        update { it.copy(roi = roi, saveError = null, prefilledFromDetection = false) }
    }

    /** Restores the default ROI around the current net point. */
    fun resetRoi() {
        roiAdjusted = false
        update { current ->
            current.netPoint?.let { current.copy(roi = CourtGeometry.defaultRoi(it), prefilledFromDetection = false) }
                ?: current
        }
    }

    fun save() {
        val current = state.value
        val court = Court(roi = current.roi ?: return, netPoint = current.netPoint ?: return)
        if (current.saving) return
        update { it.copy(saving = true, saveError = null) }
        scope.launch {
            // A failing local write (full disk, locked database) must not leave the screen stuck in "saving" or
            // escape the scope as an uncaught exception on the UI thread.
            val remoteId =
                try {
                    projectStore.setCourt(projectId, court)
                    projectStore.get(projectId)?.remoteVideoId
                } catch (e: Exception) {
                    update {
                        it.copy(
                            saving = false,
                            saveError = "Could not save the court on this computer: ${e.message}",
                        )
                    }
                    return@launch
                }
            try {
                if (remoteId != null) uploadCourt(remoteId, court)
                update { it.copy(saving = false) }
                onSaved()
            } catch (e: ApiError) {
                val message =
                    if (e is ApiError.Network) {
                        "Saved on this computer, but the server could not be reached: ${e.message}"
                    } else {
                        "Saved on this computer, but the server did not accept it: ${e.message}"
                    }
                update { it.copy(saving = false, saveError = message) }
            }
        }
    }

    override fun close() {
        closed = true
        player?.close()
        super.close()
    }

    private fun pickSource(
        proxyPath: String?,
        originalPath: String,
    ): Path {
        // The proxy decodes much faster and shows the same picture the model will analyze.
        val proxy = proxyPath?.let { Path.of(it) }
        return if (proxy != null && Files.exists(proxy)) proxy else Path.of(originalPath)
    }

    private companion object {
        /** The first frame shown is a tenth into the video, where players are usually already on the court. */
        const val INITIAL_POSITION_DIVISOR = 10
    }
}
