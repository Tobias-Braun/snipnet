package app.snipnet.desktop.editor

import app.snipnet.shared.api.ApiError
import app.snipnet.shared.model.EditOp
import app.snipnet.shared.model.EditOpKind
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentSet
import app.snipnet.shared.model.SegmentSetKind
import app.snipnet.shared.store.PendingSave
import app.snipnet.shared.store.ProjectStore
import app.snipnet.shared.store.openInMemoryDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A save was stored on the server but its answer was unreadable and the re-read failed. If the user saves again
 * before the retry, the newer save must continue from the stored set instead of becoming its orphaned sibling.
 */
class SaveQueueRebaseTest {
    private val store =
        ProjectStore(openInMemoryDatabase(), newId = { "p1" }, now = { 1L }, currentUserId = { "u1" })
    private val firstOp = EditOp(EditOpKind.DELETE, 1L, listOf(Segment(0, 10_000)), listOf(Segment(0, 5_000)))
    private val secondOp = EditOp(EditOpKind.DELETE, 2L, listOf(Segment(0, 5_000)), listOf(Segment(0, 2_000)))
    private val first = PendingSave("set-1", listOf(Segment(0, 5_000)), listOf(firstOp), isFinal = false)
    private val second =
        PendingSave("set-1", listOf(Segment(0, 2_000)), listOf(firstOp, secondOp), isFinal = false)
    private val stored = storedSet("stored-1", first, "set-1")

    private var serverSets: List<SegmentSet> = emptyList()
    private var lookupFailure: ApiError? = null
    private var uploadFailure: ApiError? = ApiError.MalformedResponse(201, SerializationException("bad body"))
    private val uploaded = mutableListOf<PendingSave>()
    private val savedCallbacks = mutableListOf<FlushResult.Saved>()

    private val queue =
        SaveQueue(
            store,
            loadSets = {
                lookupFailure?.let { throw it }
                serverSets
            },
            upload = { _, save ->
                uploaded += save
                uploadFailure?.let { throw it }
                storedSet("stored-2", save, save.parentSetId)
            },
        )

    private fun storedSet(
        id: String,
        save: PendingSave,
        parent: String?,
    ) = SegmentSet(
        id = id,
        videoId = "remote-1",
        kind = SegmentSetKind.USER,
        parentSetId = parent,
        jobId = null,
        modelVersion = null,
        segments = save.segments,
        scores = null,
        editLog = save.editLog,
        isFinal = save.isFinal,
        createdAt = "2026-01-02T00:00:00Z",
    )

    /** Sends [first] so that the server stores it but neither the answer nor the re-read reaches the client. */
    private suspend fun sendFirstWithoutConfirmation() {
        store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        store.saveDraft("p1", first.segments, first.editLog)
        store.setPendingSave("p1", first)
        lookupFailure = ApiError.Network(RuntimeException("offline"))
        assertTrue(queue.flush("p1") is FlushResult.Offline)
        lookupFailure = null
        serverSets = listOf(stored)
    }

    private fun saveAgain() {
        store.saveDraft("p1", second.segments, second.editLog)
        store.setPendingSave("p1", second)
    }

    @Test
    fun aNewerSaveIsRebasedOntoTheStoredSetInsteadOfBecomingItsSibling() =
        runTest {
            sendFirstWithoutConfirmation()
            saveAgain()
            uploadFailure = null

            val result = queue.flush("p1", onSaved = { savedCallbacks += it })

            assertTrue(result is FlushResult.Saved)
            assertEquals("stored-2", result.set.id)
            assertEquals(2, uploaded.size)
            val rebased = uploaded.last()
            assertEquals("stored-1", rebased.parentSetId)
            assertEquals(listOf(secondOp), rebased.editLog)
            assertEquals(second.segments, rebased.segments)
            assertNull(store.get("p1")?.pendingSave)
            assertEquals("stored-2", store.get("p1")?.baseSetId)
            assertEquals(emptyList(), store.get("p1")?.draftEditLog)
            // The editor mirrors the store: it learns about the confirmed set first and then about the rebased one.
            assertEquals(listOf("stored-1", "stored-2"), savedCallbacks.map { it.set.id })
            assertEquals(listOf(secondOp), savedCallbacks.first().remainingLog)
        }

    @Test
    fun aRebasedSaveThatCannotBeUploadedStaysQueuedOnTheStoredSet() =
        runTest {
            sendFirstWithoutConfirmation()
            saveAgain()
            uploadFailure = ApiError.Network(RuntimeException("offline"))

            assertTrue(queue.flush("p1") is FlushResult.Offline)

            val project = store.get("p1")
            assertEquals("stored-1", project?.baseSetId)
            assertEquals("stored-1", project?.pendingSave?.parentSetId)
            assertEquals(listOf(secondOp), project?.pendingSave?.editLog)
            assertEquals(listOf(secondOp), project?.draftEditLog)
        }

    @Test
    fun theSameSaveIsStillConfirmedWithoutAnotherUpload() =
        runTest {
            sendFirstWithoutConfirmation()

            val result = queue.flush("p1")

            assertTrue(result is FlushResult.Saved)
            assertEquals("stored-1", result.set.id)
            assertEquals(1, uploaded.size)
            assertNull(store.get("p1")?.pendingSave)
        }

    @Test
    fun aNewerSaveIsPostedAsIsWhenTheServerDoesNotHaveTheSentOne() =
        runTest {
            sendFirstWithoutConfirmation()
            saveAgain()
            serverSets = emptyList()
            uploadFailure = null

            val result = queue.flush("p1")

            assertTrue(result is FlushResult.Saved)
            assertEquals(second, uploaded.last())
        }
}
