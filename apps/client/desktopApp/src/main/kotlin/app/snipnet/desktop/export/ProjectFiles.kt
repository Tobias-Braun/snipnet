package app.snipnet.desktop.export

import app.snipnet.desktop.video.VideoInfo
import app.snipnet.shared.editing.TimeRange

/**
 * Writers of the two interchange formats that let a professional editor pick up the rally cuts. Both are pure
 * functions of their input, so the output is deterministic and covered by golden files.
 */
object ProjectFiles {
    /** First frame of the record side; editing systems conventionally start sequences at one hour. */
    private const val RECORD_START_HOURS = 1

    /**
     * FCPXML 1.8: one asset that points at [sourceUri] and a sequence whose spine holds one asset clip per
     * range, back to back. Times are exact fractions of the frame duration, as Final Cut Pro requires.
     */
    fun fcpxml(
        name: String,
        sourceUri: String,
        info: VideoInfo,
        ranges: List<TimeRange>,
    ): String {
        val rate = FrameRate.fromFps(info.frameRate)
        val origin = sourceStartFrames(info, rate)
        // Clip starts are positions on the source's own timecode, so they include the embedded start timecode.
        val clips =
            ranges.map { origin + rate.framesAt(it.startMs) to rate.framesAt(it.endMs) - rate.framesAt(it.startMs) }
        val audio =
            if (info.hasAudio) {
                " hasAudio=\"1\" audioSources=\"1\" audioChannels=\"${info.audioChannels}\" audioRate=\"${info.audioSampleRate}\""
            } else {
                ""
            }
        val assetDuration = rate.rational(rate.framesAt(info.durationMs))
        val sequenceDuration = rate.rational(clips.sumOf { it.second })

        val out = StringBuilder()
        out.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        out.appendLine("<!DOCTYPE fcpxml>")
        out.appendLine("<fcpxml version=\"1.8\">")
        out.appendLine("  <resources>")
        out.appendLine(
            "    <format id=\"r1\" frameDuration=\"${rate.frameDuration}\" width=\"${info.width}\" height=\"${info.height}\"/>",
        )
        out.appendLine(
            "    <asset id=\"r2\" name=\"${xml(name)}\" src=\"${xml(sourceUri)}\" start=\"${rate.rational(origin)}\" " +
                "duration=\"$assetDuration\" hasVideo=\"1\" format=\"r1\"$audio/>",
        )
        out.appendLine("  </resources>")
        out.appendLine("  <library>")
        out.appendLine("    <event name=\"Snipnet\">")
        out.appendLine("      <project name=\"${xml(name)}\">")
        out.appendLine(
            "        <sequence format=\"r1\" duration=\"$sequenceDuration\" tcStart=\"0s\" tcFormat=\"NDF\">",
        )
        out.appendLine("          <spine>")
        var offset = 0L
        clips.forEachIndexed { index, (start, length) ->
            out.appendLine(
                "            <asset-clip ref=\"r2\" name=\"Rally ${index + 1}\" offset=\"${rate.rational(offset)}\" " +
                    "start=\"${rate.rational(
                        start,
                    )}\" duration=\"${rate.rational(length)}\" format=\"r1\" tcFormat=\"NDF\"/>",
            )
            offset += length
        }
        out.appendLine("          </spine>")
        out.appendLine("        </sequence>")
        out.appendLine("      </project>")
        out.appendLine("    </event>")
        out.appendLine("  </library>")
        out.appendLine("</fcpxml>")
        return out.toString()
    }

    /**
     * CMX3600 EDL with one cut event per range on the auxiliary reel `AX`, audio and video together. Source
     * timecodes continue from the timecode embedded in the file (00:00:00:00 when it has none), the record side
     * starts at 01:00:00:00 and runs without gaps.
     * Timecodes count the nominal frame rate without drop frames.
     */
    fun edl(
        name: String,
        clipName: String,
        info: VideoInfo,
        ranges: List<TimeRange>,
    ): String {
        val rate = FrameRate.fromFps(info.frameRate)
        val origin = sourceStartFrames(info, rate)
        val out = StringBuilder()
        out.append("TITLE: ").appendLine(singleLine(name))
        out.appendLine("FCM: NON-DROP FRAME")
        var record = RECORD_START_HOURS * 3600L * rate.nominal
        ranges.forEachIndexed { index, range ->
            val start = origin + rate.framesAt(range.startMs)
            val end = origin + rate.framesAt(range.endMs)
            val recordEnd = record + (end - start)
            out.appendLine()
            out.appendLine(
                "%03d  AX       AA/V  C        %s %s %s %s".format(
                    index + 1,
                    rate.timecode(start),
                    rate.timecode(end),
                    rate.timecode(record),
                    rate.timecode(recordEnd),
                ),
            )
            out.append("* FROM CLIP NAME: ").appendLine(singleLine(clipName))
            record = recordEnd
        }
        return out.toString()
    }

    /** Frame index of the file's first frame on its embedded timecode; absent or unparsable timecodes count as 0. */
    private fun sourceStartFrames(
        info: VideoInfo,
        rate: FrameRate,
    ): Long = info.startTimecode?.let { rate.framesOf(it) } ?: 0L

    private fun singleLine(text: String) = text.replace(Regex("[\\r\\n]+"), " ")

    private fun xml(text: String) =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
