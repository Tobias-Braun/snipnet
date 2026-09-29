package app.snipnet.desktop.editor

import app.snipnet.shared.editing.EditSegment
import kotlin.math.abs
import kotlin.math.pow

/**
 * Pixel layout of the timeline rows, top to bottom: ruler, thumbnail strip, waveform, AI probability heat strip and
 * the segment track. All values are in physical pixels; [density] converts the dp sizes of the design.
 */
class TimelineLayout(
    val density: Float,
) {
    val rulerHeight = 22 * density
    val thumbnailsTop = rulerHeight
    val thumbnailsHeight = 48 * density
    val waveformTop = thumbnailsTop + thumbnailsHeight
    val waveformHeight = 44 * density
    val heatTop = waveformTop + waveformHeight
    val heatHeight = 12 * density
    val trackTop = heatTop + heatHeight
    val trackHeight = 40 * density
    val totalHeight = trackTop + trackHeight

    /** How far from a segment edge a press still grabs the edge. */
    val edgeGrabPx = 7 * density

    /** Pointer movement below this distance still counts as a click instead of a drag. */
    val clickSlopPx = 4 * density

    val totalHeightDp: Float get() = totalHeight / density
}

/** Finds the segment edge under a press at [x], the closest one within the grab distance, or null. */
fun hitEdge(
    segments: List<EditSegment>,
    viewportTimeToPx: (Long) -> Double,
    x: Float,
    grabPx: Float,
): Pair<Long, SegmentEdge>? {
    var best: Pair<Long, SegmentEdge>? = null
    var bestDistance = grabPx.toDouble()
    for (segment in segments) {
        for ((edge, time) in listOf(SegmentEdge.Start to segment.startMs, SegmentEdge.End to segment.endMs)) {
            val distance = abs(viewportTimeToPx(time) - x)
            if (distance <= bestDistance) {
                best = segment.id to edge
                bestDistance = distance
            }
        }
    }
    return best
}

/**
 * Turns raw pointer input on the timeline into calls on the [holder]. Kept apart from the composable so it holds
 * no Compose state: it reads the current viewport and segments from [holder] on every event.
 *
 * - Press on the ruler, thumbnails, waveform or heat strip seeks and keeps seeking while dragging (scrubbing).
 * - Press on a segment edge starts a trim drag; press on a segment body selects it (ctrl/cmd/shift adds to the
 *   selection); either way a following drag scrolls, unless it began on an edge.
 * - Dragging the empty segment track scrolls; a click there seeks and clears the selection.
 * - The mouse wheel scrolls, and with ctrl or cmd it zooms around the cursor.
 */
class TimelineGestures(
    private val holder: EditorStateHolder,
    private val layout: TimelineLayout,
) {
    private enum class Mode { None, Scrub, Trim, Pan }

    private var mode = Mode.None
    private var pressX = 0f
    private var lastX = 0f
    private var moved = false
    private var clickSeeks = false

    fun press(
        x: Float,
        y: Float,
        additive: Boolean,
    ) {
        val state = holder.state.value
        val viewport = state.viewport
        pressX = x
        lastX = x
        moved = false
        clickSeeks = false
        if (y < layout.trackTop) {
            mode = Mode.Scrub
            holder.scrub(viewport.pxToTime(x.toDouble()), exact = false)
            return
        }
        val edge = hitEdge(state.segments, viewport::timeToPx, x, layout.edgeGrabPx)
        if (edge != null) {
            mode = Mode.Trim
            holder.beginDrag(edge.first, edge.second)
            return
        }
        mode = Mode.Pan
        val time = viewport.pxToTime(x.toDouble())
        val segment = state.timeline?.segmentAt(time)
        if (segment != null) {
            holder.select(segment.id, additive)
        } else {
            clickSeeks = true
        }
    }

    fun move(x: Float) {
        val viewport = holder.state.value.viewport
        if (abs(x - pressX) > layout.clickSlopPx) moved = true
        when (mode) {
            Mode.Scrub -> holder.scrub(viewport.pxToTime(x.toDouble()), exact = false)
            Mode.Trim -> holder.dragEdge(viewport.pxToTime(x.toDouble()))
            Mode.Pan -> if (moved) holder.scrollByPx((lastX - x).toDouble())
            Mode.None -> Unit
        }
        lastX = x
    }

    fun release(x: Float) {
        val viewport = holder.state.value.viewport
        when (mode) {
            Mode.Scrub -> holder.seek(viewport.pxToTime(x.toDouble()))
            Mode.Trim -> holder.endDrag()
            Mode.Pan -> if (clickSeeks && !moved) seekOnEmptyTrack(viewport.pxToTime(x.toDouble()))
            Mode.None -> Unit
        }
        mode = Mode.None
    }

    private fun seekOnEmptyTrack(timeMs: Long) {
        holder.clearSelection()
        holder.seek(timeMs)
    }

    /** Wheel input at [x]: zoom around the cursor with [zoom], else scroll with the horizontal (or vertical) delta. */
    fun scroll(
        x: Float,
        deltaX: Float,
        deltaY: Float,
        zoom: Boolean,
    ) {
        if (zoom) {
            holder.zoomAround(x.toDouble(), ZOOM_PER_NOTCH.pow(-deltaY.toDouble()))
        } else {
            val delta = if (deltaX != 0f) deltaX else deltaY
            holder.scrollByPx(delta * SCROLL_PX_PER_NOTCH.toDouble() * layout.density)
        }
    }

    private companion object {
        const val ZOOM_PER_NOTCH = 1.25
        const val SCROLL_PX_PER_NOTCH = 60f
    }
}
