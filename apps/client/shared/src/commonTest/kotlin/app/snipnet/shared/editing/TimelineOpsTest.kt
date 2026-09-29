package app.snipnet.shared.editing

import app.snipnet.shared.model.EditOpKind
import app.snipnet.shared.model.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimelineOpsTest {
    private val timeline =
        Timeline.fromSegments(
            durationMs = 100_000,
            segments =
                listOf(
                    Segment(10_000, 20_000, confidence = 0.9),
                    Segment(30_000, 40_000),
                    Segment(50_000, 60_000),
                ),
        )

    /** Start/end of a segment as an Int pair, so expectations can be written without Long literals. */
    private fun EditSegment.span() = startMs.toInt() to endMs.toInt()

    private fun Timeline.bounds() = segments.map { it.span() }

    private fun Edit?.spanOf(id: Long) = this!!.timeline.find(id)!!.span()

    @Test
    fun timelineRejectsInvalidState() {
        assertFailsWith<IllegalArgumentException> { Timeline(0) }
        assertFailsWith<IllegalArgumentException> { Timeline(1000, playheadMs = 2000) }
        assertFailsWith<IllegalArgumentException> { EditSegment(1, 5, 5) }
        val overlapping = listOf(EditSegment(1, 0, 600), EditSegment(2, 500, 900))
        assertFailsWith<IllegalArgumentException> { Timeline(1000, overlapping) }
        val duplicateIds = listOf(EditSegment(1, 0, 500), EditSegment(1, 600, 900))
        assertFailsWith<IllegalArgumentException> { Timeline(1000, duplicateIds) }
        assertFailsWith<IllegalArgumentException> { Timeline(1000, listOf(EditSegment(1, 0, 1500))) }
        assertFailsWith<IllegalArgumentException> { Timeline(1000, listOf(EditSegment(5, 0, 500)), nextId = 5) }
    }

    @Test
    fun lookupsSelectionAndApiConversion() {
        assertEquals(1L, timeline.segmentAt(15_000)?.id)
        assertNull(timeline.segmentAt(20_000))
        assertNull(timeline.find(99))
        assertEquals(setOf(1L, 2L), timeline.select(setOf(1, 2, 99)).selection)
        assertEquals(500L, timeline.seek(500).playheadMs)
        assertEquals(100_000L, timeline.seek(1_000_000).playheadMs)
        assertEquals(0L, timeline.seek(-5).playheadMs)
        assertEquals(10_000L, timeline.segments[0].lengthMs)

        val rejected = timeline.toggleAccept(setOf(2), 0)!!.timeline
        assertEquals(
            listOf(Segment(10_000, 20_000, confidence = 0.9), Segment(50_000, 60_000)),
            rejected.toApiSegments(),
        )
    }

    @Test
    fun trimStartClampsToMinimumLengthAndPredecessor() {
        val moved = timeline.trimStart(2, 32_000, 7)!!
        assertEquals(32_000 to 40_000, moved.spanOf(2))
        assertEquals(EditOpKind.TRIM, moved.op.op)
        assertEquals(7L, moved.op.atMs)
        assertEquals(listOf(Segment(30_000, 40_000)), moved.op.before)
        assertEquals(listOf(Segment(32_000, 40_000)), moved.op.after)

        assertEquals(39_800 to 40_000, timeline.trimStart(2, 45_000, 0).spanOf(2))
        assertEquals(20_000 to 40_000, timeline.trimStart(2, 5_000, 0).spanOf(2))
        assertEquals(0 to 20_000, timeline.trimStart(1, -500, 0).spanOf(1))
        assertNull(timeline.trimStart(2, 30_000, 0))
        assertNull(timeline.trimStart(99, 1, 0))
    }

    @Test
    fun trimEndClampsToMinimumLengthAndSuccessor() {
        assertEquals(30_000 to 38_000, timeline.trimEnd(2, 38_000, 0).spanOf(2))
        assertEquals(30_000 to 30_200, timeline.trimEnd(2, 0, 0).spanOf(2))
        assertEquals(30_000 to 50_000, timeline.trimEnd(2, 55_000, 0).spanOf(2))
        assertEquals(50_000 to 100_000, timeline.trimEnd(3, 200_000, 0).spanOf(3))
        assertNull(timeline.trimEnd(2, 40_000, 0))
        assertNull(timeline.trimEnd(99, 1, 0))
    }

    @Test
    fun trimOfSegmentShorterThanMinimumOnlyLengthens() {
        // The API allows segments shorter than MIN_SEGMENT_MS, and here the neighbours touch both edges.
        val tight =
            Timeline.fromSegments(
                durationMs = 10_000,
                segments = listOf(Segment(0, 1_000), Segment(1_000, 1_100), Segment(1_100, 2_000)),
            )
        assertNull(tight.trimStart(2, 1_050, 0))
        assertNull(tight.trimEnd(2, 1_050, 0))

        val roomy = Timeline.fromSegments(10_000, listOf(Segment(1_000, 1_100)))
        assertNull(roomy.trimStart(1, 1_050, 0))
        assertEquals(800 to 1_100, roomy.trimStart(1, 800, 0).spanOf(1))
        assertNull(roomy.trimEnd(1, 1_050, 0))
        assertEquals(1_000 to 1_300, roomy.trimEnd(1, 1_300, 0).spanOf(1))
    }

    @Test
    fun splitCreatesTwoSegmentsWithFreshId() {
        val edit = timeline.split(15_000, 3)!!
        assertEquals(
            listOf(10_000 to 15_000, 15_000 to 20_000, 30_000 to 40_000, 50_000 to 60_000),
            edit.timeline.bounds(),
        )
        assertEquals(4L, edit.timeline.segments[1].id)
        assertEquals(5L, edit.timeline.nextId)
        assertEquals(EditOpKind.SPLIT, edit.op.op)
        assertEquals(1, edit.op.before.size)
        assertEquals(2, edit.op.after.size)
    }

    @Test
    fun splitRejectsGapsAndTooShortHalves() {
        assertNull(timeline.split(25_000, 0))
        assertNull(timeline.split(10_100, 0))
        assertNull(timeline.split(19_900, 0))
        assertNotNull(timeline.split(10_200, 0))
        assertNotNull(timeline.split(19_800, 0))
    }

    @Test
    fun mergeAbsorbsGapsAndSelectsResult() {
        val edit = timeline.merge(setOf(1, 3), 0)!!
        assertEquals(listOf(10_000 to 60_000), edit.timeline.bounds())
        assertEquals(EditOpKind.MERGE, edit.op.op)
        assertEquals(3, edit.op.before.size)
        assertEquals(setOf(1L), edit.timeline.selection)
        assertEquals(0.9, edit.timeline.segments[0].confidence)
    }

    @Test
    fun mergeSelectionUsesSelectionAndNeedsTwoSegments() {
        assertNull(timeline.select(setOf(1)).mergeSelection(0))
        assertNull(timeline.merge(setOf(1, 99), 0))
        val merged = timeline.select(setOf(1, 2)).mergeSelection(0)!!
        assertEquals(listOf(10_000 to 40_000, 50_000 to 60_000), merged.timeline.bounds())
    }

    @Test
    fun mergeOfRejectedSegmentsStaysRejected() {
        val allRejected = timeline.toggleAccept(setOf(1, 2), 0)!!.timeline
        assertEquals(
            false,
            allRejected
                .merge(setOf(1, 2), 0)!!
                .timeline.segments[0]
                .accepted,
        )
        val mixed = timeline.toggleAccept(setOf(1), 0)!!.timeline
        assertEquals(
            true,
            mixed
                .merge(setOf(1, 2), 0)!!
                .timeline.segments[0]
                .accepted,
        )
    }

    @Test
    fun deleteRemovesSegmentsAndDropsSelection() {
        val edit = timeline.select(setOf(1, 2)).delete(setOf(1), 0)!!
        assertEquals(listOf(30_000 to 40_000, 50_000 to 60_000), edit.timeline.bounds())
        assertEquals(setOf(2L), edit.timeline.selection)
        assertEquals(EditOpKind.DELETE, edit.op.op)
        assertEquals(emptyList(), edit.op.after)
        assertNull(timeline.delete(setOf(99), 0))
    }

    @Test
    fun addFromMarksInsertsInEitherOrderAndClamps() {
        val edit = timeline.addFromMarks(25_000, 22_000, 0)!!
        assertEquals(
            listOf(10_000 to 20_000, 22_000 to 25_000, 30_000 to 40_000, 50_000 to 60_000),
            edit.timeline.bounds(),
        )
        assertEquals(EditOpKind.ADD, edit.op.op)
        assertEquals(emptyList(), edit.op.before)
        assertEquals(setOf(4L), edit.timeline.selection)

        assertEquals(
            0 to 500,
            timeline
                .addFromMarks(-1_000, 500, 0)!!
                .timeline
                .bounds()
                .first(),
        )
        assertEquals(
            90_000 to 100_000,
            timeline
                .addFromMarks(90_000, 150_000, 0)!!
                .timeline
                .bounds()
                .last(),
        )
    }

    @Test
    fun addFromMarksAbsorbsOverlappedSegments() {
        val edit = timeline.addFromMarks(15_000, 35_000, 0)!!
        assertEquals(listOf(10_000 to 40_000, 50_000 to 60_000), edit.timeline.bounds())
        assertEquals(2, edit.op.before.size)
        assertEquals(listOf(5_000 to 65_000), timeline.addFromMarks(5_000, 65_000, 0)!!.timeline.bounds())
        assertNull(timeline.addFromMarks(70_000, 70_100, 0))
    }

    @Test
    fun toggleAcceptFlipsEachSegment() {
        val edit = timeline.toggleAccept(setOf(1), 0)!!
        assertEquals(false, edit.timeline.find(1)!!.accepted)
        assertEquals(EditOpKind.TOGGLE, edit.op.op)
        assertEquals(1, edit.op.before.size)
        assertEquals(0, edit.op.after.size)
        assertEquals(
            true,
            edit.timeline
                .toggleAccept(setOf(1), 0)!!
                .timeline
                .find(1)!!
                .accepted,
        )
        assertNull(timeline.toggleAccept(setOf(99), 0))
    }

    @Test
    fun moveShiftsWithinNeighboursAndKeepsLength() {
        val edit = timeline.move(2, 3_000, 0)
        assertEquals(33_000 to 43_000, edit.spanOf(2))
        assertEquals(EditOpKind.MOVE, edit!!.op.op)
        assertEquals(40_000 to 50_000, timeline.move(2, 99_000, 0).spanOf(2))
        assertEquals(20_000 to 30_000, timeline.move(2, -99_000, 0).spanOf(2))
        assertEquals(0 to 10_000, timeline.move(1, -99_000, 0).spanOf(1))
        assertEquals(90_000 to 100_000, timeline.move(3, 500_000, 0).spanOf(3))
        assertNull(timeline.move(1, 0, 0))
        assertNull(timeline.move(99, 5, 0))
    }
}
