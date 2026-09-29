package app.snipnet.desktop.editor

import app.snipnet.shared.api.ApiError
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentSet
import app.snipnet.shared.model.SegmentSetKind
import app.snipnet.shared.store.PendingSave
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A 2xx answer to the save that cannot be parsed means the server stored the set; posting the save again would create
 * a duplicate user set.
 */
class SaveQueueMalformedResponseTest {
    private val store = newStore()
    private val save = PendingSave("set-1", listOf(Segment(0, 10_000)), emptyList(), isFinal = false)
    private val stored =
        SegmentSet(
            id = "stored-1",
            videoId = "remote-1",
            kind = SegmentSetKind.USER,
            parentSetId = "set-1",
            jobId = null,
            modelVersion = null,
            segments = save.segments,
            scores = null,
            editLog = emptyList(),
            isFinal = false,
            createdAt = "2026-01-02T00:00:00Z",
        )
    private var uploads = 0
    private var lookups = 0
    private var lookupFailure: ApiError? = null
    private var serverSets: List<SegmentSet> = emptyList()
    private var status = 201

    private val queue =
        SaveQueue(
            store,
            loadSets = {
                lookups++
                lookupFailure?.let { throw it }
                serverSets
            },
            upload = { _, _ ->
                uploads++
                throw ApiError.MalformedResponse(status, SerializationException("bad body"))
            },
        )

    private fun queueSave() {
        store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        store.setPendingSave("p1", save)
    }

    @Test
    fun aMatchingNewestSetCountsAsSaved() =
        runTest {
            queueSave()
            serverSets = listOf(stored)

            val result = queue.flush("p1")

            assertTrue(result is FlushResult.Saved)
            assertEquals("stored-1", result.set.id)
            assertNull(store.get("p1")?.pendingSave)
            assertEquals("stored-1", store.get("p1")?.baseSetId)
            assertEquals(1, uploads)
        }

    @Test
    fun aSetWithOtherSegmentsKeepsTheSaveQueued() =
        runTest {
            queueSave()
            serverSets = listOf(stored.copy(segments = listOf(Segment(0, 5_000))))

            val result = queue.flush("p1")

            assertTrue(result is FlushResult.Rejected)
            assertNotNull(store.get("p1")?.pendingSave)
        }

    @Test
    fun aSetWithAnotherParentKeepsTheSaveQueued() =
        runTest {
            queueSave()
            serverSets = listOf(stored.copy(parentSetId = "other"))

            assertTrue(queue.flush("p1") is FlushResult.Rejected)
            assertNotNull(store.get("p1")?.pendingSave)
        }

    @Test
    fun onlyTheNewestUserSetIsCompared() =
        runTest {
            queueSave()
            serverSets = listOf(stored, stored.copy(id = "stored-2", segments = listOf(Segment(1, 2))))

            assertTrue(queue.flush("p1") is FlushResult.Rejected)
        }

    @Test
    fun aFailedLookupIsRetriedWithoutPostingAgain() =
        runTest {
            queueSave()
            lookupFailure = ApiError.Network(RuntimeException("offline"))
            serverSets = listOf(stored)
            // Every later request would be answered normally: only a duplicate post could show up as an upload.
            assertTrue(queue.flush("p1") is FlushResult.Offline)
            assertNotNull(store.get("p1")?.pendingSave)

            lookupFailure = null
            val result = queue.flush("p1")

            assertTrue(result is FlushResult.Saved)
            assertEquals(1, uploads)
            assertNull(store.get("p1")?.pendingSave)
        }

    @Test
    fun aSaveTheServerDoesNotHaveIsPostedAgainAfterTheCheck() =
        runTest {
            queueSave()
            assertTrue(queue.flush("p1") is FlushResult.Rejected)
            assertEquals(1, uploads)

            // Nothing matched, so the next manual save verifies again and then posts the save anew.
            assertTrue(queue.flush("p1") is FlushResult.Rejected)
            assertEquals(2, uploads)
        }

    @Test
    fun aMalformedNon2xxResponseDoesNotLookAtTheServerSets() =
        runTest {
            queueSave()
            status = 502
            serverSets = listOf(stored)

            assertTrue(queue.flush("p1") is FlushResult.Rejected)
            assertEquals(0, lookups)
        }
}
