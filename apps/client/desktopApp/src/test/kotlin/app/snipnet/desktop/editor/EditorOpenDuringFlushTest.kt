package app.snipnet.desktop.editor

import app.snipnet.shared.model.Segment
import app.snipnet.shared.store.PendingSave
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor opening while the background loop is uploading the queued save of the same project. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorOpenDuringFlushTest {
    private val store = newStore()
    private val server = FakeSegmentSetServer()
    private val uploadStarted = CompletableDeferred<Unit>()
    private val releaseUpload = CompletableDeferred<Unit>()
    private val queue =
        SaveQueue(
            store,
            loadSets = { server.sets },
            upload = { remoteId, save ->
                uploadStarted.complete(Unit)
                releaseUpload.await()
                server.upload(remoteId, save)
            },
        )

    private fun queueSave(save: PendingSave) {
        store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        store.setPendingSave("p1", save)
    }

    @Test
    fun theEditorStartsFromTheSetTheBackgroundFlushSaved() =
        runTest {
            store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            store.saveDraft("p1", listOf(Segment(5_000, 15_000)), emptyList())
            store.setBaseSetId("p1", "s1")
            store.setPendingSave("p1", PendingSave("s1", listOf(Segment(5_000, 15_000)), emptyList(), false))

            val background = launch { queue.flush("p1", background = true) }
            uploadStarted.await()

            val holder =
                EditorStateHolder(
                    "p1",
                    store,
                    FakeEngine(),
                    queue,
                    loadSets = { listOf(prediction(threeRallies)) },
                    now = { 1L },
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    retryDelayMs = 1_000_000,
                )
            assertTrue(holder.state.value.loading)

            releaseUpload.complete(Unit)
            background.join()
            runCurrent()

            assertEquals("user-1", holder.state.value.baseSetId)
            assertEquals(emptyList(), holder.state.value.priorEditLog)
            assertFalse(holder.state.value.queued)
            assertNull(store.get("p1")?.pendingSave)
            assertEquals(1, server.uploads.size)
            holder.close()
        }

    @Test
    fun aBackgroundFlushThatLostTheRaceToTheEditorLeavesTheSaveAlone() =
        runTest {
            queueSave(PendingSave("s1", listOf(Segment(0, 10_000)), emptyList(), false))
            queue.attach("p1")

            assertEquals(FlushResult.Idle, queue.flush("p1", background = true))

            assertTrue(server.uploads.isEmpty())
        }
}
