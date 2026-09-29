package app.snipnet.desktop.export

import app.snipnet.desktop.video.VideoInfo
import app.snipnet.shared.editing.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectFilesTest {
    private val info = VideoInfo(60_000, 1920, 1080, 25.0, "h264", "aac", 48_000, 2)
    private val ranges = listOf(TimeRange(5_000, 15_000), TimeRange(20_000, 30_000))

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/golden/$name")) { "missing golden file $name" }
            .readBytes()
            .toString(Charsets.UTF_8)

    @Test
    fun fcpxmlMatchesTheGoldenFile() {
        assertEquals(
            golden("two-rallies-25fps.fcpxml"),
            ProjectFiles.fcpxml("match", "file:///videos/match.mp4", info, ranges),
        )
    }

    @Test
    fun edlMatchesTheGoldenFile() {
        assertEquals(golden("two-rallies-25fps.edl"), ProjectFiles.edl("match", "match.mp4", info, ranges))
    }

    @Test
    fun fcpxmlOffsetsAssetAndClipsByTheEmbeddedStartTimecode() {
        val stamped = info.copy(startTimecode = "10:00:00:00")
        assertEquals(
            golden("two-rallies-25fps-tc10h.fcpxml"),
            ProjectFiles.fcpxml("match", "file:///videos/match.mp4", stamped, ranges),
        )
    }

    @Test
    fun edlSourceTimecodesContinueFromTheEmbeddedStartTimecode() {
        val stamped = info.copy(startTimecode = "10:00:00:00")
        assertEquals(golden("two-rallies-25fps-tc10h.edl"), ProjectFiles.edl("match", "match.mp4", stamped, ranges))
    }

    @Test
    fun anUnparsableStartTimecodeIsIgnored() {
        val garbled = info.copy(startTimecode = "not a timecode")
        assertEquals(golden("two-rallies-25fps.edl"), ProjectFiles.edl("match", "match.mp4", garbled, ranges))
    }

    @Test
    fun timecodesParseToFrameIndexes() {
        assertEquals(90_000L, FrameRate(25, 1).framesOf("01:00:00:00"))
        assertEquals(null, FrameRate(25, 1).framesOf("00:00:00:25"))
        assertEquals(null, FrameRate(25, 1).framesOf("garbage"))
        // One hour of drop-frame timecode is 107892 real frames at 29.97 fps, non-drop counts 108000.
        assertEquals(107_892L, FrameRate(30000, 1001).framesOf("01:00:00;00"))
        assertEquals(108_000L, FrameRate(30000, 1001).framesOf("01:00:00:00"))
        assertEquals(1_800L, FrameRate(30000, 1001).framesOf("00:01:00;02"))
    }

    @Test
    fun ntscRatesUseExactFractionsInFcpxmlAndNominalTimecodesInEdl() {
        val ntsc = info.copy(frameRate = 30000.0 / 1001)
        val one = listOf(TimeRange(10_000, 20_000))

        val xml = ProjectFiles.fcpxml("m", "file:///m.mp4", ntsc, one)
        assertTrue("frameDuration=\"1001/30000s\"" in xml, xml)
        // 10 s and 20 s at 29.97 fps round to frames 300 and 599, so the clip is 299 frames long.
        assertTrue("duration=\"299299/30000s\"" in xml, xml)

        val edl = ProjectFiles.edl("m", "m.mp4", ntsc, one)
        assertTrue("00:00:10:00 00:00:19:29 01:00:00:00 01:00:09:29" in edl, edl)
    }

    @Test
    fun namesAreEscapedOrFlattened() {
        val xml = ProjectFiles.fcpxml("a&b \"c\"", "file:///a&b.mp4", info, ranges)
        assertTrue("name=\"a&amp;b &quot;c&quot;\"" in xml)
        assertTrue("src=\"file:///a&amp;b.mp4\"" in xml)

        val edl = ProjectFiles.edl("two\nlines", "clip\r\nname.mp4", info, ranges)
        assertTrue(edl.startsWith("TITLE: two lines\n"))
        assertTrue("* FROM CLIP NAME: clip name.mp4\n" in edl)
    }

    @Test
    fun aVideoWithoutAudioDeclaresNone() {
        val silent = info.copy(audioCodec = null, audioChannels = 0)
        assertTrue("hasAudio" !in ProjectFiles.fcpxml("m", "file:///m.mp4", silent, ranges))
    }

    @Test
    fun frameRatesSnapToStandardValues() {
        assertEquals(FrameRate(30000, 1001), FrameRate.fromFps(29.97002997))
        assertEquals(FrameRate(24000, 1001), FrameRate.fromFps(23.976))
        assertEquals(FrameRate(50, 1), FrameRate.fromFps(50.0))
        assertEquals(FrameRate(25, 1), FrameRate.fromFps(0.0))
    }

    @Test
    fun edlRefusesFrameRatesWithThreeDigitFrameNumbers() {
        val slowMotion = info.copy(frameRate = 120.0)
        val error = assertFailsWith<ExportException> { ProjectFiles.edl("m", "m.mp4", slowMotion, ranges) }
        assertTrue("120 fps" in error.message.orEmpty(), error.message)
        val ntsc = info.copy(frameRate = 120000.0 / 1001)
        assertFailsWith<ExportException> { ProjectFiles.edl("m", "m.mp4", ntsc, ranges) }
    }

    @Test
    fun edlStillWritesTheHighestTwoDigitFrameRate() {
        val edl = ProjectFiles.edl("m", "m.mp4", info.copy(frameRate = 99.0), listOf(TimeRange(0, 990)))
        assertTrue("00:00:00:00 00:00:00:98 01:00:00:00 01:00:00:98" in edl, edl)
    }

    @Test
    fun edlCutsLongTitlesAndClipNames() {
        val long = "x".repeat(200)
        val edl = ProjectFiles.edl(long, long, info, ranges)
        assertTrue("TITLE: ${"x".repeat(70)}\n" in edl, edl)
        assertTrue("* FROM CLIP NAME: ${"x".repeat(70)}\n" in edl, edl)
    }

    @Test
    fun edlCutsNeverSplitASurrogatePair() {
        val title = "x".repeat(69) + "🏐" + "tail"
        val edl = ProjectFiles.edl(title, "m.mp4", info, ranges)
        assertTrue("TITLE: ${"x".repeat(69)}\n" in edl, edl)
    }
}
