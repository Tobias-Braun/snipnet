package app.snipnet.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.snipnet.desktop.di.AppContainer
import app.snipnet.desktop.nav.Screen
import app.snipnet.desktop.projects.ProjectRow
import app.snipnet.desktop.projects.ProjectStatus
import app.snipnet.desktop.projects.ProjectsStateHolder
import app.snipnet.desktop.projects.SupportedVideo
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.nio.file.Path

/**
 * The projects list: import by file picker or by dropping files anywhere on the screen, a badge and progress per
 * project, and the retry, cancel, court, analyze and open actions each state allows.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectsScreen(container: AppContainer) {
    val holder = remember { container.projectsStateHolder() }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val state by holder.state.collectAsState()
    val user by container.session.user.collectAsState()
    var dragging by remember { mutableStateOf(false) }

    val dropTarget =
        remember(holder) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    dragging = true
                }

                override fun onExited(event: DragAndDropEvent) {
                    dragging = false
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    dragging = false
                    val files = droppedFiles(event)
                    if (files.isEmpty()) return false
                    holder.import(files)
                    return true
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .then(
                    if (dragging) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Projects", style = MaterialTheme.typography.headlineMedium)
                Text(
                    user?.email?.let { "Signed in as $it" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = holder::refresh, enabled = !state.refreshing) { Text("Refresh") }
            OutlinedButton(onClick = container::logout) { Text("Sign out") }
            Button(onClick = { holder.import(pickVideoFiles()) }) { Text("Import video") }
        }

        state.notice?.let { NoticeBar(it, holder::dismissNotice) }

        if (state.rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Drop a video here or use Import video (${SupportedVideo.extensions.joinToString(
                        ", ",
                    ) { ".$it" }}).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.rows, key = { it.project.id }) { row -> ProjectCard(row, holder, container) }
            }
        }
    }
}

@Composable
private fun NoticeBar(
    text: String,
    onDismiss: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun ProjectCard(
    row: ProjectRow,
    holder: ProjectsStateHolder,
    container: AppContainer,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                StatusBadge(row.status)
            }
            if (row.status in PROGRESS_STATUSES) {
                val progress = row.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            row.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ProjectActions(row, holder, container) }
        }
    }
}

@Composable
private fun ProjectActions(
    row: ProjectRow,
    holder: ProjectsStateHolder,
    container: AppContainer,
) {
    val id = row.project.id
    when (row.status) {
        ProjectStatus.FAILED -> Button(onClick = { holder.retry(id) }) { Text("Retry") }
        ProjectStatus.READY ->
            if (row.hasCourt) {
                Button(onClick = { holder.analyze(id) }) { Text("Analyze") }
            } else {
                Button(onClick = { container.navigator.push(Screen.CourtSelection(id)) }) { Text("Select court") }
            }
        ProjectStatus.ANALYZED -> Button(onClick = { container.navigator.push(Screen.Editor(id)) }) { Text("Open") }
        else -> Unit
    }
    if (row.cancellable) TextButton(onClick = { holder.cancel(row.project.id) }) { Text("Cancel") }
    if (row.status != ProjectStatus.PROXY && row.status != ProjectStatus.UPLOADING) {
        TextButton(onClick = { holder.remove(row.project.id) }) { Text("Remove") }
    }
}

@Composable
private fun StatusBadge(status: ProjectStatus) {
    val (label, container, content) = badgeColors(status)
    Text(
        label,
        Modifier.background(container, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = content,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun badgeColors(status: ProjectStatus): Triple<String, Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        ProjectStatus.PROXY -> Triple("Preparing", scheme.surfaceVariant, scheme.onSurfaceVariant)
        ProjectStatus.UPLOADING -> Triple("Uploading", scheme.surfaceVariant, scheme.onSurfaceVariant)
        ProjectStatus.READY -> Triple("Ready", scheme.secondary, scheme.onSecondary)
        ProjectStatus.ANALYZING -> Triple("Analyzing", scheme.secondary, scheme.onSecondary)
        ProjectStatus.ANALYZED -> Triple("Analyzed", scheme.primaryContainer, scheme.onPrimaryContainer)
        ProjectStatus.FAILED -> Triple("Failed", scheme.error, scheme.onError)
        ProjectStatus.UNKNOWN -> Triple("Unknown", scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

private val PROGRESS_STATUSES = setOf(ProjectStatus.PROXY, ProjectStatus.UPLOADING, ProjectStatus.ANALYZING)

/** The files of a drop, as paths. Anything that is not a file list (dragged text, for example) yields nothing. */
@OptIn(ExperimentalComposeUiApi::class)
private fun droppedFiles(event: DragAndDropEvent): List<Path> {
    val transferable = event.awtTransferable
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()

    @Suppress("UNCHECKED_CAST")
    val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<java.io.File> ?: return emptyList()
    return files.map { it.toPath() }
}

/**
 * Opens the platform's native file dialog (multiple selection). The extension filter is only honoured on some
 * platforms, so the import validates the files again.
 */
private fun pickVideoFiles(): List<Path> {
    val dialog = FileDialog(null as Frame?, "Import video", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in SupportedVideo.extensions }
    dialog.isVisible = true
    return dialog.files.map { it.toPath() }
}
