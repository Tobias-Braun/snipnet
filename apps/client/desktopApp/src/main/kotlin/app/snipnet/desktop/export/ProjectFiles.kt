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

    /** CMX3600 has a two-digit frame field, so timecodes cannot express nominal rates above this. */
    private const val EDL_MAX_NOMINAL_FPS = 99

    /** CMX3600 readers accept at most 70 characters on the TITLE line. */
    private const val EDL_MAX_TITLE_LENGTH = 70

    /** Keeps the `* FROM CLIP NAME:` comment within the same width readers tolerate for the title. */
    private const val EDL_MAX_CLIP_NAME_LENGTH = 70

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
        val tcFormat = if (isDropFrame(info, rate)) "DF" else "NDF"
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
            "        <sequence format=\"r1\" duration=\"$sequenceDuration\" tcStart=\"0s\" tcFormat=\"$tcFormat\">",
        )
        out.appendLine("          <spine>")
        var offset = 0L
        clips.forEachIndexed { index, (start, length) ->
            out.appendLine(
                "            <asset-clip ref=\"r2\" name=\"Rally ${index + 1}\" offset=\"${rate.rational(offset)}\" " +
                    "start=\"${rate.rational(
                        start,
                    )}\" duration=\"${rate.rational(length)}\" format=\"r1\" tcFormat=\"$tcFormat\"/>",
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
     * Timecodes count the nominal frame rate without drop frames, except for 29.97/59.94 fps sources whose
     * embedded start timecode is drop frame (`;` separator): those are written as `FCM: DROP FRAME` with `;`
     * timecodes on both sides so they match the camera's labels. TITLE and clip name are cut to safe lengths.
     *
     * Throws [ExportException] for nominal rates above 99 fps (for example 120 fps slow motion): the frame field
     * has two digits, and timecodes against a lower base rate would silently misplace every cut in the editor,
     * so refusing with a clear message is safer. FCPXML supports such rates.
     */
    fun edl(
        name: String,
        clipName: String,
        info: VideoInfo,
        ranges: List<TimeRange>,
    ): String {
        val rate = FrameRate.fromFps(info.frameRate)
        edlUnsupportedFps(info)?.let { fps ->
            throw ExportException(
                "The EDL format cannot represent $fps fps footage (frame numbers are limited to two " +
                    "digits). Export as FCPXML or as video instead.",
            )
        }
        val origin = sourceStartFrames(info, rate)
        val dropFrame = isDropFrame(info, rate)
        val timecode: (Long) -> String = if (dropFrame) rate::dropFrameTimecode else rate::timecode
        val out = StringBuilder()
        out.append("TITLE: ").appendLine(singleLine(name, EDL_MAX_TITLE_LENGTH))
        out.appendLine(if (dropFrame) "FCM: DROP FRAME" else "FCM: NON-DROP FRAME")
        // One hour of drop-frame timecode holds fewer real frames than one hour of nominal counting.
        var record =
            if (dropFrame) {
                checkNotNull(rate.framesOf("%02d:00:00;00".format(RECORD_START_HOURS)))
            } else {
                RECORD_START_HOURS * 3600L * rate.nominal
            }
        ranges.forEachIndexed { index, range ->
            val start = origin + rate.framesAt(range.startMs)
            val end = origin + rate.framesAt(range.endMs)
            val recordEnd = record + (end - start)
            out.appendLine()
            out.appendLine(
                "%03d  AX       AA/V  C        %s %s %s %s".format(
                    index + 1,
                    timecode(start),
                    timecode(end),
                    timecode(record),
                    timecode(recordEnd),
                ),
            )
            out.append("* FROM CLIP NAME: ").appendLine(singleLine(clipName, EDL_MAX_CLIP_NAME_LENGTH))
            record = recordEnd
        }
        return out.toString()
    }

    /**
     * The nominal frame rate of [info] when [edl] cannot write it (above two-digit frame numbers), otherwise null.
     * The export dialog asks this up front so it can steer the user away from EDL instead of failing at export time.
     */
    fun edlUnsupportedFps(info: VideoInfo): Int? =
        FrameRate.fromFps(info.frameRate).nominal.takeIf { it > EDL_MAX_NOMINAL_FPS }

    /** Frame index of the file's first frame on its embedded timecode; absent or unparsable timecodes count as 0. */
    private fun sourceStartFrames(
        info: VideoInfo,
        rate: FrameRate,
    ): Long = info.startTimecode?.let { rate.framesOf(it) } ?: 0L

    /** Whether the source's embedded start timecode is drop frame and the rate can express it. */
    private fun isDropFrame(
        info: VideoInfo,
        rate: FrameRate,
    ): Boolean {
        val timecode = info.startTimecode?.trim() ?: return false
        return rate.supportsDropFrame && ';' in timecode && rate.framesOf(timecode) != null
    }

    /** Joins line breaks into spaces and cuts to [maxLength] characters without splitting a surrogate pair. */
    private fun singleLine(
        text: String,
        maxLength: Int = Int.MAX_VALUE,
    ): String {
        val flat = text.replace(Regex("[\\r\\n]+"), " ")
        if (flat.length <= maxLength) return flat
        val end = if (Character.isHighSurrogate(flat[maxLength - 1])) maxLength - 1 else maxLength
        return flat.substring(0, end)
    }

    private fun xml(text: String) =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
