package app.snipnet.shared.editing

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-based tests: random operation sequences must never break the timeline invariants, and undoing every
 * step must return to the starting segments.
 */
class TimelinePropertyTest {
    private val duration = 120_000L

    private fun randomEdit(
        timeline: Timeline,
        random: Random,
    ): Edit? {
        val ids = timeline.segments.map { it.id }
        val someId = if (ids.isEmpty()) 1L else ids.random(random)
        val time = random.nextLong(-5_000, duration + 5_000)
        val now = random.nextLong(0, 1_000_000)
        return when (random.nextInt(8)) {
            0 -> timeline.trimStart(someId, time, now)
            1 -> timeline.trimEnd(someId, time, now)
            2 -> timeline.split(time, now)
            3 -> timeline.merge(ids.filter { random.nextBoolean() }.toSet(), now)
            4 -> timeline.delete(setOf(someId), now)
            5 -> timeline.addFromMarks(time, random.nextLong(-5_000, duration + 5_000), now)
            6 -> timeline.toggleAccept(setOf(someId), now)
            else -> timeline.move(someId, random.nextLong(-30_000, 30_000), now)
        }
    }

    private fun assertInvariants(timeline: Timeline) {
        var previousEnd = 0L
        for (segment in timeline.segments) {
            assertTrue(segment.startMs >= previousEnd, "overlap or unsorted at $segment")
            assertTrue(segment.endMs <= duration, "beyond the video: $segment")
            previousEnd = segment.endMs
        }
        assertEquals(
            timeline.segments.size,
            timeline.segments
                .map { it.id }
                .toSet()
                .size,
            "duplicate ids",
        )
        assertTrue(timeline.selection.all { timeline.find(it) != null }, "dangling selection")
    }

    @Test
    fun operationsKeepSegmentsSortedNonOverlappingAndInBounds() =
        runTest {
            checkAll(200, Arb.long(0, Long.MAX_VALUE)) { seed ->
                val random = Random(seed)
                var timeline = Timeline(duration)
                repeat(60) {
                    val edit = randomEdit(timeline, random)
                    if (edit != null) timeline = edit.timeline
                    assertInvariants(timeline)
                }
            }
        }

    @Test
    fun noOperationProducesASegmentShorterThanTheMinimum() =
        runTest {
            checkAll(200, Arb.long(0, Long.MAX_VALUE)) { seed ->
                val random = Random(seed)
                var timeline = Timeline(duration)
                repeat(40) {
                    val edit = randomEdit(timeline, random)
                    if (edit != null) timeline = edit.timeline
                    val tooShort = timeline.segments.filter { it.lengthMs < MIN_SEGMENT_MS }
                    assertTrue(tooShort.isEmpty(), "segment shorter than the minimum: $tooShort")
                }
            }
        }

    @Test
    fun undoingEverythingRestoresTheStartAndRedoRestoresTheEnd() =
        runTest {
            checkAll(100, Arb.long(0, Long.MAX_VALUE)) { seed ->
                val random = Random(seed)
                val initial =
                    Timeline(
                        duration,
                    ).addFromMarks(1_000, 9_000, 0)!!.timeline.addFromMarks(20_000, 30_000, 0)!!.timeline
                var history = EditHistory(initial)
                repeat(30) {
                    history = history.apply(randomEdit(history.timeline, random))
                }
                val finalSegments = history.timeline.segments
                val logSize = history.editLog.size
                while (history.canUndo) history = history.undo()
                assertEquals(initial.segments, history.timeline.segments)
                while (history.canRedo) history = history.redo()
                assertEquals(finalSegments, history.timeline.segments)
                assertEquals(logSize, history.editLog.size)
            }
        }
}
