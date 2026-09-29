package app.snipnet.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color tokens that Material 3's scheme has no slot for: the timeline and video surfaces of the editor. Reach them
 * through [SnipnetTheme.editor] so feature code never hardcodes colors.
 */
data class EditorColors(
    val videoBackground: Color,
    val timelineBackground: Color,
    val timelineTrack: Color,
    val rallyBlock: Color,
    val rallyBlockRejected: Color,
    val playhead: Color,
    val waveform: Color,
    val probabilityHigh: Color,
)

val DarkEditorColors =
    EditorColors(
        videoBackground = Color(0xFF000000),
        timelineBackground = Color(0xFF14171C),
        timelineTrack = Color(0xFF1E232B),
        rallyBlock = Color(0xFF3DDC97),
        rallyBlockRejected = Color(0xFF6B4A4F),
        playhead = Color(0xFFFF5D5D),
        waveform = Color(0xFF7A8699),
        probabilityHigh = Color(0xFFFFC857),
    )

private val DarkScheme =
    darkColorScheme(
        primary = Color(0xFF3DDC97),
        onPrimary = Color(0xFF00382A),
        primaryContainer = Color(0xFF005139),
        onPrimaryContainer = Color(0xFF8CF7C4),
        secondary = Color(0xFF8FB4FF),
        onSecondary = Color(0xFF00296B),
        background = Color(0xFF0F1216),
        onBackground = Color(0xFFE3E6EB),
        surface = Color(0xFF171B21),
        onSurface = Color(0xFFE3E6EB),
        surfaceVariant = Color(0xFF232932),
        onSurfaceVariant = Color(0xFFB4BBC7),
        outline = Color(0xFF6C7686),
        error = Color(0xFFFF6B6B),
    )

private val LocalEditorColors = staticCompositionLocalOf { DarkEditorColors }

object SnipnetTheme {
    val editor: EditorColors
        @Composable get() = LocalEditorColors.current
}

/** The editor is dark-only: video work is done in dim rooms and the timeline colors are tuned for a dark surface. */
@Composable
fun SnipnetTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEditorColors provides DarkEditorColors) {
        MaterialTheme(colorScheme = DarkScheme, content = content)
    }
}
