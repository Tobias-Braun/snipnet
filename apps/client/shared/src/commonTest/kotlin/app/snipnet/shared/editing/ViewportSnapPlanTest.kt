package app.snipnet.shared.editing

import app.snipnet.shared.model.ScoreCurve
import app.snipnet.shared.model.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ViewportSnapPlanTest {
    private val timeline =
        Timeline.fromSegments(60_000, listOf(Segment(10_000, 20_000), Segment(30_000, 40_000), Segment(50_000, 55_000)))

    @Test
    fun timePixelConversionsAreInverse() {
        val viewport = Viewport(pxPerMs = 0.1, scrollMs = 5_000)
        assertEquals(0.0, viewport.timeToPx(5_000))
        assertEquals(500.0, viewport.timeToPx(10_000))
        assertEquals(10_000L, viewport.pxToTime(500.0))
        assertEquals(5_000L..15_000L, viewport.visibleRangeMs(1_000.0))
        assertFailsWith<IllegalArgumentException> { Viewport(0.0) }
        assertFailsWith<IllegalArgumentException> { Viewport(Double.NaN) }
    }

    @Test
    fun zoomKeepsAnchorTimeUnderCursor() {
        val viewport = Viewport(0.01, 0)
        val zoomed = viewport.zoomAround(400.0, 4.0, 60_000, 800.0, 0.001, 1.0)
        assertEquals(0.04, zoomed.pxPerMs)
        assertEquals(viewport.pxToTime(400.0), zoomed.pxToTime(400.0))
        assertEquals(0.02, viewport.zoomAround(0.0, 100.0, 60_000, 800.0, 0.001, 0.02).pxPerMs)
        assertEquals(0.001, viewport.zoomAround(0.0, 0.0001, 60_000, 800.0, 0.001, 1.0).pxPerMs)
    }

    @Test
    fun scrollingIsClampedToTheVideo() {
        val viewport = Viewport(0.1, 0)
        assertEquals(0L, viewport.scrollBy(-500.0, 60_000, 1_000.0).scrollMs)
        assertEquals(1_000L, viewport.scrollBy(100.0, 60_000, 1_000.0).scrollMs)
        assertEquals(50_000L, viewport.scrollBy(1_000_000.0, 60_000, 1_000.0).scrollMs)
        assertEquals(0L, Viewport(0.1, 3_000).clampScroll(5_000, 1_000.0).scrollMs)
    }

    @Test
    fun scoreTransitionsAreFoundAtThreshold() {
        val curve = ScoreCurve(hz = 2.0, values = listOf(0.1, 0.2, 0.7, 0.9, 0.4, 0.5))
        assertEquals(listOf(1_000L, 2_000L, 2_500L), curve.transitionTimesMs())
        assertEquals(emptyList(), ScoreCurve(0.0, listOf(0.0, 1.0)).transitionTimesMs())
        assertEquals(emptyList(), ScoreCurve(1.0, emptyList()).transitionTimesMs())
    }

    @Test
    fun snapTargetsCollectPlayheadEdgesAndTransitions() {
        val seeked = timeline.seek(25_000)
        val scores = ScoreCurve(1.0, listOf(0.0, 1.0))
        assertEquals(
            listOf(25_000L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 55_000L, 1_000L),
            snapTargets(seeked, scores),
        )
        assertEquals(listOf(25_000L, 30_000L, 40_000L), snapTargets(seeked, excludeIds = setOf(1, 3)))
    }

    @Test
    fun snapToleranceDependsOnZoom() {
        val targets = listOf(10_000L, 20_000L)
        val zoomedOut = Viewport(0.01)
        val zoomedIn = Viewport(1.0)
        assertEquals(10_000L, snap(10_500, targets, zoomedOut))
        assertEquals(10_500L, snap(10_500, targets, zoomedIn))
        assertEquals(10_000L, snap(10_010, targets, Viewport(0.5)))
        assertEquals(10_020L, snap(10_020, targets, Viewport(0.5)))
        assertEquals(20_000L, snap(19_500, targets, zoomedOut))
        assertEquals(5_000L, snap(5_000, emptyList(), zoomedOut))
    }

    @Test
    fun playbackPlanContainsAcceptedRangesOnly() {
        val rejected = timeline.toggleAccept(setOf(2), 0)!!.timeline
        val plan = rejected.playbackPlan()
        assertEquals(listOf(TimeRange(10_000, 20_000), TimeRange(50_000, 55_000)), plan.ranges)
        assertEquals(15_000L, plan.totalDurationMs)
        assertEquals(12_000L, plan.nextPlayable(12_000))
        assertEquals(50_000L, plan.nextPlayable(20_000))
        assertEquals(10_000L, plan.nextPlayable(0))
        assertNull(plan.nextPlayable(55_000))
        assertEquals(0L, Timeline(1000).playbackPlan().totalDurationMs)
    }
}
