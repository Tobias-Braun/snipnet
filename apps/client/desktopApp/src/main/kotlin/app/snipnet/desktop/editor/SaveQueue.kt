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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

    /** The project the flush that currently holds [mutex] works on, or null while no flush runs. */
    private val flushing = MutableStateFlow<String?>(null)

    /**
     * Projects whose last upload got a 2xx answer that could not be read, mapped to the parent set that upload used.
     * The server most likely stored the set, so posting again would create a duplicate; the next flush first checks
     * the server's sets and only uploads when the save is not among them.
     */
    private val unconfirmed = HashMap<String, String?>()

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
            // Published before the attached check and reset on every exit, so settle sees exactly the project the
            // flush holding the mutex works on.
            flushing.value = projectId
            try {
                flushLocked(projectId, onSaved, background)
            } finally {
                flushing.value = null
            }
        }

    private suspend fun flushLocked(
        projectId: String,
        onSaved: (FlushResult.Saved) -> Unit,
        background: Boolean,
    ): FlushResult =
        run {
            if (background && projectId in attached) return@run FlushResult.Idle
            rejected.remove(projectId)
            if (!store.isOwnedByCurrentUser(projectId)) return@run FlushResult.Idle
            val project = store.get(projectId) ?: return@run FlushResult.Idle
            val save = project.pendingSave ?: return@run FlushResult.Idle
            val remoteId =
                project.remoteVideoId
                    ?: return@run FlushResult.Offline("The video is not uploaded yet.")
            try {
                val sent =
                    if (projectId in unconfirmed) {
                        val found = findStoredCopy(remoteId, save, unconfirmed.getValue(projectId))
                        if (!store.isOwnedByCurrentUser(projectId)) return@run FlushResult.Idle
                        found ?: uploadNew(projectId, remoteId, save)
                    } else {
                        uploadNew(projectId, remoteId, save)
                    }
                unconfirmed -= projectId
                val saved = FlushResult.Saved(sent, store.markSaved(projectId, sent.id, save))
                onSaved(saved)
                saved
            } catch (e: NotOwnedException) {
                FlushResult.Idle
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

    /** Thrown when the account changed while a lookup was suspended; the save stays queued untouched. */
    private class NotOwnedException : Exception()

    /**
     * Posts [save] as a new set. A 2xx answer that cannot be parsed means the set exists on the server, so the
     * project is marked [unconfirmed] and the server's sets are checked right away; if that check cannot confirm the
     * save (or fails), the error propagates as a failed attempt and the next flush verifies again instead of posting.
     */
    private suspend fun uploadNew(
        projectId: String,
        remoteId: String,
        save: PendingSave,
    ): SegmentSet {
        val parentSetId = save.parentSetId ?: latestSetId(remoteId)
        // The set lookup suspends, so the account may have changed since the check in flush; the request would
        // carry the new account's token.
        if (!store.isOwnedByCurrentUser(projectId)) throw NotOwnedException()
        try {
            return upload(remoteId, save.copy(parentSetId = parentSetId))
        } catch (e: ApiError.MalformedResponse) {
            if (e.status !in 200..299) throw e
            unconfirmed[projectId] = parentSetId
            // The upload suspended, so the account may have changed; the lookup would carry the new account's token.
            // The project stays unconfirmed, so its owner's next flush does the check instead.
            if (!store.isOwnedByCurrentUser(projectId)) throw NotOwnedException()
            return findStoredCopy(remoteId, save, parentSetId) ?: throw e
        }
    }

    /**
     * The newest user set on the server if it carries the segments and parent of [save], else null. Only the newest
     * set counts: an older one with the same content is an earlier save, not the one that just went out.
     */
    private suspend fun findStoredCopy(
        remoteVideoId: String,
        save: PendingSave,
        parentSetId: String?,
    ): SegmentSet? {
        val newest = loadSets(remoteVideoId).lastOrNull { it.kind == SegmentSetKind.USER } ?: return null
        return newest.takeIf { it.segments == save.segments && it.parentSetId == parentSetId }
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
     * Waits for a flush of [projectId] that is running right now. A flush of another project cannot touch this
     * project's store entry, and waiting on it could take a full request timeout. An editor calls it after [attach] and before it reads its project
     * from the store: a background flush that started before the attach may still update the store (new base set,
     * drained edit log, cleared queue), and an editor that read the project earlier would keep the stale parent and
     * post edit log entries that were already saved.
     */
    suspend fun settle(projectId: String) {
        flushing.first { it != projectId }
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
