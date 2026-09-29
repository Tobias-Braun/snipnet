package app.snipnet.desktop.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.snipnet.desktop.export.ExportMode
import app.snipnet.desktop.export.ExportQuality
import app.snipnet.desktop.export.ExportUiState
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import javax.swing.JFileChooser

/**
 * The export dialog: target folder, mode, quality and the rejected-segments toggle. While an export runs the
 * choices are locked and the dialog shows progress with a cancel button; afterwards it lists what was written or
 * the reason for the failure.
 */
@Composable
fun ExportDialog(
    state: ExportUiState,
    exportableCount: Int,
    holder: EditorStateHolder,
) {
    if (!state.open) return
    val options = state.options
    val videoMode = options.mode == ExportMode.SingleVideo || options.mode == ExportMode.PerRally
    AlertDialog(
        onDismissRequest = { if (!state.running) holder.closeExport() },
        modifier = Modifier.testTag("export-dialog"),
        title = { Text("Export rallies") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        options.folder?.toString() ?: "No folder chosen",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).testTag("export-folder"),
                    )
                    OutlinedButton(
                        enabled = !state.running,
                        onClick = {
                            pickFolder(options.folder)?.let { chosen ->
                                holder.setExportOptions { it.copy(folder = chosen) }
                            }
                        },
                    ) { Text("Choose folder") }
                }
                Text("Format", style = MaterialTheme.typography.titleSmall)
                ExportMode.entries.forEach { mode ->
                    ChoiceRow(mode.label, options.mode == mode, !state.running, "export-mode-${mode.name}") {
                        holder.setExportOptions { it.copy(mode = mode) }
                    }
                }
                if (videoMode) {
                    Text("Quality", style = MaterialTheme.typography.titleSmall)
                    ExportQuality.entries.forEach { quality ->
                        ChoiceRow(
                            quality.label,
                            options.quality == quality,
                            !state.running,
                            "export-quality-${quality.name}",
                        ) {
                            holder.setExportOptions { it.copy(quality = quality) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = options.includeRejected,
                        onCheckedChange = { checked -> holder.setExportOptions { it.copy(includeRejected = checked) } },
                        enabled = !state.running,
                        modifier = Modifier.testTag("export-include-rejected"),
                    )
                    Text("Include rejected segments")
                }
                Text(
                    "$exportableCount segments. Files with the same name in the folder are replaced.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.running) {
                    LinearProgressIndicator(progress = {
                        state.progress.toFloat()
                    }, modifier = Modifier.fillMaxWidth().testTag("export-progress"))
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("export-error"))
                }
                state.result?.let { files ->
                    Text(
                        "Exported ${files.size} file(s) to ${files.first().parent}.",
                        modifier = Modifier.testTag("export-done"),
                    )
                }
            }
        },
        confirmButton = {
            if (state.running) {
                TextButton(
                    onClick = holder::cancelExport,
                    modifier = Modifier.testTag("export-cancel"),
                ) { Text("Cancel") }
            } else {
                TextButton(
                    onClick = holder::startExport,
                    enabled = options.folder != null && exportableCount > 0,
                    modifier = Modifier.testTag("export-start"),
                ) { Text("Export") }
            }
        },
        dismissButton = {
            if (!state.running) TextButton(onClick = holder::closeExport) { Text("Close") }
        },
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled, modifier = Modifier.testTag(tag))
        Text(label)
    }
}

/**
 * Asks for a directory with the native dialog: macOS only offers folder selection through the AWT file dialog
 * with a system property, other systems through the Swing chooser. Returns null when the user cancels.
 */
private fun pickFolder(start: Path?): Path? {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, "Export folder", FileDialog.LOAD)
            start?.let { dialog.directory = it.toString() }
            dialog.isVisible = true
            val name = dialog.file ?: return null
            return Path.of(dialog.directory, name)
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    val chooser = JFileChooser(start?.toFile()).apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
