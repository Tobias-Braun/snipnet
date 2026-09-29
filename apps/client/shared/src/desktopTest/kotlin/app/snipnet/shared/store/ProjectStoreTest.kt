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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProjectStoreTest {
    private var clock = 1_000L
    private var counter = 0

    private fun store(database: app.snipnet.shared.store.db.SnipnetDatabase = openInMemoryDatabase()) =
        ProjectStore(database, newId = { "p${++counter}" }, now = { clock })

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

        val project = assertNotNull(store(openDatabase(file)).get("old"))

        assertEquals("remote-1", project.remoteVideoId)
        assertNull(project.baseSetId)
        assertNull(project.pendingSave)
        assertEquals(emptyList(), project.draftEditLog)
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
    fun deleteAndClearRemoveProjects() {
        val store = store()
        val a = store.create("/a.mp4").id
        store.create("/b.mp4")
        store.delete(a)
        assertEquals(1, store.list().size)
        store.clear()
        assertEquals(emptyList(), store.list())
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
