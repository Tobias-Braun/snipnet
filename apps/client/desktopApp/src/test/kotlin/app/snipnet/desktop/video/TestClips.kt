package app.snipnet.desktop.video

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Generates test clips with the bundled ffmpeg, so tests neither ship binary fixtures nor depend on an ffmpeg
 * installed on the machine.
 */
object TestClips {
    const val FPS = 25
    const val SECONDS = 4
    const val KEYFRAME_INTERVAL = 25

    /**
     * A clip whose luma encodes the frame number (`16 + 2 * N` in the limited range, N < 100), so a test can tell which
     * frame it is looking at, plus mono AAC audio that is a loud sine for the first half and silence afterwards.
     * Keyframes only every second, so most seek targets are not keyframes.
     */
    fun frameCounter(target: Path): Path {
        run(
            "-f",
            "lavfi",
            "-i",
            "color=c=black:s=320x240:r=$FPS:d=$SECONDS,geq=lum='16+2*N':cb=128:cr=128",
            "-f",
            "lavfi",
            "-i",
            "aevalsrc='if(lt(t,2),0.8*sin(2*PI*440*t),0)':s=44100:d=$SECONDS",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "8",
            "-pix_fmt",
            "yuv420p",
            "-g",
            "$KEYFRAME_INTERVAL",
            "-c:a",
            "aac",
            "-ac",
            "1",
            target.toString(),
        )
        return target
    }

    /** A synthetic clip of any size and duration with noise-free content, for the decode benchmark. */
    fun benchmarkClip(
        target: Path,
        size: String,
        seconds: Int,
        codec: String = "libx264",
    ): Path {
        run(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=s=$size:r=30:d=$seconds",
            "-c:v",
            codec,
            "-preset",
            "veryfast",
            "-crf",
            "23",
            "-pix_fmt",
            "yuv420p",
            "-g",
            "60",
            target.toString(),
        )
        return target
    }

    private fun run(vararg args: String) {
        val process =
            ProcessBuilder(listOf(ffmpegPath().toString(), "-y", "-loglevel", "error") + args)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "ffmpeg failed: $output" }
    }

    /** Frame number of a [frameCounter] picture, read from the brightness of one pixel. */
    fun frameIndexOf(bitmap: ImageBitmap): Int {
        val gray = bitmap.toAwtImage().getRGB(10, 10) and 0xff
        // Limited range luma 16 + 2N expands to roughly 2.33 * N in full range RGB.
        return Math.round(gray / 2.33).toInt()
    }
}
