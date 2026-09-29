package app.snipnet.desktop.editor

import app.snipnet.shared.model.ScoreCurve
import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state holder runs on an unconfined test dispatcher, so every launched coroutine runs to completion right away
 * and assertions can follow each call directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorStateHolderTest {
    private val store = newStore()
    private val engine = FakeEngine()
    private val player = engine.player
    private var clock = 1_000L
    private var loadedPrediction: SegmentSet? =
        prediction(
            threeRallies,
            ScoreCurve(
                1.0,
                List(60) {
                    if (it in
                        5..14
                    ) {
                        0.9
                    } else {
                        0.1
                    }
                },
            ),
        )

    private val server = FakeSegmentSetServer()
    private val queue = newQueue(store, server)

    private fun holder(createProject: Boolean = true): EditorStateHolder {
        if (createProject) store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        return EditorStateHolder(
            "p1",
            store,
            engine,
            queue,
            loadSets = { listOfNotNull(loadedPrediction) },
            now = { clock++ },
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun EditorStateHolder.starts() = state.value.segments.map { it.startMs }

    @Test
    fun loadsThePredictionAndItsScoresWhenThereIsNoDraft() {
        val holder = holder()
        assertEquals(listOf(5_000L, 20_000L, 40_000L), holder.starts())
        assertNotNull(holder.state.value.scores)
        assertEquals(TEST_DURATION_MS, holder.state.value.durationMs)
    }

    @Test
    fun aSavedDraftWinsOverThePrediction() {
        store.create("/videos/match.mp4", remoteVideoId = "remote-1")
        store.saveDraft("p1", listOf(Segment(1_000, 2_000)))
        val holder = holder(createProject = false)
        assertEquals(listOf(1_000L), holder.starts())
    }

    @Test
    fun anUnknownProjectReportsALoadError() {
        val holder = EditorStateHolder("missing", store, engine, queue, dispatcher = UnconfinedTestDispatcher())
        assertNotNull(holder.state.value.loadError)
    }

    @Test
    fun splitCutsTheSegmentUnderThePlayheadAndUndoRestoresIt() {
        val holder = holder()
        holder.seek(10_000)
        holder.perform(EditorCommand.Split)
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), holder.starts())

        holder.perform(EditorCommand.Undo)
        assertEquals(listOf(5_000L, 20_000L, 40_000L), holder.starts())
        holder.perform(EditorCommand.Redo)
        assertEquals(4, holder.state.value.segments.size)
    }

    @Test
    fun deleteRemovesTheSelectionOrElseTheSegmentUnderThePlayhead() {
        val holder = holder()
        holder.select(
            holder.state.value.segments[2]
                .id,
            additive = false,
        )
        holder.perform(EditorCommand.Delete)
        assertEquals(listOf(5_000L, 20_000L), holder.starts())

        holder.seek(6_000)
        holder.perform(EditorCommand.Delete)
        assertEquals(listOf(20_000L), holder.starts())
    }

    @Test
    fun mergeJoinsTheSelectedSegments() {
        val holder = holder()
        val ids =
            holder.state.value.segments
                .map { it.id }
        holder.select(ids[0], additive = false)
        holder.select(ids[1], additive = true)
        holder.perform(EditorCommand.Merge)
        assertEquals(
            listOf(5_000L to 30_000L, 40_000L to 50_000L),
            holder.state.value.segments.map {
                it.startMs to
                    it.endMs
            },
        )
    }

    @Test
    fun toggleAcceptFlipsTheSegmentAndKeepsItOutOfTheDraft() {
        val holder = holder()
        holder.seek(25_000)
        holder.perform(EditorCommand.ToggleAccept)
        assertFalse(
            holder.state.value.segments[1]
                .accepted,
        )
        assertEquals(listOf(5_000L, 40_000L), store.get("p1")!!.draftSegments!!.map { it.startMs })
    }

    @Test
    fun marksAddASegmentWithEnterAndAreThenCleared() {
        val holder = holder()
        holder.seek(32_000)
        holder.perform(EditorCommand.MarkIn)
        holder.seek(36_000)
        holder.perform(EditorCommand.MarkOut)
        holder.perform(EditorCommand.AddFromMarks)
        assertEquals(listOf(5_000L, 20_000L, 32_000L, 40_000L), holder.starts())
        assertNull(holder.state.value.markInMs)
        assertNull(holder.state.value.markOutMs)
    }

    @Test
    fun enterWithoutBothMarksDoesNothing() {
        val holder = holder()
        holder.perform(EditorCommand.MarkIn)
        holder.perform(EditorCommand.AddFromMarks)
        assertEquals(3, holder.state.value.segments.size)
        assertEquals(0L, holder.state.value.markInMs)
    }

    @Test
    fun nextAndPreviousRallyJumpSelectAndSeek() {
        val holder = holder()
        holder.perform(EditorCommand.NextRally)
        assertEquals(5_000L, holder.state.value.playheadMs)
        holder.perform(EditorCommand.NextRally)
        assertEquals(20_000L, holder.state.value.playheadMs)
        assertEquals(
            setOf(
                holder.state.value.segments[1]
                    .id,
            ),
            holder.state.value.timeline!!
                .selection,
        )
        assertEquals(20_000L to true, player.seeks.last())

        holder.perform(EditorCommand.PreviousRally)
        assertEquals(5_000L, holder.state.value.playheadMs)
        holder.perform(EditorCommand.PreviousRally)
        assertEquals(5_000L, holder.state.value.playheadMs)
    }

    @Test
    fun trimDragSnapsToTheNeighbourAndIsOneUndoStep() {
        val holder = holder()
        holder.setViewportWidth(1200.0)
        val id =
            holder.state.value.segments[0]
                .id
        holder.beginDrag(id, SegmentEdge.End)
        // The 60 s video over 1200 px is 20 px per second, so 8 px of snap tolerance is 0.4 s: 19.8 s snaps to 20 s.
        holder.dragEdge(17_000)
        holder.dragEdge(19_800)
        assertEquals(
            20_000L,
            holder.state.value.segments[0]
                .endMs,
        )
        assertEquals(20_000L, holder.state.value.snapGuideMs)
        holder.endDrag()
        assertNull(holder.state.value.snapGuideMs)

        holder.perform(EditorCommand.Undo)
        assertEquals(
            15_000L,
            holder.state.value.segments[0]
                .endMs,
        )
    }

    @Test
    fun dragWithoutSnapTargetsNearbyFollowsTheCursor() {
        val holder = holder()
        holder.setViewportWidth(1200.0)
        holder.beginDrag(
            holder.state.value.segments[1]
                .id,
            SegmentEdge.Start,
        )
        holder.dragEdge(24_000)
        holder.endDrag()
        assertEquals(
            24_000L,
            holder.state.value.segments[1]
                .startMs,
        )
        assertEquals(listOf(5_000L, 24_000L, 40_000L), store.get("p1")!!.draftSegments!!.map { it.startMs })
    }

    @Test
    fun togglePlayStartsAndPausesThePlayer() {
        val holder = holder()
        holder.perform(EditorCommand.TogglePlay)
        assertTrue(player.isPlaying.value)
        assertTrue(holder.state.value.isPlaying)
        holder.perform(EditorCommand.TogglePlay)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun frameStepsMoveByExactlyOneFrameAndPause() {
        val holder = holder()
        holder.seek(1_000)
        holder.perform(EditorCommand.StepForward)
        assertEquals(1_040L, holder.state.value.playheadMs)
        holder.perform(EditorCommand.StepBack)
        holder.perform(EditorCommand.StepBack)
        assertEquals(960L, holder.state.value.playheadMs)
        holder.perform(EditorCommand.StepForwardLong)
        assertEquals(1_360L, holder.state.value.playheadMs)
    }

    @Test
    fun lSpeedsUpPlaybackAndKResetsIt() {
        val holder = holder()
        holder.perform(EditorCommand.PlayFaster)
        assertTrue(player.isPlaying.value)
        assertEquals(1.0, player.rate.value)
        holder.perform(EditorCommand.PlayFaster)
        assertEquals(2.0, player.rate.value)
        holder.perform(EditorCommand.PlayFaster)
        assertEquals(4.0, player.rate.value)
        holder.perform(EditorCommand.PlayFaster)
        assertEquals(1.0, player.rate.value)
        holder.perform(EditorCommand.Pause)
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun jumpBackGoesFiveSecondsBack() {
        val holder = holder()
        holder.seek(12_000)
        holder.perform(EditorCommand.JumpBack)
        assertEquals(7_000L, holder.state.value.playheadMs)
    }

    @Test
    fun ralliesOnlyPlaybackSkipsGapsAndRejectedSegments() {
        val holder = holder()
        holder.perform(EditorCommand.ToggleRalliesOnly)
        holder.seek(22_000)
        holder.seek(25_000)
        holder.perform(EditorCommand.ToggleAccept)
        holder.seek(16_000)
        player.seeks.clear()

        holder.perform(EditorCommand.TogglePlay)
        // Starting inside a gap first jumps to the next accepted segment, which is the third one (the second is rejected).
        assertEquals(40_000L to true, player.seeks.first())

        player.seeks.clear()
        player.position.value = 15_000
        assertEquals(40_000L to true, player.seeks.last())
    }

    @Test
    fun ralliesOnlyPlaybackPausesAfterTheLastAcceptedSegment() {
        val holder = holder()
        holder.perform(EditorCommand.ToggleRalliesOnly)
        holder.seek(45_000)
        holder.perform(EditorCommand.TogglePlay)
        player.position.value = 50_000
        assertFalse(player.isPlaying.value)
    }

    @Test
    fun pausedHolderIgnoresLatePlayerPositions() {
        val holder = holder()
        holder.seek(30_000)
        player.position.value = 1_000
        assertEquals(30_000L, holder.state.value.playheadMs)
    }

    @Test
    fun zoomingKeepsTheTimeUnderTheCursorInPlace() {
        val holder = holder()
        holder.setViewportWidth(1000.0)
        holder.scrollByPx(200.0)
        val before =
            holder.state.value.viewport
                .pxToTime(400.0)
        holder.zoomAround(400.0, 2.0)
        val after = holder.state.value.viewport
        assertTrue(after.pxPerMs > 1000.0 / 60_000)
        assertTrue(kotlin.math.abs(after.pxToTime(400.0) - before) <= 2)
    }

    @Test
    fun keyboardSeeksScrollAnOffScreenPlayheadIntoView() {
        val holder = holder()
        holder.setViewportWidth(1000.0)
        holder.zoomAround(0.0, 4.0)
        holder.scrollByPx(2_000.0)
        val scrolled = holder.state.value.viewport
        holder.seek(scrolled.pxToTime(500.0))

        holder.perform(EditorCommand.JumpBack)
        holder.perform(EditorCommand.JumpBack)

        val view = holder.state.value.viewport
        assertTrue(view.scrollMs < scrolled.scrollMs, "scrolled back from ${scrolled.scrollMs} to ${view.scrollMs}")
        assertTrue(view.timeToPx(holder.state.value.playheadMs) in 0.0..1000.0)
    }

    @Test
    fun theWholeVideoFitsAtMinimumZoomAndScrollStaysInside() {
        val holder = holder()
        holder.setViewportWidth(1000.0)
        repeat(20) { holder.zoomAround(500.0, 0.5) }
        assertEquals(0L, holder.state.value.viewport.scrollMs)
        assertEquals(1000.0 / TEST_DURATION_MS, holder.state.value.viewport.pxPerMs, 1e-9)
        holder.scrollByPx(5_000.0)
        assertEquals(0L, holder.state.value.viewport.scrollMs)
    }

    @Test
    fun closingReleasesThePlayer() {
        val holder = holder()
        holder.close()
        assertTrue(player.closed)
    }

    @Test
    fun sanitizeSegmentsSortsClampsAndRemovesOverlaps() {
        val result =
            sanitizeSegments(
                10_000,
                listOf(
                    Segment(8_000, 12_000),
                    Segment(1_000, 4_000),
                    Segment(3_000, 6_000),
                    Segment(9_000, 9_500),
                    Segment(11_000, 12_000),
                ),
            )
        assertContentEquals(
            listOf(Segment(1_000, 4_000), Segment(4_000, 6_000), Segment(8_000, 10_000)),
            result,
        )
    }
}
