package app.snipnet.desktop.editor

import app.snipnet.shared.api.ApiError
import app.snipnet.shared.model.EditOp
import app.snipnet.shared.model.SegmentSet
import app.snipnet.shared.model.SegmentSetKind
import app.snipnet.shared.store.PendingSave
import app.snipnet.shared.store.ProjectStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** What one attempt to upload the queued save of a project did. */
sealed interface FlushResult {
    /** Nothing was queued. */
    data object Idle : FlushResult

    /** The server accepted the save as [set]; [remainingLog] is the part of the edit log made after the save. */
    data class Saved(
        val set: SegmentSet,
        val remainingLog: List<EditOp>,
    ) : FlushResult

    /** The server could not be reached or failed; the save stays queued and is retried. */
    data class Offline(
        val message: String,
    ) : FlushResult

    /** The server refused the save for good (invalid, forbidden, unknown video); it stays queued but is not retried. */
    data class Rejected(
        val message: String,
    ) : FlushResult
}

/**
 * Uploads the saves that [ProjectStore.setPendingSave] queued. A save is queued first and removed only after the
 * server accepted it, so a crash, a closed editor or a missing connection never loses it.
 *
 * The editor of a project calls [flush] itself and retries while it is open; [start] runs a loop for everything
 * else, so a save queued in an editor that has since been closed still goes out once the API is reachable again.
 * A single mutex keeps two flushes from posting the same save twice.
 *
 * A save is only sent while the project belongs to the signed-in user, checked right before the request: the API
 * attaches the current session's token, so after a logout and a login as someone else a save of the previous account
 * would otherwise go out under the wrong identity and be refused or dropped. Such a save stays queued untouched and
 * goes out when its owner signs in again.
 *
 * @param loadSets fetches all segment sets of a remote video, oldest first. It is only called for a save queued
 *   without a parent (the editor started offline from a draft whose base set was never stored), because the server
 *   requires `parentSetId`; the newest user set, else the prediction, then stands in for the set the edits started
 *   from.
 * @param upload posts a save as a new user segment set of the remote video.
 */
class SaveQueue(
    private val store: ProjectStore,
    private val loadSets: suspend (remoteVideoId: String) -> List<SegmentSet>,
    private val upload: suspend (remoteVideoId: String, save: PendingSave) -> SegmentSet,
) {
    private val mutex = Mutex()
    private val attached = ConcurrentHashMap.newKeySet<String>()
    private val rejected = HashSet<String>()

    /**
     * Uploads the queued save of [projectId]. On success the store is updated first (new base set, drained edit
     * log, cleared queue) and then [onSaved] runs without suspending in between, so a caller that mirrors the store
     * in memory cannot see a half-updated state.
     *
     * A [background] flush (the retry loop) gives up when an editor attached to the project in the meantime, so
     * once [attach] and [settle] returned, no flush other than the editor's own touches that project.
     */
    suspend fun flush(
        projectId: String,
        onSaved: (FlushResult.Saved) -> Unit = {},
        background: Boolean = false,
    ): FlushResult =
        mutex.withLock {
            if (background && projectId in attached) return@withLock FlushResult.Idle
            rejected.remove(projectId)
            if (!store.isOwnedByCurrentUser(projectId)) return@withLock FlushResult.Idle
            val project = store.get(projectId) ?: return@withLock FlushResult.Idle
            val save = project.pendingSave ?: return@withLock FlushResult.Idle
            val remoteId =
                project.remoteVideoId
                    ?: return@withLock FlushResult.Offline("The video is not uploaded yet.")
            try {
                val parentSetId = save.parentSetId ?: latestSetId(remoteId)
                // The set lookup suspends, so the account may have changed since the check above; the request
                // would carry the new account's token.
                if (!store.isOwnedByCurrentUser(projectId)) return@withLock FlushResult.Idle
                val created = upload(remoteId, save.copy(parentSetId = parentSetId))
                val saved = FlushResult.Saved(created, store.markSaved(projectId, created.id, save))
                onSaved(saved)
                saved
            } catch (e: ApiError) {
                if (e.isTemporary()) {
                    FlushResult.Offline(e.message.orEmpty())
                } else {
                    rejected += projectId
                    FlushResult.Rejected(e.message.orEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A local store failure; retrying on a timer could post the same save again, so it waits for the user.
                rejected += projectId
                FlushResult.Rejected("Could not update the local project: ${e.message}")
            }
        }

    private suspend fun latestSetId(remoteVideoId: String): String? {
        val sets = loadSets(remoteVideoId)
        return (sets.lastOrNull { it.kind == SegmentSetKind.USER } ?: sets.lastOrNull())?.id
    }

    /** While an editor is open for [projectId] it retries by itself, so the background loop leaves the project alone. */
    fun attach(projectId: String) {
        attached += projectId
    }

    /**
     * Waits for a flush that is running right now. An editor calls it after [attach] and before it reads its project
     * from the store: a background flush that started before the attach may still update the store (new base set,
     * drained edit log, cleared queue), and an editor that read the project earlier would keep the stale parent and
     * post edit log entries that were already saved.
     */
    suspend fun settle() {
        mutex.withLock { }
    }

    fun detach(projectId: String) {
        attached -= projectId
    }

    /** Retries every queued save of a project without an open editor every [intervalMs]. */
    fun start(
        scope: CoroutineScope,
        intervalMs: Long = RETRY_INTERVAL_MS,
    ) {
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                store
                    .withPendingSave()
                    .map { it.id }
                    .filter { it !in attached && it !in rejected }
                    .forEach { flush(it, background = true) }
            }
        }
    }

    companion object {
        const val RETRY_INTERVAL_MS = 15_000L
    }
}

/**
 * Failures that a later attempt can fix, as opposed to a request the server will never accept. A refused token counts
 * as temporary because signing in again makes the same save acceptable.
 */
private fun ApiError.isTemporary(): Boolean =
    this is ApiError.Network || this is ApiError.Server || this is ApiError.RateLimited || this is ApiError.Unauthorized
