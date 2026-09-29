package app.snipnet.desktop.editor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** Everything the editor can be told to do from the keyboard or a button. */
enum class EditorCommand {
    TogglePlay,
    JumpBack,
    Pause,
    PlayFaster,
    StepBack,
    StepForward,
    StepBackLong,
    StepForwardLong,
    Split,
    Delete,
    MarkIn,
    MarkOut,
    AddFromMarks,
    Merge,
    ToggleAccept,
    NextRally,
    PreviousRally,
    Undo,
    Redo,
    ToggleRalliesOnly,
    Save,
}

/** Plain (unmodified) key bindings. The shift variants of the arrow keys are listed in [LONG_STEP_KEYS]. */
private val KEY_BINDINGS: Map<Key, EditorCommand> =
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
        Key.NumPadEnter to EditorCommand.AddFromMarks,
        Key.M to EditorCommand.Merge,
        Key.A to EditorCommand.ToggleAccept,
        Key.N to EditorCommand.NextRally,
        Key.P to EditorCommand.PreviousRally,
        Key.R to EditorCommand.ToggleRalliesOnly,
    )

/** What the arrow keys do while shift is held: a longer step instead of a single frame. */
private val LONG_STEP_KEYS: Map<Key, EditorCommand> =
    mapOf(
        Key.DirectionLeft to EditorCommand.StepBackLong,
        Key.DirectionRight to EditorCommand.StepForwardLong,
    )

/**
 * Maps a key press to its editor command, or null when the key is not bound. Only key-down events count, and
 * letter shortcuts are ignored while ctrl or cmd is held so that platform shortcuts never trigger an edit
 * (ctrl/cmd+S saves instead of splitting). Undo is ctrl/cmd+Z and redo adds shift.
 */
fun editorCommandFor(event: KeyEvent): EditorCommand? {
    if (event.type != KeyEventType.KeyDown) return null
    return editorCommandFor(event.key, event.isCtrlPressed || event.isMetaPressed, event.isShiftPressed)
}

/** Key lookup behind [editorCommandFor], with the modifier state as plain flags; [shortcut] means ctrl or cmd. */
fun editorCommandFor(
    key: Key,
    shortcut: Boolean,
    shift: Boolean,
): EditorCommand? {
    if (shortcut) return if (key == Key.S && !shift) EditorCommand.Save else undoRedoFor(key, shift)
    return (if (shift) LONG_STEP_KEYS[key] else null) ?: KEY_BINDINGS[key]
}

private fun undoRedoFor(
    key: Key,
    shift: Boolean,
): EditorCommand? =
    when {
        key != Key.Z -> null
        shift -> EditorCommand.Redo
        else -> EditorCommand.Undo
    }

/** Formats a media time as `h:mm:ss.mmm`, dropping the hour part below one hour (`m:ss.mmm`). */
fun formatTimecode(ms: Long): String {
    val total = ms.coerceAtLeast(0)
    val millis = total % 1000
    val seconds = total / 1000 % 60
    val minutes = total / 60_000 % 60
    val hours = total / 3_600_000
    val tail = "%02d.%03d".format(seconds, millis)
    return if (hours > 0) "%d:%02d:%s".format(hours, minutes, tail) else "%d:%s".format(minutes, tail)
}

/** Formats a length as `m:ss` (or `h:mm:ss`), rounded to whole seconds, for the compact segment list. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) + 500) / 1000
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60 % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
