package app.snipnet.desktop.export

import app.snipnet.shared.editing.TimeRange
import java.nio.file.Path

/** Builds the ffmpeg invocations of the video export modes; kept free of process handling so tests can assert them. */
object FfmpegCommands {
    private val MOVFLAGS_CONTAINERS = setOf("mp4", "m4v", "mov")

    /** Container extensions that can hold the copied streams of the common camera formats. */
    private val COPYABLE_CONTAINERS = setOf("mp4", "m4v", "mov", "mkv")

    /**
     * File extension of the exported videos. Re-encoding always yields mp4; stream copy keeps the container of the
     * original, because its codecs are only guaranteed to fit there, and falls back to mp4 for rare containers.
     */
    fun extensionFor(
        source: Path,
        quality: ExportQuality,
    ): String {
        if (quality == ExportQuality.Reencode) return "mp4"
        val original =
            source.fileName
                .toString()
                .substringAfterLast('.', "")
                .lowercase()
        return if (original in COPYABLE_CONTAINERS) original else "mp4"
    }

    /** `12.345` for 12345 ms; ffmpeg's plain seconds syntax with millisecond precision. */
    fun seconds(ms: Long): String = "%d.%03d".format(ms / 1000, ms % 1000)

    /**
     * Cuts [range] out of [input] into [output]. Seeking before the input makes ffmpeg decode from the previous
     * keyframe and drop frames up to the exact start when re-encoding, so the cut is frame accurate. With stream
     * copy it can only start on a keyframe, and `-avoid_negative_ts` keeps the timestamps of the result at zero.
     * Only the first video and all audio streams are mapped, which drops the data streams cameras add.
     */
    fun cut(
        ffmpeg: Path,
        input: Path,
        output: Path,
        range: TimeRange,
        quality: ExportQuality,
    ): List<String> {
        val codec =
            when (quality) {
                ExportQuality.Copy -> listOf("-c", "copy", "-avoid_negative_ts", "make_zero")
                ExportQuality.Reencode ->
                    listOf(
                        "-c:v",
                        "libx264",
                        "-preset",
                        "fast",
                        "-crf",
                        "18",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-b:a",
                        "192k",
                    )
            }
        return base(ffmpeg, progress = true) +
            listOf("-ss", seconds(range.startMs), "-i", input.toString(), "-t", seconds(range.endMs - range.startMs)) +
            listOf("-map", "0:v:0", "-map", "0:a?") +
            codec +
            containerFlags(output) +
            output.toString()
    }

    /** Joins the already cut parts listed in [listFile] (see [concatList]) into [output] without re-encoding. */
    fun concat(
        ffmpeg: Path,
        listFile: Path,
        output: Path,
    ): List<String> =
        base(ffmpeg, progress = false) +
            listOf("-f", "concat", "-safe", "0", "-i", listFile.toString(), "-c", "copy") +
            containerFlags(output) +
            output.toString()

    /** Content of the concat demuxer list: one quoted `file` line per part, with single quotes escaped. */
    fun concatList(parts: List<Path>): String =
        parts.joinToString(separator = "\n", postfix = "\n") { "file '${it.toString().replace("'", "'\\''")}'" }

    private fun base(
        ffmpeg: Path,
        progress: Boolean,
    ): List<String> =
        listOf(ffmpeg.toString(), "-y", "-nostdin", "-loglevel", "error") +
            if (progress) listOf("-progress", "pipe:1", "-nostats") else emptyList()

    /** Fast start moves the index to the front of MP4-like files; other muxers reject the option. */
    private fun containerFlags(output: Path): List<String> =
        if (output.fileName
                .toString()
                .substringAfterLast('.')
                .lowercase() in MOVFLAGS_CONTAINERS
        ) {
            listOf("-movflags", "+faststart")
        } else {
            emptyList()
        }
}
