package app.snipnet.desktop.export

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * A video frame rate as the exact fraction [numerator]/[denominator] frames per second, which is what FCPXML needs
 * (29.97 fps is 30000/1001, not 29.97) and what keeps frame counts free of rounding drift.
 */
data class FrameRate(
    val numerator: Int,
    val denominator: Int,
) {
    /** The integer rate timecodes count in: 30 for 29.97 fps. */
    val nominal: Int get() = (numerator.toDouble() / denominator).roundToInt()

    /** The frame index nearest to [ms]. */
    fun framesAt(ms: Long): Long = (ms * numerator / (1000.0 * denominator)).roundToLong()

    /** FCPXML time value for [frames] frames, for example `100/25s`. */
    fun rational(frames: Long): String = if (frames == 0L) "0s" else "${frames * denominator}/${numerator}s"

    /** FCPXML duration of one frame, for example `1001/30000s`. */
    val frameDuration: String get() = "$denominator/${numerator}s"

    /** Non-drop-frame `HH:MM:SS:FF` timecode of [frames], counting [nominal] frames per second. */
    fun timecode(frames: Long): String {
        val fps = nominal
        val frame = frames % fps
        val totalSeconds = frames / fps
        return "%02d:%02d:%02d:%02d".format(totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60, frame)
    }

    /**
     * The frame index a `HH:MM:SS:FF` or drop-frame `HH:MM:SS;FF` [timecode] denotes, or null when it is not a
     * timecode. Drop-frame values (only defined for 29.97 and 59.94 fps) are converted to the real frame count.
     */
    fun framesOf(timecode: String): Long? {
        val match = TIMECODE.matchEntire(timecode.trim()) ?: return null
        val (h, m, s, separator, f) = match.destructured
        val fps = nominal
        val frame = f.toLong()
        if (frame >= fps) return null
        val minutes = h.toLong() * 60 + m.toLong()
        val nominalFrames = (minutes * 60 + s.toLong()) * fps + frame
        if (separator != ";" || denominator != 1001 || fps % 30 != 0) return nominalFrames
        // Drop frame skips frame numbers 0 until drop at every minute except each tenth one.
        val drop = fps / 15
        return nominalFrames - drop * (minutes - minutes / 10)
    }

    companion object {
        private val TIMECODE = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})([:;])(\\d{2,3})")

        /** Integer rates that also exist as NTSC "/1.001" variants. */
        private val NTSC_BASES = listOf(24, 30, 48, 60, 120)
        private const val NTSC_TOLERANCE = 0.02
        private const val FALLBACK_FPS = 25

        /**
         * Snaps a probed, possibly slightly inexact [fps] (29.97002997...) to the nearest standard rate. Values
         * that match no NTSC rate are rounded to a whole number; an unusable value falls back to 25.
         */
        fun fromFps(fps: Double): FrameRate {
            if (!fps.isFinite() || fps < 1.0) return FrameRate(FALLBACK_FPS, 1)
            NTSC_BASES.firstOrNull { abs(fps - it * 1000.0 / 1001.0) < NTSC_TOLERANCE }?.let {
                return FrameRate(it * 1000, 1001)
            }
            return FrameRate(fps.roundToInt(), 1)
        }
    }
}
