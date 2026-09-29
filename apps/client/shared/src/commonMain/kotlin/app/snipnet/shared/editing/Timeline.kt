package app.snipnet.shared.editing

import app.snipnet.shared.model.Segment
import app.snipnet.shared.model.SegmentLabel

/** Shortest segment the editor allows, in milliseconds. Trims, splits and adds never produce anything shorter. */
const val MIN_SEGMENT_MS: Long = 200

/**
 * A segment as the editor sees it. On top of the API [Segment] it carries a stable [id] (so selection and drags
 * survive edits that change the boundaries) and the [accepted] flag: a rejected segment stays visible on the
 * timeline but is left out of playback and of the segments sent to the API.
 */
data class EditSegment(
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    val accepted: Boolean = true,
    val confidence: Double? = null,
) {
    init {
        require(startMs < endMs) { "Segment $id must have startMs < endMs but was $startMs..$endMs" }
    }

    val lengthMs: Long get() = endMs - startMs

    fun toSegment(): Segment = Segment(startMs, endMs, SegmentLabel.RALLY, confidence)
}

/**
 * Immutable editor state: the video [durationMs], the [segments] (always sorted, non-overlapping and inside
 * `0..durationMs`), the selected segment ids and the [playheadMs]. Every operation lives in `TimelineOps.kt`
 * and returns a new [Timeline]; nothing here mutates.
 */
data class Timeline(
    val durationMs: Long,
    val segments: List<EditSegment> = emptyList(),
    val selection: Set<Long> = emptySet(),
    val playheadMs: Long = 0,
    /** Next id handed to a newly created segment; ids are never reused within one timeline lineage. */
    val nextId: Long = (segments.maxOfOrNull { it.id } ?: 0L) + 1,
) {
    init {
        require(durationMs > 0) { "durationMs must be positive but was $durationMs" }
        require(playheadMs in 0..durationMs) { "playhead $playheadMs outside 0..$durationMs" }
        var previousEnd = 0L
        val seen = HashSet<Long>()
        for (segment in segments) {
            require(seen.add(segment.id)) { "Duplicate segment id ${segment.id}" }
            require(segment.startMs >= previousEnd) { "Segments overlap or are unsorted at ${segment.startMs}" }
            require(segment.endMs <= durationMs) { "Segment ${segment.id} ends after the video" }
            previousEnd = segment.endMs
        }
        require(nextId > (segments.maxOfOrNull { it.id } ?: 0L)) { "nextId collides with an existing id" }
    }

    /** Segments in the shape of the API contract: only accepted ones, ready for a segment set upload. */
    fun toApiSegments(): List<Segment> = segments.filter { it.accepted }.map { it.toSegment() }

    fun segmentAt(timeMs: Long): EditSegment? = segments.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }

    fun find(id: Long): EditSegment? = segments.firstOrNull { it.id == id }

    /** Replaces the selection, silently dropping ids that do not exist. */
    fun select(ids: Set<Long>): Timeline = copy(selection = ids.filterTo(HashSet()) { find(it) != null })

    fun seek(timeMs: Long): Timeline = copy(playheadMs = timeMs.coerceIn(0, durationMs))

    companion object {
        /** Builds a timeline from an API segment list, giving every segment an id and marking it accepted. */
        fun fromSegments(
            durationMs: Long,
            segments: List<Segment>,
        ): Timeline =
            Timeline(
                durationMs = durationMs,
                segments =
                    segments.mapIndexed { index, s ->
                        EditSegment(index + 1L, s.startMs, s.endMs, accepted = true, confidence = s.confidence)
                    },
            )
    }
}
