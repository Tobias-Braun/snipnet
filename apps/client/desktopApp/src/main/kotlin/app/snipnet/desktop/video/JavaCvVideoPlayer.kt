package app.snipnet.desktop.video

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.withLock

/**
 * Playback over two independent FFmpeg demuxers, one decoding pictures and one decoding audio, each on its own
 * thread. Splitting them avoids the classic single-thread problem where a container interleaves audio and video
 * in chunks of up to a second: a single reader that sleeps on a video frame starves the audio buffer, and one that
 * reads ahead needs unbounded queues. Here each thread simply waits for its next sample to become due on the shared
 * [MediaClock] and outputs it.
 *
 * All state shared between the threads and the caller (playing flag, rate, seek requests, the clock) is guarded
 * by [lock]. A seek bumps [generation]; every thread notices the change, repositions its own demuxer and reports
 * back through [seekDone]. The clock only starts again once all threads have finished their seek, so playback
 * never starts with the picture still catching up to the target.
 */
internal class JavaCvVideoPlayer(
    private val file: Path,
    override val info: VideoInfo,
    audioOutput: Boolean = true,
) : VideoPlayer {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val clock = MediaClock()

    private var playing = false
    private var playbackRate = 1.0
    private var generation = 0
    private var seekTargetMs = 0L
    private var seekExact = true
    private var pendingSeeks = 0
    private var closed = false

    private val audioLine: SourceDataLine? = if (audioOutput && info.hasAudio) openAudioLine() else null
    private val pipelineCount = if (audioLine != null) 2 else 1

    private val frameFlow = MutableSharedFlow<ImageBitmap>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val positionFlow = MutableStateFlow(0L)
    private val playingFlow = MutableStateFlow(false)
    private val rateFlow = MutableStateFlow(1.0)

    override val frames: SharedFlow<ImageBitmap> = frameFlow
    override val position: StateFlow<Long> = positionFlow.asStateFlow()
    override val isPlaying: StateFlow<Boolean> = playingFlow.asStateFlow()
    override val rate: StateFlow<Double> = rateFlow.asStateFlow()

    private val threads = mutableListOf<Thread>()

    init {
        lock.withLock { requestSeekLocked(0, exact = true) }
        threads += startThread("snipnet-video", ::runVideo)
        if (audioLine != null) threads += startThread("snipnet-audio", ::runAudio)
    }

    override fun play() {
        lock.withLock {
            if (closed) return
            if (positionFlow.value >= info.durationMs - 1) requestSeekLocked(0, exact = true)
            playing = true
            playingFlow.value = true
            if (pendingSeeks == 0) clock.start()
            changed.signalAll()
        }
        audioLine?.start()
    }

    override fun pause() {
        lock.withLock {
            playing = false
            playingFlow.value = false
            clock.stop()
            changed.signalAll()
        }
        audioLine?.stop()
    }

    override fun seek(
        positionMs: Long,
        exact: Boolean,
    ) {
        lock.withLock { if (!closed) requestSeekLocked(positionMs, exact) }
        audioLine?.flush()
    }

    override fun setRate(rate: Double) {
        require(rate > 0) { "rate must be positive" }
        lock.withLock {
            if (closed) return
            playbackRate = rate
            rateFlow.value = rate
            clock.rate = rate
            // Audio is only produced at 1x, so a rate change re-synchronizes both threads from the current position.
            requestSeekLocked(clock.nowMs().toLong(), exact = true)
        }
        audioLine?.flush()
    }

    override fun close() {
        lock.withLock {
            closed = true
            playing = false
            playingFlow.value = false
            changed.signalAll()
        }
        audioLine?.stop()
        audioLine?.flush()
        threads.forEach { it.join(2_000) }
        audioLine?.close()
    }

    private fun requestSeekLocked(
        positionMs: Long,
        exact: Boolean,
    ) {
        // Clamped to the last frame: a target at or past the end would decode to the end of the stream and find nothing.
        val lastFrameMs = maxOf(0L, info.durationMs - info.frameDurationMs.toLong())
        val target = positionMs.coerceIn(0, lastFrameMs)
        generation++
        seekTargetMs = target
        seekExact = exact
        pendingSeeks = pipelineCount
        clock.stopAt(target)
        changed.signalAll()
    }

    private fun seekDone(gen: Int) =
        lock.withLock {
            if (gen != generation) return@withLock
            pendingSeeks--
            if (pendingSeeks == 0 && playing) clock.start()
            changed.signalAll()
        }

    /** What a pipeline thread should do next, decided under the lock. */
    private sealed interface Work {
        data class Seek(
            val generation: Int,
            val targetMs: Long,
            val exact: Boolean,
        ) : Work

        data object Play : Work

        data object Stop : Work
    }

    private fun nextWork(localGen: Int): Work =
        lock.withLock {
            while (!closed) {
                if (generation != localGen) return@withLock Work.Seek(generation, seekTargetMs, seekExact)
                if (playing && pendingSeeks == 0) return@withLock Work.Play
                changed.await()
            }
            Work.Stop
        }

    /**
     * Blocks until [timestampMs] is reached on the media clock. Returns false when the wait became pointless
     * (paused, seeked away or closed) so the caller drops the sample it was holding.
     */
    private fun awaitDue(
        gen: Int,
        timestampMs: Long,
    ): Boolean =
        lock.withLock {
            while (true) {
                if (closed || generation != gen || !playing) return@withLock false
                val remaining = timestampMs - clock.nowMs()
                if (remaining <= 0) return@withLock true
                val waitMs = (remaining / playbackRate).toLong().coerceIn(1, MAX_WAIT_SLICE_MS)
                changed.await(waitMs, TimeUnit.MILLISECONDS)
            }
            false
        }

    private fun isCurrent(gen: Int): Boolean = lock.withLock { !closed && generation == gen }

    private fun runVideo() {
        val grabber = newGrabber()
        val converter = Java2DFrameConverter()
        try {
            grabber.start()
            var localGen = 0
            while (true) {
                when (val work = nextWork(localGen)) {
                    is Work.Seek -> {
                        localGen = work.generation
                        seekVideo(grabber, work, converter)
                        seekDone(work.generation)
                    }
                    Work.Play -> playVideo(grabber, converter, localGen)
                    Work.Stop -> return
                }
            }
        } catch (e: Exception) {
            LOG.log(System.Logger.Level.ERROR, "video pipeline of $file failed", e)
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            converter.close()
        }
    }

    private fun seekVideo(
        grabber: FFmpegFrameGrabber,
        seek: Work.Seek,
        converter: Java2DFrameConverter,
    ) {
        grabber.setTimestamp(seek.targetMs * 1000, false)
        // The demuxer lands on the keyframe at or before the target. An exact seek decodes forward from there and
        // keeps the last frame that does not overshoot the target (within half a frame of tolerance).
        val toleranceMs = info.frameDurationMs / 2
        while (isCurrent(seek.generation)) {
            val frame = grabber.grabImage() ?: return
            val ts = frame.timestamp / 1000
            if (!seek.exact || ts + toleranceMs >= seek.targetMs) {
                if (isCurrent(seek.generation)) publish(frame, ts, converter)
                return
            }
        }
    }

    private fun playVideo(
        grabber: FFmpegFrameGrabber,
        converter: Java2DFrameConverter,
        gen: Int,
    ) {
        while (true) {
            val frame = grabber.grabImage()
            if (frame == null) {
                onEnded(gen)
                return
            }
            val ts = frame.timestamp / 1000
            if (!awaitDue(gen, ts)) return
            // A frame that is far behind the clock (slow decode) is skipped so the picture catches up instead
            // of drifting away from the audio.
            val lateMs = lock.withLock { clock.nowMs() } - ts
            if (lateMs > MAX_LATE_MS) continue
            publish(frame, ts, converter)
        }
    }

    private fun publish(
        frame: Frame,
        timestampMs: Long,
        converter: Java2DFrameConverter,
    ) {
        val image = converter.convert(frame) ?: return
        frameFlow.tryEmit(image.toComposeImageBitmap())
        positionFlow.value = timestampMs
    }

    private fun onEnded(gen: Int) {
        lock.withLock {
            if (generation != gen) return
            playing = false
            playingFlow.value = false
            clock.stopAt(info.durationMs)
            changed.signalAll()
        }
        audioLine?.stop()
    }

    private fun runAudio() {
        val line = audioLine ?: return
        val grabber = newGrabber(audio = true)
        try {
            grabber.start()
            var localGen = 0
            var firstAfterSeek = true
            while (true) {
                when (val work = nextWork(localGen)) {
                    is Work.Seek -> {
                        localGen = work.generation
                        grabber.setAudioTimestamp(work.targetMs * 1000)
                        firstAfterSeek = true
                        seekDone(work.generation)
                    }
                    Work.Play -> {
                        if (lock.withLock { playbackRate } != 1.0) {
                            waitForGenerationChange(localGen)
                        } else {
                            firstAfterSeek = playAudio(grabber, line, localGen, firstAfterSeek)
                        }
                    }
                    Work.Stop -> return
                }
            }
        } catch (e: Exception) {
            LOG.log(System.Logger.Level.ERROR, "audio pipeline of $file failed", e)
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
        }
    }

    /** Returns whether the next chunk is still the first one after a seek (i.e. nothing was written yet). */
    private fun playAudio(
        grabber: FFmpegFrameGrabber,
        line: SourceDataLine,
        gen: Int,
        wasFirstAfterSeek: Boolean,
    ): Boolean {
        var first = wasFirstAfterSeek
        while (true) {
            val frame = grabber.grabSamples()
            if (frame == null) {
                waitForGenerationChange(gen)
                return first
            }
            val bytes = frame.toPcmBytes() ?: continue
            val startMs = frame.timestamp / 1000
            val durationMs = bytes.size * 1000L / (info.audioSampleRate * BYTES_PER_SAMPLE * channelsOut())
            // The first chunk after a seek starts exactly when due. Later chunks are written a little early so the
            // output buffer never runs dry; writing the first one early would shift all audio ahead of the picture.
            val dueMs = if (first) startMs else startMs - AUDIO_LEAD_MS
            if (!awaitDue(gen, dueMs)) return first
            if (lock.withLock { clock.nowMs() } > startMs + durationMs + MAX_LATE_MS) continue
            line.write(bytes, 0, bytes.size)
            first = false
        }
    }

    private fun waitForGenerationChange(gen: Int) =
        lock.withLock {
            while (!closed && generation == gen) changed.await()
        }

    private fun channelsOut() = minOf(info.audioChannels, 2)

    private fun Frame.toPcmBytes(): ByteArray? {
        val buffer = samples?.firstOrNull() as? ShortBuffer ?: return null
        val shorts = buffer.duplicate().also { it.rewind() }
        val out = ByteBuffer.allocate(shorts.remaining() * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        out.asShortBuffer().put(shorts)
        return out.array()
    }

    private fun newGrabber(audio: Boolean = false): FFmpegFrameGrabber {
        FfmpegRuntime.ensureLoaded()
        return FFmpegFrameGrabber(file.toFile()).apply {
            if (audio) {
                // Interleaved signed 16 bit at the source rate, at most stereo: exactly what the sound line takes.
                sampleFormat = avutil.AV_SAMPLE_FMT_S16
                audioChannels = channelsOut()
                sampleRate = info.audioSampleRate
            } else {
                audioChannels = 0
                setVideoOption("threads", "auto")
            }
        }
    }

    private fun openAudioLine(): SourceDataLine? =
        try {
            val format = AudioFormat(info.audioSampleRate.toFloat(), 16, channelsOut(), true, false)
            val bufferBytes = info.audioSampleRate * BYTES_PER_SAMPLE * channelsOut() * LINE_BUFFER_MS / 1000
            AudioSystem.getSourceDataLine(format).also { it.open(format, bufferBytes) }
        } catch (e: Exception) {
            // Headless machines (CI) and machines without an output device play silently instead of failing.
            LOG.log(System.Logger.Level.WARNING, "no audio output, playing $file silently: ${e.message}")
            null
        }

    private fun startThread(
        name: String,
        body: () -> Unit,
    ): Thread = Thread(body, name).also { it.isDaemon = true }.also { it.start() }

    private companion object {
        val LOG: System.Logger = System.getLogger("snipnet.video")
        const val BYTES_PER_SAMPLE = 2
        const val MAX_WAIT_SLICE_MS = 20L
        const val MAX_LATE_MS = 250L
        const val AUDIO_LEAD_MS = 60L
        const val LINE_BUFFER_MS = 250
    }
}

/**
 * The playback position as a function of wall time. Not thread safe on its own: the player only touches it while
 * holding its lock.
 */
internal class MediaClock {
    private var baseMs = 0.0
    private var baseNanos = 0L
    private var running = false

    var rate = 1.0
        set(value) {
            baseMs = nowMs()
            baseNanos = System.nanoTime()
            field = value
        }

    fun nowMs(): Double = if (running) baseMs + (System.nanoTime() - baseNanos) / 1_000_000.0 * rate else baseMs

    fun start() {
        if (running) return
        baseNanos = System.nanoTime()
        running = true
    }

    fun stop() {
        baseMs = nowMs()
        running = false
    }

    fun stopAt(positionMs: Long) {
        baseMs = positionMs.toDouble()
        running = false
    }
}
