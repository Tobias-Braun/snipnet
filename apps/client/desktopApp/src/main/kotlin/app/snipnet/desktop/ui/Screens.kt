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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
fun LoginScreen(navigator: Navigator) {
    PlaceholderScreen("Sign in", "Login and registration arrive with the API client.") {
        Button(onClick = { navigator.resetTo(Screen.Projects) }) { Text("Continue") }
    }
}

@Composable
fun ProjectsScreen(navigator: Navigator) {
    PlaceholderScreen("Projects", "Your videos will be listed here.") {
        OutlinedButton(onClick = { navigator.resetTo(Screen.Login) }) { Text("Sign out") }
        Button(onClick = { navigator.push(Screen.CourtSelection(PLACEHOLDER_VIDEO_ID)) }) { Text("Open sample") }
    }
}

@Composable
fun CourtSelectionScreen(
    navigator: Navigator,
    videoId: String,
) {
    PlaceholderScreen("Court selection", "Mark the net and court area for video $videoId.") {
        OutlinedButton(onClick = { navigator.back() }) { Text("Back") }
        Button(onClick = { navigator.push(Screen.Editor(videoId)) }) { Text("Continue to editor") }
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
