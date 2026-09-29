package app.snipnet.shared.store

import app.snipnet.shared.model.Court
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SnipnetJson
import app.snipnet.shared.store.db.SnipnetDatabase
import kotlinx.serialization.builtins.ListSerializer

/**
 * A locally known video project.
 *
 * @property originalPath the user's source file on disk; never uploaded.
 * @property proxyPath the locally generated 480p proxy, null until the proxy has been created.
 * @property remoteVideoId the server-side video id, null until the proxy has been registered with the API.
 * @property draftSegments the unsaved editing state, so a crash or restart does not lose work; null when there is
 * no draft yet.
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
)

/**
 * Persistence for [Project]s on top of the SQLDelight [database]. All methods are synchronous and cheap (single-row
 * statements on a local file), so callers on the UI thread may use them directly for small lists.
 *
 * @param newId generates the local id of a new project; injectable so tests get deterministic ids.
 * @param now clock in epoch milliseconds, injectable for the same reason.
 */
class ProjectStore(
    database: SnipnetDatabase,
    private val newId: () -> String,
    private val now: () -> Long,
) {
    private val queries = database.projectQueries

    /** All projects, most recently opened first. */
    fun list(): List<Project> = queries.selectAll().executeAsList().map { it.toProject() }

    fun get(id: String): Project? = queries.selectById(id).executeAsOneOrNull()?.toProject()

    fun findByRemoteVideoId(remoteVideoId: String): Project? =
        queries.selectByRemoteVideoId(remoteVideoId).executeAsOneOrNull()?.toProject()

    /** Registers a newly opened video file; it starts as the most recently opened project. */
    fun create(
        originalPath: String,
        proxyPath: String? = null,
        remoteVideoId: String? = null,
    ): Project {
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

    /** Stores the current unsaved segment state; pass null to discard the draft, for example after a final save. */
    fun saveDraft(
        id: String,
        segments: List<Segment>?,
    ) = queries.updateDraft(segments?.let { SnipnetJson.encodeToString(segmentListSerializer, it) }, id)

    /** Marks the project as opened just now so it sorts to the top of the list. */
    fun markOpened(id: String) = queries.updateLastOpened(now(), id)

    fun delete(id: String) = queries.deleteById(id)

    /** Removes every project, used on logout so the next account does not see this account's videos. */
    fun clear() = queries.deleteAll()

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
        )

    private companion object {
        val segmentListSerializer = ListSerializer(Segment.serializer())
    }
}
