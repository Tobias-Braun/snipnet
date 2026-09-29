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
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.ui.App
import app.snipnet.desktop.window.WindowSettings
import app.snipnet.shared.AppInfo
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

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

@OptIn(FlowPreview::class)
fun main() =
    application {
        val container = AppContainer()
        val saved = container.windowSettingsStore.load()
        val windowState =
            rememberWindowState(
                placement = if (saved.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
                position =
                    if (saved.x != null && saved.y != null) {
                        WindowPosition(saved.x.dp, saved.y.dp)
                    } else {
                        WindowPosition.PlatformDefault
                    },
                size = DpSize(saved.width.dp, saved.height.dp),
            )

        // Persist geometry shortly after the user stops moving or resizing rather than on every pixel of a drag.
        // While maximized the floating size and position are kept from the last non-maximized state.
        LaunchedEffect(windowState) {
            snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { (size, position, placement) ->
                    val maximized = placement == WindowPlacement.Maximized
                    val previous = container.windowSettingsStore.load()
                    container.windowSettingsStore.save(
                        if (maximized) {
                            previous.copy(maximized = true)
                        } else {
                            WindowSettings(
                                width = size.width.value,
                                height = size.height.value,
                                x = position.takeIf { it.isSpecified }?.x?.value,
                                y = position.takeIf { it.isSpecified }?.y?.value,
                                maximized = false,
                            )
                        },
                    )
                }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = AppInfo.windowTitle(),
            icon = PlaceholderIcon,
        ) {
            App(container)
        }
    }

private const val SAVE_DEBOUNCE_MS = 500L
