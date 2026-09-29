package app.snipnet.shared.editing

import app.snipnet.shared.model.EditOpKind
import app.snipnet.shared.model.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditHistoryTest {
    private val start =
        EditHistory(Timeline.fromSegments(100_000, listOf(Segment(10_000, 20_000), Segment(30_000, 40_000))))

    @Test
    fun applyingNullEditChangesNothing() {
        assertSame(start, start.apply(null))
        assertSame(start, start.undo())
        assertSame(start, start.redo())
        assertFalse(start.canUndo)
        assertFalse(start.canRedo)
    }

    @Test
    fun undoAndRedoRoundTrip() {
        val edited = start.apply(start.timeline.delete(setOf(1), 1))
        assertEquals(1, edited.timeline.segments.size)
        assertTrue(edited.canUndo)
        assertEquals(listOf(EditOpKind.DELETE), edited.editLog.map { it.op })

        val undone = edited.undo()
        assertEquals(start.timeline.segments, undone.timeline.segments)
        assertTrue(undone.canRedo)
        assertEquals(emptyList(), undone.editLog)

        val redone = undone.redo()
        assertEquals(edited.timeline.segments, redone.timeline.segments)
        assertEquals(edited.editLog, redone.editLog)
    }

    @Test
    fun newEditClearsRedoStack() {
        val edited = start.apply(start.timeline.delete(setOf(1), 1)).undo()
        val other = edited.apply(edited.timeline.toggleAccept(setOf(2), 2))
        assertFalse(other.canRedo)
    }

    @Test
    fun undoKeepsPlayheadAndPrunesSelection() {
        val split = start.apply(start.timeline.split(15_000, 1))
        val viewChanged = split.updateView { it.seek(12_345).select(setOf(3)) }
        val undone = viewChanged.undo()
        assertEquals(12_345L, undone.timeline.playheadMs)
        assertEquals(emptySet(), undone.timeline.selection)
    }

    @Test
    fun gestureEditsCoalesceIntoOneUndoStep() {
        var history = start
        for (end in listOf(21_000L, 22_000L, 23_000L)) {
            history = history.apply(history.timeline.trimEnd(1, end, end), gestureKey = "drag-1")
        }
        assertEquals(1, history.editLog.size)
        val op = history.editLog.single()
        assertEquals(listOf(Segment(10_000, 20_000)), op.before)
        assertEquals(listOf(Segment(10_000, 23_000)), op.after)
        assertEquals(21_000L, op.atMs)

        val undone = history.undo()
        assertEquals(20_000L, undone.timeline.find(1)!!.endMs)
        assertFalse(undone.canUndo)
    }

    @Test
    fun endGestureAndDifferentKeysStartNewSteps() {
        var history = start.apply(start.timeline.trimEnd(1, 21_000, 0), "a")
        history = history.apply(history.timeline.trimEnd(1, 22_000, 0), "b")
        assertEquals(2, history.editLog.size)

        history = history.endGesture()
        history = history.apply(history.timeline.trimEnd(1, 23_000, 0), "b")
        assertEquals(3, history.editLog.size)

        val plain = history.apply(history.timeline.trimEnd(1, 24_000, 0))
        assertEquals(4, plain.editLog.size)
    }

    @Test
    fun sameGestureKeyOnDifferentSegmentsStartsNewEntry() {
        var history = start.apply(start.timeline.trimEnd(1, 21_000, 0), "drag")
        history = history.apply(history.timeline.trimEnd(2, 41_000, 0), "drag")
        assertEquals(2, history.editLog.size)
        assertEquals(listOf(Segment(10_000, 21_000)), history.editLog[0].after)
        assertEquals(listOf(Segment(30_000, 40_000)), history.editLog[1].before)
        assertEquals(listOf(Segment(30_000, 41_000)), history.editLog[1].after)

        val undone = history.undo()
        assertEquals(40_000L, undone.timeline.find(2)!!.endMs)
        assertEquals(21_000L, undone.timeline.find(1)!!.endMs)
    }

    @Test
    fun sameGestureKeyWithDifferentOpKindStartsNewEntry() {
        var history = start.apply(start.timeline.trimEnd(1, 21_000, 0), "drag")
        history = history.apply(history.timeline.delete(setOf(1), 0), "drag")
        assertEquals(listOf(EditOpKind.TRIM, EditOpKind.DELETE), history.editLog.map { it.op })
    }

    @Test
    fun undoClosesOpenGesture() {
        var history = start.apply(start.timeline.trimEnd(1, 21_000, 0), "a")
        history = history.apply(history.timeline.trimEnd(1, 22_000, 0), "b").undo().redo()
        history = history.apply(history.timeline.trimEnd(1, 23_000, 0), "b")
        assertEquals(3, history.editLog.size)
    }
}
