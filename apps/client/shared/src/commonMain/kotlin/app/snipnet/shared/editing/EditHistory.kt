package app.snipnet.shared.editing

import app.snipnet.shared.model.EditOp

/**
 * Undo/redo state around a [Timeline]. Only segment changes are undoable; selection and playhead are view state
 * that undo leaves alone (apart from dropping selected ids that no longer exist).
 *
 * The [editLog] is the list of [EditOp]s for everything currently applied, in order: undo removes the last entry
 * and redo puts it back, so the log always describes how the original prediction became the current segments and
 * can be uploaded as is.
 *
 * Drags produce a stream of tiny edits. Passing the same non-null `gestureKey` to [apply] for each of them
 * coalesces them into a single undo step and a single log entry whose `before` is the state at drag start and
 * whose `after` is the latest one. Any other apply, undo, redo or [endGesture] closes the open gesture.
 */
data class EditHistory(
    val timeline: Timeline,
    private val undoStack: List<Entry> = emptyList(),
    private val redoStack: List<Entry> = emptyList(),
    private val openGesture: Any? = null,
) {
    /** One undo step: the segments before it, the (possibly coalesced) op and the gesture that produced it. */
    data class Entry(
        val segmentsBefore: List<EditSegment>,
        val op: EditOp,
        val gestureKey: Any?,
    )

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val editLog: List<EditOp> get() = undoStack.map { it.op }

    /** Applies [edit], or returns this unchanged when it is null (an operation that was a no-op). */
    fun apply(
        edit: Edit?,
        gestureKey: Any? = null,
    ): EditHistory {
        if (edit == null) return this
        val last = undoStack.lastOrNull()
        val stack =
            if (gestureKey != null && openGesture == gestureKey && last != null && last.gestureKey == gestureKey) {
                undoStack.dropLast(1) + last.copy(op = last.op.copy(after = edit.op.after))
            } else {
                undoStack + Entry(timeline.segments, edit.op, gestureKey)
            }
        return EditHistory(edit.timeline, stack, emptyList(), gestureKey)
    }

    /** Closes the open drag gesture so the next edit starts a new undo step. */
    fun endGesture(): EditHistory = copy(openGesture = null)

    fun undo(): EditHistory {
        val entry = undoStack.lastOrNull() ?: return this
        val swapped = entry.copy(segmentsBefore = timeline.segments)
        return EditHistory(restore(entry.segmentsBefore), undoStack.dropLast(1), redoStack + swapped, null)
    }

    fun redo(): EditHistory {
        val entry = redoStack.lastOrNull() ?: return this
        val swapped = entry.copy(segmentsBefore = timeline.segments)
        return EditHistory(restore(entry.segmentsBefore), undoStack + swapped, redoStack.dropLast(1), null)
    }

    /** Runs a view-only change (selection, playhead) that must not create an undo step. */
    fun updateView(transform: (Timeline) -> Timeline): EditHistory = copy(timeline = transform(timeline))

    private fun restore(segments: List<EditSegment>): Timeline =
        timeline.copy(
            segments = segments,
            selection = timeline.selection.filterTo(HashSet()) { id -> segments.any { it.id == id } },
        )
}
