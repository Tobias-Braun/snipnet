package app.snipnet.desktop.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.snipnet.desktop.theme.EditorColors
import app.snipnet.desktop.theme.SnipnetTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Candidate ruler intervals in milliseconds; the first one that is far enough apart on screen is used. */
private val RULER_STEPS_MS =
    longArrayOf(
        10,
        20,
        50,
        100,
        200,
        500,
        1_000,
        2_000,
        5_000,
        10_000,
        15_000,
        30_000,
        60_000,
        120_000,
        300_000,
        600_000,
        900_000,
        1_800_000,
        3_600_000,
    )

private const val MIN_RULER_TICK_PX = 90f
private const val WAVEFORM_COLUMN_PX = 2f

/** Everything a draw pass needs, bundled so the row painters stay small. */
private class DrawContext(
    val scope: DrawScope,
    val state: EditorState,
    val layout: TimelineLayout,
    val colors: EditorColors,
    val outline: Color,
    val labelColor: Color,
    val textMeasurer: TextMeasurer,
) {
    val width: Float get() = scope.size.width
    val viewport get() = state.viewport
}

/**
 * The timeline: time ruler, thumbnail strip, audio waveform, AI probability heat strip, segment track and the
 * playhead, drawn on a single canvas. Every row only visits what is inside the visible time window (thumbnail
 * tiles, waveform columns, score samples and segments in view), so the cost per frame depends on the window width,
 * not on the video length, which keeps a two hour video smooth. Input is handled by [TimelineGestures].
 */
@Composable
fun TimelineView(
    state: EditorState,
    holder: EditorStateHolder,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val layout = remember(density) { TimelineLayout(density) }
    val gestures = remember(holder, layout) { TimelineGestures(holder, layout) }
    val colors = SnipnetTheme.editor
    val outline = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val labelColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(layout.totalHeightDp.dp)
                .testTag("timeline")
                .onSizeChanged { holder.setViewportWidth(it.width.toDouble()) }
                .pointerInput(gestures) { handlePointer(gestures) },
    ) {
        val context = DrawContext(this, state, layout, colors, outline, labelColor, textMeasurer)
        clipRect { context.drawAll() }
    }
}

/** Feeds pointer events to [gestures]; wheel events are consumed so a surrounding scroll container ignores them. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.handlePointer(gestures: TimelineGestures) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            val x = change.position.x
            when (event.type) {
                PointerEventType.Press -> {
                    val additive =
                        event.keyboardModifiers.let {
                            it.isCtrlPressed ||
                                it.isMetaPressed ||
                                it.isShiftPressed
                        }
                    gestures.press(x, change.position.y, additive)
                }
                PointerEventType.Move -> if (change.pressed) gestures.move(x)
                PointerEventType.Release -> gestures.release(x)
                PointerEventType.Scroll -> {
                    val zoom = event.keyboardModifiers.let { it.isCtrlPressed || it.isMetaPressed }
                    gestures.scroll(x, change.scrollDelta.x, change.scrollDelta.y, zoom)
                    change.consume()
                }
                else -> Unit
            }
        }
    }
}

private fun DrawContext.drawAll() {
    drawBackground()
    drawRuler()
    drawThumbnails()
    drawWaveform()
    drawHeatStrip()
    drawSegments()
    drawMarksAndGuides()
    drawPlayhead()
}

private fun DrawContext.drawBackground() {
    scope.drawRect(colors.timelineBackground, size = scope.size)
    scope.drawRect(
        colors.timelineTrack,
        topLeft = Offset(0f, layout.trackTop),
        size = Size(width, layout.trackHeight),
    )
}

private fun rulerStepMs(pxPerMs: Double): Long =
    RULER_STEPS_MS.firstOrNull { it * pxPerMs >= MIN_RULER_TICK_PX } ?: RULER_STEPS_MS.last()

private fun DrawContext.drawRuler() {
    val step = rulerStepMs(viewport.pxPerMs)
    val firstTick = (viewport.scrollMs / step) * step
    val lastVisible = viewport.pxToTime(width.toDouble())
    var time = firstTick
    val style = TextStyle(color = labelColor, fontSize = 10.sp)
    while (time <= minOf(lastVisible, state.durationMs)) {
        val x = viewport.timeToPx(time).toFloat()
        scope.drawLine(labelColor, Offset(x, layout.rulerHeight * 0.55f), Offset(x, layout.rulerHeight), 1f)
        scope.drawText(textMeasurer, formatRulerLabel(time, step), Offset(x + 3f, 2f), style)
        time += step
    }
}

private fun formatRulerLabel(
    timeMs: Long,
    stepMs: Long,
): String = if (stepMs < 1_000) formatTimecode(timeMs) else formatTimecode(timeMs).substringBefore('.')

/**
 * Draws thumbnails as tiles of the image's own aspect ratio. Each visible tile shows the thumbnail closest to the
 * time at its center, so zooming in repeats frames instead of stretching them.
 */
private fun DrawContext.drawThumbnails() {
    val thumbnails = state.thumbnails
    if (thumbnails.isEmpty() || state.thumbnailCount <= 0 || state.durationMs <= 0) return
    val tileHeight = layout.thumbnailsHeight
    val sample = thumbnails.values.first()
    val tileWidth = tileHeight * sample.width / sample.height
    val origin = viewport.timeToPx(0)
    val endPx = minOf(width.toDouble(), viewport.timeToPx(state.durationMs))
    var tile = floor(-origin / tileWidth).toInt().coerceAtLeast(0)
    while (origin + tile * tileWidth < endPx) {
        drawThumbnailTile(thumbnails, (origin + tile * tileWidth).toFloat(), tileWidth, tileHeight)
        tile++
    }
}

private fun DrawContext.drawThumbnailTile(
    thumbnails: Map<Int, ImageBitmap>,
    left: Float,
    tileWidth: Float,
    tileHeight: Float,
) {
    val centerTime = viewport.pxToTime(left + tileWidth / 2.0).coerceIn(0, state.durationMs)
    val slice = (centerTime.toDouble() / state.durationMs * state.thumbnailCount).toInt()
    val bitmap =
        thumbnails[slice.coerceIn(0, state.thumbnailCount - 1)] ?: nearestThumbnail(thumbnails, slice) ?: return
    scope.drawImage(
        bitmap,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(left.roundToInt(), layout.thumbnailsTop.roundToInt()),
        dstSize = IntSize(tileWidth.roundToInt() + 1, tileHeight.roundToInt()),
    )
}

/** While thumbnails are still being generated, a tile borrows the closest one that is ready. */
private fun nearestThumbnail(
    thumbnails: Map<Int, ImageBitmap>,
    index: Int,
): ImageBitmap? = thumbnails.minByOrNull { kotlin.math.abs(it.key - index) }?.value

private fun DrawContext.drawWaveform() {
    val waveform = state.waveform ?: return
    val peaks = waveform.peaks
    if (peaks.isEmpty() || waveform.durationMs <= 0) return
    val bucketMs = waveform.durationMs.toDouble() / peaks.size
    val mid = layout.waveformTop + layout.waveformHeight / 2
    val half = layout.waveformHeight / 2 - 2 * layout.density
    var x = 0f
    while (x < width) {
        val t0 = viewport.scrollMs + x / viewport.pxPerMs
        if (t0 >= waveform.durationMs) break
        val t1 = t0 + WAVEFORM_COLUMN_PX / viewport.pxPerMs
        val first = floor(t0 / bucketMs).toInt().coerceIn(0, peaks.size - 1)
        val last = ceil(t1 / bucketMs).toInt().coerceIn(first + 1, peaks.size)
        var peak = 0f
        for (i in first until last) peak = maxOf(peak, peaks[i])
        val h = maxOf(1f, peak * half)
        scope.drawLine(colors.waveform, Offset(x, mid - h), Offset(x, mid + h), WAVEFORM_COLUMN_PX)
        x += WAVEFORM_COLUMN_PX
    }
}

private fun DrawContext.drawHeatStrip() {
    val scores = state.scores ?: return
    if (scores.values.isEmpty() || scores.hz <= 0) return
    var x = 0f
    while (x < width) {
        val t0 = viewport.scrollMs + x / viewport.pxPerMs
        if (t0 >= state.durationMs) break
        val t1 = t0 + WAVEFORM_COLUMN_PX / viewport.pxPerMs
        val first = floor(t0 / 1000.0 * scores.hz).toInt().coerceIn(0, scores.values.size - 1)
        val last = ceil(t1 / 1000.0 * scores.hz).toInt().coerceIn(first + 1, scores.values.size)
        var value = 0.0
        for (i in first until last) value = maxOf(value, scores.values[i])
        scope.drawRect(
            lerp(colors.timelineTrack, colors.probabilityHigh, value.toFloat().coerceIn(0f, 1f)),
            topLeft = Offset(x, layout.heatTop),
            size = Size(WAVEFORM_COLUMN_PX, layout.heatHeight),
        )
        x += WAVEFORM_COLUMN_PX
    }
}

private fun DrawContext.drawSegments() {
    val selection = state.timeline?.selection ?: emptySet()
    val visibleEnd = viewport.pxToTime(width.toDouble())
    val pad = 3 * layout.density
    for (segment in state.segments) {
        if (segment.endMs < viewport.scrollMs || segment.startMs > visibleEnd) continue
        val left = viewport.timeToPx(segment.startMs).toFloat()
        val right = viewport.timeToPx(segment.endMs).toFloat()
        val topLeft = Offset(left, layout.trackTop + pad)
        val size = Size(maxOf(right - left, 1f), layout.trackHeight - 2 * pad)
        scope.drawRect(if (segment.accepted) colors.rallyBlock else colors.rallyBlockRejected, topLeft, size)
        if (segment.id in selection) {
            scope.drawRect(outline, topLeft, size, style = Stroke(2 * layout.density))
        }
        drawEdgeHandle(left, pad)
        drawEdgeHandle(right, pad)
    }
}

private fun DrawContext.drawEdgeHandle(
    x: Float,
    pad: Float,
) {
    scope.drawLine(
        outline,
        Offset(x, layout.trackTop + pad),
        Offset(x, layout.trackTop + layout.trackHeight - pad),
        2 * layout.density,
    )
}

private fun DrawContext.drawMarksAndGuides() {
    listOfNotNull(state.markInMs, state.markOutMs).forEach { mark ->
        val x = viewport.timeToPx(mark).toFloat()
        scope.drawLine(colors.probabilityHigh, Offset(x, 0f), Offset(x, layout.totalHeight), 1.5f * layout.density)
    }
    state.snapGuideMs?.let { guide ->
        val x = viewport.timeToPx(guide).toFloat()
        scope.drawLine(outline, Offset(x, layout.trackTop), Offset(x, layout.totalHeight), layout.density)
    }
}

private fun DrawContext.drawPlayhead() {
    val x = viewport.timeToPx(state.playheadMs).toFloat()
    if (x < -1 || x > width + 1) return
    val head = 6 * layout.density
    scope.drawLine(colors.playhead, Offset(x, 0f), Offset(x, layout.totalHeight), 2 * layout.density)
    val triangle =
        Path().apply {
            moveTo(x - head, 0f)
            lineTo(x + head, 0f)
            lineTo(x, head * 1.4f)
            close()
        }
    scope.drawPath(triangle, colors.playhead)
}
