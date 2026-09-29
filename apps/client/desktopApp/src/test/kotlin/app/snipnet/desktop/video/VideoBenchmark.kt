package app.snipnet.desktop.video

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Measurements behind docs/adr/0001-video-engine.md. It generates 1080p and 4K clips, so it only runs when the
 * environment variable `SNIPNET_BENCHMARK` is set and prints its results instead of asserting on them:
 *
 * `SNIPNET_BENCHMARK=1 ./gradlew :desktopApp:test --tests '*VideoBenchmark*' -i`
 */
class VideoBenchmark {
    private val tmp: Path = Files.createTempDirectory("snipnet-benchmark")

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun measure() {
        if (System.getenv("SNIPNET_BENCHMARK").isNullOrBlank()) return
        val clips =
            listOf(
                Triple("1080p H.264", "1920x1080", "libx264"),
                Triple("4K H.264", "3840x2160", "libx264"),
                Triple("1080p HEVC", "1920x1080", "libx265"),
            )
        for ((label, size, codec) in clips) {
            val file =
                TestClips.benchmarkClip(
                    tmp.resolve("${label.replace(' ', '-')}.mp4"),
                    size,
                    seconds = 10,
                    codec = codec,
                )
            println("BENCH $label: decode ${decodeFps(file)} fps, seek ${seekLatencies(file)}")
        }
    }

    /** Decodes and converts every picture as fast as possible, the upper bound for real-time playback. */
    private fun decodeFps(file: Path): String {
        FfmpegRuntime.ensureLoaded()
        val grabber = FFmpegFrameGrabber(file.toFile()).apply { setVideoOption("threads", "auto") }
        grabber.start()
        var frames = 0
        val start = System.nanoTime()
        while (grabber.grabImage() != null) frames++
        val seconds = (System.nanoTime() - start) / 1e9
        grabber.stop()
        return "%.0f".format(frames / seconds)
    }

    /** Median time from `seek` to the new picture, measured on the real player (decode, convert and publish). */
    private fun seekLatencies(file: Path): String =
        runBlocking {
            val engine = JavaCvVideoEngine(tmp.resolve("cache"), audioOutput = false)
            engine.open(file).use { player ->
                suspend fun median(exact: Boolean): Long {
                    val samples =
                        (1..9).map { i ->
                            val target = i * 1_000L - 300 + (if (exact) 0 else 7)
                            val before = player.position.value
                            val start = System.nanoTime()
                            player.seek(target, exact)
                            withTimeout(30_000) { player.position.first { it != before } }
                            (System.nanoTime() - start) / 1_000_000
                        }
                    return samples.sorted()[samples.size / 2]
                }
                "exact ${median(exact = true)} ms, keyframe ${median(exact = false)} ms"
            }
        }
}
