package app.snipnet.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.nav.Navigator
import app.snipnet.desktop.nav.Screen

/** Shared layout of the placeholder screens until the real features land: a title, a hint and navigation buttons. */
@Composable
private fun PlaceholderScreen(
    title: String,
    hint: String,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
    }
}

@Composable
fun ProjectsScreen(container: AppContainer) {
    val user by container.session.user.collectAsState()
    val projectCount = remember { container.projectStore.list().size }
    PlaceholderScreen(
        "Projects",
        "Signed in as ${user?.email ?: "unknown"}. $projectCount local project(s); the list arrives with the editor.",
    ) {
        OutlinedButton(onClick = container::logout) { Text("Sign out") }
        Button(onClick = { container.navigator.push(Screen.CourtSelection(PLACEHOLDER_VIDEO_ID)) }) {
            Text("Open sample")
        }
    }
}

@Composable
fun EditorScreen(
    navigator: Navigator,
    videoId: String,
) {
    PlaceholderScreen("Editor", "The timeline for video $videoId will appear here.") {
        OutlinedButton(onClick = { navigator.back() }) { Text("Back") }
    }
}

private const val PLACEHOLDER_VIDEO_ID = "sample"
