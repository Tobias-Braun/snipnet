package app.snipnet.desktop.video

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/** Container and stream facts of a media file, as reported by FFmpeg. */
data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Double,
    val videoCodec: String?,
    val audioCodec: String?,
    val audioSampleRate: Int,
    val audioChannels: Int,
) {
    val hasAudio: Boolean get() = audioCodec != null && audioChannels > 0

    /** Duration of one frame in milliseconds, the granularity of frame-accurate seeking. */
    val frameDurationMs: Double get() = if (frameRate > 0) 1000.0 / frameRate else 40.0
}

/** One generated timeline thumbnail: the JPEG on disk and the media time it was taken at. */
data class Thumbnail(
    val index: Int,
    val timestampMs: Long,
    val file: Path,
)

/**
 * Peak amplitudes of the audio track, one value in 0..1 per bucket, buckets evenly spread over [durationMs].
 * Empty [peaks] means the file has no audio track.
 */
class Waveform(
    val durationMs: Long,
    val peaks: FloatArray,
)

/**
 * Everything the editor needs from the video stack: probing, playback, thumbnails and waveform. The interface hides
 * the FFmpeg/JavaCV implementation so UI and editing code can be tested with fakes.
 */
interface VideoEngine {
    /** Reads container and stream metadata without decoding any frames. */
    suspend fun probe(file: Path): VideoInfo

    /** Opens a player for [file]. The caller owns it and must [VideoPlayer.close] it. */
    suspend fun open(file: Path): VideoPlayer

    /**
     * Emits [count] thumbnails of [height] pixels, evenly spread over the video, as they become available.
     * Thumbnails already on disk are emitted immediately; the flow runs on a background dispatcher and is cancelled
     * with its collector.
     */
    fun thumbnails(
        file: Path,
        count: Int,
        height: Int,
    ): Flow<Thumbnail>

    /** Computes (or loads from the disk cache) the audio peak envelope with [buckets] values. */
    suspend fun waveform(
        file: Path,
        buckets: Int,
    ): Waveform
}

/**
 * A playing or paused video. Decoded frames are pushed to [frames] (also while paused after a seek), audio is played
 * through the default output device and follows the same clock as the picture.
 */
interface VideoPlayer : AutoCloseable {
    val info: VideoInfo

    /**
     * Most recent decoded picture. A late collector gets the latest frame first; a slow collector skips frames
     * instead of stalling playback.
     */
    val frames: SharedFlow<ImageBitmap>

    /** Media time of the frame currently shown, in milliseconds. */
    val position: StateFlow<Long>

    val isPlaying: StateFlow<Boolean>

    /** Playback speed multiplier; audio is only audible at 1.0. */
    val rate: StateFlow<Double>

    /** Starts (or resumes) playback; at the end of the video it restarts from the beginning. */
    fun play()

    fun pause()

    /**
     * Jumps to [positionMs] and shows the frame there. With [exact] the frame at that time is decoded (starting from
     * the previous keyframe), otherwise the nearest earlier keyframe is shown, which is much faster for scrubbing.
     */
    fun seek(
        positionMs: Long,
        exact: Boolean = true,
    )

    fun setRate(rate: Double)
}
