package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ethereumphone.andyclaw.extensions.ExtensionDescriptor
import org.ethereumphone.andyclaw.extensions.ExtensionType

@Composable
fun ExtensionManagementSection(
    extensions: List<ExtensionDescriptor>,
    isScanning: Boolean,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Extensions",
                style = MaterialTheme.typography.titleMedium,
            )
            FilledTonalButton(
                onClick = onRescan,
                enabled = !isScanning,
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Rescan")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (extensions.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isScanning) "Scanning for extensions…"
                        else "No extensions found",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Extensions are discovered from installed APKs with AndyClaw metadata ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            for (ext in extensions) {
                ExtensionRow(ext)
            }
        }
    }
}

@Composable
private fun ExtensionRow(descriptor: ExtensionDescriptor) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = descriptor.name,
                    style = MaterialTheme.typography.bodyLarge,
                )
                val detail = buildString {
                    append(
                        when (descriptor.type) {
                            ExtensionType.APK -> "APK"
                        }
                    )
                    append(" · ${descriptor.functions.size} function")
                    if (descriptor.functions.size != 1) append("s")
                    if (descriptor.version > 1) append(" · v${descriptor.version}")
                    if (descriptor.trusted) append(" · Trusted")
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
