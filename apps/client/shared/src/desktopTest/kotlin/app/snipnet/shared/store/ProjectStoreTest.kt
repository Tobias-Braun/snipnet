package app.snipnet.shared.store

import app.snipnet.shared.model.Court
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
