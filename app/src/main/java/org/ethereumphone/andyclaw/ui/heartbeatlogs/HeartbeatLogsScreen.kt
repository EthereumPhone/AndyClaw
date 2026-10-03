package org.ethereumphone.andyclaw.ui.heartbeatlogs

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dgenlibrary.DetailItem
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.dgenWhite
import org.ethereumphone.andyclaw.heartbeat.HeartbeatLogEntry
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.heartbeat.HeartbeatToolCall
import org.ethereumphone.andyclaw.ui.components.DgenBackNavigationBackground
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.SmallDetailItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HeartbeatLogsSubScreen {
    Main,
    Detail,
}

@Composable
fun HeartbeatLogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: HeartbeatLogsViewModel = viewModel(),
) {
    val logs by viewModel.logs.collectAsState()
    var currentSubScreen by remember { mutableStateOf(HeartbeatLogsSubScreen.Main) }
    var selectedEntry by remember { mutableStateOf<HeartbeatLogEntry?>(null) }

    val context = LocalContext.current
    LaunchedEffect(Unit) { SystemColorManager.refresh(context) }
    val primaryColor = SystemColorManager.primaryColor

    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)

    DgenBackNavigationBackground(
        title = when (currentSubScreen) {
            HeartbeatLogsSubScreen.Main -> "Heartbeat Logs"
            HeartbeatLogsSubScreen.Detail -> "Log Detail"
        },
        primaryColor = primaryColor,
        onNavigateBack = {
            when (currentSubScreen) {
                HeartbeatLogsSubScreen.Main -> onNavigateBack()
                else -> { currentSubScreen = HeartbeatLogsSubScreen.Main }
            }
        },
    ) {
        Crossfade(targetState = currentSubScreen, label = "heartbeat_crossfade") { screen ->
            when (screen) {
                HeartbeatLogsSubScreen.Main -> {
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "NO HEARTBEAT LOGS YET",
                                    style = sectionTitleStyle,
                                    color = primaryColor,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Logs will appear after the next heartbeat run",
                                    style = contentBodyStyle,
                                    color = dgenWhite,
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "RUN HISTORY",
                                    style = sectionTitleStyle,
                                    color = primaryColor,
                                )
                                DgenSmallPrimaryButton(
                                    text = "Clear",
                                    primaryColor = primaryColor,
                                    onClick = { viewModel.clearLogs() },
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(
                                    items = logs,
                                    key = { it.timestampMs },
                                ) { entry ->
                                    HeartbeatLogRow(
                                        entry = entry,
                                        primaryColor = primaryColor,
                                        contentTitleStyle = contentTitleStyle,
                                        contentBodyStyle = contentBodyStyle,
                                        onClick = {
                                            selectedEntry = entry
                                            currentSubScreen = HeartbeatLogsSubScreen.Detail
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                HeartbeatLogsSubScreen.Detail -> {
                    val entry = selectedEntry ?: return@Crossfade
                    HeartbeatLogDetail(
                        entry = entry,
                        primaryColor = primaryColor,
                        sectionTitleStyle = sectionTitleStyle,
                        contentTitleStyle = contentTitleStyle,
                        contentBodyStyle = contentBodyStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeartbeatLogRow(
    entry: HeartbeatLogEntry,
    primaryColor: Color,
    contentTitleStyle: TextStyle,
    contentBodyStyle: TextStyle,
    onClick: () -> Unit,
) {
    val timeText = remember(entry.timestampMs) {
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }
    val isError = entry.outcome == "error"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = timeText,
                style = contentTitleStyle,
                color = primaryColor,
            )
            Text(
                text = entry.outcome.uppercase(),
                style = contentTitleStyle,
                color = if (isError) Color(0xFFFF6B6B) else primaryColor,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append("${entry.durationMs}ms")
                if (entry.toolCalls.isNotEmpty()) {
                    append(" · ${entry.toolCalls.size} tool call(s)")
                }
            },
            style = contentBodyStyle,
            color = dgenWhite.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // A failed run has no response; what went wrong is the line worth showing.
            text = entry.responseText.ifBlank { entry.error?.takeIf { it.isNotBlank() } ?: "(no response)" },
            style = contentBodyStyle,
            color = dgenWhite,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeartbeatLogDetail(
    entry: HeartbeatLogEntry,
    primaryColor: Color,
    sectionTitleStyle: TextStyle,
    contentTitleStyle: TextStyle,
    contentBodyStyle: TextStyle,
) {
    val timeText = remember(entry.timestampMs) {
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        DetailItem(
            label = "TIMESTAMP",
            value = timeText,
            primaryColor = primaryColor
        )

        DetailItem(
            label = "OUTCOME",
            value = entry.outcome.uppercase(),
            primaryColor = primaryColor
        )

        DetailItem(
            label = "DURATION",
            value = "${entry.durationMs}ms",
            primaryColor = primaryColor
        )
        DetailItem(
            label = "PROMPT",
            value = entry.prompt.ifBlank { "(empty)" },
            primaryColor = primaryColor
        )
        DetailItem(
            label = "RESPONSE",
            value = entry.responseText.ifBlank { "(no response)" },
            primaryColor = primaryColor
        )

        if (!entry.error.isNullOrBlank()) {

            HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))


            DetailItem(
                label = "ERROR",
                value = entry.error,
                primaryColor = Color(0xFFFF6B6B)
            )
        }

        if (entry.toolCalls.isNotEmpty()) {
            HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
            Text(text = "TOOL CALLS", style = sectionTitleStyle, color = primaryColor)
            for (tool in entry.toolCalls) {
                SmallDetailItem(
                    label = tool.toolName.uppercase(),
                    value = tool.result,
                    primaryColor = primaryColor,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

private val previewPrimaryColor = Color(0xFF00E5FF)

private val previewSectionTitleStyle = AppTextStyles.sectionTitle(previewPrimaryColor)
private val previewContentTitleStyle = AppTextStyles.contentTitle(previewPrimaryColor)
private val previewContentBodyStyle = AppTextStyles.contentBody(previewPrimaryColor)

private val sampleEntrySuccess = HeartbeatLogEntry(
    timestampMs = 1_709_650_000_000L,
    outcome = "success",
    prompt = "Check battery level and report status",
    responseText = "Battery is at 87 %. No action needed.",
    durationMs = 1_230L,
    toolCalls = listOf(
        HeartbeatToolCall("getBatteryLevel", "87"),
        HeartbeatToolCall("getChargingState", "discharging"),
    ),
)

private val sampleEntryError = HeartbeatLogEntry(
    timestampMs = 1_709_650_060_000L,
    outcome = "error",
    prompt = "Fetch latest news",
    responseText = "",
    error = "NetworkException: Unable to resolve host api.example.com",
    durationMs = 4_500L,
)

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewHeartbeatLogRowSuccess() {
    HeartbeatLogRow(
        entry = sampleEntrySuccess,
        primaryColor = previewPrimaryColor,
        contentTitleStyle = previewContentTitleStyle,
        contentBodyStyle = previewContentBodyStyle,
        onClick = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewHeartbeatLogRowError() {
    HeartbeatLogRow(
        entry = sampleEntryError,
        primaryColor = previewPrimaryColor,
        contentTitleStyle = previewContentTitleStyle,
        contentBodyStyle = previewContentBodyStyle,
        onClick = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewHeartbeatLogDetailSuccess() {
    HeartbeatLogDetail(
        entry = sampleEntrySuccess,
        primaryColor = previewPrimaryColor,
        sectionTitleStyle = previewSectionTitleStyle,
        contentTitleStyle = previewContentTitleStyle,
        contentBodyStyle = previewContentBodyStyle,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewHeartbeatLogDetailError() {
    HeartbeatLogDetail(
        entry = sampleEntryError,
        primaryColor = previewPrimaryColor,
        sectionTitleStyle = previewSectionTitleStyle,
        contentTitleStyle = previewContentTitleStyle,
        contentBodyStyle = previewContentBodyStyle,
    )
}
