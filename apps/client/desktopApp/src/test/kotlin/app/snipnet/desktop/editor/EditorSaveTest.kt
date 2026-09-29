package app.snipnet.desktop.editor

import app.snipnet.shared.api.ApiError
import app.snipnet.shared.model.EditOpKind
import app.snipnet.shared.model.ScoreCurve
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentSet
import app.snipnet.shared.model.SegmentSetKind
import app.snipnet.shared.store.PendingSave
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Loading the latest sets, saving corrections, the offline queue and the unsaved-changes indicator. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorSaveTest {
    private val store = newStore()
    private val server = FakeSegmentSetServer()
    private val queue = newQueue(store, server)
    private var sets = listOf(prediction(threeRallies))

    private fun holder(dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()): EditorStateHolder {
        if (store.get("p1") == null) store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        return EditorStateHolder(
            "p1",
            store,
            FakeEngine(),
            queue,
            loadSets = { sets },
            now = { 1L },
            dispatcher = dispatcher,
            retryDelayMs = 1_000,
        )
    }

    private fun userSet(
        id: String,
        segments: List<Segment>,
        isFinal: Boolean = false,
    ) = SegmentSet(id, "remote-1", SegmentSetKind.USER, "s1", null, null, segments, null, emptyList(), isFinal, "t")

    private fun EditorStateHolder.splitAt(timeMs: Long) {
        seek(timeMs)
        perform(EditorCommand.Split)
    }

    private fun EditorStateHolder.starts() = state.value.segments.map { it.startMs }

    private val offline = ApiError.Network(IOException("no route to host"))

    @Test
    fun startsFromTheLatestUserSetWhileKeepingThePredictionScores() {
        sets =
            listOf(
                prediction(threeRallies, scores = ScoreCurve(1.0, List(60) { 0.5 })),
                userSet("u1", listOf(Segment(1_000, 2_000))),
                userSet("u2", listOf(Segment(3_000, 4_000))),
            )
        val holder = holder()
        assertEquals(listOf(3_000L), holder.starts())
        assertEquals("u2", holder.state.value.baseSetId)
        assertNotNull(holder.state.value.scores)
        assertFalse(holder.state.value.hasUnsavedChanges)
    }

    @Test
    fun aFreshVideoIsNotUnsavedUntilItIsEdited() {
        val holder = holder()
        assertEquals("s1", holder.state.value.baseSetId)
        assertFalse(holder.state.value.hasUnsavedChanges)
        holder.splitAt(10_000)
        assertTrue(holder.state.value.hasUnsavedChanges)
        holder.perform(EditorCommand.Undo)
        assertFalse(holder.state.value.hasUnsavedChanges)
    }

    @Test
    fun savePostsTheSegmentsWithTheParentSetAndTheEditLog() {
        val holder = holder()
        holder.splitAt(10_000)
        holder.perform(EditorCommand.Save)

        val (remoteId, save) = server.uploads.single()
        assertEquals("remote-1", remoteId)
        assertEquals("s1", save.parentSetId)
        assertFalse(save.isFinal)
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), save.segments.map { it.startMs })
        assertEquals(listOf(EditOpKind.SPLIT), save.editLog.map { it.op })
        assertFalse(holder.state.value.hasUnsavedChanges)
        assertNull(store.get("p1")?.pendingSave)
    }

    @Test
    fun afterASaveTheNextOneContinuesFromTheCreatedSetWithOnlyNewEdits() {
        val holder = holder()
        holder.splitAt(10_000)
        holder.perform(EditorCommand.Save)
        holder.splitAt(25_000)
        assertTrue(holder.state.value.hasUnsavedChanges)
        holder.perform(EditorCommand.Save)

        val second = server.uploads[1].second
        assertEquals("user-1", second.parentSetId)
        assertEquals(1, second.editLog.size)
        assertEquals(5, second.segments.size)
    }

    @Test
    fun editsDoneBeforeARestartStayInTheLogOfTheNextSave() {
        holder().also {
            it.splitAt(10_000)
            it.close()
        }
        val reopened = holder()
        assertTrue(reopened.state.value.hasUnsavedChanges)
        reopened.splitAt(25_000)
        reopened.perform(EditorCommand.Save)

        assertEquals(
            2,
            server.uploads
                .single()
                .second.editLog.size,
        )
    }

    @Test
    fun aDraftKeepsItsParentWhenTheNextStartIsOffline() {
        sets = listOf(prediction(threeRallies), userSet("u2", threeRallies))
        holder().also {
            it.splitAt(10_000)
            it.close()
        }
        assertEquals("u2", store.get("p1")?.baseSetId)

        sets = emptyList()
        val reopened = holder()
        reopened.perform(EditorCommand.Save)

        assertEquals(
            "u2",
            server.uploads
                .single()
                .second.parentSetId,
        )
    }

    @Test
    fun aSaveQueuedWithoutAParentIsSentWithTheNewestSetAsParent() =
        runTest {
            store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            store.setPendingSave("p1", PendingSave(null, threeRallies, emptyList(), isFinal = false))
            server.sets = listOf(prediction(threeRallies), userSet("u1", threeRallies))

            assertTrue(queue.flush("p1") is FlushResult.Saved)

            assertEquals(
                "u1",
                server.uploads
                    .single()
                    .second.parentSetId,
            )
            assertNull(store.get("p1")?.pendingSave)
        }

    @Test
    fun markAsFinalSavesTheSetAsFinalEvenWithoutEdits() {
        val holder = holder()
        holder.save(markFinal = true)

        assertTrue(
            server.uploads
                .single()
                .second.isFinal,
        )
        assertTrue(holder.state.value.savedFinal)
        assertEquals("Saved as final", syncLabel(holder.state.value))
    }

    @Test
    fun anUnreachableServerQueuesTheSaveAndShowsTheIndicator() {
        server.failure = offline
        val holder = holder()
        holder.splitAt(10_000)
        holder.perform(EditorCommand.Save)

        assertTrue(holder.state.value.queued)
        assertTrue(holder.state.value.hasUnsavedChanges)
        assertEquals("Offline, save queued", syncLabel(holder.state.value))
        assertNotNull(store.get("p1")?.pendingSave)
    }

    @Test
    fun aQueuedSaveIsRetriedUntilTheServerIsBack() =
        runTest {
            server.failure = offline
            val holder = holder(UnconfinedTestDispatcher(testScheduler))
            holder.splitAt(10_000)
            holder.perform(EditorCommand.Save)
            advanceTimeBy(2_500)
            assertTrue(holder.state.value.queued)
            assertTrue(server.uploads.isEmpty())

            server.failure = null
            advanceTimeBy(1_000)

            assertEquals(1, server.uploads.size)
            assertFalse(holder.state.value.queued)
            assertFalse(holder.state.value.hasUnsavedChanges)
            holder.close()
        }

    @Test
    fun aSaveQueuedBeforeAClosedEditorIsSentByTheQueue() =
        runTest {
            server.failure = offline
            val holder = holder()
            holder.splitAt(10_000)
            holder.perform(EditorCommand.Save)
            holder.close()

            server.failure = null
            val result = queue.flush("p1")

            assertTrue(result is FlushResult.Saved)
            assertEquals(1, server.uploads.size)
            val project = store.get("p1")!!
            assertNull(project.pendingSave)
            assertEquals("user-1", project.baseSetId)
            assertTrue(project.draftEditLog.isEmpty())
        }

    @Test
    fun aRefusedSaveShowsTheErrorAndIsNotRetriedAutomatically() =
        runTest {
            server.failure = ApiError.Validation("validation_error", "Segments overlap.")
            val holder = holder(UnconfinedTestDispatcher(testScheduler))
            holder.perform(EditorCommand.Save)

            assertEquals("Segments overlap.", holder.state.value.syncError)
            assertTrue(holder.state.value.queued)

            server.failure = null
            advanceTimeBy(5_000)
            assertTrue(server.uploads.isEmpty())

            holder.perform(EditorCommand.Save)
            assertEquals(1, server.uploads.size)
            assertNull(holder.state.value.syncError)
            holder.close()
        }

    @Test
    fun aQueuedSaveStillPendingAtStartupIsSentWhenTheEditorOpens() {
        server.failure = offline
        holder().also {
            it.splitAt(10_000)
            it.perform(EditorCommand.Save)
            it.close()
        }
        server.failure = null
        val reopened = holder()

        assertEquals(1, server.uploads.size)
        assertFalse(reopened.state.value.queued)
    }

    @Test
    fun nothingIsSentWhenThereIsNothingQueued() =
        runTest {
            store.create("/videos/match.mp4", remoteVideoId = "remote-1")
            assertEquals(FlushResult.Idle, queue.flush("p1"))
            assertEquals(FlushResult.Idle, queue.flush("missing"))
        }
}
