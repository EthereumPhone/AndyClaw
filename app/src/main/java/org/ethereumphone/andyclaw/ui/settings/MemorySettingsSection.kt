package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.example.dgenlibrary.DgenLoadingMatrix
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.dgenWhite
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.DgenSquareSwitch

@Composable
fun MemorySettingsSection(
    memoryCount: Int,
    autoStoreEnabled: Boolean,
    smartExtractionEnabled: Boolean,
    aiRerankingEnabled: Boolean,
    isReindexing: Boolean,
    onAutoStoreToggle: (Boolean) -> Unit,
    onSmartExtractionToggle: (Boolean) -> Unit,
    onAiRerankingToggle: (Boolean) -> Unit,
    onReindex: () -> Unit,
    onClearMemories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = SystemColorManager.primaryColor
    val secondaryColor = SystemColorManager.secondaryColor

    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)

    Column(modifier = modifier) {
        Text(
            text = "LONG-TERM MEMORY",
            style = sectionTitleStyle,
            color = primaryColor,
        )
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = "$memoryCount MEMOR${if (memoryCount == 1) "Y" else "IES"} STORED",
                style = contentTitleStyle,
                color = primaryColor,
            )
            Text(
                text = "Memories persist across conversations and are searchable by the agent. " +
                    "Hybrid keyword + semantic search is used when an embedding provider is configured.",
                style = contentBodyStyle,
                color = dgenWhite,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AUTO-STORE CONVERSATIONS",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = "Automatically save conversation turns to memory for future recall",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }
            Spacer(Modifier.width(20.dp))
            DgenSquareSwitch(
                checked = autoStoreEnabled,
                onCheckedChange = onAutoStoreToggle,
                activeColor = primaryColor,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SMART EXTRACTION",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = "Uses an extra LLM call after each turn to extract structured memories " +
                        "(preferences, corrections, project context). Higher quality but costs additional tokens.",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }
            Spacer(Modifier.width(20.dp))
            DgenSquareSwitch(
                checked = smartExtractionEnabled,
                onCheckedChange = onSmartExtractionToggle,
                activeColor = primaryColor,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI MEMORY RECALL",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = "Uses a cheap LLM call to filter retrieved memories for relevance. " +
                        "Reduces noise when many memories are stored. ~500 tokens per query.",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }
            Spacer(Modifier.width(20.dp))
            DgenSquareSwitch(
                checked = aiRerankingEnabled,
                onCheckedChange = onAiRerankingToggle,
                activeColor = primaryColor,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            DgenSmallPrimaryButton(
                text = if (isReindexing) "Reindexing..." else "Reindex",
                primaryColor = primaryColor,
                onClick = onReindex,
                enabled = !isReindexing && memoryCount > 0,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            )
            DgenSmallPrimaryButton(
                text = "Clear All",
                primaryColor = primaryColor,
                onClick = onClearMemories,
                enabled = memoryCount > 0,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
        }

        if (isReindexing) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DgenLoadingMatrix(
                    size = 14.dp,
                    LEDSize = 3.5.dp,
                    activeLEDColor = primaryColor,
                    unactiveLEDColor = secondaryColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "REINDEX IN PROGRESS",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
            }
        }
    }
}
