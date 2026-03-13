package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.ethereumphone.andyclaw.ui.components.DgenCursorTextfield
import androidx.compose.material3.Slider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dgenlibrary.ui.theme.PitagonsSans
import com.example.dgenlibrary.ui.theme.SpaceMono
import com.example.dgenlibrary.ui.theme.dgenWhite
import com.example.dgenlibrary.ui.theme.pulseOpacity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.LinearProgressIndicator
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.ui.components.DgenBackNavigationBackground
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.DgenSquareSwitch
import org.ethereumphone.andyclaw.llm.LlmProvider
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.body1_fontSize
import com.example.dgenlibrary.ui.theme.body2_fontSize
import com.example.dgenlibrary.ui.theme.button_fontSize
import com.example.dgenlibrary.ui.theme.label_fontSize
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.GlowStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.KeyboardType
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToClawHub: () -> Unit = {},
    onNavigateToHeartbeatLogs: () -> Unit = {},
    onNavigateToAgentDisplayTest: () -> Unit = {},
    onNavigateToAgentTxHistory: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val tinfoilApiKey by viewModel.tinfoilApiKey.collectAsState()
    val openRouterApiKey by viewModel.apiKey.collectAsState()
    val openaiApiKey by viewModel.openaiApiKey.collectAsState()
    val veniceApiKey by viewModel.veniceApiKey.collectAsState()
    val claudeOauthRefreshToken by viewModel.claudeOauthRefreshToken.collectAsState()
    val downloadProgress by viewModel.modelDownloadManager.downloadProgress.collectAsState()
    val isDownloading by viewModel.modelDownloadManager.isDownloading.collectAsState()
    val downloadError by viewModel.modelDownloadManager.downloadError.collectAsState()
    val yoloMode by viewModel.yoloMode.collectAsState()
    val safetyEnabled by viewModel.safetyEnabled.collectAsState()
    val notificationReplyEnabled by viewModel.notificationReplyEnabled.collectAsState()
    val executiveSummaryEnabled by viewModel.executiveSummaryEnabled.collectAsState()
    val heartbeatOnNotificationEnabled by viewModel.heartbeatOnNotificationEnabled.collectAsState()
    val heartbeatOnXmtpMessageEnabled by viewModel.heartbeatOnXmtpMessageEnabled.collectAsState()
    val heartbeatIntervalMinutes by viewModel.heartbeatIntervalMinutes.collectAsState()
    val heartbeatUseSameModel by viewModel.heartbeatUseSameModel.collectAsState()
    val heartbeatProvider by viewModel.heartbeatProvider.collectAsState()
    val heartbeatModel by viewModel.heartbeatModel.collectAsState()
    val memoryCount by viewModel.memoryCount.collectAsState()
    val autoStoreEnabled by viewModel.autoStoreEnabled.collectAsState()
    val isReindexing by viewModel.isReindexing.collectAsState()
    val extensions by viewModel.extensions.collectAsState()
    val isExtensionScanning by viewModel.isExtensionScanning.collectAsState()
    val enabledSkills by viewModel.enabledSkills.collectAsState()
    val paymasterBalance by viewModel.paymasterBalance.collectAsState()
    val telegramBotEnabled by viewModel.telegramBotEnabled.collectAsState()
    val telegramOwnerChatId by viewModel.telegramOwnerChatId.collectAsState()
    val ledMaxBrightness by viewModel.ledMaxBrightness.collectAsState()
    val googleRefreshToken by viewModel.googleOauthRefreshToken.collectAsState()
    val googleClientId by viewModel.googleOauthClientId.collectAsState()
    val googleClientSecret by viewModel.googleOauthClientSecret.collectAsState()
    val inspectedSkill by viewModel.inspectedSkill.collectAsState()
    var showTelegramOnboarding by remember { mutableStateOf(false) }
    var currentSubScreen by remember { mutableStateOf(SettingsSubScreen.Main) }
    var lastBrightnessValue by remember { mutableStateOf(ledMaxBrightness) }

    inspectedSkill?.let { skill ->
        SkillInspectionDialog(
            inspectedSkill = skill,
            onDismiss = viewModel::dismissSkillInspection,
        )
    }

    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(Unit) {
        SystemColorManager.refresh(context)
    }

    val primaryColor = SystemColorManager.primaryColor
    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)
    val rowControlSpacing = 20.dp

    val providerChoices = if (viewModel.isPrivileged) {
        listOf(LlmProvider.ETHOS_PREMIUM, LlmProvider.OPEN_ROUTER, LlmProvider.CLAUDE_OAUTH, LlmProvider.OPENAI, LlmProvider.VENICE, LlmProvider.TINFOIL, LlmProvider.LOCAL)
    } else {
        listOf(LlmProvider.OPEN_ROUTER, LlmProvider.CLAUDE_OAUTH, LlmProvider.OPENAI, LlmProvider.VENICE, LlmProvider.TINFOIL, LlmProvider.LOCAL)
    }

    DgenBackNavigationBackground(
        title = when (currentSubScreen) {
            SettingsSubScreen.Main -> "Settings"
            SettingsSubScreen.ModelSelection -> "Select Model"
            SettingsSubScreen.ProviderSelection -> "Select Provider"
            SettingsSubScreen.HeartbeatModelSelection -> "Heartbeat Model"
            SettingsSubScreen.HeartbeatProviderSelection -> "Heartbeat Provider"
        },
        primaryColor = primaryColor,
        onNavigateBack = {
            when (currentSubScreen) {
                SettingsSubScreen.Main -> onNavigateBack()
                else -> { currentSubScreen = SettingsSubScreen.Main }
            }
        },
    ) {
        Crossfade(targetState = currentSubScreen, label = "settings_crossfade") { screen ->
        when (screen) {
        SettingsSubScreen.Main ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Model Selection
            Text(
                text = "MODEL",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))
            val isLocalProvider = selectedProvider == LlmProvider.LOCAL
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isLocalProvider) Modifier
                        else Modifier.clickable { currentSubScreen = SettingsSubScreen.ModelSelection }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isLocalProvider) "Qwen2.5-1.5B-Instruct (On-Device)"
                        else AnthropicModels.fromModelId(selectedModel)?.name ?: selectedModel,
                    style = TextStyle(
                        fontFamily = PitagonsSans,
                        color = if (isLocalProvider) dgenWhite.copy(alpha = 0.5f) else dgenWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = body1_fontSize,
                        lineHeight = body1_fontSize,
                        shadow = GlowStyle.body(dgenWhite),
                    ),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = if (isLocalProvider) primaryColor.copy(alpha = 0.3f) else primaryColor,
                )
            }

            // Paymaster Balance (ethOS privileged only)
            if (viewModel.isPrivileged && paymasterBalance != null) {
                Spacer(Modifier.height(16.dp))
                val context = LocalContext.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PAYMASTER BALANCE",
                            style = sectionTitleStyle,
                            color = primaryColor,
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
                            style = TextStyle(
                                fontFamily = PitagonsSans,
                                color = dgenWhite,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = body2_fontSize,
                                lineHeight = body2_fontSize,
                                shadow = GlowStyle.body(dgenWhite),
                            ),
                            color = dgenWhite,
                        )
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSmallPrimaryButton(
                        text = "Fill up",
                        primaryColor = primaryColor,
                        onClick = {
                        val intent = Intent().apply {
                            setClassName("io.freedomfactory.paymaster", "io.freedomfactory.paymaster.MainActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    })
                }
            }

            // Agent Wallet (ethOS privileged only)
            if (viewModel.isPrivileged) {
                Spacer(Modifier.height(24.dp))
                GlowingDivider(primaryColor)
                Spacer(Modifier.height(16.dp))
                AgentWalletSection(
                    primaryColor = primaryColor,
                    sectionTitleStyle = sectionTitleStyle,
                    contentTitleStyle = contentTitleStyle,
                    contentBodyStyle = contentBodyStyle,
                    onNavigateToTxHistory = onNavigateToAgentTxHistory,
                )
            }

            // AI Provider
            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            Text(
                text = "AI PROVIDER",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { currentSubScreen = SettingsSubScreen.ProviderSelection }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedProvider.displayName,
                    style = TextStyle(
                        fontFamily = PitagonsSans,
                        color = dgenWhite,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = body1_fontSize,
                        lineHeight = body1_fontSize,
                        shadow = GlowStyle.body(dgenWhite),
                    ),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = primaryColor,
                )
            }

            Spacer(Modifier.height(8.dp))

            when (selectedProvider) {
                LlmProvider.ETHOS_PREMIUM -> {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            text = "BILLED VIA PAYMASTER BALANCE",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "No API key needed. Inference is charged against your ethOS paymaster balance.",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                }
                LlmProvider.OPEN_ROUTER -> {
                    var editingOpenRouterKey by remember { mutableStateOf(openRouterApiKey) }
                    DgenCursorTextfield(
                        value = editingOpenRouterKey,
                        onValueChange = {
                            editingOpenRouterKey = it
                            viewModel.setApiKey(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "OpenRouter API Key",
                        placeholder = { Text("sk-or-...", color = dgenWhite.copy(alpha = 0.3f), style = MaterialTheme.typography.bodySmall.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                        visualTransformation = PasswordVisualTransformation(),
                        primaryColor = primaryColor,
                    )
                }
                LlmProvider.CLAUDE_OAUTH -> {
                    var editingToken by remember { mutableStateOf(claudeOauthRefreshToken) }
                    DgenCursorTextfield(
                        value = editingToken,
                        onValueChange = {
                            editingToken = it
                            viewModel.setClaudeOauthRefreshToken(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Claude Setup Token",
                        placeholder = { Text("sk-ant-ort01-...", color = dgenWhite.copy(alpha = 0.3f), style = MaterialTheme.typography.bodySmall.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                        visualTransformation = PasswordVisualTransformation(),
                        primaryColor = primaryColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Run `claude setup-token` in Claude Code CLI to generate this token. Requires a Claude Pro or Max subscription.",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                LlmProvider.TINFOIL -> {
                    var editingKey by remember { mutableStateOf(tinfoilApiKey) }
                    DgenCursorTextfield(
                        value = editingKey,
                        onValueChange = {
                            editingKey = it
                            viewModel.setTinfoilApiKey(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Tinfoil API Key",
                        placeholder = { Text("tf-...", color = dgenWhite.copy(alpha = 0.3f), style = MaterialTheme.typography.bodySmall.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                        visualTransformation = PasswordVisualTransformation(),
                        primaryColor = primaryColor,
                    )
                }
                LlmProvider.OPENAI -> {
                    var editingKey by remember { mutableStateOf(openaiApiKey) }
                    DgenCursorTextfield(
                        value = editingKey,
                        onValueChange = {
                            editingKey = it
                            viewModel.setOpenaiApiKey(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "OpenAI API Key",
                        placeholder = { Text("sk-...", color = dgenWhite.copy(alpha = 0.3f), style = MaterialTheme.typography.bodySmall.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                        visualTransformation = PasswordVisualTransformation(),
                        primaryColor = primaryColor,
                    )
                }
                LlmProvider.VENICE -> {
                    var editingKey by remember { mutableStateOf(veniceApiKey) }
                    DgenCursorTextfield(
                        value = editingKey,
                        onValueChange = {
                            editingKey = it
                            viewModel.setVeniceApiKey(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Venice API Key",
                        placeholder = { Text("vce-...", color = dgenWhite.copy(alpha = 0.3f), style = MaterialTheme.typography.bodySmall.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                        visualTransformation = PasswordVisualTransformation(),
                        primaryColor = primaryColor,
                    )
                }
                LlmProvider.LOCAL -> {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            text = "ON-DEVICE MODEL (QWEN2.5-1.5B)",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "~2.5 GB Q4_K_M quantization",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                        Spacer(Modifier.height(12.dp))

                        if (viewModel.modelDownloadManager.isModelDownloaded) {
                            Text(
                                text = "MODEL DOWNLOADED",
                                style = contentTitleStyle,
                                color = primaryColor,
                            )
                            Spacer(Modifier.height(8.dp))
                            DgenSmallPrimaryButton(
                                text = "Delete Model",
                                primaryColor = primaryColor,
                                onClick = { viewModel.deleteLocalModel() },
                            )
                        } else if (isDownloading) {
                            Text(
                                text = "DOWNLOADING... ${(downloadProgress * 100).toInt()}%",
                                style = contentTitleStyle,
                                color = primaryColor,
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
                                    style = contentBodyStyle,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            DgenSmallPrimaryButton(
                                text = "Download Model",
                                primaryColor = primaryColor,
                                onClick = { viewModel.downloadLocalModel() },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            // Tier Display
            Text(
                text = "DEVICE TIER",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = viewModel.currentTier.uppercase(),
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = if (viewModel.isPrivileged) "Full access to all skills and tools"
                    else "Some skills are restricted to privileged OS builds",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }

            // LED Matrix Brightness (ethOS + dGEN1 only)
            if (viewModel.isPrivileged) {
                Spacer(Modifier.height(24.dp))
                GlowingDivider(primaryColor)
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "LED MATRIX",
                    color = primaryColor,
                    style = sectionTitleStyle,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = "MAX BRIGHTNESS: $ledMaxBrightness",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "Caps the RGB value sent to the 3×3 LED driver. LEDs get very dim below ~100. Default is 255 (full).",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = ledMaxBrightness.toFloat(),
                        onValueChange = {
                            val newVal = it.toInt()
                            if (newVal != lastBrightnessValue) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                lastBrightnessValue = newVal
                            }
                            viewModel.setLedMaxBrightness(newVal)
                        },
                        valueRange = 100f..255f,
                        colors = SliderDefaults.colors(
                            thumbColor = primaryColor,
                            activeTrackColor = primaryColor,
                            inactiveTrackColor = primaryColor.copy(alpha = 0.2f),
                        ),
                        modifier = Modifier.sliderGlow(primaryColor),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("100", style = contentBodyStyle, color = dgenWhite)
                        Text("255", style = contentBodyStyle, color = dgenWhite)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            // YOLO Mode
            Text(
                text = "YOLO MODE",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AUTO-APPROVE ALL TOOLS",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "Skip approval prompts for all tool and skill usage, including heartbeat and chat",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                DgenSquareSwitch(
                    checked = yoloMode,
                    onCheckedChange = { viewModel.setYoloMode(it) },
                    activeColor = primaryColor,
                )
            }

            // Safety Mode
            Spacer(Modifier.height(12.dp))
            Text(
                text = "SAFETY MODE",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ENABLE SAFETY CHECKS",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = if (yoloMode) "Disabled while YOLO mode is active"
                        else "Scans tool output for secret leaks, prompt injection, and dangerous patterns. Blocked actions will explain why.",
                        style = contentBodyStyle,
                        color = if (yoloMode) MaterialTheme.colorScheme.error else dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                DgenSquareSwitch(
                    checked = safetyEnabled && !yoloMode,
                    onCheckedChange = { viewModel.setSafetyEnabled(it) },
                    enabled = !yoloMode,
                    activeColor = primaryColor,
                )
            }

            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            // Notification Reply
            Text(
                text = "AUTO-REPLY TO NOTIFICATIONS",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ALLOW REPLYING TO MESSAGES",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "When enabled, the AI can reply to incoming notifications (Telegram, WhatsApp, etc.) on your behalf",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                DgenSquareSwitch(
                    checked = notificationReplyEnabled,
                    onCheckedChange = { viewModel.setNotificationReplyEnabled(it) },
                    activeColor = primaryColor,
                )
            }

            // Heartbeat Disable Switch
            val heartbeatDisabled = heartbeatIntervalMinutes <= 0
            var lastHeartbeatInterval by remember { mutableIntStateOf(if (heartbeatIntervalMinutes > 0) heartbeatIntervalMinutes else 15) }
            // Keep track of the last positive interval so we can restore it
            LaunchedEffect(heartbeatIntervalMinutes) {
                if (heartbeatIntervalMinutes > 0) lastHeartbeatInterval = heartbeatIntervalMinutes
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "HEARTBEAT SYSTEM",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "DISABLE HEARTBEAT",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "When enabled, the AI heartbeat system is completely turned off and will not run",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                DgenSquareSwitch(
                    checked = heartbeatDisabled,
                    onCheckedChange = { disabled ->
                        if (disabled) {
                            viewModel.setHeartbeatIntervalMinutes(-1)
                        } else {
                            viewModel.setHeartbeatIntervalMinutes(lastHeartbeatInterval)
                        }
                    },
                    activeColor = primaryColor,
                )
            }

            if (!heartbeatDisabled) {
                // Heartbeat on Notification
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "HEARTBEAT ON NOTIFICATION",
                    color = primaryColor,
                    style = sectionTitleStyle,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TRIGGER HEARTBEAT ON NEW NOTIFICATION",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "When enabled, the AI heartbeat runs whenever a new notification arrives so it can react to messages and alerts",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSquareSwitch(
                        checked = heartbeatOnNotificationEnabled,
                        onCheckedChange = { viewModel.setHeartbeatOnNotificationEnabled(it) },
                        activeColor = primaryColor,
                    )
                }

                // Heartbeat on XMTP Message — privileged only
                if (viewModel.isPrivileged) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "HEARTBEAT ON XMTP MESSAGE",
                        color = primaryColor,
                        style = sectionTitleStyle,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TRIGGER HEARTBEAT ON NEW XMTP MESSAGE",
                                style = contentTitleStyle,
                                color = primaryColor,
                            )
                            Text(
                                text = "When enabled, the AI heartbeat runs with the new messages as context whenever the background sync detects incoming XMTP messages",
                                style = contentBodyStyle,
                                color = dgenWhite,
                            )
                        }
                        Spacer(Modifier.width(rowControlSpacing))
                        DgenSquareSwitch(
                            checked = heartbeatOnXmtpMessageEnabled,
                            onCheckedChange = { viewModel.setHeartbeatOnXmtpMessageEnabled(it) },
                            activeColor = primaryColor,
                        )
                    }
                }

                // Executive Summary
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "EXECUTIVE SUMMARY",
                    color = primaryColor,
                    style = sectionTitleStyle,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SHOW ON LOCKSCREEN",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "Display a concise AI-generated summary on the lockscreen, updated with each heartbeat and voice command",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSquareSwitch(
                        checked = executiveSummaryEnabled,
                        onCheckedChange = { viewModel.setExecutiveSummaryEnabled(it) },
                        activeColor = primaryColor,
                    )
                }

                // Heartbeat Interval
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "HEARTBEAT INTERVAL",
                    color = primaryColor,
                    style = sectionTitleStyle,
                )
                Spacer(Modifier.height(8.dp))

                // Preset intervals: value in minutes -> display label
                val presetIntervals = remember {
                    listOf(
                        5 to "5M", 10 to "10M", 15 to "15M", 30 to "30M",
                        60 to "1H", 120 to "2H", 240 to "4H", 480 to "8H",
                        720 to "12H", 1440 to "24H",
                    )
                }
                val isCustomValue = heartbeatIntervalMinutes !in presetIntervals.map { it.first }
                var showCustomDialog by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    val displayText = remember(heartbeatIntervalMinutes) {
                        if (heartbeatIntervalMinutes >= 60 && heartbeatIntervalMinutes % 60 == 0) {
                            val hours = heartbeatIntervalMinutes / 60
                            "EVERY $hours HOUR${if (hours > 1) "S" else ""}"
                        } else if (heartbeatIntervalMinutes >= 60) {
                            val hours = heartbeatIntervalMinutes / 60
                            val mins = heartbeatIntervalMinutes % 60
                            "EVERY ${hours}H ${mins}M"
                        } else {
                            "EVERY $heartbeatIntervalMinutes MINUTES"
                        }
                    }
                    Text(
                        text = displayText,
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = if (viewModel.isPrivileged) "How often the OS triggers the AI heartbeat check"
                        else "How often the background service runs the AI heartbeat check",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                    Spacer(Modifier.height(12.dp))
                    val allChips = presetIntervals + listOf(
                        -1 to if (isCustomValue) "CUSTOM (${heartbeatIntervalMinutes}M)" else "CUSTOM"
                    )
                    allChips.chunked(5).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { (minutes, label) ->
                                val isSelected = if (minutes == -1) isCustomValue else heartbeatIntervalMinutes == minutes
                                Box(
                                    modifier = Modifier
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(6.dp),
                                        )
                                        .background(
                                            color = if (isSelected) primaryColor.copy(alpha = 0.15f) else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp),
                                        )
                                        .clickable {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            if (minutes == -1) showCustomDialog = true
                                            else viewModel.setHeartbeatIntervalMinutes(minutes)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        style = contentBodyStyle,
                                        color = if (isSelected) primaryColor else dgenWhite,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // Custom interval dialog
                if (showCustomDialog) {
                    var hoursText by remember { mutableStateOf((heartbeatIntervalMinutes / 60).toString()) }
                    var minutesText by remember { mutableStateOf((heartbeatIntervalMinutes % 60).toString()) }
                    AlertDialog(
                        onDismissRequest = { showCustomDialog = false },
                        containerColor = Color(0xFF1A1A1A),
                        title = {
                            Text(
                                "CUSTOM INTERVAL",
                                style = contentTitleStyle,
                                color = primaryColor,
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "Set a custom heartbeat interval (min 5 minutes, max 24 hours)",
                                    style = contentBodyStyle,
                                    color = dgenWhite,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = hoursText,
                                        onValueChange = { hoursText = it.filter { c -> c.isDigit() }.take(2) },
                                        label = { Text("HOURS", color = dgenWhite.copy(alpha = 0.6f)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = primaryColor,
                                            unfocusedTextColor = dgenWhite,
                                            focusedBorderColor = primaryColor,
                                            unfocusedBorderColor = primaryColor.copy(alpha = 0.3f),
                                            cursorColor = primaryColor,
                                        ),
                                        textStyle = TextStyle(
                                            fontFamily = SpaceMono,
                                            fontSize = body1_fontSize,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(":", color = primaryColor, style = contentTitleStyle)
                                    OutlinedTextField(
                                        value = minutesText,
                                        onValueChange = { minutesText = it.filter { c -> c.isDigit() }.take(2) },
                                        label = { Text("MINUTES", color = dgenWhite.copy(alpha = 0.6f)) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = primaryColor,
                                            unfocusedTextColor = dgenWhite,
                                            focusedBorderColor = primaryColor,
                                            unfocusedBorderColor = primaryColor.copy(alpha = 0.3f),
                                            cursorColor = primaryColor,
                                        ),
                                        textStyle = TextStyle(
                                            fontFamily = SpaceMono,
                                            fontSize = body1_fontSize,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val h = hoursText.toIntOrNull() ?: 0
                                val m = minutesText.toIntOrNull() ?: 0
                                val totalMinutes = (h * 60 + m).coerceIn(5, 1440)
                                viewModel.setHeartbeatIntervalMinutes(totalMinutes)
                                showCustomDialog = false
                            }) {
                                Text("SET", color = primaryColor, fontFamily = SpaceMono)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCustomDialog = false }) {
                                Text("CANCEL", color = dgenWhite, fontFamily = SpaceMono)
                            }
                        },
                    )
                }

                // Heartbeat Model Override
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "HEARTBEAT MODEL",
                    color = primaryColor,
                    style = sectionTitleStyle,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "USE SAME MODEL AS MAIN",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "When enabled, heartbeat uses the same AI provider and model. Disable pick a different provider and model for background tasks",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSquareSwitch(
                        checked = heartbeatUseSameModel,
                        onCheckedChange = { viewModel.setHeartbeatUseSameModel(it) },
                        activeColor = primaryColor,
                    )
                }

                if (!heartbeatUseSameModel) {
                    // Heartbeat Provider selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentSubScreen = SettingsSubScreen.HeartbeatProviderSelection }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PROVIDER",
                                style = contentBodyStyle.copy(color = primaryColor.copy(alpha = 0.7f)),
                                color = primaryColor.copy(alpha = 0.7f),
                            )
                            Text(
                                text = heartbeatProvider.displayName,
                                style = TextStyle(
                                    fontFamily = PitagonsSans,
                                    color = dgenWhite,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = body1_fontSize,
                                    lineHeight = body1_fontSize,
                                    shadow = GlowStyle.body(dgenWhite),
                                ),
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = primaryColor,
                        )
                    }

                    // Heartbeat Model selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { currentSubScreen = SettingsSubScreen.HeartbeatModelSelection }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MODEL",
                                style = contentBodyStyle.copy(color = primaryColor.copy(alpha = 0.7f)),
                                color = primaryColor.copy(alpha = 0.7f),
                            )
                            Text(
                                text = AnthropicModels.fromModelId(heartbeatModel)?.name ?: heartbeatModel,
                                style = TextStyle(
                                    fontFamily = PitagonsSans,
                                    color = dgenWhite,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = body1_fontSize,
                                    lineHeight = body1_fontSize,
                                    shadow = GlowStyle.body(dgenWhite),
                                ),
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = primaryColor,
                        )
                    }
                }

                // Heartbeat Logs
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "HEARTBEAT LOGS",
                    color = primaryColor,
                    style = sectionTitleStyle,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "RUN HISTORY",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "View past heartbeat runs, tool calls, and responses",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSmallPrimaryButton(
                        text = "Open",
                        primaryColor = primaryColor,
                        onClick = onNavigateToHeartbeatLogs,
                    )
                }
            }

            // Telegram Bot
            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            Text(
                text = "TELEGRAM BOT",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))

            val telegramConfigured = telegramBotEnabled && telegramOwnerChatId != 0L
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (telegramConfigured) {
                        Text(
                            text = "TELEGRAM BOT CONNECTED",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "The AI can send you proactive messages via Telegram",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    } else {
                        Text(
                            text = "NOT CONFIGURED",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "Set up a Telegram bot so the AI can reach you via Telegram",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                }
                Spacer(Modifier.width(rowControlSpacing))
                if (telegramConfigured) {
                    DgenSmallPrimaryButton(
                        text = "Disconnect",
                        primaryColor = primaryColor,
                        onClick = { viewModel.clearTelegramSetup() },
                    )
                } else {
                    DgenSmallPrimaryButton(
                        text = "Set up",
                        primaryColor = primaryColor,
                        onClick = { showTelegramOnboarding = true },
                    )
                }
            }

            if (showTelegramOnboarding) {
                TelegramOnboardingDialog(
                    onComplete = { token, ownerChatId ->
                        viewModel.completeTelegramSetup(token, ownerChatId)
                        showTelegramOnboarding = false
                    },
                    onDismiss = { showTelegramOnboarding = false },
                )
            }

            // Google Workspace
            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            Text(
                text = "GOOGLE WORKSPACE",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))

            val googleConnected = googleRefreshToken.isNotBlank()
            var googleMissingFields by remember { mutableStateOf(false) }
            var googleSetupGuideExpanded by remember { mutableStateOf(false) }

            if (!googleConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { googleSetupGuideExpanded = !googleSetupGuideExpanded }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (googleSetupGuideExpanded) "▼ " else "▶ ",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "SETUP GUIDE",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                }

                if (googleSetupGuideExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, primaryColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = "1. CREATE A GOOGLE CLOUD PROJECT",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Go to console.cloud.google.com and create a new project (or select an existing one).",
                            style = contentBodyStyle,
                            color = dgenWhite.copy(alpha = 0.8f),
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "2. ENABLE 4 APIS",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Go to APIs & Services > Library and enable:\n" +
                                "• Gmail API\n" +
                                "• Google Drive API\n" +
                                "• Google Calendar API\n" +
                                "• Google Sheets API",
                            style = contentBodyStyle,
                            color = dgenWhite.copy(alpha = 0.8f),
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "3. CONFIGURE OAUTH CONSENT SCREEN",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Go to APIs & Services > OAuth consent screen:\n" +
                                "• User type: External\n" +
                                "• Fill in app name (anything)\n" +
                                "• Add your Google email as a test user under Audience\n" +
                                "• Save (no need for verification for personal use)",
                            style = contentBodyStyle,
                            color = dgenWhite.copy(alpha = 0.8f),
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "4. CREATE OAUTH CREDENTIALS",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Go to APIs & Services > Credentials:\n" +
                                "• Create Credentials > OAuth client ID\n" +
                                "• Application type: Desktop app\n" +
                                "• Copy the Client ID and Client Secret below",
                            style = contentBodyStyle,
                            color = dgenWhite.copy(alpha = 0.8f),
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "5. CONNECT",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Paste the credentials below and tap Connect. A browser window will open for Google sign-in. If you see \"Google hasn't verified this app\", click Advanced > Continue.",
                            style = contentBodyStyle,
                            color = dgenWhite.copy(alpha = 0.8f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                DgenCursorTextfield(
                    modifier = Modifier.fillMaxWidth(),
                    label = "OAuth Client ID",
                    value = googleClientId,
                    onValueChange = {
                        viewModel.setGoogleOauthClientId(it)
                        googleMissingFields = false
                    },
                    primaryColor = primaryColor,
                )
                Spacer(Modifier.height(8.dp))
                DgenCursorTextfield(
                    modifier = Modifier.fillMaxWidth(),
                    label = "OAuth Client Secret",
                    value = googleClientSecret,
                    onValueChange = {
                        viewModel.setGoogleOauthClientSecret(it)
                        googleMissingFields = false
                    },
                    primaryColor = primaryColor,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (googleMissingFields) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enter Client ID and Client Secret first",
                        style = contentBodyStyle,
                        color = Color(0xFFFF6B6B),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (googleConnected) {
                        Text(
                            text = "GOOGLE ACCOUNT CONNECTED",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "Gmail, Drive, Calendar, and Sheets are available",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    } else {
                        Text(
                            text = "NOT CONNECTED",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "Connect your Google account to enable Gmail, Drive, Calendar, and Sheets",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                }
                Spacer(Modifier.width(rowControlSpacing))
                if (googleConnected) {
                    DgenSmallPrimaryButton(
                        text = "Disconnect",
                        primaryColor = primaryColor,
                        onClick = { viewModel.disconnectGoogle() },
                    )
                } else {
                    DgenSmallPrimaryButton(
                        text = "Connect",
                        primaryColor = primaryColor,
                        onClick = {
                            if (googleClientId.isNotBlank() && googleClientSecret.isNotBlank()) {
                                viewModel.startGoogleOAuthFlow(context)
                            } else {
                                googleMissingFields = true
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
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
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            // Extensions
            ExtensionManagementSection(
                extensions = extensions,
                isScanning = isExtensionScanning,
                onRescan = { viewModel.rescanExtensions() },
            )

            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            // ClawHub
            Text(
                text = "CLAWHUB",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SKILL REGISTRY",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "Browse, install, and manage skills from ClawHub",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                DgenSmallPrimaryButton(
                    text = "Open",
                    primaryColor = primaryColor,
                    onClick = onNavigateToClawHub,
                )
            }

            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            // Skills
            SkillManagementSection(
                skills = viewModel.registeredSkills,
                enabledSkills = enabledSkills,
                onToggleSkill = { skillId, enabled -> viewModel.toggleSkill(skillId, enabled) },
                onSkillClick = viewModel::inspectSkill,
            )

            // Hidden Agent Display Test (ethOS only)
            if (viewModel.isPrivileged) {
                Spacer(Modifier.height(24.dp))
                GlowingDivider(primaryColor)
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "AGENT DISPLAY",
                    color = primaryColor,
                    style = sectionTitleStyle,
                )
                Spacer(Modifier.height(8.dp))

                // AI toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ALLOW AI TO USE AGENT DISPLAY",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "Let the AI create a virtual display, launch apps, take screenshots, and interact with UI elements",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSquareSwitch(
                        checked = enabledSkills.contains("agent_display"),
                        onCheckedChange = { enabled ->
                            viewModel.toggleSkill("agent_display", enabled)
                        },
                        activeColor = primaryColor,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Test screen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "VIRTUAL DISPLAY TEST",
                            style = contentTitleStyle,
                            color = primaryColor,
                        )
                        Text(
                            text = "Test the AgentDisplay service (create display, launch apps, capture frames)",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSmallPrimaryButton(
                        text = "Open",
                        primaryColor = primaryColor,
                        onClick = onNavigateToAgentDisplayTest,
                    )
                }
            }
        }

        SettingsSubScreen.ModelSelection -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(viewModel.availableModels) { model ->
                    SelectionRow(
                        text = model.name,
                        subtitle = model.modelId,
                        isSelected = model.modelId == selectedModel,
                        primaryColor = primaryColor,
                        onClick = {
                            viewModel.setSelectedModel(model.modelId)
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                }
            }
        }

        SettingsSubScreen.ProviderSelection -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(providerChoices) { provider ->
                    SelectionRow(
                        text = provider.displayName,
                        isSelected = provider == selectedProvider,
                        primaryColor = primaryColor,
                        onClick = {
                            viewModel.setSelectedProvider(provider)
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                }
            }
        }

        SettingsSubScreen.HeartbeatModelSelection -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(viewModel.availableHeartbeatModels) { model ->
                    SelectionRow(
                        text = model.name,
                        subtitle = model.modelId,
                        isSelected = model.modelId == heartbeatModel,
                        primaryColor = primaryColor,
                        onClick = {
                            viewModel.setHeartbeatModel(model.modelId)
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                }
            }
        }

        SettingsSubScreen.HeartbeatProviderSelection -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(providerChoices) { provider ->
                    val configured = viewModel.isProviderConfigured(provider)
                    SelectionRow(
                        text = provider.displayName,
                        isSelected = provider == heartbeatProvider,
                        primaryColor = primaryColor,
                        enabled = configured,
                        disabledSubtitle = if (!configured) "Set up API key in AI Provider settings" else null,
                        onClick = {
                            viewModel.setHeartbeatProvider(provider)
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                }
            }
        }
        }
        }
    }
}

@Composable
private fun SkillInspectionDialog(
    inspectedSkill: InspectedSkillInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = inspectedSkill.name,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = inspectedSkill.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = inspectedSkill.content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun SelectionRow(
    text: String,
    isSelected: Boolean,
    primaryColor: Color,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    disabledSubtitle: String? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = TextStyle(
                    fontFamily = SpaceMono,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor.copy(alpha = alpha),
                    shadow = if (enabled) GlowStyle.title(primaryColor) else null,
                ),
            )
            if (!enabled && disabledSubtitle != null) {
                Text(
                    text = disabledSubtitle,
                    style = TextStyle(
                        fontFamily = PitagonsSans,
                        fontSize = label_fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF6B6B),
                        shadow = GlowStyle.subtitle(Color(0xFFFF6B6B)),
                    ),
                )
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = PitagonsSans,
                        fontSize = label_fontSize,
                        fontWeight = FontWeight.SemiBold,
                        color = dgenWhite.copy(alpha = alpha),
                        shadow = if (enabled) GlowStyle.subtitle(dgenWhite) else null,
                    ),
                )
            }
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(primaryColor, shape = CircleShape)
            )
        }
    }
}

@Composable
private fun GlowingDivider(primaryColor: Color) {
    HorizontalDivider(
        color = primaryColor.copy(alpha = 0.4f),
        modifier = Modifier.drawBehind {
            drawContext.canvas.nativeCanvas.drawLine(
                0f, size.height / 2, size.width, size.height / 2,
                android.graphics.Paint().apply {
                    color = primaryColor.copy(alpha = 0.5f).toArgb()
                    strokeWidth = 2.dp.toPx()
                    maskFilter = android.graphics.BlurMaskFilter(
                        8.dp.toPx(),
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                },
            )
        },
    )
}

private fun Modifier.sliderGlow(primaryColor: Color) = drawBehind {
    drawContext.canvas.nativeCanvas.drawLine(
        0f, size.height / 2, size.width, size.height / 2,
        android.graphics.Paint().apply {
            color = primaryColor.copy(alpha = 0.4f).toArgb()
            strokeWidth = 6.dp.toPx()
            maskFilter = android.graphics.BlurMaskFilter(
                12.dp.toPx(),
                android.graphics.BlurMaskFilter.Blur.NORMAL,
            )
        },
    )
}

@Composable
private fun AgentWalletSection(
    primaryColor: Color,
    sectionTitleStyle: TextStyle,
    contentTitleStyle: TextStyle,
    contentBodyStyle: TextStyle,
    onNavigateToTxHistory: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as org.ethereumphone.andyclaw.NodeApp
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var agentAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sdk = org.ethereumphone.subwalletsdk.SubWalletSDK(
                    context = context,
                    web3jInstance = org.web3j.protocol.Web3j.build(
                        org.web3j.protocol.http.HttpService(
                            "https://eth-mainnet.g.alchemy.com/v2/${org.ethereumphone.andyclaw.BuildConfig.ALCHEMY_API}"
                        )
                    ),
                    bundlerRPCUrl = "https://api.pimlico.io/v2/1/rpc?apikey=${org.ethereumphone.andyclaw.BuildConfig.BUNDLER_API}",
                )
                agentAddress = sdk.getAddress()
            } catch (_: Exception) {
                // SubWallet not available
            }
        }
    }

    Text(
        text = "AGENT WALLET",
        color = primaryColor,
        style = sectionTitleStyle,
    )
    Spacer(Modifier.height(8.dp))

    if (agentAddress != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ADDRESS",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Spacer(Modifier.height(2.dp))
                SelectionContainer {
                    Text(
                        text = agentAddress!!,
                        style = contentBodyStyle.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        color = dgenWhite,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    uriHandler.openUri("https://blockscan.com/address/${agentAddress}")
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VIEW ON BLOCKSCAN",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = "Open agent wallet on block explorer",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }
            Spacer(Modifier.width(20.dp))
            Text(
                text = ">",
                style = sectionTitleStyle,
                color = primaryColor,
            )
        }
    } else {
        Text(
            text = "Agent wallet not available",
            style = contentBodyStyle,
            color = dgenWhite.copy(alpha = 0.5f),
        )
    }

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateToTxHistory)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "TRANSACTION HISTORY",
                style = contentTitleStyle,
                color = primaryColor,
            )
            Text(
                text = "View all agent wallet transactions",
                style = contentBodyStyle,
                color = dgenWhite,
            )
        }
        Spacer(Modifier.width(20.dp))
        DgenSmallPrimaryButton(
            text = "Open",
            primaryColor = primaryColor,
            onClick = onNavigateToTxHistory,
        )
    }
}

private enum class SettingsSubScreen {
    Main,
    ModelSelection,
    ProviderSelection,
    HeartbeatModelSelection,
    HeartbeatProviderSelection,
}
