package app.snipnet.shared.editing

import kotlin.math.roundToLong

/**
 * The visible window onto the timeline: [pxPerMs] is the zoom and [scrollMs] the media time at the left edge.
 * Pixel positions are relative to the left edge of the timeline view.
 */
data class Viewport(
    val pxPerMs: Double,
    val scrollMs: Long = 0,
) {
    init {
        require(pxPerMs > 0.0 && pxPerMs.isFinite()) { "pxPerMs must be positive and finite but was $pxPerMs" }
    }

    fun timeToPx(timeMs: Long): Double = (timeMs - scrollMs) * pxPerMs

    fun pxToTime(px: Double): Long = scrollMs + (px / pxPerMs).roundToLong()

    /** Media time range visible in a view [widthPx] wide. */
    fun visibleRangeMs(widthPx: Double): LongRange = scrollMs..pxToTime(widthPx)

    /**
     * Zooms by [factor] (above 1 zooms in) keeping the media time under [anchorPx] fixed, e.g. the cursor position
     * during a pinch or ctrl-scroll. The result is clamped to [minPxPerMs]..[maxPxPerMs] and scrolled back into
     * `0..durationMs`.
     */
    fun zoomAround(
        anchorPx: Double,
        factor: Double,
        durationMs: Long,
        widthPx: Double,
        minPxPerMs: Double,
        maxPxPerMs: Double,
    ): Viewport {
        val anchorMs = pxToTime(anchorPx)
        val zoom = (pxPerMs * factor).coerceIn(minPxPerMs, maxPxPerMs)
        val scroll = anchorMs - (anchorPx / zoom).roundToLong()
        return Viewport(zoom, scroll).clampScroll(durationMs, widthPx)
    }

    /** Scrolls by a pixel delta, clamped to the video. */
    fun scrollBy(
        deltaPx: Double,
        durationMs: Long,
        widthPx: Double,
    ): Viewport = copy(scrollMs = scrollMs + (deltaPx / pxPerMs).roundToLong()).clampScroll(durationMs, widthPx)

    /** Keeps the visible window inside the video; a video shorter than the view is pinned to the left edge. */
    fun clampScroll(
        durationMs: Long,
        widthPx: Double,
    ): Viewport {
        val visibleMs = (widthPx / pxPerMs).roundToLong()
        return copy(scrollMs = scrollMs.coerceIn(0, maxOf(0, durationMs - visibleMs)))
    }
}
