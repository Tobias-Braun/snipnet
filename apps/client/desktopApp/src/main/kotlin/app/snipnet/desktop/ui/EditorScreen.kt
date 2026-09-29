package app.snipnet.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.editor.EditorContent

/** Route for `Screen.Editor`: creates the state holder for the local project and closes it when the screen leaves. */
@Composable
fun EditorScreen(
    container: AppContainer,
    projectId: String,
) {
    val holder = remember(projectId) { container.editorStateHolder(projectId) }
    DisposableEffect(holder) { onDispose { holder.close() } }
    EditorContent(holder, onBack = { container.navigator.back() })
}
