package app.snipnet.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.theme.SnipnetTheme

/** Root composable: applies the theme and renders the top of the navigator's back stack. */
@Composable
fun App(container: AppContainer) {
    val stack by container.navigator.stack.collectAsState()
    val navigator = container.navigator

    SnipnetTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val screen = stack.last()) {
                Screen.Login -> LoginScreen(navigator)
                Screen.Projects -> ProjectsScreen(navigator)
                is Screen.CourtSelection -> CourtSelectionScreen(navigator, screen.videoId)
                is Screen.Editor -> EditorScreen(navigator, screen.videoId)
            }
        }
    }
}
