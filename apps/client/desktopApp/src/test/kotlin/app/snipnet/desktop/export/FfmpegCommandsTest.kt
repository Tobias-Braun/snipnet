package app.snipnet.desktop.export

import app.snipnet.shared.editing.TimeRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegCommandsTest {
    private val ffmpeg = Path.of("/bin/ffmpeg")
    private val input = Path.of("/videos/match.mov")
    private val range = TimeRange(5_040, 15_000)

    @Test
    fun reencodeCutSeeksBeforeTheInputAndUsesCrf18() {
        val command = FfmpegCommands.cut(ffmpeg, input, Path.of("/out/a.mp4"), range, ExportQuality.Reencode)

        assertEquals("/bin/ffmpeg", command.first())
        val ss = command.indexOf("-ss")
        assertTrue(ss in 0 until command.indexOf("-i"), "-ss must precede -i for fast seeking")
        assertEquals("5.040", command[ss + 1])
        assertEquals("9.960", command[command.indexOf("-t") + 1])
        assertEquals("libx264", command[command.indexOf("-c:v") + 1])
        assertEquals("18", command[command.indexOf("-crf") + 1])
        assertEquals("aac", command[command.indexOf("-c:a") + 1])
        assertTrue("+faststart" in command)
        assertEquals("/out/a.mp4", command.last())
    }

    @Test
    fun copyCutDoesNotDecode() {
        val command = FfmpegCommands.cut(ffmpeg, input, Path.of("/out/a.mov"), range, ExportQuality.Copy)

        assertEquals("copy", command[command.indexOf("-c") + 1])
        assertFalse("libx264" in command)
        assertTrue("-avoid_negative_ts" in command)
    }

    @Test
    fun onlyVideoAndAudioAreMapped() {
        val command = FfmpegCommands.cut(ffmpeg, input, Path.of("/out/a.mp4"), range, ExportQuality.Copy)
        val maps = command.withIndex().filter { it.value == "-map" }.map { command[it.index + 1] }
        assertEquals(listOf("0:v:0", "0:a?"), maps)
    }

    @Test
    fun matroskaOutputGetsNoMovflags() {
        val command = FfmpegCommands.cut(ffmpeg, Path.of("/v/a.mkv"), Path.of("/out/a.mkv"), range, ExportQuality.Copy)
        assertFalse("-movflags" in command)
    }

    @Test
    fun extensionFollowsTheQuality() {
        assertEquals("mp4", FfmpegCommands.extensionFor(input, ExportQuality.Reencode))
        assertEquals("mov", FfmpegCommands.extensionFor(input, ExportQuality.Copy))
        assertEquals("mkv", FfmpegCommands.extensionFor(Path.of("/v/a.MKV"), ExportQuality.Copy))
        assertEquals("mp4", FfmpegCommands.extensionFor(Path.of("/v/a.avi"), ExportQuality.Copy))
    }

    @Test
    fun concatUsesTheDemuxerWithStreamCopy() {
        val command = FfmpegCommands.concat(ffmpeg, Path.of("/tmp/parts.txt"), Path.of("/out/all.mp4"))
        assertEquals(
            listOf("-f", "concat", "-safe", "0", "-i", "/tmp/parts.txt", "-c", "copy"),
            command.subList(
                command.indexOf("-f"),
                command.indexOf("-f") + 8,
            ),
        )
        assertEquals("/out/all.mp4", command.last())
    }

    @Test
    fun concatListEscapesQuotes() {
        val list = FfmpegCommands.concatList(listOf(Path.of("/tmp/a.mp4"), Path.of("/tmp/it's.mp4")))
        assertEquals("file '/tmp/a.mp4'\nfile '/tmp/it'\\''s.mp4'\n", list)
    }

    @Test
    fun secondsKeepMillisecondPrecision() {
        assertEquals("0.000", FfmpegCommands.seconds(0))
        assertEquals("61.007", FfmpegCommands.seconds(61_007))
    }
}
