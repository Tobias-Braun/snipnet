package app.snipnet.desktop.export

import app.snipnet.desktop.video.VideoInfo
import app.snipnet.shared.editing.TimeRange
import app.snipnet.shared.editing.Timeline
import java.io.IOException
import java.nio.file.Path

/** What the export writes into the target folder. */
enum class ExportMode(
    val label: String,
) {
    SingleVideo("One concatenated video"),
    PerRally("One file per rally"),
    Fcpxml("Final Cut Pro (FCPXML)"),
    Edl("CMX3600 EDL"),
}

/** How the video modes cut the original; the FCPXML and EDL modes never touch the media and ignore it. */
enum class ExportQuality(
    val label: String,
) {
    /** Streams are copied without decoding: fast and lossless, but cuts can only start on keyframes. */
    Copy("Fast: copy streams (cuts snap to keyframes)"),

    /** H.264 CRF 18 and AAC: slower, but every cut is frame accurate. */
    Reencode("Frame accurate: re-encode H.264 (CRF 18)"),
}

/**
 * The choices of the export dialog. [folder] is null until the dialog has filled in the default, the folder of
 * the original video.
 */
data class ExportOptions(
    val folder: Path? = null,
    val mode: ExportMode = ExportMode.SingleVideo,
    val quality: ExportQuality = ExportQuality.Reencode,
    val includeRejected: Boolean = false,
)

/**
 * Everything the exporter needs. [source] is always the original file, never the proxy, and [info] its probed
 * facts (frame rate, size and audio for the project files). [ranges] are the cuts in playback order.
 */
data class ExportRequest(
    val source: Path,
    val info: VideoInfo,
    val ranges: List<TimeRange>,
    val folder: Path,
    val mode: ExportMode,
    val quality: ExportQuality,
)

/** The export failed; [message] is meant for the user and carries the tail of ffmpeg's error output if any. */
class ExportException(
    message: String,
) : IOException(message)

/**
 * The time ranges to export: the accepted segments, plus the rejected ones when [includeRejected] is set. The
 * result stays in timeline order, so rejected segments are interleaved where they sit.
 */
fun Timeline.exportRanges(includeRejected: Boolean): List<TimeRange> =
    segments.filter { it.accepted || includeRejected }.map { TimeRange(it.startMs, it.endMs) }
