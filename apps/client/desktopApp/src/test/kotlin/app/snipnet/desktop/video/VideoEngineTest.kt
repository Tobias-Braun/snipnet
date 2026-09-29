package app.snipnet.desktop.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoEngineTest {
    private val tmp: Path = Files.createTempDirectory("snipnet-video")

    private val engine by lazy { JavaCvVideoEngine(tmp.resolve("cache"), audioOutput = false) }
    private val clip by lazy { TestClips.frameCounter(tmp.resolve("clip.mp4")) }
    private val players = mutableListOf<VideoPlayer>()

    @AfterTest
    fun cleanUp() {
        players.forEach { it.close() }
        tmp.toFile().deleteRecursively()
    }

    private suspend fun open(): VideoPlayer = engine.open(clip).also { players += it }

    /** Latest frame after the player settles on [positionMs]: the flow replays its newest picture to a new collector. */
    private suspend fun VideoPlayer.frameAfterSeek(
        positionMs: Long,
        exact: Boolean,
    ): Int {
        seek(positionMs, exact)
        // The first replayed picture may still be the one of the previous seek; wait for the position to change.
        withTimeout(TIMEOUT_MS) { position.first { if (exact) it in (positionMs - 40)..(positionMs + 40) else true } }
        return TestClips.frameIndexOf(withTimeout(TIMEOUT_MS) { frames.first() })
    }

    @Test
    fun ffmpegPathPointsToARunnableBinary() {
        val path = ffmpegPath()
        assertTrue(Files.isExecutable(path), "$path is not executable")
        val process = ProcessBuilder(path.toString(), "-version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertTrue(output.startsWith("ffmpeg version"), output)
    }

    @Test
    fun probeReportsStreamFacts() =
        runBlocking {
            val info = engine.probe(clip)
            assertEquals(320, info.width)
            assertEquals(240, info.height)
            assertEquals(TestClips.FPS.toDouble(), info.frameRate, 0.01)
            assertEquals("h264", info.videoCodec)
            assertEquals("aac", info.audioCodec)
            assertEquals(1, info.audioChannels)
            assertEquals(44100, info.audioSampleRate)
            assertTrue(info.hasAudio)
            assertTrue(info.durationMs in 3_900..4_200, "duration was ${info.durationMs}")
        }

    @Test
    fun probeReadsTheEmbeddedStartTimecode() =
        runBlocking {
            assertEquals(null, engine.probe(clip).startTimecode)
            val stamped = TestClips.withTimecode(clip, tmp.resolve("stamped.mov"), "10:00:00:00")
            assertEquals("10:00:00:00", engine.probe(stamped).startTimecode)
        }

    @Test
    fun probeRejectsMissingFiles() {
        assertFailsWith<VideoEngineException> { runBlocking { engine.probe(tmp.resolve("missing.mp4")) } }
    }

    @Test
    fun probeRejectsFilesThatAreNotMedia() {
        val text = Files.writeString(tmp.resolve("notes.mp4"), "not a video")
        assertFailsWith<VideoEngineException> { runBlocking { engine.probe(text) } }
    }

    @Test
    fun exactSeekLandsOnTheRequestedFrameEvenBetweenKeyframes() =
        runBlocking {
            val player = open()
            // 1720 ms is frame 43, far from the keyframes at frames 25 and 50.
            assertEquals(43, player.frameAfterSeek(1_720, exact = true))
            assertEquals(2, player.frameAfterSeek(80, exact = true))
            assertEquals(75, player.frameAfterSeek(3_000, exact = true))
        }

    @Test
    fun inexactSeekNeverOvershootsTheTarget() =
        runBlocking {
            val player = open()
            // The keyframe before frame 43 is frame 25; the demuxer may decode a few frames past it but never beyond.
            val frame = player.frameAfterSeek(1_720, exact = false)
            assertTrue(frame in 25..43, "showed frame $frame")
        }

    @Test
    fun playbackAdvancesThePositionAndPauseHoldsIt() =
        runBlocking {
            val player = open()
            player.play()
            assertTrue(player.isPlaying.value)
            withTimeout(TIMEOUT_MS) { player.position.first { it >= 400 } }
            player.pause()
            assertFalse(player.isPlaying.value)

            val held = player.position.value
            Thread.sleep(300)
            assertEquals(held, player.position.value)
        }

    @Test
    fun playbackPicturesFollowThePosition() =
        runBlocking {
            val player = open()
            player.seek(1_000, exact = true)
            withTimeout(TIMEOUT_MS) { player.position.first { it in 960..1040 } }
            player.play()
            withTimeout(TIMEOUT_MS) { player.position.first { it >= 1_600 } }
            player.pause()
            val shown = TestClips.frameIndexOf(player.frames.first())
            assertEquals(player.position.value / 40, shown.toLong(), "picture and position disagree")
        }

    @Test
    fun higherRateFinishesEarlyAndStopsAtTheEnd() =
        runBlocking {
            val player = open()
            player.setRate(8.0)
            assertEquals(8.0, player.rate.value)
            player.seek(0, exact = true)
            player.play()
            withTimeout(TIMEOUT_MS) { player.isPlaying.first { !it } }
            assertTrue(player.position.value >= 3_800, "stopped at ${player.position.value}")
        }

    @Test
    fun playingAfterTheEndRestartsFromTheBeginning() =
        runBlocking {
            val player = open()
            player.setRate(8.0)
            player.play()
            withTimeout(TIMEOUT_MS) { player.isPlaying.first { !it } }
            val endPosition = player.position.value
            assertTrue(endPosition >= 3_800, "first run stopped at $endPosition")

            // Recorded from before the second play() so the jump back to the start cannot be missed at 8x speed.
            val seen = CopyOnWriteArrayList<Long>()
            val recorder = launch(Dispatchers.Default) { player.position.collect { seen += it } }
            player.play()
            withTimeout(TIMEOUT_MS) { player.isPlaying.first { !it } }
            recorder.cancel()
            assertTrue(seen.any { it < 1_000 }, "never went back to the start: $seen")
            assertTrue(player.position.value >= 3_800, "second run stopped at ${player.position.value}")
        }

    @Test
    fun thumbnailsAreGeneratedAndServedFromTheCacheAfterwards() =
        runBlocking {
            val first = engine.thumbnails(clip, count = 4, height = 48).toList()
            assertEquals(listOf(0, 1, 2, 3), first.map { it.index })
            first.forEach {
                val image = ImageIO.read(it.file.toFile())
                assertEquals(48, image.height)
                assertEquals(64, image.width)
            }
            // Each thumbnail is the middle of its slice, so the picture brightness rises with the index.
            val brightness = first.map { ImageIO.read(it.file.toFile()).getRGB(5, 5) and 0xff }
            assertEquals(brightness.sorted(), brightness)

            val modified = first.map { Files.getLastModifiedTime(it.file) }
            val second = engine.thumbnails(clip, count = 4, height = 48).toList()
            assertEquals(first, second)
            assertEquals(modified, second.map { Files.getLastModifiedTime(it.file) })
        }

    @Test
    fun waveformShowsTheLoudFirstHalfAndTheSilentSecondHalf() =
        runBlocking {
            val waveform = engine.waveform(clip, buckets = 20)
            assertEquals(20, waveform.peaks.size)
            assertTrue(waveform.peaks.take(8).all { it > 0.5f }, waveform.peaks.joinToString())
            assertTrue(waveform.peaks.takeLast(8).all { it < 0.05f }, waveform.peaks.joinToString())

            val cached = engine.waveform(clip, buckets = 20)
            assertTrue(waveform.peaks.contentEquals(cached.peaks))
        }

    @Test
    fun cacheIsInvalidatedWhenTheSourceChanges() =
        runBlocking {
            val before = engine.thumbnails(clip, count = 2, height = 32).toList()
            Files.setLastModifiedTime(
                clip,
                java.nio.file.attribute.FileTime.fromMillis(
                    System.currentTimeMillis() + 60_000,
                ),
            )
            val after = engine.thumbnails(clip, count = 2, height = 32).toList()
            assertTrue(before.map { it.file }.intersect(after.map { it.file }.toSet()).isEmpty())
        }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
