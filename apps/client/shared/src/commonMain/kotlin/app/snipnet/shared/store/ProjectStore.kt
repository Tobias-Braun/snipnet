package app.snipnet.shared.store

import app.snipnet.shared.model.Court
import app.snipnet.shared.model.EditOp
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SnipnetJson
import app.snipnet.shared.store.db.SnipnetDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A locally known video project.
 *
 * @property originalPath the user's source file on disk; never uploaded.
 * @property proxyPath the locally generated 480p proxy, null until the proxy has been created.
 * @property remoteVideoId the server-side video id, null until the proxy has been registered with the API.
 * @property draftSegments the unsaved editing state, so a crash or restart does not lose work; null when there is
 * no draft yet.
 * @property baseSetId the segment set the draft started from, sent as `parentSetId` on the next save; null when the
 * video has no segment set yet.
 * @property draftEditLog the edit operations since [baseSetId] that led to [draftSegments].
 * @property pendingSave a save that has not reached the server yet; retried until it is accepted.
 */
data class Project(
    val id: String,
    val originalPath: String,
    val proxyPath: String?,
    val remoteVideoId: String?,
    val court: Court?,
    val draftSegments: List<Segment>?,
    val createdAtMs: Long,
    val lastOpenedMs: Long,
    val baseSetId: String? = null,
    val draftEditLog: List<EditOp> = emptyList(),
    val pendingSave: PendingSave? = null,
)

/**
 * A user segment set waiting to be posted to `POST /v1/videos/:id/segment-sets`. It is a snapshot: later edits do
 * not change it, they produce a newer save that replaces it.
 */
@Serializable
data class PendingSave(
    val parentSetId: String?,
    val segments: List<Segment>,
    val editLog: List<EditOp>,
    val isFinal: Boolean,
)

/**
 * Persistence for [Project]s on top of the SQLDelight [database]. All methods are synchronous and cheap (single-row
 * statements on a local file), so callers on the UI thread may use them directly for small lists.
 *
 * Projects are scoped per user: every project is stored with the id of the account that created it, and the list,
 * the remote-id lookup and the pending-save query only return the projects of [currentUserId]. Nothing is deleted on
 * logout, so unsaved drafts are still there when the same account signs in again, while another account never sees
 * them. Lookups by local project id ([get]) and the single-row updates are not filtered: local ids are random and
 * only reachable through a scoped list.
 *
 * @param currentUserId the id of the signed-in user, or null when nobody is signed in (then nothing is listed and
 * [create] fails). Read on every call, so it may change over the life of the store.
 *
 * @param newId generates the local id of a new project; injectable so tests get deterministic ids.
 * @param now clock in epoch milliseconds, injectable for the same reason.
 */
class ProjectStore(
    database: SnipnetDatabase,
    private val newId: () -> String,
    private val now: () -> Long,
    private val currentUserId: () -> String?,
) {
    private val queries = database.projectQueries

    /** The signed-in user's projects, most recently opened first; empty when nobody is signed in. */
    fun list(): List<Project> {
        val userId = currentUserId() ?: return emptyList()
        return queries.selectAllForUser(userId).executeAsList().map { it.toProject() }
    }

    fun get(id: String): Project? = queries.selectById(id).executeAsOneOrNull()?.toProject()

    /**
     * Whether project [id] belongs to the user who is signed in right now. Anything that sends a project's data with
     * the current session's token must check this immediately before, because the account can change at any moment.
     */
    fun isOwnedByCurrentUser(id: String): Boolean {
        val userId = currentUserId() ?: return false
        return queries.selectById(id).executeAsOneOrNull()?.user_id == userId
    }

    fun findByRemoteVideoId(remoteVideoId: String): Project? {
        val userId = currentUserId() ?: return null
        val rows = queries.selectByRemoteVideoId(remoteVideoId, userId).executeAsList()
        return rows.firstOrNull()?.toProject()
    }

    /**
     * Registers a newly opened video file for the signed-in user; it starts as the most recently opened project.
     *
     * @throws IllegalStateException when nobody is signed in.
     */
    fun create(
        originalPath: String,
        proxyPath: String? = null,
        remoteVideoId: String? = null,
    ): Project {
        val userId = checkNotNull(currentUserId()) { "Cannot create a project without a signed-in user" }
        val timestamp = now()
        val project =
            Project(
                id = newId(),
                originalPath = originalPath,
                proxyPath = proxyPath,
                remoteVideoId = remoteVideoId,
                court = null,
                draftSegments = null,
                createdAtMs = timestamp,
                lastOpenedMs = timestamp,
            )
        queries.insert(
            id = project.id,
            user_id = userId,
            original_path = project.originalPath,
            proxy_path = project.proxyPath,
            remote_video_id = project.remoteVideoId,
            court_json = null,
            draft_segments_json = null,
            created_at_ms = timestamp,
            last_opened_ms = timestamp,
        )
        return project
    }

    fun setProxyPath(
        id: String,
        proxyPath: String?,
    ) = queries.updateProxyPath(proxyPath, id)

    fun setRemoteVideoId(
        id: String,
        remoteVideoId: String?,
    ) = queries.updateRemoteVideoId(remoteVideoId, id)

    fun setCourt(
        id: String,
        court: Court?,
    ) = queries.updateCourt(court?.let { SnipnetJson.encodeToString(Court.serializer(), it) }, id)

    /**
     * Stores the current unsaved segment state and the edit operations that led to it; pass null segments to
     * discard the draft.
     */
    fun saveDraft(
        id: String,
        segments: List<Segment>?,
        editLog: List<EditOp> = emptyList(),
    ) = queries.updateDraft(
        segments?.let { SnipnetJson.encodeToString(segmentListSerializer, it) },
        if (segments == null || editLog.isEmpty()) null else SnipnetJson.encodeToString(editLogSerializer, editLog),
        id,
    )

    fun setBaseSetId(
        id: String,
        baseSetId: String?,
    ) = queries.updateBaseSet(baseSetId, id)

    /** Queues [save] for upload, replacing an older queued save; null clears the queue. */
    fun setPendingSave(
        id: String,
        save: PendingSave?,
    ) = queries.updatePendingSave(save?.let { SnipnetJson.encodeToString(PendingSave.serializer(), it) }, id)

    /** Projects with a save that still has to reach the server. */
    fun withPendingSave(): List<Project> {
        val userId = currentUserId() ?: return emptyList()
        return queries.selectWithPendingSave(userId).executeAsList().mapNotNull { get(it) }
    }

    /**
     * Records that the server accepted [saved] as the segment set [createdSetId]: later edits start from that set,
     * the draft's edit log keeps only the operations made after [saved], and the queued save is cleared unless a
     * newer one replaced it meanwhile. Returns the remaining edit log.
     *
     * When the draft log no longer begins with the saved operations (the user undid saved edits meanwhile), it can
     * no longer be expressed relative to the new base, so it starts over empty.
     */
    fun markSaved(
        id: String,
        createdSetId: String,
        saved: PendingSave,
    ): List<EditOp> {
        val project = get(id) ?: return emptyList()
        val log = project.draftEditLog
        val remaining = if (log.take(saved.editLog.size) == saved.editLog) log.drop(saved.editLog.size) else emptyList()
        val pending = project.pendingSave.takeUnless { it == saved }
        queries.updateSyncedState(
            createdSetId,
            if (remaining.isEmpty()) null else SnipnetJson.encodeToString(editLogSerializer, remaining),
            pending?.let { SnipnetJson.encodeToString(PendingSave.serializer(), it) },
            id,
        )
        return remaining
    }

    /** Marks the project as opened just now so it sorts to the top of the list. */
    fun markOpened(id: String) = queries.updateLastOpened(now(), id)

    fun delete(id: String) = queries.deleteById(id)

    private fun app.snipnet.shared.store.db.Project.toProject() =
        Project(
            id = id,
            originalPath = original_path,
            proxyPath = proxy_path,
            remoteVideoId = remote_video_id,
            court = court_json?.let { SnipnetJson.decodeFromString(Court.serializer(), it) },
            draftSegments = draft_segments_json?.let { SnipnetJson.decodeFromString(segmentListSerializer, it) },
            createdAtMs = created_at_ms,
            lastOpenedMs = last_opened_ms,
            baseSetId = base_set_id,
            draftEditLog =
                draft_edit_log_json?.let { SnipnetJson.decodeFromString(editLogSerializer, it) } ?: emptyList(),
            pendingSave = pending_save_json?.let { SnipnetJson.decodeFromString(PendingSave.serializer(), it) },
        )

    private companion object {
        val segmentListSerializer = ListSerializer(Segment.serializer())
        val editLogSerializer = ListSerializer(EditOp.serializer())
    }
}
