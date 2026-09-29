package app.snipnet.desktop.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** What the training consent means, shown next to the toggle. */
const val TRAINING_CONSENT_EXPLANATION =
    "When this is on, the videos you mark as final (their low-resolution proxy and your corrected rallies) can be " +
        "used to train and improve the rally detection model. When it is off, your videos are never used for " +
        "training. You can change this at any time."

/** The settings dialog with the training-consent toggle; [holder] is closed when the dialog leaves the composition. */
@Composable
fun SettingsDialog(
    holder: SettingsStateHolder,
    onDismiss: () -> Unit,
) {
    DisposableEffect(holder) { onDispose { holder.close() } }
    val state by holder.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.testTag("settings-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Help improve Snipnet",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.trainingConsent,
                        onCheckedChange = holder::setTrainingConsent,
                        enabled = !state.saving,
                        modifier = Modifier.width(52.dp).testTag("training-consent"),
                    )
                }
                Text(
                    TRAINING_CONSENT_EXPLANATION,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
