package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import android.content.Intent
import java.math.BigDecimal
import java.math.RoundingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClawHub: () -> Unit = {},
    onNavigateToHeartbeatLogs: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val tinfoilApiKey by viewModel.tinfoilApiKey.collectAsState()
    val downloadProgress by viewModel.modelDownloadManager.downloadProgress.collectAsState()
    val isDownloading by viewModel.modelDownloadManager.isDownloading.collectAsState()
    val downloadError by viewModel.modelDownloadManager.downloadError.collectAsState()
    val yoloMode by viewModel.yoloMode.collectAsState()
    val notificationReplyEnabled by viewModel.notificationReplyEnabled.collectAsState()
    val heartbeatOnNotificationEnabled by viewModel.heartbeatOnNotificationEnabled.collectAsState()
    val heartbeatOnXmtpMessageEnabled by viewModel.heartbeatOnXmtpMessageEnabled.collectAsState()
    val heartbeatIntervalMinutes by viewModel.heartbeatIntervalMinutes.collectAsState()
    val memoryCount by viewModel.memoryCount.collectAsState()
    val autoStoreEnabled by viewModel.autoStoreEnabled.collectAsState()
    val isReindexing by viewModel.isReindexing.collectAsState()
    val extensions by viewModel.extensions.collectAsState()
    val isExtensionScanning by viewModel.isExtensionScanning.collectAsState()
    val enabledSkills by viewModel.enabledSkills.collectAsState()
    val paymasterBalance by viewModel.paymasterBalance.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Model Selection
            Text(
                text = "Model",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            var modelDropdownExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = modelDropdownExpanded,
                onExpandedChange = { modelDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = AnthropicModels.fromModelId(selectedModel)?.name ?: selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
                )
                ExposedDropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { modelDropdownExpanded = false },
                ) {
                    for (model in viewModel.availableModels) {
                        DropdownMenuItem(
                            text = { Text("${model.name} (${model.modelId})") },
                            onClick = {
                                viewModel.setSelectedModel(model.modelId)
                                modelDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            // Paymaster Balance (ethOS privileged only)
            if (viewModel.isPrivileged && paymasterBalance != null) {
                Spacer(Modifier.height(16.dp))
                val context = LocalContext.current
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Paymaster Balance",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            val formattedBalance = try {
                                val bd = BigDecimal(paymasterBalance!!)
                                "$${bd.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
                            } catch (_: NumberFormatException) {
                                "$0.00"
                            }
                            Text(
                                text = formattedBalance,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        FilledTonalButton(onClick = {
                            val intent = Intent("org.ethereumphone.walletmanager.ACTION_OPEN_GAS").apply {
                                setPackage("org.ethereumphone.walletmanager")
                            }
                            context.startActivity(intent)
                        }) {
                            Text("Fill up")
                        }
                    }
                }
            }

            // AI Provider (non-ethOS only)
            if (!viewModel.isPrivileged) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "AI Provider",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))

                var providerDropdownExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = providerDropdownExpanded,
                    onExpandedChange = { providerDropdownExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
                        supportingText = { Text(selectedProvider.description) },
                    )
                    ExposedDropdownMenu(
                        expanded = providerDropdownExpanded,
                        onDismissRequest = { providerDropdownExpanded = false },
                    ) {
                        for (provider in LlmProvider.entries) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(provider.displayName)
                                        Text(
                                            text = provider.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setSelectedProvider(provider)
                                    providerDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Provider-specific config
                when (selectedProvider) {
                    LlmProvider.OPEN_ROUTER -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "OpenRouter API Key",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = "Configured during onboarding. Re-run onboarding to change.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    LlmProvider.TINFOIL -> {
                        var editingKey by remember { mutableStateOf(tinfoilApiKey) }
                        OutlinedTextField(
                            value = editingKey,
                            onValueChange = {
                                editingKey = it
                                viewModel.setTinfoilApiKey(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Tinfoil API Key") },
                            placeholder = { Text("tf-...") },
                            singleLine = true,
                        )
                    }
                    LlmProvider.LOCAL -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "On-Device Model (Qwen2.5-1.5B)",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = "~2.5 GB Q4_K_M quantization",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))

                                if (viewModel.modelDownloadManager.isModelDownloaded) {
                                    Text(
                                        text = "Model downloaded",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.deleteLocalModel() },
                                    ) {
                                        Text("Delete Model")
                                    }
                                } else if (isDownloading) {
                                    Text(
                                        text = "Downloading... ${(downloadProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    if (downloadError != null) {
                                        Text(
                                            text = "Error: $downloadError",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    Button(
                                        onClick = { viewModel.downloadLocalModel() },
                                    ) {
                                        Text("Download Model")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Tier Display
            Text(
                text = "Device Tier",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = viewModel.currentTier,
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (viewModel.isPrivileged) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (viewModel.isPrivileged) "Full access to all skills and tools"
                        else "Some skills are restricted to privileged OS builds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // YOLO Mode
            Text(
                text = "YOLO Mode",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-approve all tools",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Skip approval prompts for all tool and skill usage, including heartbeat and chat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = yoloMode,
                        onCheckedChange = { viewModel.setYoloMode(it) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Notification Reply
            Text(
                text = "Auto-Reply to Notifications",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow replying to messages",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "When enabled, the AI can reply to incoming notifications (Telegram, WhatsApp, etc.) on your behalf",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notificationReplyEnabled,
                        onCheckedChange = { viewModel.setNotificationReplyEnabled(it) },
                    )
                }
            }

            // Heartbeat on Notification
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Heartbeat on Notification",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Trigger heartbeat on new notification",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "When enabled, the AI heartbeat runs whenever a new notification arrives so it can react to messages and alerts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = heartbeatOnNotificationEnabled,
                        onCheckedChange = { viewModel.setHeartbeatOnNotificationEnabled(it) },
                    )
                }
            }

            // Heartbeat on XMTP Message — privileged only
            if (viewModel.isPrivileged) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Heartbeat on XMTP Message",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Trigger heartbeat on new XMTP message",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "When enabled, the AI heartbeat runs with the new messages as context whenever the background sync detects incoming XMTP messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = heartbeatOnXmtpMessageEnabled,
                            onCheckedChange = { viewModel.setHeartbeatOnXmtpMessageEnabled(it) },
                        )
                    }
                }
            }

            // Heartbeat Interval
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Heartbeat Interval",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "Every $heartbeatIntervalMinutes minutes",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (viewModel.isPrivileged) "How often the OS triggers the AI heartbeat check"
                        else "How often the background service runs the AI heartbeat check",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = heartbeatIntervalMinutes.toFloat(),
                        onValueChange = { viewModel.setHeartbeatIntervalMinutes(it.toInt()) },
                        valueRange = 5f..60f,
                        steps = 10,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("5 min", style = MaterialTheme.typography.labelSmall)
                        Text("60 min", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Heartbeat Logs
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Heartbeat Logs",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Run History",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "View past heartbeat runs, tool calls, and responses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = onNavigateToHeartbeatLogs) {
                        Text("Open")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Long-term Memory
            MemorySettingsSection(
                memoryCount = memoryCount,
                autoStoreEnabled = autoStoreEnabled,
                isReindexing = isReindexing,
                onAutoStoreToggle = { viewModel.setAutoStoreEnabled(it) },
                onReindex = { viewModel.reindexMemory() },
                onClearMemories = { viewModel.clearAllMemories() },
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Extensions
            ExtensionManagementSection(
                extensions = extensions,
                isScanning = isExtensionScanning,
                onRescan = { viewModel.rescanExtensions() },
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // ClawHub
            Text(
                text = "ClawHub",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Skill Registry",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Browse, install, and manage skills from ClawHub",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = onNavigateToClawHub) {
                        Text("Open")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // Skills
            SkillManagementSection(
                skills = viewModel.registeredSkills,
                enabledSkills = enabledSkills,
                onToggleSkill = { skillId, enabled -> viewModel.toggleSkill(skillId, enabled) },
            )
        }
    }
}
