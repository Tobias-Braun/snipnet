package app.snipnet.desktop.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyTranscoderTest {
    private val tmp: Path = Files.createTempDirectory("snipnet-proxy")
    private val engine by lazy { JavaCvVideoEngine(tmp.resolve("cache"), audioOutput = false) }
    private val transcoder = FfmpegProxyTranscoder()

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun progressLinesAreScaledAndCapped() {
        assertEquals(0.5, FfmpegProxyTranscoder.parseProgress("out_time_us=2000000", 4_000)!!, 1e-9)
        assertEquals(0.99, FfmpegProxyTranscoder.parseProgress("out_time_us=9000000", 4_000)!!, 1e-9)
        assertEquals(0.0, FfmpegProxyTranscoder.parseProgress("out_time_us=-5", 4_000)!!, 1e-9)
    }

    @Test
    fun otherProgressLinesAreIgnored() {
        assertNull(FfmpegProxyTranscoder.parseProgress("out_time_us=N/A", 4_000))
        assertNull(FfmpegProxyTranscoder.parseProgress("frame=12", 4_000))
        assertNull(FfmpegProxyTranscoder.parseProgress("progress=end", 4_000))
        assertNull(FfmpegProxyTranscoder.parseProgress("out_time_us=1000", 0))
    }

    @Test
    fun proxyFollowsTheContractFormat() =
        runBlocking {
            val source = TestClips.benchmarkClip(tmp.resolve("big.mp4"), "1920x1080", 3)
            val output = tmp.resolve("out/proxy.mp4")
            val progress = CopyOnWriteArrayList<Double>()

            transcoder.transcode(source, output, engine.probe(source).durationMs) { progress += it }

            val info = engine.probe(output)
            assertEquals(854, info.width)
            assertEquals(480, info.height)
            assertTrue(abs(info.frameRate - FfmpegProxyTranscoder.FPS) < 0.01, "fps was ${info.frameRate}")
            assertEquals("h264", info.videoCodec)
            assertEquals(1.0, progress.last(), 0.0)
            assertEquals(progress.sorted(), progress.toList())
            assertFalse(Files.exists(output.resolveSibling("proxy.mp4.part")))
        }

    @Test
    fun portraitFootageIsLimitedOnItsLongSide() =
        runBlocking {
            val source = TestClips.benchmarkClip(tmp.resolve("portrait.mp4"), "1080x1920", 2)
            val output = tmp.resolve("portrait-proxy.mp4")
            transcoder.transcode(source, output, 2_000) {}
            val info = engine.probe(output)
            assertEquals(480, info.width)
            assertEquals(854, info.height)
        }

    @Test
    fun unreadableInputFailsAndLeavesNoFile() =
        runBlocking {
            val broken = Files.writeString(tmp.resolve("broken.mp4"), "not a video")
            val output = tmp.resolve("broken-proxy.mp4")
            assertFailsWith<TranscodeException> { transcoder.transcode(broken, output, 1_000) {} }
            assertFalse(Files.exists(output))
            assertFalse(Files.exists(output.resolveSibling("broken-proxy.mp4.part")))
        }

    @Test
    fun cancellingStopsFfmpegAndRemovesPartialOutput() =
        runBlocking {
            val source = TestClips.benchmarkClip(tmp.resolve("long.mp4"), "1280x720", 60)
            val output = tmp.resolve("long-proxy.mp4")
            val started = CompletableDeferred<Unit>()
            val job =
                launch(Dispatchers.Default) {
                    transcoder.transcode(source, output, 60_000) { started.complete(Unit) }
                }
            withTimeout(60_000) { started.await() }
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse(Files.exists(output))
            assertFalse(Files.exists(output.resolveSibling("long-proxy.mp4.part")))
        }

    /**
     * Killing ffmpeg can close its pipes under a blocked read, which used to surface as an IOException ("Stream
     * closed") instead of a cancellation. Cancelling at varying moments must always end as a cancellation.
     */
    @Test
    fun cancellingRepeatedlyAlwaysEndsAsCancellation() =
        runBlocking {
            val source = TestClips.benchmarkClip(tmp.resolve("repeat.mp4"), "1280x720", 60)
            repeat(8) { attempt ->
                val output = tmp.resolve("repeat-proxy-$attempt.mp4")
                val started = CompletableDeferred<Unit>()
                val job =
                    async(Dispatchers.Default) {
                        transcoder.transcode(source, output, 60_000) { started.complete(Unit) }
                    }
                withTimeout(60_000) { started.await() }
                delay(attempt * 15L)
                job.cancel()
                assertFailsWith<CancellationException> { job.await() }
                assertFalse(Files.exists(output))
                assertFalse(Files.exists(output.resolveSibling("repeat-proxy-$attempt.mp4.part")))
            }
        }
}
