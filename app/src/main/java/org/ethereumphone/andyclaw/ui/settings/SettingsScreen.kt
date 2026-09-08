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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay
import androidx.compose.foundation.combinedClickable
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    onNavigateToAgentWalletSend: () -> Unit = {},
    initialSubScreen: SettingsSubScreen = SettingsSubScreen.Main,
    viewModel: SettingsViewModel = viewModel(),
) {
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val tinfoilApiKey by viewModel.tinfoilApiKey.collectAsState()
    val openRouterApiKey by viewModel.apiKey.collectAsState()
    val openaiApiKey by viewModel.openaiApiKey.collectAsState()
    val veniceApiKey by viewModel.veniceApiKey.collectAsState()
    val claudeOauthRefreshToken by viewModel.claudeOauthRefreshToken.collectAsState()
    val chatgptOauthRefreshToken by viewModel.chatgptOauthRefreshToken.collectAsState()
    val customBaseUrl  by viewModel.customBaseUrl.collectAsState()
    val customApiKey   by viewModel.customApiKey.collectAsState()
    val customModelId  by viewModel.customModelId.collectAsState()
    val customAvailableModels   by viewModel.customAvailableModels.collectAsState()
    val customModelsFetching    by viewModel.customModelsFetching.collectAsState()
    val customModelsFetchError  by viewModel.customModelsFetchError.collectAsState()
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
    val syncProviderToAll by viewModel.syncProviderToAll.collectAsState()
    val heartbeatUseSameModel by viewModel.heartbeatUseSameModel.collectAsState()
    val heartbeatProvider by viewModel.heartbeatProvider.collectAsState()
    val heartbeatModel by viewModel.heartbeatModel.collectAsState()
    val memoryCount by viewModel.memoryCount.collectAsState()
    val autoStoreEnabled by viewModel.autoStoreEnabled.collectAsState()
    val smartExtractionEnabled by viewModel.smartExtractionEnabled.collectAsState()
    val aiRerankingEnabled by viewModel.aiRerankingEnabled.collectAsState()
    val isReindexing by viewModel.isReindexing.collectAsState()
    val extensions by viewModel.extensions.collectAsState()
    val isExtensionScanning by viewModel.isExtensionScanning.collectAsState()
    val persistedEnabledSkills by viewModel.enabledSkills.collectAsState()
    val enabledSkills = if (yoloMode) {
        viewModel.registeredSkills.map { it.id }.toSet()
    } else {
        persistedEnabledSkills
    }
    val paymasterBalance by viewModel.paymasterBalance.collectAsState()
    val telegramBotEnabled by viewModel.telegramBotEnabled.collectAsState()
    val telegramOwnerChatId by viewModel.telegramOwnerChatId.collectAsState()
    val ledMaxBrightness by viewModel.ledMaxBrightness.collectAsState()
    val googleRefreshToken by viewModel.googleOauthRefreshToken.collectAsState()
    val googleClientId by viewModel.googleOauthClientId.collectAsState()
    val googleClientSecret by viewModel.googleOauthClientSecret.collectAsState()
    val inspectedSkill by viewModel.inspectedSkill.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val pendingImportInfo by viewModel.pendingImportInfo.collectAsState()
    var showTelegramOnboarding by remember { mutableStateOf(false) }
    var currentSubScreen by remember { mutableStateOf(initialSubScreen) }
    var lastBrightnessValue by remember { mutableStateOf(ledMaxBrightness) }

    inspectedSkill?.let { skill ->
        SkillInspectionDialog(
            inspectedSkill = skill,
            onDismiss = viewModel::dismissSkillInspection,
        )
    }

    val context = LocalContext.current
    val view = LocalView.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.onImportFilePicked(it, context) }
    }

    val ggufImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.importGgufFromUri(it) }
    }

    LaunchedEffect(Unit) {
        SystemColorManager.refresh(context)
    }

    val primaryColor = SystemColorManager.primaryColor
    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)
    val rowControlSpacing = 20.dp

    val providerChoices = if (viewModel.isPrivileged) {
        // OPENAI_OAUTH (ChatGPT via Codex Responses) is built but hidden from
        // the picker until the live round-trip is validated. The enum entry,
        // client, token manager, model entries, and Settings UI all stay in
        // the tree; re-add OPENAI_OAUTH here to re-enable. See chatgpt-oauth
        // memory entry for the v1 limitations (no tools, no 401-retry).
        listOf(LlmProvider.ETHOS_PREMIUM, LlmProvider.OPEN_ROUTER, LlmProvider.CLAUDE_OAUTH, LlmProvider.OPENAI, LlmProvider.VENICE, LlmProvider.TINFOIL, LlmProvider.LOCAL, LlmProvider.CUSTOM)
    } else {
        listOf(LlmProvider.OPEN_ROUTER, LlmProvider.CLAUDE_OAUTH, LlmProvider.OPENAI, LlmProvider.VENICE, LlmProvider.TINFOIL, LlmProvider.LOCAL, LlmProvider.CUSTOM)
    }

    DgenBackNavigationBackground(
        title = when (currentSubScreen) {
            SettingsSubScreen.Main -> "Settings"
            SettingsSubScreen.ModelSelection -> "Select Model"
            SettingsSubScreen.ProviderSelection -> "Select Provider"
            SettingsSubScreen.HeartbeatModelSelection -> "Heartbeat Model"
            SettingsSubScreen.HeartbeatProviderSelection -> "Heartbeat Provider"
            SettingsSubScreen.CompactionModelSelection -> "Compaction Model"
            SettingsSubScreen.CompactionProviderSelection -> "Compaction Provider"
            SettingsSubScreen.RoutingModeSelection -> "Routing Mode"
            SettingsSubScreen.RoutingPresetSelection -> "Always-On Skills Preset"
            SettingsSubScreen.RoutingPresetEditor -> "Edit Preset"
            SettingsSubScreen.RoutingProviderSelection -> "Routing Provider"
            SettingsSubScreen.RoutingModelSelection -> "Routing Model"
            SettingsSubScreen.BudgetPresetSelection -> "Select Budget Preset"
            SettingsSubScreen.BudgetPresetEditor -> "Edit Budget Preset"
            SettingsSubScreen.ModelRoutingLightSelection -> "Easy Task Model"
            SettingsSubScreen.ModelRoutingStandardSelection -> "Medium Task Model"
            SettingsSubScreen.ModelRoutingPowerfulSelection -> "Hard Task Model"
            SettingsSubScreen.LocalLlmSettings -> "Local LLM"
        },
        primaryColor = primaryColor,
        onNavigateBack = {
            when {
                currentSubScreen == SettingsSubScreen.Main -> onNavigateBack()
                // If we were launched directly into a sub-screen, back exits entirely
                initialSubScreen != SettingsSubScreen.Main -> onNavigateBack()
                else -> { currentSubScreen = SettingsSubScreen.Main }
            }
        },
    ) {
        val mainScrollState = rememberScrollState()
        Crossfade(targetState = currentSubScreen, label = "settings_crossfade") { screen ->
        when (screen) {
        SettingsSubScreen.Main ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(mainScrollState)
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
                    onNavigateToSend = onNavigateToAgentWalletSend,
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
                LlmProvider.OPENAI_OAUTH -> {
                    var editingChatGptToken by remember { mutableStateOf(chatgptOauthRefreshToken) }
                    DgenCursorTextfield(
                        value = editingChatGptToken,
                        onValueChange = {
                            editingChatGptToken = it
                            viewModel.setChatGptOauthRefreshToken(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "ChatGPT Refresh Token",
                        placeholder = { Text("eyJhbGciOiJSUzI1Ni...", color = dgenWhite.copy(alpha = 0.3f), style = MaterialTheme.typography.bodySmall.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                        visualTransformation = PasswordVisualTransformation(),
                        primaryColor = primaryColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Install the Codex CLI (`npm i -g @openai/codex`), run `codex login`, then paste the `tokens.refresh_token` from `~/.codex/auth.json`. Requires a ChatGPT Plus / Pro / Business subscription. Codex-supported models only; tools not yet wired.",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                LlmProvider.CUSTOM -> {
                    var editingUrl by remember { mutableStateOf(customBaseUrl) }
                    var editingKey by remember { mutableStateOf(customApiKey) }

                    // Debounced auto-fetch: 500 ms after the user stops typing
                    // the URL we hit GET {base}/v1/models so the MODEL picker
                    // at the top of Settings is populated by the time the user
                    // looks at it.
                    LaunchedEffect(customBaseUrl, customApiKey) {
                        if (customBaseUrl.isNotBlank()) {
                            delay(500)
                            viewModel.fetchCustomModels()
                        }
                    }

                    DgenCursorTextfield(
                        value = editingUrl,
                        onValueChange = {
                            editingUrl = it
                            viewModel.setCustomBaseUrl(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "Base URL",
                        placeholder = { Text("http://192.168.1.42:11434/v1/chat/completions", color = dgenWhite.copy(alpha = 0.3f), style = MaterialTheme.typography.bodySmall.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                        primaryColor = primaryColor,
                    )
                    Spacer(Modifier.height(8.dp))
                    DgenCursorTextfield(
                        value = editingKey,
                        onValueChange = {
                            editingKey = it
                            viewModel.setCustomApiKey(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = "API Key (optional)",
                        placeholder = { Text("leave empty if your server doesn't auth", color = dgenWhite.copy(alpha = 0.3f), style = MaterialTheme.typography.bodySmall.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                        visualTransformation = PasswordVisualTransformation(),
                        primaryColor = primaryColor,
                    )

                    // Connection status — auto-fetched /v1/models result. Model
                    // picking happens via the MODEL row at the top of Settings
                    // (it reads the same fetched list + lets you type any id
                    // directly via the search bar).
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = when {
                            customModelsFetching -> "Fetching /v1/models…"
                            customModelsFetchError != null -> "Models fetch failed: $customModelsFetchError"
                            customAvailableModels.isNotEmpty() -> "${customAvailableModels.size} models available on server"
                            customBaseUrl.isBlank() -> "Enter a Base URL to discover models"
                            else -> "No models reported by server"
                        }
                        Text(label, style = contentBodyStyle, color = dgenWhite, modifier = Modifier.weight(1f))
                        DgenSmallPrimaryButton(
                            text = "Refresh",
                            primaryColor = primaryColor,
                            onClick = { viewModel.fetchCustomModels() },
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Connect to any OpenAI-compatible HTTP endpoint you host (Ollama, LM Studio, vLLM, llama.cpp server, LocalAI). Phone and server must share a network. Plain HTTP is allowed for LAN servers. Pick a model from the MODEL row at the top of Settings — the picker lists what the server returns from /v1/models, and the search bar there lets you type any id directly (useful for servers that don't expose the list).",
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

                        Spacer(Modifier.height(12.dp))
                        DgenSmallPrimaryButton(
                            text = "Configure local LLM →",
                            primaryColor = primaryColor,
                            onClick = { currentSubScreen = SettingsSubScreen.LocalLlmSettings },
                        )
                    }
                }
            }

            // Sync provider to heartbeat & compaction
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "APPLY TO HEARTBEAT & COMPACTION",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "When enabled, changing the AI provider also updates heartbeat and compaction to match",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                DgenSquareSwitch(
                    checked = syncProviderToAll,
                    onCheckedChange = { viewModel.setSyncProviderToAll(it) },
                    activeColor = primaryColor,
                )
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

            // Heartbeat Enable Switch
            val heartbeatEnabled = heartbeatIntervalMinutes > 0
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
                        text = "ENABLE HEARTBEAT",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "When enabled, the AI heartbeat system runs periodically in the background",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                DgenSquareSwitch(
                    checked = heartbeatEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            viewModel.setHeartbeatIntervalMinutes(lastHeartbeatInterval)
                        } else {
                            viewModel.setHeartbeatIntervalMinutes(-1)
                        }
                    },
                    activeColor = primaryColor,
                )
            }

            if (heartbeatEnabled) {
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
                smartExtractionEnabled = smartExtractionEnabled,
                aiRerankingEnabled = aiRerankingEnabled,
                isReindexing = isReindexing,
                onAutoStoreToggle = { viewModel.setAutoStoreEnabled(it) },
                onSmartExtractionToggle = { viewModel.setSmartExtractionEnabled(it) },
                onAiRerankingToggle = { viewModel.setAiRerankingEnabled(it) },
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

            // Backup & Restore
            val showExportPasswordDialog by viewModel.showExportPasswordDialog.collectAsState()
            val showImportPasswordDialog by viewModel.showImportPasswordDialog.collectAsState()
            val backupError by viewModel.backupError.collectAsState()
            BackupSettingsSection(
                isExporting = isExporting,
                isImporting = isImporting,
                backupInfo = pendingImportInfo,
                showExportPasswordDialog = showExportPasswordDialog,
                showImportPasswordDialog = showImportPasswordDialog,
                backupError = backupError,
                onExport = { viewModel.requestExport() },
                onExportWithPassword = { password -> viewModel.createBackup(context, password) },
                onDismissExportPassword = { viewModel.dismissExportPasswordDialog() },
                onImport = { importLauncher.launch(arrayOf("*/*")) },
                onImportPasswordEntered = { password -> viewModel.onImportPasswordEntered(context, password) },
                onDismissImportPassword = { viewModel.dismissImportPasswordDialog() },
                onConfirmImport = { viewModel.confirmImport(context) },
                onDismissImportDialog = { viewModel.dismissImportDialog() },
                onDismissError = { viewModel.dismissBackupError() },
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

            // Always-On Tools Preset
            val selectedRoutingPresetId by viewModel.selectedRoutingPresetId.collectAsState()
            val routingPresets by viewModel.routingPresets.collectAsState()
            val selectedPresetName = routingPresets
                .firstOrNull { it.id == selectedRoutingPresetId }?.name ?: "Unknown"
            AlwaysOnToolsSection(
                selectedPresetName = selectedPresetName,
                onNavigateToPresetSelection = { currentSubScreen = SettingsSubScreen.RoutingPresetSelection },
                onNavigateToPresetEditor = { currentSubScreen = SettingsSubScreen.RoutingPresetEditor },
            )

            Spacer(Modifier.height(24.dp))
            GlowingDivider(primaryColor)
            Spacer(Modifier.height(16.dp))

            // Budget Mode
            val budgetModeEnabled by viewModel.budgetModeEnabled.collectAsState()
            val selectedBudgetPresetId by viewModel.selectedBudgetPresetId.collectAsState()
            val budgetPresets by viewModel.budgetPresets.collectAsState()
            val selectedBudgetPresetName = budgetPresets
                .firstOrNull { it.id == selectedBudgetPresetId }?.name ?: "Unknown"
            BudgetModeSection(
                enabled = budgetModeEnabled,
                onEnabledChange = { viewModel.setBudgetModeEnabled(it) },
                selectedPresetName = selectedBudgetPresetName,
                onNavigateToPresetSelection = { currentSubScreen = SettingsSubScreen.BudgetPresetSelection },
                onNavigateToPresetEditor = { currentSubScreen = SettingsSubScreen.BudgetPresetEditor },
            )

            // ── Context Compaction ──
            val compactionCfg by viewModel.compactionConfig.collectAsState()
            val compactionUseSameModel by viewModel.compactionUseSameModel.collectAsState()
            val compactionProvider by viewModel.compactionProvider.collectAsState()
            val compactionModel by viewModel.compactionModel.collectAsState()

            Spacer(Modifier.height(12.dp))
            Text(
                text = "CONTEXT COMPACTION",
                color = primaryColor,
                style = sectionTitleStyle,
            )
            Spacer(Modifier.height(8.dp))

            // Enable toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ENABLE AUTO-COMPACTION", style = contentTitleStyle, color = primaryColor)
                    Text("Automatically summarize older messages when context window fills up", style = contentBodyStyle, color = dgenWhite)
                }
                Spacer(Modifier.width(rowControlSpacing))
                DgenSquareSwitch(
                    checked = compactionCfg.enabled,
                    onCheckedChange = { viewModel.updateCompactionConfig { it.copy(enabled = !it.enabled) } },
                    activeColor = primaryColor,
                )
            }

            if (compactionCfg.enabled) {
                // Microcompact toggle
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MICROCOMPACT (PROGRAMMATIC)", style = contentTitleStyle, color = primaryColor)
                        Text("Truncate old assistant messages and collapse whitespace — no LLM cost", style = contentBodyStyle, color = dgenWhite)
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSquareSwitch(
                        checked = compactionCfg.microcompactEnabled,
                        onCheckedChange = { viewModel.updateCompactionConfig { it.copy(microcompactEnabled = !it.microcompactEnabled) } },
                        activeColor = primaryColor,
                    )
                }

                // LLM Summary toggle
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("LLM SUMMARY", style = contentTitleStyle, color = primaryColor)
                        Text("Use an LLM to generate a structured summary of older messages", style = contentBodyStyle, color = dgenWhite)
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSquareSwitch(
                        checked = compactionCfg.llmSummaryEnabled,
                        onCheckedChange = { viewModel.updateCompactionConfig { it.copy(llmSummaryEnabled = !it.llmSummaryEnabled) } },
                        activeColor = primaryColor,
                    )
                }

                // Model picker (only when LLM summary is on)
                if (compactionCfg.llmSummaryEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("USE SAME MODEL AS MAIN", style = contentTitleStyle, color = primaryColor)
                            Text("Pick a fast, cheap model for summarization", style = contentBodyStyle, color = dgenWhite)
                        }
                        Spacer(Modifier.width(rowControlSpacing))
                        DgenSquareSwitch(
                            checked = compactionUseSameModel,
                            onCheckedChange = { viewModel.setCompactionUseSameModel(it) },
                            activeColor = primaryColor,
                        )
                    }

                    if (!compactionUseSameModel) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { currentSubScreen = SettingsSubScreen.CompactionProviderSelection }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("PROVIDER", style = contentBodyStyle.copy(color = primaryColor.copy(alpha = 0.7f)), color = primaryColor.copy(alpha = 0.7f))
                                Text(compactionProvider.displayName, style = TextStyle(fontFamily = PitagonsSans, color = dgenWhite, fontWeight = FontWeight.SemiBold, fontSize = body1_fontSize, lineHeight = body1_fontSize, shadow = GlowStyle.body(dgenWhite)))
                            }
                            Icon(Icons.Default.ArrowDropDown, null, tint = primaryColor)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { currentSubScreen = SettingsSubScreen.CompactionModelSelection }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("MODEL", style = contentBodyStyle.copy(color = primaryColor.copy(alpha = 0.7f)), color = primaryColor.copy(alpha = 0.7f))
                                Text(AnthropicModels.fromModelId(compactionModel)?.name ?: compactionModel, style = TextStyle(fontFamily = PitagonsSans, color = dgenWhite, fontWeight = FontWeight.SemiBold, fontSize = body1_fontSize, lineHeight = body1_fontSize, shadow = GlowStyle.body(dgenWhite)))
                            }
                            Icon(Icons.Default.ArrowDropDown, null, tint = primaryColor)
                        }
                    }
                }

                // Trigger threshold slider
                Spacer(Modifier.height(8.dp))
                Text("TRIGGER THRESHOLD: ${(compactionCfg.threshold * 100).toInt()}%", style = contentTitleStyle, color = primaryColor)
                Text("Compact when context window usage exceeds this percentage", style = contentBodyStyle, color = dgenWhite)
                var thresholdSlider by remember(compactionCfg.threshold) { mutableStateOf(compactionCfg.threshold) }
                androidx.compose.material3.Slider(
                    value = thresholdSlider,
                    onValueChange = { raw ->
                        thresholdSlider = (kotlin.math.round(raw * 20) / 20f).coerceIn(0.50f, 0.95f)
                    },
                    onValueChangeFinished = {
                        viewModel.updateCompactionConfig { it.copy(threshold = thresholdSlider) }
                    },
                    valueRange = 0.50f..0.95f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = primaryColor,
                        activeTrackColor = primaryColor,
                        inactiveTrackColor = primaryColor.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Turn interval slider
                Spacer(Modifier.height(4.dp))
                val intervalLabel = if (compactionCfg.interval == 0) "TURN INTERVAL: OFF" else "TURN INTERVAL: EVERY ${compactionCfg.interval} TURNS"
                Text(intervalLabel, style = contentTitleStyle, color = primaryColor)
                Text("Also compact every N turns regardless of token usage (0 = off)", style = contentBodyStyle, color = dgenWhite)
                var intervalSlider by remember(compactionCfg.interval) { mutableStateOf(compactionCfg.interval.toFloat()) }
                androidx.compose.material3.Slider(
                    value = intervalSlider,
                    onValueChange = { raw ->
                        intervalSlider = (kotlin.math.round(raw / 5f) * 5f).coerceIn(0f, 30f)
                    },
                    onValueChangeFinished = {
                        viewModel.updateCompactionConfig { it.copy(interval = intervalSlider.toInt()) }
                    },
                    valueRange = 0f..30f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = primaryColor,
                        activeTrackColor = primaryColor,
                        inactiveTrackColor = primaryColor.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Keep recent slider
                Spacer(Modifier.height(4.dp))
                Text("KEEP RECENT: ${compactionCfg.keepRecent} MESSAGES", style = contentTitleStyle, color = primaryColor)
                Text("Number of recent messages kept verbatim after compaction", style = contentBodyStyle, color = dgenWhite)
                var keepRecentSlider by remember(compactionCfg.keepRecent) { mutableStateOf(compactionCfg.keepRecent.toFloat()) }
                androidx.compose.material3.Slider(
                    value = keepRecentSlider,
                    onValueChange = { raw ->
                        keepRecentSlider = (kotlin.math.round(raw / 2f) * 2f).coerceIn(2f, 20f)
                    },
                    onValueChangeFinished = {
                        viewModel.updateCompactionConfig { it.copy(keepRecent = keepRecentSlider.toInt()) }
                    },
                    valueRange = 2f..20f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = primaryColor,
                        activeTrackColor = primaryColor,
                        inactiveTrackColor = primaryColor.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Overlap slider
                Spacer(Modifier.height(4.dp))
                Text("OVERLAP: ${compactionCfg.overlap} MESSAGES", style = contentTitleStyle, color = primaryColor)
                Text("Messages re-included for continuity between summaries", style = contentBodyStyle, color = dgenWhite)
                var overlapSlider by remember(compactionCfg.overlap) { mutableStateOf(compactionCfg.overlap.toFloat()) }
                androidx.compose.material3.Slider(
                    value = overlapSlider,
                    onValueChange = { raw ->
                        overlapSlider = kotlin.math.round(raw).coerceIn(0f, 5f)
                    },
                    onValueChangeFinished = {
                        viewModel.updateCompactionConfig { it.copy(overlap = overlapSlider.toInt()) }
                    },
                    valueRange = 0f..5f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = primaryColor,
                        activeTrackColor = primaryColor,
                        inactiveTrackColor = primaryColor.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
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
            val searchQuery by viewModel.modelSearchQuery.collectAsState()
            LaunchedEffect(Unit) {
                viewModel.refreshOpenRouterModelsIfNeeded()
                // For CUSTOM provider, also auto-fetch /v1/models so the
                // picker is populated by the time the user looks at it.
                if (selectedProvider == LlmProvider.CUSTOM) viewModel.fetchCustomModels()
            }
            // Re-key on customAvailableModels too so the list rebuilds when
            // /v1/models lands asynchronously.
            val displayModels = remember(searchQuery, selectedProvider, customAvailableModels) {
                viewModel.getDisplayModels()
            }
            EnrichedModelSelectionContent(
                displayModels = displayModels,
                selectedModelId = selectedModel,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setModelSearchQuery(it) },
                onSelectModel = {
                    viewModel.setSelectedModel(it)
                    viewModel.setModelSearchQuery("")
                    if (initialSubScreen != SettingsSubScreen.Main) {
                        onNavigateBack()
                    } else {
                        currentSubScreen = SettingsSubScreen.Main
                    }
                },
                primaryColor = primaryColor,
            )
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
            val hbSearchQuery by viewModel.heartbeatModelSearchQuery.collectAsState()
            LaunchedEffect(Unit) { viewModel.refreshOpenRouterModelsIfNeeded() }
            val hbDisplayModels = remember(hbSearchQuery, heartbeatProvider) { viewModel.getHeartbeatDisplayModels() }
            EnrichedModelSelectionContent(
                displayModels = hbDisplayModels,
                selectedModelId = heartbeatModel,
                searchQuery = hbSearchQuery,
                onSearchQueryChange = { viewModel.setHeartbeatModelSearchQuery(it) },
                onSelectModel = {
                    viewModel.setHeartbeatModel(it)
                    viewModel.setHeartbeatModelSearchQuery("")
                    currentSubScreen = SettingsSubScreen.Main
                },
                primaryColor = primaryColor,
            )
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
                        disabledSubtitle = if (!configured) providerDisabledMessage(provider) else null,
                        onClick = {
                            viewModel.setHeartbeatProvider(provider)
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                }
            }
        }

        SettingsSubScreen.CompactionModelSelection -> {
            val cmpSearchQuery by viewModel.compactionModelSearchQuery.collectAsState()
            val cmpProvider by viewModel.compactionProvider.collectAsState()
            LaunchedEffect(Unit) { viewModel.refreshOpenRouterModelsIfNeeded() }
            val cmpDisplayModels = remember(cmpSearchQuery, cmpProvider) { viewModel.getCompactionDisplayModels() }
            EnrichedModelSelectionContent(
                displayModels = cmpDisplayModels,
                selectedModelId = viewModel.compactionModel.value,
                searchQuery = cmpSearchQuery,
                onSearchQueryChange = { viewModel.setCompactionModelSearchQuery(it) },
                onSelectModel = {
                    viewModel.setCompactionModel(it)
                    viewModel.setCompactionModelSearchQuery("")
                    currentSubScreen = SettingsSubScreen.Main
                },
                primaryColor = primaryColor,
            )
        }

        SettingsSubScreen.CompactionProviderSelection -> {
            val cmpProvider by viewModel.compactionProvider.collectAsState()
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
                        isSelected = provider == cmpProvider,
                        primaryColor = primaryColor,
                        enabled = configured,
                        disabledSubtitle = if (!configured) providerDisabledMessage(provider) else null,
                        onClick = {
                            viewModel.setCompactionProvider(provider)
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                }
            }
        }

        SettingsSubScreen.RoutingModeSelection -> {
            val rmMode by viewModel.routingMode.collectAsState()
            RoutingModeSelectionScreen(
                selectedMode = rmMode,
                onSelectMode = { mode ->
                    viewModel.setRoutingMode(mode)
                    currentSubScreen = SettingsSubScreen.Main
                },
                primaryColor = primaryColor,
            )
        }

        SettingsSubScreen.RoutingPresetSelection -> {
            val rpPresets by viewModel.routingPresets.collectAsState()
            val rpSelectedId by viewModel.selectedRoutingPresetId.collectAsState()
            RoutingPresetSelectionScreen(
                presets = rpPresets,
                selectedPresetId = rpSelectedId,
                onSelectPreset = { id ->
                    viewModel.selectRoutingPreset(id)
                    currentSubScreen = SettingsSubScreen.Main
                },
                onDeletePreset = { viewModel.deleteRoutingPreset(it) },
                onRevertPreset = { viewModel.revertStockPreset(it) },
                onCreateNewPreset = {
                    val current = rpPresets.firstOrNull { it.id == rpSelectedId }
                    if (current != null) {
                        val customCount = rpPresets.count { !it.isStock } + 1
                        val newPreset = current.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "Custom $customCount",
                            isStock = false,
                        )
                        viewModel.saveRoutingPreset(newPreset)
                        viewModel.selectRoutingPreset(newPreset.id)
                        currentSubScreen = SettingsSubScreen.RoutingPresetEditor
                    }
                },
                primaryColor = primaryColor,
            )
        }

        SettingsSubScreen.RoutingPresetEditor -> {
            val rpPresets by viewModel.routingPresets.collectAsState()
            val rpSelectedId by viewModel.selectedRoutingPresetId.collectAsState()
            val editPreset = rpPresets.firstOrNull { it.id == rpSelectedId }
            if (editPreset != null) {
                RoutingPresetEditorScreen(
                    preset = editPreset,
                    allSkills = viewModel.registeredSkills,
                    onSave = { viewModel.saveRoutingPreset(it) },
                    onDone = { currentSubScreen = SettingsSubScreen.Main },
                    primaryColor = primaryColor,
                )
            } else {
                currentSubScreen = SettingsSubScreen.Main
            }
        }

        SettingsSubScreen.RoutingProviderSelection -> {
            val rpProvider by viewModel.routingProvider.collectAsState()
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
                        isSelected = provider == rpProvider,
                        primaryColor = primaryColor,
                        enabled = configured,
                        disabledSubtitle = if (!configured) providerDisabledMessage(provider) else null,
                        onClick = {
                            viewModel.setRoutingProvider(provider)
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                }
            }
        }

        SettingsSubScreen.RoutingModelSelection -> {
            val rpModel by viewModel.routingModel.collectAsState()
            val rpProvider by viewModel.routingProvider.collectAsState()
            val rtSearchQuery by viewModel.routingModelSearchQuery.collectAsState()
            LaunchedEffect(Unit) { viewModel.refreshOpenRouterModelsIfNeeded() }
            val rtDisplayModels = remember(rtSearchQuery, rpProvider) { viewModel.getRoutingDisplayModels() }
            EnrichedModelSelectionContent(
                displayModels = rtDisplayModels,
                selectedModelId = rpModel,
                searchQuery = rtSearchQuery,
                onSearchQueryChange = { viewModel.setRoutingModelSearchQuery(it) },
                onSelectModel = {
                    viewModel.setRoutingModel(it)
                    viewModel.setRoutingModelSearchQuery("")
                    currentSubScreen = SettingsSubScreen.Main
                },
                primaryColor = primaryColor,
            )
        }

        SettingsSubScreen.ModelRoutingLightSelection -> {
            val currentId by viewModel.modelRoutingLight.collectAsState()
            val mrSearchQuery by viewModel.modelRoutingSearchQuery.collectAsState()
            LaunchedEffect(Unit) { viewModel.refreshOpenRouterModelsIfNeeded() }
            val mrDisplayModels = remember(mrSearchQuery, selectedProvider) { viewModel.getModelRoutingDisplayModels() }
            EnrichedModelSelectionContent(
                displayModels = mrDisplayModels,
                selectedModelId = currentId,
                searchQuery = mrSearchQuery,
                onSearchQueryChange = { viewModel.setModelRoutingSearchQuery(it) },
                onSelectModel = {
                    viewModel.setModelRoutingLight(it)
                    viewModel.setModelRoutingSearchQuery("")
                    currentSubScreen = SettingsSubScreen.Main
                },
                primaryColor = primaryColor,
                headerContent = {
                    SelectionRow(
                        text = "Auto",
                        subtitle = "Let the router pick the best model for easy tasks",
                        isSelected = currentId.isBlank(),
                        primaryColor = primaryColor,
                        onClick = {
                            viewModel.setModelRoutingLight("")
                            viewModel.setModelRoutingSearchQuery("")
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                },
            )
        }

        SettingsSubScreen.ModelRoutingStandardSelection -> {
            val currentId by viewModel.modelRoutingStandard.collectAsState()
            val mrSearchQuery by viewModel.modelRoutingSearchQuery.collectAsState()
            LaunchedEffect(Unit) { viewModel.refreshOpenRouterModelsIfNeeded() }
            val mrDisplayModels = remember(mrSearchQuery, selectedProvider) { viewModel.getModelRoutingDisplayModels() }
            EnrichedModelSelectionContent(
                displayModels = mrDisplayModels,
                selectedModelId = currentId,
                searchQuery = mrSearchQuery,
                onSearchQueryChange = { viewModel.setModelRoutingSearchQuery(it) },
                onSelectModel = {
                    viewModel.setModelRoutingStandard(it)
                    viewModel.setModelRoutingSearchQuery("")
                    currentSubScreen = SettingsSubScreen.Main
                },
                primaryColor = primaryColor,
                headerContent = {
                    SelectionRow(
                        text = "Auto",
                        subtitle = "Let the router pick the best model for medium tasks",
                        isSelected = currentId.isBlank(),
                        primaryColor = primaryColor,
                        onClick = {
                            viewModel.setModelRoutingStandard("")
                            viewModel.setModelRoutingSearchQuery("")
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                },
            )
        }

        SettingsSubScreen.ModelRoutingPowerfulSelection -> {
            val currentId by viewModel.modelRoutingPowerful.collectAsState()
            val mrSearchQuery by viewModel.modelRoutingSearchQuery.collectAsState()
            LaunchedEffect(Unit) { viewModel.refreshOpenRouterModelsIfNeeded() }
            val mrDisplayModels = remember(mrSearchQuery, selectedProvider) { viewModel.getModelRoutingDisplayModels() }
            EnrichedModelSelectionContent(
                displayModels = mrDisplayModels,
                selectedModelId = currentId,
                searchQuery = mrSearchQuery,
                onSearchQueryChange = { viewModel.setModelRoutingSearchQuery(it) },
                onSelectModel = {
                    viewModel.setModelRoutingPowerful(it)
                    viewModel.setModelRoutingSearchQuery("")
                    currentSubScreen = SettingsSubScreen.Main
                },
                primaryColor = primaryColor,
                headerContent = {
                    SelectionRow(
                        text = "Auto",
                        subtitle = "Let the router pick the best model for hard tasks",
                        isSelected = currentId.isBlank(),
                        primaryColor = primaryColor,
                        onClick = {
                            viewModel.setModelRoutingPowerful("")
                            viewModel.setModelRoutingSearchQuery("")
                            currentSubScreen = SettingsSubScreen.Main
                        },
                    )
                },
            )
        }

        SettingsSubScreen.BudgetPresetSelection -> {
            val bpPresets by viewModel.budgetPresets.collectAsState()
            val bpSelectedId by viewModel.selectedBudgetPresetId.collectAsState()
            BudgetPresetSelectionScreen(
                presets = bpPresets,
                selectedPresetId = bpSelectedId,
                onSelectPreset = { id ->
                    viewModel.selectBudgetPreset(id)
                    currentSubScreen = SettingsSubScreen.Main
                },
                onDeletePreset = { viewModel.deleteBudgetPreset(it) },
                onRevertPreset = { viewModel.revertBudgetPreset(it) },
                onCreateNewPreset = {
                    val current = bpPresets.firstOrNull { it.id == bpSelectedId }
                    if (current != null) {
                        val customCount = bpPresets.count { !it.isStock } + 1
                        val newPreset = current.copy(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "Custom $customCount",
                            isStock = false,
                        )
                        viewModel.saveBudgetPreset(newPreset)
                        viewModel.selectBudgetPreset(newPreset.id)
                        currentSubScreen = SettingsSubScreen.BudgetPresetEditor
                    }
                },
                primaryColor = primaryColor,
            )
        }

        SettingsSubScreen.BudgetPresetEditor -> {
            val bpPresets by viewModel.budgetPresets.collectAsState()
            val bpSelectedId by viewModel.selectedBudgetPresetId.collectAsState()
            val editPreset = bpPresets.firstOrNull { it.id == bpSelectedId }
            if (editPreset != null) {
                BudgetPresetEditorScreen(
                    preset = editPreset,
                    onSave = { viewModel.saveBudgetPreset(it) },
                    onDone = { currentSubScreen = SettingsSubScreen.Main },
                    primaryColor = primaryColor,
                )
            } else {
                currentSubScreen = SettingsSubScreen.Main
            }
        }
        SettingsSubScreen.LocalLlmSettings -> {
            LocalLlmSettingsContent(
                viewModel = viewModel,
                onImportClick = { ggufImportLauncher.launch(arrayOf("*/*")) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectionRow(
    text: String,
    isSelected: Boolean,
    primaryColor: Color,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    disabledSubtitle: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.4f
    val rowModifier = if (onLongClick != null) {
        Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 16.dp, horizontal = 16.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 16.dp)
    }
    Row(
        modifier = rowModifier,
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
    onNavigateToSend: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as org.ethereumphone.andyclaw.NodeApp
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    var agentAddress by remember { mutableStateOf<String?>(null) }
    var agentLoading by remember { mutableStateOf(true) }
    var agentHoldings by remember {
        mutableStateOf<List<org.ethereumphone.andyclaw.agentwallet.ChainBalances>>(emptyList())
    }

    // Goes through the shared repository so this agrees with the send screen and the
    // agent's own tools about which chains exist. It used to build a mainnet-only SDK here.
    LaunchedEffect(Unit) {
        agentAddress = app.agentWalletRepository.getAddress()
        agentLoading = false
        if (agentAddress != null) {
            agentHoldings = app.agentWalletRepository.scanAll().filter { it.hasFunds }
        }
    }

    Text(
        text = "AGENT WALLET",
        color = primaryColor,
        style = sectionTitleStyle,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = "A dedicated wallet for your agent, separate from your dGEN1 wallet. Fund it if you want the agent to execute transactions autonomously. You can send funds back out at any time with Send below.",
        style = contentBodyStyle,
        color = dgenWhite.copy(alpha = 0.7f),
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
        if (agentHoldings.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "HOLDINGS",
                style = contentTitleStyle,
                color = primaryColor,
            )
            Spacer(Modifier.height(2.dp))
            agentHoldings.forEach { chain ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = chain.chainName,
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                    Text(
                        text = chain.all
                            .filter { it.raw.signum() > 0 }
                            .joinToString("  ") { "${it.display()} ${it.symbol}" },
                        style = contentBodyStyle,
                        color = dgenWhite.copy(alpha = 0.7f),
                    )
                }
            }
        }
    } else {
        Text(
            text = if (agentLoading) "Loading agent wallet…" else "Agent wallet not available",
            style = contentBodyStyle,
            color = dgenWhite.copy(alpha = 0.5f),
        )
    }

    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateToSend)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SEND",
                style = contentTitleStyle,
                color = primaryColor,
            )
            Text(
                text = "Move funds out of the agent wallet yourself",
                style = contentBodyStyle,
                color = dgenWhite,
            )
        }
        Spacer(Modifier.width(20.dp))
        DgenSmallPrimaryButton(
            text = "Send",
            primaryColor = primaryColor,
            onClick = onNavigateToSend,
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

private fun providerDisabledMessage(provider: LlmProvider): String = when (provider) {
    LlmProvider.LOCAL -> "Download the model in AI Provider settings"
    else -> "Set up API key in AI Provider settings"
}

/**
 * Reusable enriched model selection screen with search bar, provider subtitles,
 * and long-press pricing. Used for main model, heartbeat, routing, and tier selection.
 *
 * @param displayModels Pre-built display models from the ViewModel.
 * @param selectedModelId Currently selected model ID.
 * @param searchQuery Current search filter text.
 * @param onSearchQueryChange Callback for search text changes.
 * @param onSelectModel Callback when a model is selected (receives modelId).
 * @param primaryColor Theme color.
 * @param headerContent Optional composable shown above the list (e.g., description text, "Auto" option).
 */
@Composable
private fun EnrichedModelSelectionContent(
    displayModels: List<DisplayModel>,
    selectedModelId: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    primaryColor: Color,
    headerContent: @Composable (() -> Unit)? = null,
) {
    var longPressedModelId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        org.ethereumphone.andyclaw.ui.DgenCursorSearchTextfield(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            singleLine = true,
            maxFieldHeight = 40.dp,
            cursorWidth = 10.dp,
            cursorHeight = 20.dp,
            placeholder = {
                Text(
                    text = "Search",
                    style = TextStyle(
                        fontFamily = PitagonsSans,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal,
                        color = primaryColor.copy(alpha = 0.35f),
                        shadow = GlowStyle.placeholder(primaryColor),
                    ),
                )
            },
            cursorColor = primaryColor,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (headerContent != null) {
                item { headerContent() }
            }
            items(displayModels, key = { it.modelId }) { model ->
                val showPricing = longPressedModelId == model.modelId && model.pricingDetail != null
                SelectionRow(
                    text = model.displayName,
                    subtitle = if (showPricing) model.pricingDetail else model.subtitle,
                    isSelected = model.modelId == selectedModelId,
                    primaryColor = primaryColor,
                    onClick = {
                        onSelectModel(model.modelId)
                    },
                    onLongClick = if (model.pricingDetail != null) {
                        { longPressedModelId = if (longPressedModelId == model.modelId) null else model.modelId }
                    } else null,
                )
            }
            if (displayModels.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    Text(
                        text = "No models match \"$searchQuery\"",
                        style = TextStyle(
                            fontFamily = PitagonsSans,
                            fontSize = label_fontSize,
                            color = dgenWhite.copy(alpha = 0.5f),
                        ),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

enum class SettingsSubScreen {
    Main,
    ModelSelection,
    ProviderSelection,
    HeartbeatModelSelection,
    HeartbeatProviderSelection,
    CompactionModelSelection,
    CompactionProviderSelection,
    RoutingModeSelection,
    RoutingPresetSelection,
    RoutingPresetEditor,
    RoutingProviderSelection,
    RoutingModelSelection,
    BudgetPresetSelection,
    BudgetPresetEditor,
    ModelRoutingLightSelection,
    ModelRoutingStandardSelection,
    ModelRoutingPowerfulSelection,
    LocalLlmSettings,
}
