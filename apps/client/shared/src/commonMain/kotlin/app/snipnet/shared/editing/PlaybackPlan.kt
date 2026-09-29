package app.snipnet.shared.editing

/** A half-open media time range `[startMs, endMs)`. */
data class TimeRange(
    val startMs: Long,
    val endMs: Long,
)

/**
 * The ranges the player visits in "rallies only" mode: the accepted segments in order. The player plays a range
 * to its end and then jumps to the next one, so dead time and rejected segments are skipped.
 */
data class PlaybackPlan(
    val ranges: List<TimeRange>,
) {
    val totalDurationMs: Long get() = ranges.sumOf { it.endMs - it.startMs }

    /**
     * Where playback should be at media time [timeMs]: the time itself when it lies inside a range, the start of
     * the next range when it lies in a gap, and null once every range is behind it (playback is finished).
     */
    fun nextPlayable(timeMs: Long): Long? {
        val range = ranges.firstOrNull { timeMs < it.endMs } ?: return null
        return maxOf(timeMs, range.startMs)
    }
}

/** Builds the rallies-only playback plan from the accepted segments. */
fun Timeline.playbackPlan(): PlaybackPlan =
    PlaybackPlan(
        segments
            .filter {
                it.accepted
            }.map { TimeRange(it.startMs, it.endMs) },
    )
