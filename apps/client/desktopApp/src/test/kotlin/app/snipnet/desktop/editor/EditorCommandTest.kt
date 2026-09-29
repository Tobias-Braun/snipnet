package app.snipnet.desktop.editor

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorCommandTest {
    private fun press(
        key: Key,
        shortcut: Boolean = false,
        shift: Boolean = false,
    ) = editorCommandFor(key, shortcut, shift)

    @Test
    fun plainKeysMapToTheirCommands() {
        val expected =
            mapOf(
                Key.Spacebar to EditorCommand.TogglePlay,
                Key.J to EditorCommand.JumpBack,
                Key.K to EditorCommand.Pause,
                Key.L to EditorCommand.PlayFaster,
                Key.DirectionLeft to EditorCommand.StepBack,
                Key.DirectionRight to EditorCommand.StepForward,
                Key.S to EditorCommand.Split,
                Key.Delete to EditorCommand.Delete,
                Key.Backspace to EditorCommand.Delete,
                Key.I to EditorCommand.MarkIn,
                Key.O to EditorCommand.MarkOut,
                Key.Enter to EditorCommand.AddFromMarks,
                Key.M to EditorCommand.Merge,
                Key.A to EditorCommand.ToggleAccept,
                Key.N to EditorCommand.NextRally,
                Key.P to EditorCommand.PreviousRally,
                Key.R to EditorCommand.ToggleRalliesOnly,
            )
        expected.forEach { (key, command) -> assertEquals(command, press(key), "key $key") }
    }

    @Test
    fun undoAndRedoNeedCtrlOrCmd() {
        assertEquals(EditorCommand.Undo, press(Key.Z, shortcut = true))
        assertEquals(EditorCommand.Redo, press(Key.Z, shortcut = true, shift = true))
        assertNull(press(Key.Z))
    }

    @Test
    fun shiftedArrowsStepLonger() {
        assertEquals(EditorCommand.StepForwardLong, press(Key.DirectionRight, shift = true))
        assertEquals(EditorCommand.StepBackLong, press(Key.DirectionLeft, shift = true))
    }

    @Test
    fun otherShortcutsWithCtrlAreLeftAlone() {
        assertNull(press(Key.A, shortcut = true))
        assertNull(press(Key.S, shortcut = true, shift = true))
    }

    @Test
    fun ctrlSSavesInsteadOfSplitting() {
        assertEquals(EditorCommand.Save, press(Key.S, shortcut = true))
    }

    @Test
    fun unboundKeysMapToNothing() {
        assertNull(press(Key.Q))
    }

    @Test
    fun timecodesAreFormattedWithOptionalHours() {
        assertEquals("0:01.500", formatTimecode(1_500))
        assertEquals("12:03.007", formatTimecode(723_007))
        assertEquals("1:02:03.004", formatTimecode(3_723_004))
        assertEquals("0:00.000", formatTimecode(-5))
    }

    @Test
    fun durationsRoundToSeconds() {
        assertEquals("0:10", formatDuration(9_600))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:00:00", formatDuration(3_600_000))
    }
}
