package app.snipnet.desktop.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.snipnet.desktop.export.exportRanges
import app.snipnet.desktop.theme.SnipnetTheme
import app.snipnet.shared.editing.EditSegment

/** Keeps mouse clicks on buttons from stealing keyboard focus, so Space and the letter shortcuts keep reaching the editor. */
private fun Modifier.keepEditorFocus(): Modifier = focusProperties { canFocus = false }

/**
 * The editor screen: video preview and the segment list on top, transport controls in the middle and the timeline
 * below. The whole screen listens for the editor shortcuts (see [editorCommandFor]).
 */
@Composable
fun EditorContent(
    holder: EditorStateHolder,
    onBack: () -> Unit,
) {
    val state by holder.state.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("editor")
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    val command = editorCommandFor(event)
                    if (command != null) holder.perform(command)
                    command != null
                }.focusable(),
    ) {
        when {
            state.loading -> CenteredMessage { CircularProgressIndicator() }
            state.loadError != null -> CenteredMessage { LoadFailure(state.loadError.orEmpty(), onBack) }
            else -> LoadedEditor(state, holder, onBack)
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun LoadFailure(
    message: String,
    onBack: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun ColumnScope.LoadedEditor(
    state: EditorState,
    holder: EditorStateHolder,
    onBack: () -> Unit,
) {
    Row(Modifier.weight(1f).fillMaxWidth()) {
        VideoPreview(state, Modifier.weight(1f).fillMaxHeight())
        SegmentList(state, holder, Modifier.width(300.dp).fillMaxHeight())
    }
    TransportBar(state, holder, onBack)
    state.saveError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
    }
    TimelineView(state, holder)
    val exportable = state.timeline?.exportRanges(state.export.options.includeRejected)?.size ?: 0
    ExportDialog(state.export, exportable, holder)
}

@Composable
private fun VideoPreview(
    state: EditorState,
    modifier: Modifier,
) {
    Box(
        modifier.background(SnipnetTheme.editor.videoBackground).testTag("preview"),
        contentAlignment = Alignment.Center,
    ) {
        val frame = state.frame
        if (frame != null) {
            Image(
                frame,
                contentDescription = "Video preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TransportBar(
    state: EditorState,
    holder: EditorStateHolder,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, modifier = Modifier.keepEditorFocus()) { Text("Back") }
        CommandButton("Previous rally", EditorCommand.PreviousRally, holder)
        CommandButton("Frame back", EditorCommand.StepBack, holder)
        CommandButton(if (state.isPlaying) "Pause" else "Play", EditorCommand.TogglePlay, holder)
        CommandButton("Frame forward", EditorCommand.StepForward, holder)
        CommandButton("Next rally", EditorCommand.NextRally, holder)
        Text(
            "${formatTimecode(state.playheadMs)} / ${formatTimecode(state.durationMs)}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp).testTag("timecode"),
        )
        FilterChip(
            selected = state.ralliesOnly,
            onClick = { holder.perform(EditorCommand.ToggleRalliesOnly) },
            label = { Text("Rallies only") },
            modifier = Modifier.keepEditorFocus().testTag("rallies-only"),
        )
        Box(Modifier.weight(1f))
        CommandButton("Split", EditorCommand.Split, holder)
        CommandButton("Merge", EditorCommand.Merge, holder)
        CommandButton("Delete", EditorCommand.Delete, holder)
        CommandButton("Undo", EditorCommand.Undo, holder, enabled = state.canUndo)
        CommandButton("Redo", EditorCommand.Redo, holder, enabled = state.canRedo)
        OutlinedButton(
            onClick = holder::openExport,
            modifier = Modifier.keepEditorFocus().testTag("export-button"),
        ) { Text("Export") }
    }
}

@Composable
private fun CommandButton(
    label: String,
    command: EditorCommand,
    holder: EditorStateHolder,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = { holder.perform(command) },
        enabled = enabled,
        modifier = Modifier.keepEditorFocus(),
    ) { Text(label) }
}

/** All segments in time order with their length and accepted state; a click jumps to the segment. */
@Composable
private fun SegmentList(
    state: EditorState,
    holder: EditorStateHolder,
    modifier: Modifier,
) {
    val selection = state.timeline?.selection ?: emptySet()
    Column(modifier.background(MaterialTheme.colorScheme.surface).testTag("segment-list")) {
        Text(
            "Segments (${state.segments.size})",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(12.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(state.segments, key = { _, segment -> segment.id }) { index, segment ->
                SegmentRow(index + 1, segment, segment.id in selection, holder)
            }
        }
    }
}

@Composable
private fun SegmentRow(
    number: Int,
    segment: EditSegment,
    selected: Boolean,
    holder: EditorStateHolder,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(background)
                .keepEditorFocus()
                .clickable { holder.jumpTo(segment.id) }
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("segment-row-${segment.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("#$number", fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(formatTimecode(segment.startMs), style = MaterialTheme.typography.bodySmall)
            Text(
                "${formatDuration(segment.lengthMs)} long",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(if (segment.accepted) "Accepted" else "Rejected", style = MaterialTheme.typography.labelSmall)
        Checkbox(
            checked = segment.accepted,
            onCheckedChange = { holder.toggleAccept(segment.id) },
            modifier = Modifier.keepEditorFocus().testTag("segment-accept-${segment.id}"),
        )
    }
}
