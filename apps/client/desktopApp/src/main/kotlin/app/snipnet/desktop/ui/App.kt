package app.snipnet.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.theme.SnipnetTheme

/**
 * Root composable: applies the theme and renders the top of the navigator's back stack. On first composition it
 * tries to resume the stored session and skips the login screen when the server still accepts the token.
 */
@Composable
fun App(container: AppContainer) {
    val stack by container.navigator.stack.collectAsState()
    val navigator = container.navigator
    var restoring by remember { mutableStateOf(true) }

    LaunchedEffect(container) {
        if (container.session.restore()) navigator.resetTo(Screen.Projects)
        restoring = false
    }

    SnipnetTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (restoring) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Surface
            }
            when (val screen = stack.last()) {
                Screen.Login -> LoginScreen(container)
                Screen.Projects -> ProjectsScreen(container)
                is Screen.CourtSelection -> CourtSelectionScreen(container, screen.videoId)
                is Screen.Editor -> EditorScreen(navigator, screen.videoId)
            }
        }
    }
}
