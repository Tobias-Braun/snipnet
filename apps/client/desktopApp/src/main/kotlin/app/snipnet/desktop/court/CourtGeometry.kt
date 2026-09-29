package app.snipnet.desktop.court

import app.snipnet.shared.model.Point
import app.snipnet.shared.model.Roi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The rectangle, in the pixel space of the widget showing it, that a video frame occupies. The frame keeps its aspect
 * ratio inside the widget, so a frame that does not match the widget's shape is letterboxed (bars on the sides or on
 * top and bottom) and [left]/[top] are the size of those bars.
 *
 * Everything the contract stores is normalized to the frame itself (`docs/api.md`), so pointer positions must be
 * converted through this rectangle; dividing by the widget size instead would skew the coordinates by the bars.
 */
data class FrameBox(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
) {
    /** Maps a widget pixel to frame coordinates in [0, 1]; positions in the bars are clamped to the frame edge. */
    fun toNormalized(
        px: Double,
        py: Double,
    ): Point =
        Point(
            x = ((px - left) / width).coerceIn(0.0, 1.0),
            y = ((py - top) / height).coerceIn(0.0, 1.0),
        )

    fun toPixelX(x: Double): Double = left + x * width

    fun toPixelY(y: Double): Double = top + y * height

    /** Whether a widget pixel lies on the frame rather than in a letterbox bar. */
    fun contains(
        px: Double,
        py: Double,
    ): Boolean = px >= left && px <= left + width && py >= top && py <= top + height

    companion object {
        /**
         * The largest centered box of the frame's aspect ratio that fits a container of [containerWidth] x
         * [containerHeight]. Degenerate sizes yield an empty box at the origin.
         */
        fun fit(
            containerWidth: Double,
            containerHeight: Double,
            frameWidth: Double,
            frameHeight: Double,
        ): FrameBox {
            if (containerWidth <= 0 || containerHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
                return FrameBox(0.0, 0.0, 0.0, 0.0)
            }
            val scale = min(containerWidth / frameWidth, containerHeight / frameHeight)
            val width = frameWidth * scale
            val height = frameHeight * scale
            return FrameBox((containerWidth - width) / 2, (containerHeight - height) / 2, width, height)
        }
    }
}

/** The corners of the ROI rectangle that can be dragged to resize it. */
enum class RoiCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Pure geometry of the court selection: default ROI, moving and resizing, all in normalized coordinates. */
object CourtGeometry {
    const val DEFAULT_ROI_WIDTH = 0.6
    const val DEFAULT_ROI_HEIGHT = 0.7

    /** Smallest ROI edge, so the rectangle can never collapse into something the user cannot grab again. */
    const val MIN_ROI_SIZE = 0.05

    /**
     * The starting ROI for a net point: a box of 60 % x 70 % of the frame centered on the net. Near the frame edge
     * the box is shifted (not shrunk) so it stays inside the frame.
     */
    fun defaultRoi(net: Point): Roi =
        Roi(
            x = (net.x - DEFAULT_ROI_WIDTH / 2).coerceIn(0.0, 1.0 - DEFAULT_ROI_WIDTH),
            y = (net.y - DEFAULT_ROI_HEIGHT / 2).coerceIn(0.0, 1.0 - DEFAULT_ROI_HEIGHT),
            width = DEFAULT_ROI_WIDTH,
            height = DEFAULT_ROI_HEIGHT,
        )

    /** Moves [roi] by a normalized delta, stopping at the frame edges without changing its size. */
    fun move(
        roi: Roi,
        dx: Double,
        dy: Double,
    ): Roi =
        roi.copy(
            x = (roi.x + dx).coerceIn(0.0, 1.0 - roi.width),
            y = (roi.y + dy).coerceIn(0.0, 1.0 - roi.height),
        )

    /**
     * Drags [corner] of [roi] to [target] while the opposite corner stays fixed. The target is clamped to the frame
     * and the rectangle keeps at least [MIN_ROI_SIZE] in each direction, extending away from the fixed corner.
     */
    fun resize(
        roi: Roi,
        corner: RoiCorner,
        target: Point,
    ): Roi {
        val movesLeft = corner == RoiCorner.TOP_LEFT || corner == RoiCorner.BOTTOM_LEFT
        val movesTop = corner == RoiCorner.TOP_LEFT || corner == RoiCorner.TOP_RIGHT
        val (x, width) = axis(if (movesLeft) roi.x + roi.width else roi.x, target.x, movesLeft)
        val (y, height) = axis(if (movesTop) roi.y + roi.height else roi.y, target.y, movesTop)
        return Roi(x, y, width, height)
    }

    /** Start and length of one axis given its fixed edge and where the other edge is dragged to. */
    private fun axis(
        fixed: Double,
        dragged: Double,
        draggedIsLow: Boolean,
    ): Pair<Double, Double> {
        val target = dragged.coerceIn(0.0, 1.0)
        val length = max(MIN_ROI_SIZE, if (draggedIsLow) fixed - target else target - fixed)
        val start = if (draggedIsLow) max(0.0, fixed - length) else min(fixed, 1.0 - length)
        return start to length
    }

    /** The corner of [roi] closest to [point] within the given per-axis radii (normalized units), or null. */
    fun cornerAt(
        roi: Roi,
        point: Point,
        radiusX: Double,
        radiusY: Double,
    ): RoiCorner? =
        RoiCorner.entries
            .filter { corner ->
                val c = cornerPoint(roi, corner)
                abs(c.x - point.x) <= radiusX && abs(c.y - point.y) <= radiusY
            }.minByOrNull { corner ->
                val c = cornerPoint(roi, corner)
                (c.x - point.x) * (c.x - point.x) + (c.y - point.y) * (c.y - point.y)
            }

    fun cornerPoint(
        roi: Roi,
        corner: RoiCorner,
    ): Point =
        Point(
            x = if (corner == RoiCorner.TOP_LEFT || corner == RoiCorner.BOTTOM_LEFT) roi.x else roi.x + roi.width,
            y = if (corner == RoiCorner.TOP_LEFT || corner == RoiCorner.TOP_RIGHT) roi.y else roi.y + roi.height,
        )

    fun contains(
        roi: Roi,
        point: Point,
    ): Boolean = point.x >= roi.x && point.x <= roi.x + roi.width && point.y >= roi.y && point.y <= roi.y + roi.height
}
