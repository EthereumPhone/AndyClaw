package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MemorySettingsSection(
    memoryCount: Int,
    autoStoreEnabled: Boolean,
    isReindexing: Boolean,
    onAutoStoreToggle: (Boolean) -> Unit,
    onReindex: () -> Unit,
    onClearMemories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Long-term Memory",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))

        // Memory count card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "$memoryCount memor${if (memoryCount == 1) "y" else "ies"} stored",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Memories persist across conversations and are searchable by the agent. " +
                        "Hybrid keyword + semantic search is used when an embedding provider is configured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Auto-store toggle
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-store conversations",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Automatically save conversation turns to memory for future recall",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoStoreEnabled,
                    onCheckedChange = onAutoStoreToggle,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Action buttons
        Row(modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = onReindex,
                enabled = !isReindexing && memoryCount > 0,
                modifier = Modifier.weight(1f).padding(end = 4.dp),
            ) {
                if (isReindexing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Reindex")
                }
            }
            FilledTonalButton(
                onClick = onClearMemories,
                enabled = memoryCount > 0,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            ) {
                Text("Clear All")
            }
        }
    }
}
