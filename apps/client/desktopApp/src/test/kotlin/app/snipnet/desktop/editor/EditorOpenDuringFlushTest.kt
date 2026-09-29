package app.snipnet.desktop.editor

import app.snipnet.shared.model.EditOp
import app.snipnet.shared.model.EditOpKind
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
import kotlin.test.assertNotNull
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
            // The queued save carries the whole draft edit log, so an editor that read the project too early would
            // keep this entry and post it a second time with its next save.
            val trim = EditOp(EditOpKind.TRIM, 1L, listOf(Segment(0, 15_000)), listOf(Segment(5_000, 15_000)))
            store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            store.saveDraft("p1", listOf(Segment(5_000, 15_000)), listOf(trim))
            store.setBaseSetId("p1", "s1")
            store.setPendingSave("p1", PendingSave("s1", listOf(Segment(5_000, 15_000)), listOf(trim), false))

            val background = launch { queue.flush("p1", background = true) }
            uploadStarted.await()

            // The holder shares the test scheduler, so its endless retry loop must be cancelled even when an
            // assertion fails, or runTest keeps advancing that loop and the test hangs instead of failing.
            EditorStateHolder(
                "p1",
                store,
                FakeEngine(),
                queue,
                loadSets = { listOf(prediction(threeRallies)) },
                now = { 1L },
                dispatcher = UnconfinedTestDispatcher(testScheduler),
                retryDelayMs = 1_000_000,
            ).use { holder ->
                assertTrue(holder.state.value.loading)

                releaseUpload.complete(Unit)
                background.join()
                runCurrent()

                assertEquals("user-1", holder.state.value.baseSetId)
                assertEquals(emptyList(), holder.state.value.priorEditLog)
                assertFalse(holder.state.value.queued)
                assertNull(store.get("p1")?.pendingSave)
                val (_, uploaded) = server.uploads.single()
                assertEquals(listOf(trim), uploaded.editLog)
            }
        }

    @Test
    fun aBackgroundFlushThatLostTheRaceToTheEditorLeavesTheSaveAlone() =
        runTest {
            queueSave(PendingSave("s1", listOf(Segment(0, 10_000)), emptyList(), false))
            queue.attach("p1")

            assertEquals(FlushResult.Idle, queue.flush("p1", background = true))

            assertTrue(server.uploads.isEmpty())
            assertNotNull(store.get("p1")?.pendingSave)
        }
}
