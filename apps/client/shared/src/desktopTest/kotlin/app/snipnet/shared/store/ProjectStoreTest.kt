package app.snipnet.shared.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snipnet.shared.model.Court
import app.snipnet.shared.model.EditOp
import app.snipnet.shared.model.EditOpKind
import app.snipnet.shared.model.Point
import app.snipnet.shared.model.Roi
import app.snipnet.shared.model.Segment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectStoreTest {
    private var clock = 1_000L
    private var counter = 0

    /** The account the stores of this test act as; tests change it to simulate logout and login. */
    private var signedIn: String? = "u1"

    private fun store(database: app.snipnet.shared.store.db.SnipnetDatabase = openInMemoryDatabase()) =
        ProjectStore(database, newId = { "p${++counter}" }, now = { clock }, currentUserId = { signedIn })

    @Test
    fun twoUsersOnlySeeTheirOwnProjects() {
        val store = store()
        val annas = store.create("/anna.mp4", remoteVideoId = "remote-1").id
        signedIn = "u2"
        val bens = store.create("/ben.mp4", remoteVideoId = "remote-1").id

        assertEquals(listOf(bens), store.list().map { it.id })
        assertEquals(bens, store.findByRemoteVideoId("remote-1")?.id)

        signedIn = "u1"
        assertEquals(listOf(annas), store.list().map { it.id })
        assertEquals(annas, store.findByRemoteVideoId("remote-1")?.id)
    }

    @Test
    fun purgeOwnerlessRemovesOnlyProjectsWithoutUserId() {
        val database = openInMemoryDatabase()
        database.projectQueries.insert("old", null, "/old.mp4", "/proxies/old.mp4", null, null, null, 1L, 1L)
        val store = store(database)
        val mine = store.create("/mine.mp4").id
        signedIn = "u2"
        val theirs = store.create("/theirs.mp4").id

        val purged = store.purgeOwnerless()

        assertEquals(listOf("old"), purged.map { it.id })
        assertEquals("/proxies/old.mp4", purged.single().proxyPath)
        assertNull(store.get("old"))
        assertNotNull(store.get(mine))
        assertNotNull(store.get(theirs))
        assertEquals(emptyList(), store.purgeOwnerless())
    }

    @Test
    fun pendingSavesAreScopedToTheUser() {
        val store = store()
        val id = store.create("/a.mp4").id
        store.setPendingSave(id, PendingSave(null, listOf(Segment(0, 10)), emptyList(), isFinal = false))
        assertEquals(listOf(id), store.withPendingSave().map { it.id })

        signedIn = "u2"
        assertEquals(emptyList(), store.withPendingSave())
        signedIn = null
        assertEquals(emptyList(), store.withPendingSave())
    }

    @Test
    fun pendingVideoDeletesAreScopedToTheUserAndDeduplicated() {
        val store = store()
        store.addPendingVideoDelete("v1")
        store.addPendingVideoDelete("v1")
        store.addPendingVideoDelete("v2")
        signedIn = "u2"
        store.addPendingVideoDelete("v3")
        assertEquals(listOf("v3"), store.pendingVideoDeletes())

        signedIn = "u1"
        assertEquals(listOf("v1", "v2"), store.pendingVideoDeletes())
        store.removePendingVideoDelete("v1")
        assertEquals(listOf("v2"), store.pendingVideoDeletes())

        signedIn = null
        store.addPendingVideoDelete("v4")
        assertEquals(emptyList(), store.pendingVideoDeletes())
    }

    @Test
    fun ownershipIsCheckedAgainstTheCurrentUser() {
        val store = store()
        val id = store.create("/a.mp4").id
        assertTrue(store.isOwnedByCurrentUser(id))

        signedIn = "u2"
        assertFalse(store.isOwnedByCurrentUser(id))
        signedIn = null
        assertFalse(store.isOwnedByCurrentUser(id))
        signedIn = "u1"
        assertFalse(store.isOwnedByCurrentUser("unknown"))
    }

    @Test
    fun draftsSurviveLogoutAndReLogin() {
        val store = store()
        val id = store.create("/a.mp4").id
        val draft = listOf(Segment(0, 500), Segment(1_000, 2_000))
        store.saveDraft(id, draft)

        signedIn = null
        assertEquals(emptyList(), store.list())

        signedIn = "u2"
        assertEquals(emptyList(), store.list())

        signedIn = "u1"
        assertEquals(draft, store.list().single().draftSegments)
    }

    @Test
    fun creatingWithoutASignedInUserFails() {
        val store = store()
        signedIn = null
        assertFailsWith<IllegalStateException> { store.create("/a.mp4") }
    }

    @Test
    fun createdProjectIsReadBack() {
        val store = store()
        val created = store.create("/videos/match.mp4")
        assertEquals(created, store.get(created.id))
        assertNull(created.proxyPath)
        assertNull(created.remoteVideoId)
        assertNull(created.court)
        assertNull(created.draftSegments)
    }

    @Test
    fun updatesPersistEveryField() {
        val store = store()
        val id = store.create("/videos/match.mp4").id
        val court = Court(Roi(0.1, 0.2, 0.5, 0.6), Point(0.4, 0.5))
        val draft = listOf(Segment(0, 500, confidence = 0.9), Segment(1_000, 2_000))

        store.setProxyPath(id, "/proxy/match.mp4")
        store.setRemoteVideoId(id, "remote-1")
        store.setCourt(id, court)
        store.saveDraft(id, draft)

        val project = assertNotNull(store.get(id))
        assertEquals("/proxy/match.mp4", project.proxyPath)
        assertEquals("remote-1", project.remoteVideoId)
        assertEquals(court, project.court)
        assertEquals(draft, project.draftSegments)
        assertEquals(id, store.findByRemoteVideoId("remote-1")?.id)
    }

    private val splitOp =
        EditOp(EditOpKind.SPLIT, 1L, listOf(Segment(0, 10)), listOf(Segment(0, 5), Segment(5, 10)))
    private val deleteOp = EditOp(EditOpKind.DELETE, 2L, listOf(Segment(0, 5)), emptyList())

    @Test
    fun draftKeepsItsEditLogAndBaseSet() {
        val store = store()
        val id = store.create("/a.mp4").id
        store.saveDraft(id, listOf(Segment(0, 5)), listOf(splitOp, deleteOp))
        store.setBaseSetId(id, "set-1")

        val project = assertNotNull(store.get(id))
        assertEquals(listOf(splitOp, deleteOp), project.draftEditLog)
        assertEquals("set-1", project.baseSetId)
    }

    @Test
    fun markSavedContinuesFromTheCreatedSetAndKeepsOnlyLaterOperations() {
        val store = store()
        val id = store.create("/a.mp4").id
        val save = PendingSave("set-1", listOf(Segment(0, 5)), listOf(splitOp), isFinal = false)
        store.saveDraft(id, listOf(Segment(0, 5)), listOf(splitOp, deleteOp))
        store.setPendingSave(id, save)
        assertEquals(listOf(id), store.withPendingSave().map { it.id })

        val remaining = store.markSaved(id, "set-2", save)

        val project = assertNotNull(store.get(id))
        assertEquals(listOf(deleteOp), remaining)
        assertEquals(listOf(deleteOp), project.draftEditLog)
        assertEquals("set-2", project.baseSetId)
        assertNull(project.pendingSave)
        assertEquals(emptyList(), store.withPendingSave())
    }

    @Test
    fun markSavedKeepsANewerQueuedSaveAndDropsAnUnrelatedLog() {
        val store = store()
        val id = store.create("/a.mp4").id
        val old = PendingSave("set-1", listOf(Segment(0, 5)), listOf(splitOp), isFinal = false)
        val newer = old.copy(isFinal = true)
        store.saveDraft(id, listOf(Segment(0, 5)), listOf(deleteOp))
        store.setPendingSave(id, newer)

        val remaining = store.markSaved(id, "set-2", old)

        assertEquals(emptyList(), remaining)
        assertEquals(newer, store.get(id)?.pendingSave)
    }

    @Test
    fun aDatabaseFromTheFirstSchemaVersionIsMigrated() {
        val file = Files.createTempDirectory("snipnet-db").resolve("snipnet.db")
        val old = JdbcSqliteDriver("jdbc:sqlite:$file")
        old.execute(
            null,
            "CREATE TABLE project (id TEXT NOT NULL PRIMARY KEY, original_path TEXT NOT NULL, proxy_path TEXT, " +
                "remote_video_id TEXT, court_json TEXT, draft_segments_json TEXT, created_at_ms INTEGER NOT NULL, " +
                "last_opened_ms INTEGER NOT NULL)",
            0,
        )
        old.execute(null, "INSERT INTO project VALUES ('old', '/a.mp4', NULL, 'remote-1', NULL, NULL, 1, 1)", 0)
        old.execute(null, "PRAGMA user_version = 1", 0)
        old.close()

        val migrated = store(openDatabase(file))
        val project = assertNotNull(migrated.get("old"))

        // Rows from before the per-user scoping have no owner and are hidden from every account.
        assertEquals(emptyList(), migrated.list())
        assertNull(migrated.findByRemoteVideoId("remote-1"))
        assertEquals("remote-1", project.remoteVideoId)
        assertNull(project.baseSetId)
        assertNull(project.pendingSave)
        assertEquals(emptyList(), project.draftEditLog)
        // Migration 3 created the pending delete table.
        migrated.addPendingVideoDelete("remote-1")
        assertEquals(listOf("remote-1"), migrated.pendingVideoDeletes())
    }

    @Test
    fun draftCanBeDiscarded() {
        val store = store()
        val id = store.create("/a.mp4").id
        store.saveDraft(id, listOf(Segment(0, 10)))
        store.saveDraft(id, null)
        assertNull(store.get(id)?.draftSegments)
    }

    @Test
    fun listIsOrderedByLastOpenedDescending() {
        val store = store()
        val first = store.create("/a.mp4").id
        clock = 2_000
        val second = store.create("/b.mp4").id
        assertEquals(listOf(second, first), store.list().map { it.id })

        clock = 3_000
        store.markOpened(first)
        assertEquals(listOf(first, second), store.list().map { it.id })
        assertEquals(3_000, store.get(first)?.lastOpenedMs)
    }

    @Test
    fun deleteRemovesTheProject() {
        val store = store()
        val a = store.create("/a.mp4").id
        store.create("/b.mp4")
        store.delete(a)
        assertEquals(1, store.list().size)
        assertNull(store.get(a))
    }

    @Test
    fun fileDatabaseSurvivesReopening() {
        val file = Files.createTempDirectory("snipnet-db").resolve("nested/snipnet.db")
        val id = store(openDatabase(file)).create("/a.mp4", proxyPath = "/p.mp4").id

        val reopened = store(openDatabase(file))
        assertEquals("/p.mp4", reopened.get(id)?.proxyPath)
    }
}
