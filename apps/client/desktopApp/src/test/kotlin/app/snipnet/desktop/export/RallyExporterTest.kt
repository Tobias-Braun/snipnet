package app.snipnet.desktop.export

import app.snipnet.desktop.video.JavaCvVideoEngine
import app.snipnet.desktop.video.TestClips
import app.snipnet.shared.editing.TimeRange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the real exporter with the bundled ffmpeg on a generated clip whose luma encodes the frame number. */
class RallyExporterTest {
    private val tmp: Path = Files.createTempDirectory("snipnet-export")
    private val out: Path = tmp.resolve("out")
    private val engine by lazy { JavaCvVideoEngine(tmp.resolve("cache"), audioOutput = false) }
    private val clip by lazy { TestClips.frameCounter(tmp.resolve("match.mp4")) }
    private val exporter = FfmpegRallyExporter()

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private fun request(
        mode: ExportMode,
        quality: ExportQuality,
        ranges: List<TimeRange>,
        source: Path = clip,
    ) = ExportRequest(source, runBlocking { engine.probe(source) }, ranges, out, mode, quality)

    private val twoRallies = listOf(TimeRange(480, 1_480), TimeRange(2_000, 3_000))

    private fun durationOf(file: Path) = runBlocking { engine.probe(file).durationMs }

    private fun firstFrameIndex(file: Path): Int =
        runBlocking {
            val player = engine.open(file)
            try {
                player.seek(0, exact = true)
                withTimeout(30_000) { player.position.first { it in -40..40 } }
                TestClips.frameIndexOf(withTimeout(30_000) { player.frames.first() })
            } finally {
                player.close()
            }
        }

    @Test
    fun reencodedRalliesStartOnTheExactFrame() =
        runBlocking {
            val files = exporter.export(request(ExportMode.PerRally, ExportQuality.Reencode, twoRallies)) {}

            assertEquals(listOf("match-rally-001.mp4", "match-rally-002.mp4"), files.map { it.name })
            files.forEach { assertTrue(durationOf(it) in 940..1_060, "duration was ${durationOf(it)}") }
            // 480 ms at 25 fps is frame 12, which is not a keyframe (keyframes are every 25 frames).
            assertEquals(12, firstFrameIndex(files[0]))
            assertEquals(50, firstFrameIndex(files[1]))
        }

    @Test
    fun singleVideoJoinsTheRalliesAndLeavesNoTemporaryFiles() =
        runBlocking {
            val progress = CopyOnWriteArrayList<Double>()
            val files =
                exporter.export(request(ExportMode.SingleVideo, ExportQuality.Reencode, twoRallies)) {
                    progress +=
                        it
                }

            assertEquals(listOf("match-rallies.mp4"), files.map { it.name })
            assertTrue(durationOf(files.single()) in 1_900..2_150, "duration was ${durationOf(files.single())}")
            assertEquals(12, firstFrameIndex(files.single()))
            assertEquals(files.map { it.name }, Files.list(out).use { list -> list.map { it.name }.toList() })
            assertEquals(1.0, progress.last(), 0.0)
            assertEquals(progress.sorted(), progress.toList())
        }

    @Test
    fun streamCopyKeepsTheContainerAndCoversTheRange() =
        runBlocking {
            val files = exporter.export(request(ExportMode.PerRally, ExportQuality.Copy, twoRallies)) {}

            assertEquals(listOf("match-rally-001.mp4", "match-rally-002.mp4"), files.map { it.name })
            // Cuts snap to the previous keyframe, so the copies may start earlier but never end short.
            files.forEach { assertTrue(durationOf(it) >= 900, "duration was ${durationOf(it)}") }
        }

    @Test
    fun aPercentSignInTheSourceNameIsKeptLiterally() =
        runBlocking {
            val source = Files.copy(clip, tmp.resolve("final 100%d.mp4"))
            val files = exporter.export(request(ExportMode.PerRally, ExportQuality.Copy, twoRallies, source)) {}

            assertEquals(listOf("final 100%d-rally-001.mp4", "final 100%d-rally-002.mp4"), files.map { it.name })
        }

    @Test
    fun projectFilesDescribeTheOriginal() =
        runBlocking {
            val fcpxml = exporter.export(request(ExportMode.Fcpxml, ExportQuality.Copy, twoRallies)) {}.single()
            val edl = exporter.export(request(ExportMode.Edl, ExportQuality.Copy, twoRallies)) {}.single()

            assertEquals("match.fcpxml", fcpxml.name)
            assertEquals("match.edl", edl.name)
            val xml = Files.readString(fcpxml)
            assertTrue(clip.toUri().toString() in xml, xml)
            assertEquals(2, Regex("<asset-clip ").findAll(xml).count())
            assertTrue("TITLE: match" in Files.readString(edl))
        }

    @Test
    fun anUnreadableSourceFailsAndLeavesNoFile() {
        val broken = Files.writeString(tmp.resolve("broken.mp4"), "not a video")
        val info = runBlocking { engine.probe(clip) }
        val request = ExportRequest(broken, info, twoRallies, out, ExportMode.PerRally, ExportQuality.Reencode)

        assertFailsWith<ExportException> { runBlocking { exporter.export(request) {} } }
        assertEquals(emptyList(), Files.list(out).use { it.toList() })
    }

    @Test
    fun exportingNothingIsAnError() {
        assertFailsWith<ExportException> {
            runBlocking { exporter.export(request(ExportMode.SingleVideo, ExportQuality.Copy, emptyList())) {} }
        }
    }

    @Test
    fun cancellingStopsFfmpegAndRemovesEverything() =
        runBlocking {
            val long = TestClips.benchmarkClip(tmp.resolve("long.mp4"), "1280x720", 60)
            val request =
                request(
                    ExportMode.SingleVideo,
                    ExportQuality.Reencode,
                    listOf(TimeRange(0, 30_000), TimeRange(30_000, 59_000)),
                    long,
                )
            val started = CompletableDeferred<Unit>()
            val job = launch(Dispatchers.Default) { exporter.export(request) { started.complete(Unit) } }
            withTimeout(60_000) { started.await() }
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse(Files.list(out).use { list -> list.findAny().isPresent }, "export folder must be empty")
        }
}
