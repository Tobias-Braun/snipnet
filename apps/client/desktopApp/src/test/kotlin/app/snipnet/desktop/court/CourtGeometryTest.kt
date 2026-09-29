package app.snipnet.desktop.court

import app.snipnet.shared.model.Point
import app.snipnet.shared.model.Roi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameBoxTest {
    private val delta = 1e-9

    private fun assertPoint(
        expectedX: Double,
        expectedY: Double,
        actual: Point,
    ) {
        assertEquals(expectedX, actual.x, delta)
        assertEquals(expectedY, actual.y, delta)
    }

    @Test
    fun wideFrameInATallerAreaIsLetterboxedAtTopAndBottom() {
        // 1600x900 frame in a 800x600 area: scaled to 800x450, with 75 px bars above and below.
        val box = FrameBox.fit(800.0, 600.0, 1600.0, 900.0)
        assertEquals(FrameBox(0.0, 75.0, 800.0, 450.0), box)
    }

    @Test
    fun tallFrameInAWiderAreaIsLetterboxedAtTheSides() {
        // 1080x1920 portrait frame in 1000x800: scaled to 450x800, with 275 px bars left and right.
        val box = FrameBox.fit(1000.0, 800.0, 1080.0, 1920.0)
        assertEquals(275.0, box.left, delta)
        assertEquals(0.0, box.top, delta)
        assertEquals(450.0, box.width, delta)
        assertEquals(800.0, box.height, delta)
    }

    @Test
    fun matchingAspectRatioFillsTheArea() {
        val box = FrameBox.fit(640.0, 360.0, 1920.0, 1080.0)
        assertEquals(FrameBox(0.0, 0.0, 640.0, 360.0), box)
    }

    @Test
    fun pixelsMapRelativeToTheFrameNotTheArea() {
        val box = FrameBox.fit(800.0, 600.0, 1600.0, 900.0)
        assertPoint(0.0, 0.0, box.toNormalized(0.0, 75.0))
        assertPoint(1.0, 1.0, box.toNormalized(800.0, 525.0))
        assertPoint(0.5, 0.5, box.toNormalized(400.0, 300.0))
        assertPoint(0.25, 0.75, box.toNormalized(200.0, 75.0 + 0.75 * 450.0))
    }

    @Test
    fun pixelsInTheBarsClampToTheFrameEdge() {
        val box = FrameBox.fit(800.0, 600.0, 1600.0, 900.0)
        assertPoint(0.5, 0.0, box.toNormalized(400.0, 10.0))
        assertPoint(0.5, 1.0, box.toNormalized(400.0, 590.0))
        assertPoint(0.0, 0.5, box.toNormalized(-30.0, 300.0))
    }

    @Test
    fun normalizedAndPixelCoordinatesRoundTrip() {
        val box = FrameBox.fit(1000.0, 800.0, 1080.0, 1920.0)
        val point = Point(0.37, 0.81)
        assertPoint(point.x, point.y, box.toNormalized(box.toPixelX(point.x), box.toPixelY(point.y)))
    }

    @Test
    fun containsExcludesTheBars() {
        val box = FrameBox.fit(800.0, 600.0, 1600.0, 900.0)
        assertTrue(box.contains(400.0, 300.0))
        assertFalse(box.contains(400.0, 30.0))
    }

    @Test
    fun degenerateSizesGiveAnEmptyBox() {
        assertEquals(0.0, FrameBox.fit(0.0, 600.0, 1600.0, 900.0).width)
        assertEquals(0.0, FrameBox.fit(800.0, 600.0, 0.0, 0.0).height)
    }
}

class CourtGeometryTest {
    private val delta = 1e-9

    @Test
    fun defaultRoiIsCenteredOnTheNetWithSixtyBySeventyPercent() {
        val roi = CourtGeometry.defaultRoi(Point(0.5, 0.5))
        assertEquals(0.2, roi.x, delta)
        assertEquals(0.15, roi.y, delta)
        assertEquals(0.6, roi.width, delta)
        assertEquals(0.7, roi.height, delta)
    }

    @Test
    fun defaultRoiNearTheEdgeIsShiftedInsideTheFrameWithoutShrinking() {
        val roi = CourtGeometry.defaultRoi(Point(0.95, 0.05))
        assertEquals(0.4, roi.x, delta)
        assertEquals(0.0, roi.y, delta)
        assertEquals(0.6, roi.width, delta)
        assertEquals(0.7, roi.height, delta)
    }

    @Test
    fun moveStopsAtTheFrameEdges() {
        val roi = Roi(0.2, 0.15, 0.6, 0.7)
        val moved = CourtGeometry.move(roi, 0.5, -0.5)
        assertEquals(0.4, moved.x, delta)
        assertEquals(0.0, moved.y, delta)
        assertEquals(roi.width, moved.width)
        assertEquals(roi.height, moved.height)
    }

    @Test
    fun resizeKeepsTheOppositeCornerFixed() {
        val roi = Roi(0.2, 0.2, 0.4, 0.4)
        val resized = CourtGeometry.resize(roi, RoiCorner.BOTTOM_RIGHT, Point(0.9, 0.7))
        assertEquals(0.2, resized.x, delta)
        assertEquals(0.7, resized.width, delta)
        assertEquals(0.5, resized.height, delta)

        val topLeft = CourtGeometry.resize(roi, RoiCorner.TOP_LEFT, Point(0.1, 0.05))
        assertEquals(0.1, topLeft.x, delta)
        assertEquals(0.05, topLeft.y, delta)
        assertEquals(0.5, topLeft.width, delta)
        assertEquals(0.55, topLeft.height, delta)
    }

    @Test
    fun resizeCannotLeaveTheFrame() {
        val roi = Roi(0.2, 0.2, 0.4, 0.4)
        val resized = CourtGeometry.resize(roi, RoiCorner.TOP_LEFT, Point(-0.5, -0.5))
        assertEquals(0.0, resized.x, delta)
        assertEquals(0.0, resized.y, delta)
        assertEquals(0.6, resized.width, delta)
    }

    @Test
    fun resizeKeepsAMinimumSizeWhenDraggedPastTheOppositeCorner() {
        val roi = Roi(0.2, 0.2, 0.4, 0.4)
        val resized = CourtGeometry.resize(roi, RoiCorner.BOTTOM_RIGHT, Point(0.1, 0.1))
        assertEquals(CourtGeometry.MIN_ROI_SIZE, resized.width, delta)
        assertEquals(CourtGeometry.MIN_ROI_SIZE, resized.height, delta)
        assertEquals(0.2, resized.x, delta)
    }

    @Test
    fun cornerHitTestPicksTheNearestCornerWithinTheRadius() {
        val roi = Roi(0.2, 0.2, 0.4, 0.4)
        assertEquals(RoiCorner.BOTTOM_RIGHT, CourtGeometry.cornerAt(roi, Point(0.61, 0.59), 0.02, 0.02))
        assertNull(CourtGeometry.cornerAt(roi, Point(0.4, 0.4), 0.02, 0.02))
    }
}
