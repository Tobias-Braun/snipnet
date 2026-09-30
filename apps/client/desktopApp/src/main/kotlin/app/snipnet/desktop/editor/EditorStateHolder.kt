package app.snipnet.desktop.editor

import androidx.compose.ui.graphics.toComposeImageBitmap
import app.snipnet.desktop.export.ExportMode
import app.snipnet.desktop.export.ExportOptions
import app.snipnet.desktop.export.ExportRequest
import app.snipnet.desktop.export.FfmpegRallyExporter
import app.snipnet.desktop.export.ProjectFiles
import app.snipnet.desktop.export.RallyExporter
import app.snipnet.desktop.export.exportRanges
import app.snipnet.desktop.state.StateHolder
import app.snipnet.desktop.video.VideoEngine
import app.snipnet.desktop.video.VideoPlayer
import app.snipnet.shared.api.ApiError
import app.snipnet.shared.editing.Edit
import app.snipnet.shared.editing.EditHistory
import app.snipnet.shared.editing.Timeline
import app.snipnet.shared.editing.Viewport
import app.snipnet.shared.editing.addFromMarks
import app.snipnet.shared.editing.delete
import app.snipnet.shared.editing.mergeSelection
import app.snipnet.shared.editing.playbackPlan
import app.snipnet.shared.editing.snap
import app.snipnet.shared.editing.snapTargets
import app.snipnet.shared.editing.split
import app.snipnet.shared.editing.toggleAccept
import app.snipnet.shared.editing.trimEnd
import app.snipnet.shared.editing.trimStart
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentSet
import app.snipnet.shared.model.SegmentSetKind
import app.snipnet.shared.store.PendingSave
import app.snipnet.shared.store.Project
import app.snipnet.shared.store.ProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong

/**
 * Drives the editor screen for one local project: loads the video, the segments (the saved draft, else the newest
 * model prediction) and the timeline media, and turns commands from the keyboard, the buttons and the timeline
 * gestures into state changes.
 *
 * All segment edits go through [EditHistory], so every one of them is undoable and recorded in the edit log. The
 * current segments are written to the project's local draft after each finished edit; the draft only holds the
 * accepted segments (the shape of the API), so segments rejected before a restart come back gone.
 *
 * Saving goes through the [saveQueue]: [save] snapshots the current segments and the edit log as a
 * [PendingSave] in the project store and uploads it as a new user segment set whose parent is the set the edits
 * started from. When the API cannot be reached the snapshot stays queued and is retried every [retryDelayMs] while
 * the editor is open (and by the queue itself afterwards); [EditorState.hasUnsavedChanges] drives the indicator.
 *
 * @param loadSets fetches all segment sets of a remote video, oldest first. An [ApiError] is treated like "no
 *   sets", because the editor stays usable offline with the local draft.
 * @param now clock for the timestamps of edit log entries.
 */
class EditorStateHolder(
    private val projectId: String,
    private val projectStore: ProjectStore,
    private val engine: VideoEngine,
    private val saveQueue: SaveQueue,
    private val loadSets: suspend (remoteVideoId: String) -> List<SegmentSet> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val exporter: RallyExporter = FfmpegRallyExporter(),
    private val retryDelayMs: Long = SaveQueue.RETRY_INTERVAL_MS,
) : StateHolder<EditorState>(EditorState(), dispatcher) {
    private var player: VideoPlayer? = null

    /** The original video file, which the export cuts; unlike the playback source it is never the proxy. */
    private var originalPath: Path? = null
    private var exportJob: Job? = null
    private var closed = false

    /** Identity of the trim drag in progress; edits with the same key collapse into one undo step. */
    private var trimGesture: Any? = null
    private var trimTarget: Pair<Long, SegmentEdge>? = null

    init {
        saveQueue.attach(projectId)
        scope.launch { load() }
        scope.launch { retryQueuedSave() }
    }

    /** Tries the queued save again while it is only waiting for the server (a refused save is not retried). */
    private suspend fun retryQueuedSave() {
        while (true) {
            delay(retryDelayMs)
            val current = state.value
            if (current.queued && current.syncError == null && !current.saving) flushQueuedSave()
        }
    }

    private suspend fun load() {
        saveQueue.settle()
        val project = projectStore.get(projectId)
        if (project == null) {
            update { it.copy(loading = false, loadError = "This project no longer exists.") }
            return
        }
        originalPath = Path.of(project.originalPath)
        try {
            val opened = engine.open(playbackSource(project))
            if (closed) {
                opened.close()
                return
            }
            player = opened
            val sets = fetchSets(project)
            val prediction = sets.lastOrNull { it.kind == SegmentSetKind.PREDICTION }
            val baseline = sets.lastOrNull { it.kind == SegmentSetKind.USER } ?: prediction
            val duration = opened.info.durationMs.coerceAtLeast(1)
            val draft = project.draftSegments
            val timeline =
                Timeline.fromSegments(duration, sanitizeSegments(duration, draft ?: baseline?.segments ?: emptyList()))
            val baseSetId = if (draft != null) project.baseSetId ?: baseline?.id else baseline?.id ?: project.baseSetId
            // Stored right away, so a draft edited in this session still knows its parent when the next start is offline.
            if (baseSetId != null && baseSetId != project.baseSetId) projectStore.setBaseSetId(projectId, baseSetId)
            update {
                it.copy(
                    loading = false,
                    history = EditHistory(timeline),
                    info = opened.info,
                    scores = prediction?.scores,
                    baseSetId = baseSetId,
                    priorEditLog = if (draft != null) project.draftEditLog else emptyList(),
                    savedSegments = baseline?.let { sanitizeSegments(duration, it.segments) },
                    queued = project.pendingSave != null,
                    savedFinal = baseline?.isFinal == true,
                )
            }
            projectStore.markOpened(projectId)
            if (project.pendingSave != null) scope.launch { flushQueuedSave() }
            startPlayerCollectors(opened)
            loadMedia(playbackSource(project), duration)
            opened.seek(0, exact = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            update { it.copy(loading = false, loadError = "Could not open the video: ${e.message}") }
        }
    }

    private suspend fun fetchSets(project: Project): List<SegmentSet> {
        val remoteId = project.remoteVideoId ?: return emptyList()
        return try {
            loadSets(remoteId)
        } catch (e: ApiError) {
            emptyList()
        }
    }

    /** The original plays best (full resolution, frame accurate); the proxy is the fallback when it moved. */
    private fun playbackSource(project: Project): Path {
        val original = Path.of(project.originalPath)
        val proxy = project.proxyPath?.let { Path.of(it) }
        return if (!Files.exists(original) && proxy != null && Files.exists(proxy)) proxy else original
    }

    private fun startPlayerCollectors(opened: VideoPlayer) {
        scope.launch { opened.frames.collect { frame -> update { it.copy(frame = frame) } } }
        scope.launch { opened.isPlaying.collect { playing -> update { it.copy(isPlaying = playing) } } }
        scope.launch { opened.rate.collect { rate -> update { it.copy(rate = rate) } } }
        scope.launch { opened.position.collect { position -> onPlayerPosition(position) } }
    }

    /** Thumbnails and waveform arrive in the background; failures just leave those rows empty. */
    private fun loadMedia(
        file: Path,
        durationMs: Long,
    ) {
        val count = (durationMs / THUMBNAIL_SPACING_MS).toInt().coerceIn(MIN_THUMBNAILS, MAX_THUMBNAILS)
        update { it.copy(thumbnailCount = count) }
        scope.launch {
            try {
                engine.thumbnails(file, count, THUMBNAIL_HEIGHT_PX).collect { thumbnail ->
                    val bitmap =
                        withContext(Dispatchers.IO) {
                            Image.makeFromEncoded(Files.readAllBytes(thumbnail.file)).toComposeImageBitmap()
                        }
                    update { it.copy(thumbnails = it.thumbnails + (thumbnail.index to bitmap)) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        scope.launch {
            try {
                val buckets = (durationMs / WAVEFORM_BUCKET_MS).toInt().coerceIn(1, MAX_WAVEFORM_BUCKETS)
                val waveform = engine.waveform(file, buckets)
                update { it.copy(waveform = waveform) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    /**
     * Follows the player while it plays: moves the playhead, keeps it in view and, in rallies-only mode, jumps over
     * everything that is not an accepted segment. While paused the holder owns the playhead, so a late position
     * update of an earlier seek cannot move it back.
     */
    private fun onPlayerPosition(position: Long) {
        val current = state.value
        if (!current.isPlaying) return
        setPlayhead(position)
        followPlayhead()
        if (!current.ralliesOnly) return
        val plan = current.timeline?.playbackPlan() ?: return
        val target = plan.nextPlayable(position)
        if (target == null) {
            player?.pause()
        } else if (target > position) {
            seekPlayer(target, exact = true)
        }
    }

    private fun setPlayhead(positionMs: Long) =
        update { s ->
            s.copy(
                history =
                    s.history?.updateView {
                        it.seek(positionMs)
                    },
            )
        }

    private fun followPlayhead() =
        update { s ->
            if (s.viewportWidthPx <= 0) return@update s
            val px = s.viewport.timeToPx(s.playheadMs)
            if (px in 0.0..s.viewportWidthPx) {
                s
            } else {
                val margin = (s.viewportWidthPx * FOLLOW_MARGIN).toLong()
                val scroll = s.playheadMs - (margin / s.viewport.pxPerMs).toLong()
                s.copy(viewport = s.viewport.copy(scrollMs = scroll).clampScroll(s.durationMs, s.viewportWidthPx))
            }
        }

    /** Runs [command], the single entry point shared by keyboard shortcuts and toolbar buttons. */
    fun perform(command: EditorCommand) {
        if (state.value.history == null) return
        handlers.getValue(command)()
    }

    /** One handler per command; a table instead of a big `when` keeps every command a single readable line. */
    private val handlers: Map<EditorCommand, () -> Unit> =
        mapOf(
            EditorCommand.TogglePlay to ::togglePlay,
            EditorCommand.JumpBack to { seek(state.value.playheadMs - JUMP_BACK_MS) },
            EditorCommand.Pause to ::pauseAndResetRate,
            EditorCommand.PlayFaster to ::playFaster,
            EditorCommand.StepBack to { stepFrames(-1) },
            EditorCommand.StepForward to { stepFrames(1) },
            EditorCommand.StepBackLong to { stepFrames(-LONG_STEP_FRAMES) },
            EditorCommand.StepForwardLong to { stepFrames(LONG_STEP_FRAMES) },
            EditorCommand.Split to { edit { split(playheadMs, now()) } },
            EditorCommand.Delete to { edit { delete(targetIds(), now()) } },
            EditorCommand.MarkIn to { update { it.copy(markInMs = it.playheadMs) } },
            EditorCommand.MarkOut to { update { it.copy(markOutMs = it.playheadMs) } },
            EditorCommand.AddFromMarks to ::addFromMarks,
            EditorCommand.Merge to { edit { mergeSelection(now()) } },
            EditorCommand.ToggleAccept to { edit { toggleAccept(targetIds(), now()) } },
            EditorCommand.NextRally to { jumpToNeighbour(forward = true) },
            EditorCommand.PreviousRally to { jumpToNeighbour(forward = false) },
            EditorCommand.Undo to { changeHistory { it.undo() } },
            EditorCommand.Redo to { changeHistory { it.redo() } },
            EditorCommand.ToggleRalliesOnly to { update { it.copy(ralliesOnly = !it.ralliesOnly) } },
            EditorCommand.Save to { save(markFinal = false) },
        )

    /**
     * Uploads the current segments as a new user segment set (`Save`), or as the final one (`Mark as final`). The
     * snapshot is queued in the project store before the upload starts, so it survives a failed upload or a crash.
     */
    fun save(markFinal: Boolean) {
        val current = state.value
        val timeline = current.timeline ?: return
        if (current.saving) return
        persistDraft()
        try {
            projectStore.setPendingSave(
                projectId,
                PendingSave(current.baseSetId, timeline.toApiSegments(), current.editLog, markFinal),
            )
        } catch (e: Exception) {
            update { it.copy(syncError = "Could not queue the save on this computer: ${e.message}") }
            return
        }
        update { it.copy(queued = true, syncError = null) }
        scope.launch { flushQueuedSave() }
    }

    private suspend fun flushQueuedSave() {
        update { it.copy(saving = true) }
        val result = saveQueue.flush(projectId, onSaved = ::applySaved)
        val stillQueued = projectStore.get(projectId)?.pendingSave != null
        update {
            when (result) {
                is FlushResult.Rejected -> it.copy(saving = false, queued = stillQueued, syncError = result.message)
                else -> it.copy(saving = false, queued = stillQueued)
            }
        }
    }

    /**
     * Continues from the set the server just accepted: it becomes the parent of the next save, and the history starts
     * over so the edit log only holds what was done after it. Runs right after the store was updated, without
     * suspending, so no edit can persist a stale draft in between.
     */
    private fun applySaved(saved: FlushResult.Saved) =
        update {
            it.copy(
                baseSetId = saved.set.id,
                priorEditLog = saved.remainingLog,
                history = it.history?.let { history -> EditHistory(history.timeline) },
                savedSegments = saved.set.segments,
                savedFinal = saved.set.isFinal,
                syncError = null,
            )
        }

    /** The segments an edit applies to: the selection, or the segment under the playhead when nothing is selected. */
    private fun Timeline.targetIds(): Set<Long> =
        selection.ifEmpty { segmentAt(playheadMs)?.let { setOf(it.id) } ?: emptySet() }

    fun togglePlay() {
        val p = player ?: return
        if (state.value.isPlaying) {
            p.pause()
            return
        }
        val current = state.value
        val plan = current.timeline?.playbackPlan()
        if (current.ralliesOnly && plan != null) {
            val target = plan.nextPlayable(current.playheadMs) ?: return
            if (target != current.playheadMs) seek(target)
        }
        p.play()
    }

    /** L: starts playback, and every further press while playing doubles the speed up to 4x (audio is muted then). */
    private fun playFaster() {
        val p = player ?: return
        if (!state.value.isPlaying) {
            p.setRate(1.0)
            togglePlay()
            return
        }
        p.setRate(if (state.value.rate >= MAX_RATE) 1.0 else state.value.rate * 2)
    }

    private fun pauseAndResetRate() {
        player?.pause()
        player?.setRate(1.0)
    }

    private fun stepFrames(frames: Int) {
        val frameMs = state.value.info?.frameDurationMs ?: return
        val index = (state.value.playheadMs / frameMs).roundToLong() + frames
        player?.pause()
        seek((index * frameMs).roundToLong())
    }

    /**
     * Moves the playhead (and the picture) to [timeMs] with a frame-accurate seek and scrolls the timeline when the
     * new position is outside the view, so keyboard seeks (frame steps, J, N/P) never leave the playhead off screen.
     */
    fun seek(timeMs: Long) {
        seekPlayer(timeMs.coerceIn(0, state.value.durationMs), exact = true)
        revealPlayhead()
    }

    /** Seek while the user drags the playhead: keyframe-only decoding keeps scrubbing responsive. */
    fun scrub(
        timeMs: Long,
        exact: Boolean,
    ) = seekPlayer(timeMs.coerceIn(0, state.value.durationMs), exact)

    private fun seekPlayer(
        timeMs: Long,
        exact: Boolean,
    ) {
        setPlayhead(timeMs)
        player?.seek(timeMs, exact)
    }

    private fun jumpToNeighbour(forward: Boolean) {
        val timeline = state.value.timeline ?: return
        val playhead = timeline.playheadMs
        val target =
            if (forward) {
                timeline.segments.firstOrNull { it.startMs > playhead }
            } else {
                timeline.segments.lastOrNull { it.startMs < playhead }
            } ?: return
        selectOnly(target.id)
        seek(target.startMs)
    }

    /** Selects the segment [id], seeks to its start and scrolls it into view; used by the side list. */
    fun jumpTo(id: Long) {
        val segment = state.value.timeline?.find(id) ?: return
        selectOnly(id)
        seek(segment.startMs)
    }

    private fun selectOnly(id: Long) = update { s -> s.copy(history = s.history?.updateView { it.select(setOf(id)) }) }

    /** Selects the segment [id]; with [additive] it is toggled in the current selection instead of replacing it. */
    fun select(
        id: Long,
        additive: Boolean,
    ) = update { s ->
        s.copy(
            history =
                s.history?.updateView { timeline ->
                    val ids = if (additive) timeline.selection.symmetricDifference(id) else setOf(id)
                    timeline.select(ids)
                },
        )
    }

    fun clearSelection() = update { s -> s.copy(history = s.history?.updateView { it.select(emptySet()) }) }

    /** Toggles the accepted flag of one segment, used by the side list checkbox. */
    fun toggleAccept(id: Long) = edit { toggleAccept(setOf(id), now()) }

    private fun addFromMarks() {
        val current = state.value
        val inMs = current.markInMs ?: return
        val outMs = current.markOutMs ?: return
        val before = current.history
        edit { addFromMarks(inMs, outMs, now()) }
        if (state.value.history !== before) update { it.copy(markInMs = null, markOutMs = null) }
    }

    /**
     * Starts dragging one edge of segment [id]. All [dragEdge] calls until [endDrag] merge into one undo step.
     */
    fun beginDrag(
        id: Long,
        edge: SegmentEdge,
    ) {
        trimGesture = Any()
        trimTarget = id to edge
    }

    /** Moves the dragged edge towards [rawTimeMs], snapping to nearby edges, the playhead and score transitions. */
    fun dragEdge(rawTimeMs: Long) {
        val (id, edge) = trimTarget ?: return
        val current = state.value
        val timeline = current.timeline ?: return
        val targets = snapTargets(timeline, current.scores, excludeIds = setOf(id))
        val snapped = snap(rawTimeMs, targets, current.viewport)
        update { it.copy(snapGuideMs = snapped.takeIf { time -> time != rawTimeMs }) }
        val key = trimGesture
        edit(key, persist = false) {
            if (edge == SegmentEdge.Start) trimStart(id, snapped, now()) else trimEnd(id, snapped, now())
        }
    }

    fun endDrag() {
        trimGesture = null
        trimTarget = null
        update { s -> s.copy(snapGuideMs = null, history = s.history?.endGesture()) }
        persistDraft()
    }

    private fun edit(
        gestureKey: Any? = null,
        persist: Boolean = true,
        build: Timeline.() -> Edit?,
    ) {
        val history = state.value.history ?: return
        val next = history.apply(history.timeline.build(), gestureKey)
        if (next == history) return
        update { it.copy(history = next) }
        if (persist) persistDraft()
    }

    private fun changeHistory(transform: (EditHistory) -> EditHistory) {
        val history = state.value.history ?: return
        update { it.copy(history = transform(history)) }
        persistDraft()
    }

    private fun persistDraft() {
        val segments = state.value.timeline?.toApiSegments() ?: return
        try {
            projectStore.saveDraft(projectId, segments, state.value.editLog)
            update { it.copy(saveError = null) }
        } catch (e: Exception) {
            update { it.copy(saveError = "Could not save your edits on this computer: ${e.message}") }
        }
    }

    fun setViewportWidth(widthPx: Double) {
        if (widthPx <= 0) return
        update { s ->
            val duration = s.durationMs
            if (duration <= 0) return@update s.copy(viewportWidthPx = widthPx)
            val viewport =
                if (s.viewportWidthPx <= 0) {
                    Viewport(widthPx / minOf(duration, INITIAL_VISIBLE_MS).toDouble())
                } else {
                    s.viewport
                }
            val limited =
                viewport.copy(
                    pxPerMs = viewport.pxPerMs.coerceIn(minZoom(widthPx, duration), maxZoom(widthPx, duration)),
                )
            s.copy(viewportWidthPx = widthPx, viewport = limited.clampScroll(duration, widthPx))
        }
    }

    /** Zooms the timeline by [factor] (above 1 zooms in) keeping the time under [anchorPx] where it is. */
    fun zoomAround(
        anchorPx: Double,
        factor: Double,
    ) = update { s ->
        if (s.viewportWidthPx <= 0 || s.durationMs <= 0) return@update s
        val width = s.viewportWidthPx
        val zoomed =
            s.viewport.zoomAround(
                anchorPx,
                factor,
                s.durationMs,
                width,
                minZoom(width, s.durationMs),
                maxZoom(width, s.durationMs),
            )
        s.copy(viewport = zoomed)
    }

    /** Scrolls the timeline by [deltaPx] pixels (positive moves towards later times). */
    fun scrollByPx(deltaPx: Double) =
        update { s ->
            if (s.viewportWidthPx <=
                0
            ) {
                s
            } else {
                s.copy(viewport = s.viewport.scrollBy(deltaPx, s.durationMs, s.viewportWidthPx))
            }
        }

    /** Scrolls so that the playhead is inside the view, placing it a little in from the left edge when it was not. */
    private fun revealPlayhead() = followPlayhead()

    /** Opens the export dialog with a clean result, defaulting the target folder to the one of the original video. */
    fun openExport() =
        update { s ->
            val folder = s.export.options.folder ?: originalPath?.toAbsolutePath()?.parent
            s.copy(
                export =
                    s.export.copy(
                        open = true,
                        options = s.export.options.copy(folder = folder),
                        result = null,
                        error = null,
                        progress = 0.0,
                    ),
            )
        }.also { checkEdlSupport() }

    /**
     * Probes the original in the background to learn whether EDL can represent its frame rate. Until the probe
     * answers EDL stays selectable; when it turns out unsupported the option is disabled and a selected EDL falls
     * back to FCPXML, so the failure never has to be discovered by starting an export.
     */
    private fun checkEdlSupport() {
        val original = originalPath ?: return
        scope.launch {
            // A failed probe leaves EDL selectable; ProjectFiles.edl still refuses unsupported rates at export time.
            val fps =
                try {
                    ProjectFiles.edlUnsupportedFps(engine.probe(original))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            update { s ->
                val options = s.export.options
                val fallback = fps != null && options.mode == ExportMode.Edl && !s.export.running
                s.copy(
                    export =
                        s.export.copy(
                            edlUnsupportedFps = fps,
                            options = if (fallback) options.copy(mode = ExportMode.Fcpxml) else options,
                        ),
                )
            }
        }
    }

    /** Changes the dialog choices; ignored while an export runs, and EDL stays unselectable once known unsupported. */
    fun setExportOptions(transform: (ExportOptions) -> ExportOptions) =
        update { s ->
            val changed = transform(s.export.options)
            val blocked = changed.mode == ExportMode.Edl && s.export.edlUnsupportedFps != null
            if (s.export.running || blocked) s else s.copy(export = s.export.copy(options = changed))
        }

    /**
     * Exports the current segments of the original file with the dialog options. The original is probed again
     * rather than trusting the playback info, because the editor may be playing the proxy, whose frame rate and
     * size differ from what the project files must describe.
     */
    fun startExport() {
        val current = state.value
        val timeline = current.timeline ?: return
        val original = originalPath ?: return
        val options = current.export.options
        val folder = options.folder ?: return
        if (current.export.running) return
        update { it.copy(export = it.export.copy(running = true, progress = 0.0, result = null, error = null)) }
        exportJob =
            scope.launch {
                try {
                    val request =
                        ExportRequest(
                            source = original,
                            info = engine.probe(original),
                            ranges = timeline.exportRanges(options.includeRejected),
                            folder = folder,
                            mode = options.mode,
                            quality = options.quality,
                        )
                    val files = exporter.export(request) { fraction -> update { it.withExportProgress(fraction) } }
                    update { it.copy(export = it.export.copy(running = false, progress = 1.0, result = files)) }
                } catch (e: CancellationException) {
                    update { it.copy(export = it.export.copy(running = false, progress = 0.0)) }
                    throw e
                } catch (e: Exception) {
                    update { it.copy(export = it.export.copy(running = false, error = e.message ?: "Export failed.")) }
                }
            }
    }

    private fun EditorState.withExportProgress(fraction: Double) =
        if (export.running) copy(export = export.copy(progress = fraction)) else this

    /** Stops the running export; ffmpeg is killed and the partial files are removed. */
    fun cancelExport() {
        exportJob?.cancel()
    }

    /** Closes the dialog; a running export is cancelled first. */
    fun closeExport() {
        cancelExport()
        update { it.copy(export = it.export.copy(open = false)) }
    }

    override fun close() {
        closed = true
        saveQueue.detach(projectId)
        player?.close()
        super.close()
    }

    companion object {
        /** Initially the timeline shows at most ten minutes, which keeps segments legible on a long video. */
        const val INITIAL_VISIBLE_MS = 10 * 60_000L

        /** Highest zoom: one millisecond per pixel, so a frame at 25 fps spans 40 px. */
        const val MAX_PX_PER_MS = 1.0

        const val JUMP_BACK_MS = 5_000L
        const val LONG_STEP_FRAMES = 10
        const val MAX_RATE = 4.0
        const val FOLLOW_MARGIN = 0.1
        const val THUMBNAIL_HEIGHT_PX = 90
        const val THUMBNAIL_SPACING_MS = 30_000L
        const val MIN_THUMBNAILS = 12
        const val MAX_THUMBNAILS = 240
        const val WAVEFORM_BUCKET_MS = 250L
        const val MAX_WAVEFORM_BUCKETS = 40_000

        /** Lowest zoom: the whole video just fits the view. */
        fun minZoom(
            widthPx: Double,
            durationMs: Long,
        ): Double = widthPx / durationMs

        fun maxZoom(
            widthPx: Double,
            durationMs: Long,
        ): Double = maxOf(MAX_PX_PER_MS, minZoom(widthPx, durationMs))
    }
}

private fun Set<Long>.symmetricDifference(id: Long): Set<Long> = if (id in this) this - id else this + id

/**
 * Makes a segment list from the server or an old draft safe for [Timeline]: sorted, clamped to the video, without
 * empty or overlapping entries. Overlaps are resolved by moving the later segment's start up to the earlier end.
 */
fun sanitizeSegments(
    durationMs: Long,
    segments: List<Segment>,
): List<Segment> {
    val result = ArrayList<Segment>()
    var previousEnd = 0L
    for (segment in segments.sortedBy { it.startMs }) {
        val start = maxOf(segment.startMs, previousEnd, 0)
        val end = minOf(segment.endMs, durationMs)
        if (start >= end) continue
        result.add(segment.copy(startMs = start, endMs = end))
        previousEnd = end
    }
    return result
}
