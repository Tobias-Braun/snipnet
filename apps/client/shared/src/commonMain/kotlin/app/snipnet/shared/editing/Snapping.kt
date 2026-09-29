package app.snipnet.shared.editing

import app.snipnet.shared.model.ScoreCurve
import kotlin.math.abs
import kotlin.math.roundToLong

/** Score value at which the rally probability counts as switching between dead time and rally. */
const val SCORE_TRANSITION_THRESHOLD: Double = 0.5

/** Snap distance on screen; the tolerance in milliseconds is derived from the zoom level. */
const val SNAP_TOLERANCE_PX: Double = 8.0

/**
 * Times in the score curve where the rally probability crosses [SCORE_TRANSITION_THRESHOLD], taken at the first
 * sample on the new side. These are where a model thinks a rally starts or ends, so they make good snap targets.
 */
fun ScoreCurve.transitionTimesMs(): List<Long> {
    if (hz <= 0.0) return emptyList()
    val times = ArrayList<Long>()
    for (i in 1 until values.size) {
        val was = values[i - 1] >= SCORE_TRANSITION_THRESHOLD
        val now = values[i] >= SCORE_TRANSITION_THRESHOLD
        if (was != now) times.add((i * 1000.0 / hz).roundToLong())
    }
    return times
}

/**
 * Candidate times a dragged edge can snap to: the playhead, the edges of every segment except those in
 * [excludeIds] (the ones being dragged) and the score curve transitions.
 */
fun snapTargets(
    timeline: Timeline,
    scores: ScoreCurve? = null,
    excludeIds: Set<Long> = emptySet(),
): List<Long> {
    val edges = timeline.segments.filter { it.id !in excludeIds }.flatMap { listOf(it.startMs, it.endMs) }
    return (listOf(timeline.playheadMs) + edges + (scores?.transitionTimesMs() ?: emptyList())).distinct()
}

/**
 * Returns the target closest to [timeMs] if it lies within [tolerancePx] on screen at the given zoom, otherwise
 * [timeMs] itself. Zooming in shrinks the tolerance in milliseconds, so snapping gets finer as the user zooms.
 */
fun snap(
    timeMs: Long,
    targets: List<Long>,
    viewport: Viewport,
    tolerancePx: Double = SNAP_TOLERANCE_PX,
): Long {
    val toleranceMs = tolerancePx / viewport.pxPerMs
    val nearest = targets.minByOrNull { abs(it - timeMs) } ?: return timeMs
    return if (abs(nearest - timeMs) <= toleranceMs) nearest else timeMs
}
