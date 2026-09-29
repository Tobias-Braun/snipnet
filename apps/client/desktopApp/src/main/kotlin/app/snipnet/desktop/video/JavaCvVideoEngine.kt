package app.snipnet.desktop.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ShortBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [VideoEngine] on top of FFmpeg through JavaCV. Thumbnails and waveforms are cached under [cacheDir], everything
 * blocking runs on [Dispatchers.IO]. [audioOutput] false makes players silent, which tests use to not beep.
 */
class JavaCvVideoEngine(
    cacheDir: Path,
    private val audioOutput: Boolean = true,
) : VideoEngine {
    private val cache = MediaCache(cacheDir)

    override suspend fun probe(file: Path): VideoInfo =
        withContext(Dispatchers.IO) {
            newGrabber(file).use { grabber ->
                grabber.start()
                if (!grabber.hasVideo()) throw VideoEngineException("$file has no video stream")
                VideoInfo(
                    durationMs = grabber.lengthInTime / 1000,
                    width = grabber.imageWidth,
                    height = grabber.imageHeight,
                    frameRate = grabber.videoFrameRate,
                    videoCodec = grabber.videoCodecName,
                    audioCodec = if (grabber.hasAudio()) grabber.audioCodecName else null,
                    audioSampleRate = if (grabber.hasAudio()) grabber.sampleRate else 0,
                    audioChannels = if (grabber.hasAudio()) grabber.audioChannels else 0,
                )
            }
        }

    override suspend fun open(file: Path): VideoPlayer = JavaCvVideoPlayer(file, probe(file), audioOutput)

    override fun thumbnails(
        file: Path,
        count: Int,
        height: Int,
    ): Flow<Thumbnail> {
        require(count > 0 && height > 0) { "count and height must be positive" }
        return flow {
            val info = probe(file)
            val dir = cache.directory(file, "thumbnails-${count}x$height")
            var grabber: FFmpegFrameGrabber? = null
            val converter = Java2DFrameConverter()
            try {
                for (index in 0 until count) {
                    coroutineContext.ensureActive()
                    // Thumbnails sit in the middle of their slice of the timeline, so the first one is not the black
                    // opening frame.
                    val timestampMs = (info.durationMs * (index + 0.5) / count).toLong()
                    val target = dir.resolve("$index.jpg")
                    if (!Files.exists(target)) {
                        val open =
                            grabber ?: newGrabber(file)
                                .apply {
                                    imageHeight = height
                                    imageWidth = evenWidth(height, info)
                                    start()
                                }.also { grabber = it }
                        val frame = open.grabAt(timestampMs) ?: continue
                        val image = converter.convert(frame) ?: continue
                        cache.writeAtomically(
                            target,
                        ) { tmp -> Files.newOutputStream(tmp).use { ImageIO.write(image, "jpg", it) } }
                    }
                    emit(Thumbnail(index, timestampMs, target))
                }
            } finally {
                grabber?.let { runCatching { it.stop() } }
                grabber?.let { runCatching { it.release() } }
                converter.close()
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun waveform(
        file: Path,
        buckets: Int,
    ): Waveform {
        require(buckets > 0) { "buckets must be positive" }
        return withContext(Dispatchers.IO) {
            val info = probe(file)
            if (!info.hasAudio) return@withContext Waveform(info.durationMs, FloatArray(0))
            val target = cache.directory(file, "waveform").resolve("$buckets.bin")
            readWaveform(target)?.let { return@withContext Waveform(info.durationMs, it) }
            val peaks = computePeaks(file, info, buckets)
            cache.writeAtomically(target) { tmp ->
                DataOutputStream(Files.newOutputStream(tmp).buffered()).use { out ->
                    out.writeInt(peaks.size)
                    peaks.forEach { out.writeFloat(it) }
                }
            }
            Waveform(info.durationMs, peaks)
        }
    }

    /**
     * Decodes the audio at a reduced mono sample rate (a peak envelope does not need more) and keeps the largest
     * absolute sample of every bucket. Buckets are assigned by sample position rather than by frame timestamp, so
     * containers with gaps or odd timestamps still yield an envelope that spans the whole file.
     */
    private suspend fun computePeaks(
        file: Path,
        info: VideoInfo,
        buckets: Int,
    ): FloatArray {
        val peaks = FloatArray(buckets)
        val totalSamples = maxOf(1L, info.durationMs * ENVELOPE_SAMPLE_RATE / 1000)
        newGrabber(file).use { grabber ->
            grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
            grabber.audioChannels = 1
            grabber.sampleRate = ENVELOPE_SAMPLE_RATE
            grabber.start()
            var seen = 0L
            while (true) {
                coroutineContext.ensureActive()
                val frame = grabber.grabSamples() ?: break
                val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                val buffer = samples.duplicate().also { it.rewind() }
                while (buffer.hasRemaining()) {
                    val bucket = (seen * buckets / totalSamples).toInt().coerceIn(0, buckets - 1)
                    val amplitude = abs(buffer.get().toInt()) / SHORT_FULL_SCALE
                    if (amplitude > peaks[bucket]) peaks[bucket] = amplitude
                    seen++
                }
            }
        }
        return peaks
    }

    private fun readWaveform(source: Path): FloatArray? {
        if (!Files.exists(source)) return null
        return runCatching {
            DataInputStream(Files.newInputStream(source).buffered()).use { input ->
                FloatArray(input.readInt()) { input.readFloat() }
            }
        }.getOrNull()
    }

    /** Positions on the keyframe at or before [timestampMs]; for thumbnails the nearest keyframe is close enough. */
    private fun FFmpegFrameGrabber.grabAt(timestampMs: Long) =
        run {
            setTimestamp(timestampMs * 1000, false)
            grabImage()
        }

    /** Width for a thumbnail of [height] that keeps the aspect ratio; scaling contexts want even dimensions. */
    private fun evenWidth(
        height: Int,
        info: VideoInfo,
    ): Int {
        val width = (height * info.width.toDouble() / info.height).roundToInt()
        return maxOf(2, width - width % 2)
    }

    private fun newGrabber(file: Path): FFmpegFrameGrabber {
        FfmpegRuntime.ensureLoaded()
        if (!Files.isRegularFile(file)) throw VideoEngineException("$file does not exist")
        return FFmpegFrameGrabber(file.toFile())
    }

    private inline fun <T> FFmpegFrameGrabber.use(block: (FFmpegFrameGrabber) -> T): T =
        try {
            block(this)
        } finally {
            runCatching { stop() }
            runCatching { release() }
        }

    private companion object {
        const val ENVELOPE_SAMPLE_RATE = 8_000
        const val SHORT_FULL_SCALE = 32768f
    }
}

/** A file cannot be probed or played, for example because it is missing or has no video stream. */
class VideoEngineException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
