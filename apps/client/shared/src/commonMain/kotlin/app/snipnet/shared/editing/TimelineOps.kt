package app.snipnet.shared.editing

import app.snipnet.shared.model.EditOp
import app.snipnet.shared.model.EditOpKind

/**
 * The outcome of one applied operation: the new [timeline] and the [op] to append to the edit log.
 *
 * `EditOp.before` and `EditOp.after` hold the API view (accepted segments only) of the segments the operation
 * touched, so a toggle shows up as the segment being present on one side and absent on the other.
 */
data class Edit(
    val timeline: Timeline,
    val op: EditOp,
)

private fun List<EditSegment>.api() = filter { it.accepted }.map { it.toSegment() }

private fun Timeline.replace(
    removed: List<EditSegment>,
    added: List<EditSegment>,
    kind: EditOpKind,
    nowMs: Long,
    nextId: Long = this.nextId,
    selection: Set<Long> = this.selection,
): Edit {
    val removedIds = removed.mapTo(HashSet()) { it.id }
    val newSegments = (segments.filter { it.id !in removedIds } + added).sortedBy { it.startMs }
    val next =
        copy(
            segments = newSegments,
            nextId = nextId,
            selection = selection.filterTo(HashSet()) { id -> newSegments.any { it.id == id } },
        )
    return Edit(next, EditOp(kind, nowMs, removed.api(), added.api()))
}

/** Free space around [segment]: the end of the previous segment (or 0) and the start of the next (or the end). */
private fun Timeline.neighbourBounds(segment: EditSegment): LongRange {
    val index = segments.indexOfFirst { it.id == segment.id }
    val lower = if (index > 0) segments[index - 1].endMs else 0L
    val upper = if (index < segments.lastIndex) segments[index + 1].startMs else durationMs
    return lower..upper
}

/**
 * Moves the start edge of segment [id] to [newStartMs], clamped so the segment keeps at least
 * [MIN_SEGMENT_MS] and does not run into its predecessor. Returns null when nothing changes.
 *
 * Segments loaded from the API may already be shorter than [MIN_SEGMENT_MS]. Such a segment can only be
 * lengthened, so the upper clamp never lies before its current start (an empty clamp range would throw).
 */
fun Timeline.trimStart(
    id: Long,
    newStartMs: Long,
    nowMs: Long,
): Edit? {
    val segment = find(id) ?: return null
    val bounds = neighbourBounds(segment)
    val start = newStartMs.coerceIn(bounds.first, maxOf(segment.endMs - MIN_SEGMENT_MS, segment.startMs))
    if (start == segment.startMs) return null
    return replace(listOf(segment), listOf(segment.copy(startMs = start)), EditOpKind.TRIM, nowMs)
}

/**
 * Mirror of [trimStart] for the end edge, clamped to the next segment or the video end. A segment that is
 * already shorter than [MIN_SEGMENT_MS] can only be lengthened.
 */
fun Timeline.trimEnd(
    id: Long,
    newEndMs: Long,
    nowMs: Long,
): Edit? {
    val segment = find(id) ?: return null
    val bounds = neighbourBounds(segment)
    val end = newEndMs.coerceIn(minOf(segment.startMs + MIN_SEGMENT_MS, segment.endMs), bounds.last)
    if (end == segment.endMs) return null
    return replace(listOf(segment), listOf(segment.copy(endMs = end)), EditOpKind.TRIM, nowMs)
}

/**
 * Splits the segment under [atMs] into two. Returns null when no segment contains the time or when either half
 * would be shorter than [MIN_SEGMENT_MS]. Both halves keep the accepted flag; the right half gets a new id.
 */
fun Timeline.split(
    atMs: Long,
    nowMs: Long,
): Edit? {
    val segment = segmentAt(atMs) ?: return null
    if (atMs - segment.startMs < MIN_SEGMENT_MS || segment.endMs - atMs < MIN_SEGMENT_MS) return null
    val left = segment.copy(endMs = atMs)
    val right = segment.copy(id = nextId, startMs = atMs)
    return replace(listOf(segment), listOf(left, right), EditOpKind.SPLIT, nowMs, nextId = nextId + 1)
}

/**
 * Merges every segment from the first to the last of [ids] (by position) into one, absorbing the gaps between
 * them. The result spans the earliest start to the latest end and is accepted if any merged segment was.
 * Fewer than two matching segments is a no-op (null).
 */
fun Timeline.merge(
    ids: Set<Long>,
    nowMs: Long,
): Edit? {
    val indices = segments.indices.filter { segments[it].id in ids }
    if (indices.size < 2) return null
    val covered = segments.subList(indices.first(), indices.last() + 1)
    val merged =
        EditSegment(
            id = covered.first().id,
            startMs = covered.first().startMs,
            endMs = covered.last().endMs,
            accepted = covered.any { it.accepted },
            confidence = covered.mapNotNull { it.confidence }.maxOrNull(),
        )
    return replace(covered, listOf(merged), EditOpKind.MERGE, nowMs, selection = setOf(merged.id))
}

/** Merges the current selection, see [merge]. */
fun Timeline.mergeSelection(nowMs: Long): Edit? = merge(selection, nowMs)

/** Deletes the segments with the given [ids]; null when none of them exists. */
fun Timeline.delete(
    ids: Set<Long>,
    nowMs: Long,
): Edit? {
    val removed = segments.filter { it.id in ids }
    if (removed.isEmpty()) return null
    return replace(removed, emptyList(), EditOpKind.DELETE, nowMs)
}

/**
 * Adds a segment between the in and out marks (in either order), clamped to the video. Anything shorter than
 * [MIN_SEGMENT_MS] is rejected (null). Existing segments the new range overlaps are absorbed into it so the
 * timeline stays non-overlapping.
 */
fun Timeline.addFromMarks(
    inMs: Long,
    outMs: Long,
    nowMs: Long,
): Edit? {
    val start = minOf(inMs, outMs).coerceIn(0, durationMs)
    val end = maxOf(inMs, outMs).coerceIn(0, durationMs)
    if (end - start < MIN_SEGMENT_MS) return null
    val overlapped = segments.filter { it.startMs < end && it.endMs > start }
    val added =
        EditSegment(
            id = nextId,
            startMs = minOf(start, overlapped.firstOrNull()?.startMs ?: start),
            endMs = maxOf(end, overlapped.lastOrNull()?.endMs ?: end),
        )
    return replace(overlapped, listOf(added), EditOpKind.ADD, nowMs, nextId = nextId + 1, selection = setOf(added.id))
}

/** Flips the accepted flag of the given segments individually; null when none of them exists. */
fun Timeline.toggleAccept(
    ids: Set<Long>,
    nowMs: Long,
): Edit? {
    val targets = segments.filter { it.id in ids }
    if (targets.isEmpty()) return null
    return replace(targets, targets.map { it.copy(accepted = !it.accepted) }, EditOpKind.TOGGLE, nowMs)
}

/**
 * Shifts segment [id] by [deltaMs] without changing its length, clamped so it stays inside the free space
 * between its neighbours and the video bounds. Null when the clamped shift is zero.
 */
fun Timeline.move(
    id: Long,
    deltaMs: Long,
    nowMs: Long,
): Edit? {
    val segment = find(id) ?: return null
    val bounds = neighbourBounds(segment)
    val shift = deltaMs.coerceIn(bounds.first - segment.startMs, bounds.last - segment.endMs)
    if (shift == 0L) return null
    val moved = segment.copy(startMs = segment.startMs + shift, endMs = segment.endMs + shift)
    return replace(listOf(segment), listOf(moved), EditOpKind.MOVE, nowMs)
}
