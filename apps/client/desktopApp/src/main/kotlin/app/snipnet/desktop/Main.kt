package app.snipnet.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.ui.App
import app.snipnet.desktop.window.WindowSettings
import app.snipnet.shared.AppInfo
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/** Placeholder app icon: a green disc on the editor background, replaced by the real artwork later. */
private object PlaceholderIcon : Painter() {
    override val intrinsicSize = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        drawRect(Color(0xFF0F1216))
        drawCircle(
            Color(0xFF3DDC97),
            radius = size.minDimension * 0.35f,
            center =
                Offset(
                    size.width / 2,
                    size.height / 2,
                ),
        )
    }
}

/** Bounds of all attached screens in AWT's logical coordinates, which are the dp coordinates of [WindowPosition]. */
private fun screenBounds(): List<Rectangle> =
    runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
    }.getOrDefault(emptyList())

private fun WindowSettings.withGeometryOf(state: WindowState): WindowSettings =
    withGeometry(
        width = state.size.width.value,
        height = state.size.height.value,
        x =
            state.position
                .takeIf { it.isSpecified }
                ?.x
                ?.value,
        y =
            state.position
                .takeIf { it.isSpecified }
                ?.y
                ?.value,
        maximized = state.placement == WindowPlacement.Maximized,
    )

/**
 * The container and the saved geometry are created before `application` because its content lambda is a
 * composable that may recompose; creating them inside would rebuild the navigator (losing the back stack) and
 * re-read the settings file on every recomposition.
 */
@OptIn(FlowPreview::class)
fun main() {
    val container = AppContainer()
    var persisted = container.windowSettingsStore.load().visibleOn(screenBounds())
    val initial = persisted

    application {
        val windowState =
            rememberWindowState(
                placement = if (initial.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
                position =
                    if (initial.x != null && initial.y != null) {
                        WindowPosition(initial.x.dp, initial.y.dp)
                    } else {
                        WindowPosition.PlatformDefault
                    },
                size = DpSize(initial.width.dp, initial.height.dp),
            )

        fun persist() {
            val next = persisted.withGeometryOf(windowState)
            if (next != persisted) {
                persisted = next
                container.windowSettingsStore.save(next)
            }
        }

        // Persist geometry shortly after the user stops moving or resizing rather than on every pixel of a drag.
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { persist() }
        }

        Window(
            onCloseRequest = {
                // A move or resize within the debounce interval before quitting would otherwise be lost.
                persist()
                container.close()
                exitApplication()
            },
            state = windowState,
            title = AppInfo.windowTitle(),
            icon = PlaceholderIcon,
        ) {
            App(container)
        }
    }
}

private const val SAVE_DEBOUNCE_MS = 500L
