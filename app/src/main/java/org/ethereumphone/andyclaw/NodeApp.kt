package org.ethereumphone.andyclaw

import android.app.Application
import android.util.Log
import org.ethereumphone.andyclaw.agenttx.AgentTxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.heartbeat.HeartbeatLogStore
import org.ethereumphone.andyclaw.extensions.ExtensionEngine
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubManager
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubSkillAdapter
import org.ethereumphone.andyclaw.extensions.toSkillAdapters
import org.ethereumphone.andyclaw.skills.termux.ClawHubTermuxSkillAdapter
import org.ethereumphone.andyclaw.skills.termux.TermuxCommandRunner
import org.ethereumphone.andyclaw.skills.termux.TermuxSkillSync
import org.ethereumphone.andyclaw.llm.AnthropicClient
import org.ethereumphone.andyclaw.llm.ChatGptOauthClient
import org.ethereumphone.andyclaw.llm.ChatGptOauthTokenManager
import org.ethereumphone.andyclaw.llm.ClaudeOauthClient
import org.ethereumphone.andyclaw.llm.GgufRegistry
import org.ethereumphone.andyclaw.llm.LlamaCpp
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.llm.LocalLlmClient
import org.ethereumphone.andyclaw.llm.ModelDownloadManager
import org.ethereumphone.andyclaw.llm.OpenAiNativeClient
import org.ethereumphone.andyclaw.llm.TinfoilClient
import org.ethereumphone.andyclaw.llm.TinfoilProxyClient
import org.ethereumphone.andyclaw.skills.SkillRegistry
import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.memory.OpenAiEmbeddingProvider
import org.ethereumphone.andyclaw.sessions.SessionManager
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.builtin.AppsSkill
import org.ethereumphone.andyclaw.skills.builtin.CameraSkill
import org.ethereumphone.andyclaw.skills.builtin.ClipboardSkill
import org.ethereumphone.andyclaw.skills.builtin.ContactsSkill
import org.ethereumphone.andyclaw.skills.builtin.DeviceInfoSkill
import org.ethereumphone.andyclaw.skills.builtin.FileSystemSkill
import org.ethereumphone.andyclaw.skills.builtin.MemorySkill
import org.ethereumphone.andyclaw.skills.builtin.MessengerSkill
import org.ethereumphone.andyclaw.skills.builtin.NotificationSkill
import org.ethereumphone.andyclaw.skills.builtin.ProactiveAgentSkill
import org.ethereumphone.andyclaw.skills.builtin.SMSSkill
import org.ethereumphone.andyclaw.skills.builtin.ScreenSkill
import org.ethereumphone.andyclaw.skills.builtin.SettingsSkill
import org.ethereumphone.andyclaw.skills.builtin.ShellSkill
import org.ethereumphone.andyclaw.skills.builtin.AudioSkill
import org.ethereumphone.andyclaw.skills.builtin.CalendarSkill
import org.ethereumphone.andyclaw.skills.builtin.CodeExecutionSkill
import org.ethereumphone.andyclaw.skills.builtin.CustomToolCreatorSkill
import org.ethereumphone.andyclaw.skills.builtin.SoulSkill
import org.ethereumphone.andyclaw.skills.builtin.ConnectivitySkill
import org.ethereumphone.andyclaw.skills.builtin.DevicePowerSkill
import org.ethereumphone.andyclaw.skills.builtin.PackageManagerSkill
import org.ethereumphone.andyclaw.skills.builtin.PhoneSkill
import org.ethereumphone.andyclaw.skills.builtin.ScreenTimeSkill
import org.ethereumphone.andyclaw.skills.builtin.StorageSkill
import org.ethereumphone.andyclaw.skills.builtin.CronjobSkill
import org.ethereumphone.andyclaw.skills.builtin.ReminderSkill
import org.ethereumphone.andyclaw.skills.builtin.TermuxSkill
import org.ethereumphone.andyclaw.skills.builtin.WalletSkill
import org.ethereumphone.andyclaw.skills.builtin.AuroraStoreSkill
import org.ethereumphone.andyclaw.skills.builtin.LocationSkill
import org.ethereumphone.andyclaw.skills.builtin.ClawHubSkill
import org.ethereumphone.andyclaw.skills.builtin.clitool.CliToolManagerSkill
import org.ethereumphone.andyclaw.skills.builtin.SkillCreatorSkill
import org.ethereumphone.andyclaw.skills.builtin.SkillRefinementSkill
import org.ethereumphone.andyclaw.flows.FlowRecorder
import org.ethereumphone.andyclaw.flows.FlowRepository
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplaySkill
import org.ethereumphone.andyclaw.skills.builtin.FlowSkill
import org.ethereumphone.andyclaw.skills.builtin.RecordingDisplaySkill
import org.ethereumphone.andyclaw.skills.builtin.LedSkill
import org.ethereumphone.andyclaw.skills.builtin.TelegramSkill
import org.ethereumphone.andyclaw.skills.builtin.WebSearchSkill
import org.ethereumphone.andyclaw.skills.builtin.ENSSkill
import org.ethereumphone.andyclaw.skills.builtin.TokenLookupSkill
import org.ethereumphone.andyclaw.skills.builtin.BankrTradingSkill
import org.ethereumphone.andyclaw.skills.builtin.SwapSkill
import org.ethereumphone.andyclaw.skills.builtin.GmailSkill
import org.ethereumphone.andyclaw.skills.builtin.DriveSkill
import org.ethereumphone.andyclaw.skills.builtin.GoogleCalendarSkill
import org.ethereumphone.andyclaw.skills.builtin.SheetsSkill
import org.ethereumphone.andyclaw.google.GoogleAuthManager
import org.ethereumphone.andyclaw.safety.SafetyConfig
import org.ethereumphone.andyclaw.safety.SafetyLayer
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.onboarding.UserStoryManager
import org.ethereumphone.andyclaw.whisper.WhisperTranscriber
import org.ethereumhpone.messengersdk.MessengerSDK
import org.ethereumphone.andyclaw.led.LedMatrixController
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.OpenRouterModelRegistry
import org.ethereumphone.andyclaw.skills.ModelRoutingConfig
import org.ethereumphone.andyclaw.skills.RoutingConfig
import org.ethereumphone.andyclaw.skills.RoutingPreset
import org.ethereumphone.andyclaw.skills.SmartRouter

class NodeApp : Application() {

    companion object {
        private const val TAG = "NodeApp"
        /** One-shot marker for [seedFlowSkillEnabled]. */
        private const val FLOWS_SEEDED_KEY = "flows.skillSeeded"
        private const val DEFAULT_AGENT_ID = "default"
    }

    /** Application-scoped coroutine scope for background initialisation. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val runtime: NodeRuntime by lazy { NodeRuntime(this) }
    val securePrefs: SecurePrefs by lazy { SecurePrefs(this) }
    val userStoryManager: UserStoryManager by lazy { UserStoryManager(this) }
    val soulManager: org.ethereumphone.andyclaw.soul.SoulManager by lazy { org.ethereumphone.andyclaw.soul.SoulManager(this) }
    val sessionManager: SessionManager by lazy { SessionManager(this) }
    val agentTxRepository: AgentTxRepository by lazy { AgentTxRepository(this) }
    val heartbeatLogStore: HeartbeatLogStore by lazy { HeartbeatLogStore(filesDir) }

    /**
     * Irreversible requests that a run triggered by untrusted content asked for and
     * that no headless runner could legitimately approve. Rendered as approval cards
     * by the ambient surface.
     */
    val pendingApprovalStore: org.ethereumphone.andyclaw.safety.PendingApprovalStore by lazy {
        org.ethereumphone.andyclaw.safety.PendingApprovalStore(this)
    }
    val whisperTranscriber: WhisperTranscriber by lazy { WhisperTranscriber(this) }
    val executiveSummaryManager: org.ethereumphone.andyclaw.summary.ExecutiveSummaryManager by lazy {
        org.ethereumphone.andyclaw.summary.ExecutiveSummaryManager(this)
    }

    var permissionRequester: PermissionRequester? = null

    /**
     * Creates a SafetyLayer reflecting the current user preference.
     * Called each time an AgentLoop is created so the latest setting is used.
     */
    fun createSafetyLayer(): SafetyLayer {
        val enabled = securePrefs.safetyEnabled.value && !securePrefs.yoloMode.value
        return SafetyLayer(config = SafetyConfig(enabled = enabled))
    }

    /**
     * Creates a BudgetConfig reflecting the current user preference.
     * Returns null when budget mode is disabled or set to "Off".
     */
    fun createBudgetConfig(): org.ethereumphone.andyclaw.agent.BudgetConfig? {
        if (!securePrefs.budgetModeEnabled.value) return null
        val presetId = securePrefs.selectedBudgetPresetId.value
        val preset = securePrefs.budgetPresets.value.firstOrNull { it.id == presetId }
            ?: org.ethereumphone.andyclaw.agent.BudgetPreset.defaults()
                .firstOrNull { it.id == presetId }
            ?: return null
        if (preset.id == "stock_off") return null
        return org.ethereumphone.andyclaw.agent.BudgetConfig(preset)
    }

    // ── LED matrix (dGEN1 only) ───────────────────────────────────────────

    val ledController: LedMatrixController by lazy {
        LedMatrixController(
            context = this,
            maxRgbProvider = { securePrefs.ledMaxBrightness.value },
        )
    }

    // ── Memory subsystem ───────────────────────────────────────────────

    val memoryManager: MemoryManager by lazy {
        MemoryManager(this, agentId = DEFAULT_AGENT_ID)
    }

    private val embeddingProvider: OpenAiEmbeddingProvider by lazy {
        if (OsCapabilities.hasPrivilegedAccess) {
            OpenAiEmbeddingProvider(
                userId = { securePrefs.walletAddress.value },
                signature = { securePrefs.walletSignature.value },
            )
        } else {
            OpenAiEmbeddingProvider(
                apiKey = { securePrefs.apiKey.value },
                baseUrl = "https://openrouter.ai/api/v1",
            )
        }
    }

    // ── Extension subsystem ────────────────────────────────────────────

    val extensionEngine: ExtensionEngine by lazy {
        ExtensionEngine(this)
    }

    // ── Google Workspace subsystem ─────────────────────────────────────────
    val googleAuthManager: GoogleAuthManager by lazy { GoogleAuthManager(securePrefs) }

    // ── Telegram subsystem ───────────────────────────────────────────────

    val telegramChatStore: org.ethereumphone.andyclaw.telegram.TelegramChatStore by lazy {
        org.ethereumphone.andyclaw.telegram.TelegramChatStore(this)
    }

    // ── Termux subsystem (shared by TermuxSkill + ClawHub adapters) ────

    val termuxCommandRunner: TermuxCommandRunner by lazy { TermuxCommandRunner(this) }

    val termuxSkillSync: TermuxSkillSync by lazy {
        TermuxSkillSync(termuxCommandRunner, this)
    }

    // ── ClawHub subsystem ───────────────────────────────────────────────

    /** Directory where ClawHub-installed skills are stored. */
    val clawHubSkillsDir by lazy {
        java.io.File(filesDir, "clawhub-skills").also { it.mkdirs() }
    }

    /** Directory where AI-created skills are stored. */
    val aiSkillsDir by lazy {
        java.io.File(filesDir, "ai-skills").also { it.mkdirs() }
    }

    /** Directory where LLM-created executable custom tools are stored. */
    val customToolsDir by lazy {
        java.io.File(filesDir, "custom-tools").also { it.mkdirs() }
    }

    val customToolStore by lazy {
        org.ethereumphone.andyclaw.skills.customtools.CustomToolStore(customToolsDir)
    }

    val customToolExecutor by lazy {
        org.ethereumphone.andyclaw.skills.customtools.CustomToolExecutor(this)
    }

    /** Skill registry for SKILL.md-based skills (ClawHub + local). */
    val skillRegistry: SkillRegistry by lazy { SkillRegistry() }

    val clawHubManager: ClawHubManager by lazy {
        ClawHubManager(
            managedSkillsDir = clawHubSkillsDir,
            skillRegistry = skillRegistry,
        )
    }

    // ── Model routing (OpenRouter dynamic model selection) ──────────

    val openRouterModelRegistry: OpenRouterModelRegistry by lazy {
        OpenRouterModelRegistry(context = this)
    }

    // ── Skill Router ─────────────────────────────────────────────────

    val smartRouter: SmartRouter by lazy {
        SmartRouter(
            context = this,
            skillRegistry = nativeSkillRegistry,
            embeddingProvider = if (OsCapabilities.hasPrivilegedAccess || securePrefs.apiKey.value.isNotBlank()) {
                embeddingProvider
            } else {
                null
            },
            routingClientProvider = {
                if (securePrefs.routingUseSameModel.value) {
                    // Use the auto-selected routing model for the current provider
                    val provider = securePrefs.selectedProvider.value
                    val routingModel = AnthropicModels.routingModelForProvider(provider) ?: return@SmartRouter null
                    RoutingConfig(getLlmClientForProvider(provider, routingModel.modelId), routingModel.modelId)
                } else {
                    // Use the user-configured routing provider/model
                    val provider = securePrefs.routingProvider.value
                    val modelId = securePrefs.routingModel.value
                    if (modelId.isBlank()) return@SmartRouter null
                    RoutingConfig(getLlmClientForProvider(provider, modelId), modelId)
                }
            },
            presetProvider = {
                val presetId = securePrefs.selectedRoutingPresetId.value
                securePrefs.routingPresets.value.find { it.id == presetId }
                    ?: RoutingPreset.defaults().find { it.id == presetId }
                    ?: RoutingPreset.defaults().first { it.id == RoutingPreset.defaultPresetId }
            },
            routingModeProvider = { securePrefs.routingMode.value },
            modelRoutingConfigProvider = {
                ModelRoutingConfig(
                    enabled = securePrefs.modelRoutingEnabled.value,
                    registry = openRouterModelRegistry,
                    providerProvider = { securePrefs.selectedProvider.value },
                    defaultModelIdProvider = { securePrefs.selectedModel.value },
                    tierModelOverrideProvider = { tier ->
                        when (tier) {
                            org.ethereumphone.andyclaw.llm.ModelTier.LIGHT -> securePrefs.modelRoutingLight.value
                            org.ethereumphone.andyclaw.llm.ModelTier.STANDARD -> securePrefs.modelRoutingStandard.value
                            org.ethereumphone.andyclaw.llm.ModelTier.POWERFUL -> securePrefs.modelRoutingPowerful.value
                        }
                    },
                )
            },
            filesDir = filesDir,
        )
    }

    // ── Tool Search Service ────────────────────────────────────────────

    /**
     * Creates a [ToolSearchService] for a conversation session.
     * Each conversation gets its own instance so discovered tools are tracked per-session.
     * Returns null when tool search is disabled.
     */
    fun createToolSearchService(
        tier: org.ethereumphone.andyclaw.skills.Tier,
        enabledSkillIds: Set<String>,
    ): org.ethereumphone.andyclaw.skills.ToolSearchService? {
        if (!securePrefs.toolSearchEnabled.value) return null
        return org.ethereumphone.andyclaw.skills.ToolSearchService(
            skillRegistry = nativeSkillRegistry,
            tier = tier,
            enabledSkillIds = enabledSkillIds,
            presetProvider = {
                val presetId = securePrefs.selectedRoutingPresetId.value
                securePrefs.routingPresets.value.find { it.id == presetId }
                    ?: RoutingPreset.defaults().find { it.id == presetId }
                    ?: RoutingPreset.defaults().first { it.id == RoutingPreset.defaultPresetId }
            },
            autoLoadSiblings = securePrefs.getString("toolSearch.autoLoadSiblings") == "true",
        )
    }

    // ── Compiled flows (execution-ladder rung 3) ───────────────────────

    /** Watches display sessions so a successful one can become a flow. */
    val flowRecorder: FlowRecorder by lazy { FlowRecorder() }

    /**
     * Flows on disk, published as named tools.
     *
     * Not touched from inside [nativeSkillRegistry]'s initialiser — it registers into
     * that registry, so reaching it from there would be a cycle. It is loaded from
     * [onUserUnlocked] instead, which is also the first moment `filesDir` and the
     * keystore are readable.
     */
    val flowRepository: FlowRepository by lazy { FlowRepository(this, nativeSkillRegistry) }

    /**
     * The flow registry, or null on the open tier. Rung 3 replays through the agent
     * display, which is an ethOS-only service — off ethOS there is nothing for a flow
     * to drive, and touching this would create an empty store for no reason.
     */
    val flowRepositoryOrNull: FlowRepository?
        get() = if (OsCapabilities.hasPrivilegedAccess) flowRepository else null

    // ── Skills ─────────────────────────────────────────────────────────

    val nativeSkillRegistry: NativeSkillRegistry by lazy {
        NativeSkillRegistry().apply {
            // Day 1 base skills
            register(DeviceInfoSkill(this@NodeApp))
            register(ClipboardSkill(this@NodeApp))
            register(ShellSkill(this@NodeApp) {
                securePrefs.safetyEnabled.value && !securePrefs.yoloMode.value
            })
            register(FileSystemSkill(this@NodeApp))
            // Day 2 tier-aware skills
            register(ContactsSkill(this@NodeApp))
            register(AppsSkill(this@NodeApp))
            register(NotificationSkill(this@NodeApp))
            register(SettingsSkill(this@NodeApp))
            register(CameraSkill(this@NodeApp))
            register(SMSSkill(this@NodeApp))
            // ethOS wallet skill
            register(WalletSkill(this@NodeApp, agentTxRepository))
            // ENS name resolution (forward and reverse)
            register(ENSSkill())
            // Token lookup, price, and launched tokens (DexScreener + Clanker)
            register(TokenLookupSkill())
            // Bankr trading: limit/stop/DCA/TWAP orders and wallet lookup
            register(BankrTradingSkill(this@NodeApp))
            // Token swaps via WalletManager ContentProvider
            register(SwapSkill(this@NodeApp))
            // XMTP messenger skill
            register(MessengerSkill(this@NodeApp))
            // Day 3 showcase skills
            register(ScreenSkill())
            register(ProactiveAgentSkill())
            // Memory skill — agent can store and search long-term memory
            register(MemorySkill(memoryManager))
            // System app / priv-app skills
            register(ConnectivitySkill(this@NodeApp))
            register(PhoneSkill(this@NodeApp))
            register(CalendarSkill(this@NodeApp))
            register(ScreenTimeSkill(this@NodeApp))
            register(StorageSkill(this@NodeApp))
            register(PackageManagerSkill(this@NodeApp))
            register(AudioSkill(this@NodeApp))
            register(DevicePowerSkill(this@NodeApp))
            register(CodeExecutionSkill(
                context = this@NodeApp,
                registryProvider = { nativeSkillRegistry },
                tierProvider = { OsCapabilities.currentTier() },
                enabledSkillIdsProvider = {
                    if (securePrefs.yoloMode.value) {
                        nativeSkillRegistry.getAll().map { it.id }.toSet()
                    } else {
                        securePrefs.enabledSkills.value
                    }
                },
                enforceProvenanceProvider = { securePrefs.provenanceEnforcementEnabled.value },
            ))
            // Soul — AI can read and update its own personality
            register(SoulSkill(soulManager))
            // Custom Tool Creator — AI can create reusable executable tools at runtime
            register(CustomToolCreatorSkill(
                context = this@NodeApp,
                customToolStore = customToolStore,
                customToolExecutor = customToolExecutor,
                nativeSkillRegistry = this,
                onToolsChanged = { syncCustomTools() },
            ))
            // Reminders — schedule notifications at specific times
            register(ReminderSkill(this@NodeApp))
            // Cron Jobs — recurring scheduled agent executions via OS
            register(CronjobSkill(this@NodeApp))
            // Termux integration — full Linux environment via Termux app
            register(TermuxSkill(this@NodeApp))
            // Aurora Store — download and install apps from Play Store
            register(AuroraStoreSkill(this@NodeApp))
            // Web Search — search the web and fetch webpage content
            register(WebSearchSkill(
                context = this@NodeApp,
                isSafetyEnabled = {
                    securePrefs.safetyEnabled.value && !securePrefs.yoloMode.value
                },
                ledController = ledController,
            ))
            // Location — GPS position, nearby places, maps & navigation
            register(LocationSkill(this@NodeApp))
            // Telegram — send proactive messages to the user via Telegram bot
            register(TelegramSkill(
                chatStore = telegramChatStore,
                botToken = { securePrefs.telegramBotToken.value },
                botEnabled = { securePrefs.telegramBotEnabled.value },
                ownerChatId = { securePrefs.telegramOwnerChatId.value },
            ))
            // Google Workspace — Gmail, Drive, Calendar, Sheets
            val googleTokenProvider: suspend () -> String = { googleAuthManager.getAccessToken() }
            register(GmailSkill(googleTokenProvider))
            register(DriveSkill(googleTokenProvider))
            register(GoogleCalendarSkill(googleTokenProvider))
            register(SheetsSkill(googleTokenProvider))
            // Agent Display — operate a virtual display (ethOS privileged only).
            // Wrapped in the recorder so a successful discovery session can be compiled
            // into a flow: the decorator changes nothing about what runs, it only
            // watches, so there stays exactly one code path that drives the device.
            register(RecordingDisplaySkill(AgentDisplaySkill(), flowRecorder))
            // LED Matrix — control the 3×3 LED matrix on dGEN1 devices
            if (OsCapabilities.hasPrivilegedAccess) {
                register(LedSkill(ledController))
            }
            // Skill Creator — AI can author new SKILL.md-based skills at runtime
            register(SkillCreatorSkill(
                aiSkillsDir = aiSkillsDir,
                clawHubSkillsDir = clawHubSkillsDir,
                nativeSkillRegistry = this,
                onSkillsChanged = { syncAiSkills() },
            ))
            // Skill Refinement — overlay improvements on ClawHub and AI-created skills
            register(SkillRefinementSkill(
                aiSkillsDir = aiSkillsDir,
                clawHubSkillsDir = clawHubSkillsDir,
                nativeSkillRegistry = this,
            ))
            // ClawHub — agent can search, install, uninstall, and manage ClawHub skills
            register(ClawHubSkill(clawHubManager))
            // CLI Tool Manager — register, configure, and run arbitrary CLI tools
            register(CliToolManagerSkill(this@NodeApp, termuxCommandRunner))
        }
    }

    // ── Update channel ────────────────────────────────────────────────

    /**
     * Returns the device's update channel ("alpha", "beta", or "stable") by
     * reading the system property `sys.update.channel` set by the Updater app.
     * Falls back to reading the Updater's device-protected SharedPreferences.
     * Defaults to "stable" if neither source is available.
     */
    private fun getUpdateChannel(): String {
        // Prefer the system property (set by ethOS Updater at update time)
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            val value = get.invoke(null, "sys.update.channel", "") as String
            if (value.isNotBlank()) return value
        } catch (_: Exception) { /* not available on non-ethOS */ }

        // Fallback: read the Updater's device-protected SharedPreferences
        try {
            val deviceCtx = createDeviceProtectedStorageContext()
            val prefs = deviceCtx.getSharedPreferences(
                "${deviceCtx.packageName}_preferences", MODE_PRIVATE
            )
            val value = prefs.getString("channel", null)
            if (!value.isNullOrBlank()) return value
        } catch (_: Exception) { /* prefs not accessible */ }

        return "stable"
    }

    // ── LLM providers ────────────────────────────────────────────────

    val anthropicClient: AnthropicClient by lazy {
        if (OsCapabilities.hasPrivilegedAccess) {
            AnthropicClient(
                userId = { securePrefs.walletAddress.value },
                signature = { securePrefs.walletSignature.value },
                channel = { getUpdateChannel() },
                provider = "ethOS Premium",
            )
        } else {
            AnthropicClient(
                apiKey = { securePrefs.apiKey.value },
                baseUrl = "https://openrouter.ai/api/v1/messages",
                provider = "ethOS Premium",
            )
        }
    }

    /** BYOK OpenRouter client — always uses the user's own API key. */
    private val openRouterClient: AnthropicClient by lazy {
        AnthropicClient(
            apiKey = { securePrefs.apiKey.value },
            baseUrl = "https://openrouter.ai/api/v1/messages",
            provider = "OpenRouter",
        )
    }

    private val tinfoilClient: TinfoilClient by lazy {
        TinfoilClient(apiKey = { securePrefs.tinfoilApiKey.value })
    }

    private val openAiNativeClient: OpenAiNativeClient by lazy {
        OpenAiNativeClient(apiKey = { securePrefs.openaiApiKey.value })
    }

    private val veniceClient: OpenAiNativeClient by lazy {
        OpenAiNativeClient(
            apiKey = { securePrefs.veniceApiKey.value },
            baseUrl = "https://api.venice.ai/api/v1/chat/completions",
        )
    }

    /** User-configured OpenAI-compatible endpoint (Ollama / LM Studio / vLLM / etc.).
     *  baseUrl + apiKey are re-read from SecurePrefs on every request, so the user
     *  can change them in Settings without restarting the app. */
    private val customClient: OpenAiNativeClient by lazy {
        OpenAiNativeClient(
            apiKey = { securePrefs.customApiKey.value },
            baseUrlProvider = { securePrefs.customBaseUrl.value },
        )
    }

    val tinfoilProxyClient: TinfoilProxyClient by lazy {
        TinfoilProxyClient(
            userId = { securePrefs.walletAddress.value },
            signature = { securePrefs.walletSignature.value },
            channel = { getUpdateChannel() },
        )
    }

    val llamaCpp: LlamaCpp by lazy { LlamaCpp() }

    val modelDownloadManager: ModelDownloadManager by lazy {
        ModelDownloadManager(this)
    }

    /** Multi-GGUF registry — backs the BYO-model picker in Local LLM settings. */
    val ggufRegistry: GgufRegistry by lazy { GgufRegistry(this) }

    private val claudeOauthClient: ClaudeOauthClient by lazy {
        ClaudeOauthClient(
            setupTokenProvider = { securePrefs.claudeOauthRefreshToken.value },
        )
    }

    private val chatGptOauthTokenManager: ChatGptOauthTokenManager by lazy {
        ChatGptOauthTokenManager(
            refreshTokenProvider = { securePrefs.chatgptOauthRefreshToken.value },
            accessTokenProvider  = { securePrefs.chatgptOauthAccessToken.value },
            expiresAtProvider    = { securePrefs.chatgptOauthExpiresAt.value },
            accountIdProvider    = { securePrefs.chatgptOauthAccountId.value },
            onTokensUpdated = { accessToken, expiresAt, accountId ->
                securePrefs.setChatGptOauthAccessToken(accessToken)
                securePrefs.setChatGptOauthExpiresAt(expiresAt)
                securePrefs.setChatGptOauthAccountId(accountId)
            },
            refreshTokenSetter = { rotated ->
                // Server rotated: keep the just-persisted access-token cache warm.
                securePrefs.silentlyUpdateChatGptOauthRefreshToken(rotated)
            },
        )
    }

    private val chatGptOauthClient: ChatGptOauthClient by lazy {
        ChatGptOauthClient(chatGptOauthTokenManager)
    }

    private val localLlmClient: LocalLlmClient by lazy {
        LocalLlmClient(
            llamaCpp = llamaCpp,
            modelDownloadManager = modelDownloadManager,
            selectedModelPathProvider = {
                ggufRegistry.find(securePrefs.selectedGgufFilename.value)?.absolutePath
            },
            configProvider = { securePrefs.currentLocalLlmConfig() },
        )
    }

    /**
     * Returns the appropriate [LlmClient] based on the user's selected provider.
     *
     * ethOS (privileged) devices default to the premium gateway
     * (`api.markushaas.com`) with wallet-signature billing via [ETHOS_PREMIUM].
     * When an ethOS user explicitly selects [OPEN_ROUTER] or [TINFOIL],
     * their own API key is used instead (BYOK).
     *
     * Non-privileged devices always use the user's own keys.
     */
    fun getLlmClient(): LlmClient = getLlmClientForProvider(securePrefs.selectedProvider.value, securePrefs.selectedModel.value)

    fun getMemoryAiLlmClient(): LlmClient {
        if (securePrefs.memoryAiUseSameModel.value) return getLlmClient()
        return getLlmClientForProvider(securePrefs.memoryAiProvider.value, securePrefs.memoryAiModel.value)
    }

    fun getMemoryAiModelId(): String {
        return if (securePrefs.memoryAiUseSameModel.value) securePrefs.selectedModel.value
        else securePrefs.memoryAiModel.value
    }

    fun getHeartbeatLlmClient(): LlmClient {
        if (securePrefs.heartbeatUseSameModel.value) return getLlmClient()
        return getLlmClientForProvider(securePrefs.heartbeatProvider.value, securePrefs.heartbeatModel.value)
    }

    fun getCompactionLlmClient(): LlmClient {
        if (securePrefs.compactionUseSameModel.value) return getLlmClient()
        return getLlmClientForProvider(securePrefs.compactionProvider.value, securePrefs.compactionModel.value)
    }

    fun getCompactionModelId(): String {
        return if (securePrefs.compactionUseSameModel.value) securePrefs.selectedModel.value
        else securePrefs.compactionModel.value
    }

    private fun getLlmClientForProvider(provider: LlmProvider, modelId: String): LlmClient {
        if (OsCapabilities.hasPrivilegedAccess) {
            return when (provider) {
                LlmProvider.ETHOS_PREMIUM -> {
                    val model = AnthropicModels.fromModelId(modelId)
                    if (model?.provider == LlmProvider.OPEN_ROUTER) anthropicClient
                    else tinfoilProxyClient
                }
                LlmProvider.OPEN_ROUTER -> openRouterClient
                LlmProvider.CLAUDE_OAUTH -> claudeOauthClient
                LlmProvider.OPENAI_OAUTH -> chatGptOauthClient
                LlmProvider.TINFOIL -> tinfoilClient
                LlmProvider.OPENAI -> openAiNativeClient
                LlmProvider.VENICE -> veniceClient
                LlmProvider.LOCAL -> localLlmClient
                LlmProvider.CUSTOM -> customClient
            }
        }
        return when (provider) {
            LlmProvider.ETHOS_PREMIUM -> anthropicClient
            LlmProvider.OPEN_ROUTER -> openRouterClient
            LlmProvider.CLAUDE_OAUTH -> claudeOauthClient
            LlmProvider.OPENAI_OAUTH -> chatGptOauthClient
            LlmProvider.TINFOIL -> tinfoilClient
            LlmProvider.OPENAI -> openAiNativeClient
            LlmProvider.VENICE -> veniceClient
            LlmProvider.LOCAL -> localLlmClient
            LlmProvider.CUSTOM -> customClient
        }
    }

    override fun onCreate() {
        super.onCreate()
        OsCapabilities.init(this)

        // Register SDK wakeup handler for non-ethOS devices (standard Android fallback).
        // On ethOS, the OS relays XMTP messages directly to HeartbeatBindingService via binder.
        if (!OsCapabilities.hasPrivilegedAccess) {
            MessengerSDK.setNewMessageWakeupHandler { ctx, count ->
                val intent = android.content.Intent(ctx, NodeForegroundService::class.java)
                    .putExtra(NodeForegroundService.EXTRA_XMTP_MESSAGE_COUNT, count)
                ctx.startForegroundService(intent)
            }
        }

        // HeartbeatBindingService is directBootAware, so this process may start
        // before the user unlocks the device.  Credential-encrypted storage
        // (filesDir, SharedPreferences, EncryptedSharedPreferences) is unavailable
        // until after first unlock, so defer all CE-dependent init.
        val userManager = getSystemService(android.os.UserManager::class.java)
        if (userManager?.isUserUnlocked == true) {
            onUserUnlocked()
        } else {
            Log.i(TAG, "Device not yet unlocked — deferring CE-dependent initialization")
            registerReceiver(
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                        unregisterReceiver(this)
                        onUserUnlocked()
                    }
                },
                android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED),
                android.content.Context.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /**
     * Runs all initialization that requires credential-encrypted (CE) storage.
     * Called immediately from [onCreate] when the device is already unlocked,
     * or deferred until [Intent.ACTION_USER_UNLOCKED] during Direct Boot.
     */
    private fun onUserUnlocked() {
        // Wire up the embedding provider for semantic memory search.
        // Only set if the user has an OpenRouter API key or is on ethOS,
        // since embeddings require an OpenAI-compatible endpoint.
        // Without an embedding provider, memory degrades to keyword-only search.
        if (OsCapabilities.hasPrivilegedAccess || securePrefs.apiKey.value.isNotBlank()) {
            memoryManager.setEmbeddingProvider(embeddingProvider)
        } else {
            Log.i(TAG, "No embedding provider available — memory will use keyword-only search")
        }

        // Wire ClawHub reload: when ClawHubManager installs/uninstalls a skill,
        // re-sync all ClawHub adapters into the NativeSkillRegistry.
        skillRegistry.onReloadRequested = { syncClawHubSkills() }

        // Load any previously installed ClawHub skills on startup
        syncClawHubSkills()

        // Load any previously created AI skills on startup
        syncAiSkills()

        // Load any previously created custom executable tools on startup
        syncCustomTools()

        // Publish compiled flows as tools, and keep them pinned to the app versions
        // they were compiled against.
        if (OsCapabilities.hasPrivilegedAccess) {
            appScope.launch {
                try {
                    seedFlowSkillEnabled()
                    flowRepository.reload()
                    registerFlowPackageReceiver()
                } catch (e: Exception) {
                    Log.w(TAG, "Flow registry init failed: ${e.message}", e)
                }
            }
        }

        // Pre-load the Whisper model into RAM so voice transcription is instant.
        // The Q5_1 model (~60 MB on disk, ~388 MB in RAM) stays resident for the process lifetime.
        whisperTranscriber.warmUp(appScope)

        // Refresh OpenRouter model registry so context windows and pricing are available
        appScope.launch {
            try {
                openRouterModelRegistry.refreshIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "OpenRouter model registry refresh failed: ${e.message}")
            }
        }

        // Discover extensions in the background and bridge them into the skill system
        appScope.launch {
            try {
                extensionEngine.discoverAndRegister()
                val adapters = extensionEngine.toSkillAdapters()
                for (adapter in adapters) {
                    nativeSkillRegistry.register(adapter)
                }
                Log.i(TAG, "Discovered ${adapters.size} extension(s)")
            } catch (e: Exception) {
                Log.w(TAG, "Extension discovery failed: ${e.message}", e)
            }
        }

        // Pre-fetch OpenRouter model list for model routing (background, non-blocking)
        if (securePrefs.modelRoutingEnabled.value) {
            appScope.launch {
                try {
                    openRouterModelRegistry.refreshIfNeeded()
                } catch (e: Exception) {
                    Log.w(TAG, "OpenRouter model registry pre-fetch failed: ${e.message}")
                }
            }
        }

        // One-time: migrate ethOS Premium default model from Kimi K2.5 to Claude Sonnet 4.6
        migrateEthosPremiumDefaultModel()

        // One-time: enable executive summary on OS level after OTA install
        ensureExecutiveSummaryOsFlag()

        // One-time backfill of agent tx history from existing session messages
        backfillAgentTxHistory()
    }

    /**
     * One-time migration for ethOS Premium devices: if the user's selected model
     * is still the old default (kimi-k2-5), switch it to Claude Sonnet 4.6.
     */
    private fun migrateEthosPremiumDefaultModel() {
        val key = "ethos_premium_model_migration_v1"
        if (securePrefs.getString(key) == "true") return
        try {
            if (OsCapabilities.hasPrivilegedAccess &&
                securePrefs.selectedModel.value == "kimi-k2-5"
            ) {
                securePrefs.setSelectedModel(AnthropicModels.CLAUDE_SONNET_4_6.modelId)
                Log.i(TAG, "Migrated ethOS Premium default model to Claude Sonnet 4.6")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to migrate ethOS Premium default model", e)
        } finally {
            securePrefs.putString(key, "true")
        }
    }

    /**
     * One-time migration: if the OS-level Settings.Secure flag
     * `executive_summary_enabled` has never been written, set it to enabled
     * and ensure the app preference matches. Runs once per install/OTA.
     */
    private fun ensureExecutiveSummaryOsFlag() {
        val key = "executive_summary_os_flag_init_done"
        if (securePrefs.getString(key) == "true") return
        try {
            val existing = android.provider.Settings.Secure.getString(
                contentResolver, "executive_summary_enabled"
            )
            if (existing == null) {
                // OS flag not set yet — enable it and sync the app pref
                securePrefs.setExecutiveSummaryEnabled(true)
                Log.i(TAG, "Executive summary enabled by default (first run after OTA)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check/set executive summary OS flag", e)
        } finally {
            securePrefs.putString(key, "true")
        }
    }

    private fun backfillAgentTxHistory() {
        appScope.launch(Dispatchers.IO) {
            val key = "agent_tx_backfill_done"
            if (securePrefs.getString(key) == "true") return@launch
            try {
                val agentToolNames = listOf(
                    "agent_send_transaction",
                    "agent_transfer_token",
                    "agent_send_native_token",
                    "agent_send_token",
                    "agent_swap",
                )
                val messages = sessionManager.getToolMessagesByNames(agentToolNames)
                var count = 0
                for (msg in messages) {
                    try {
                        val json = org.json.JSONObject(msg.content)
                        val userOpHash = json.optString("user_op_hash", "")
                        if (userOpHash.isBlank()) continue
                        val chainId = json.optInt("chain_id", 1)
                        val to = json.optString("to", "")
                        val amount = json.optString("amount", json.optString("sell_amount", ""))
                        val token = json.optString("symbol",
                            json.optString("token",
                                json.optString("sell_token", "RAW")))
                        agentTxRepository.save(
                            userOpHash = userOpHash,
                            chainId = chainId,
                            to = to,
                            amount = amount,
                            token = token,
                            toolName = msg.toolName ?: "unknown",
                        )
                        count++
                    } catch (_: Exception) {
                        // Skip malformed entries
                    }
                }
                Log.i(TAG, "Backfilled $count agent transaction(s) from session history")
            } catch (e: Exception) {
                Log.w(TAG, "Agent tx backfill failed: ${e.message}", e)
            } finally {
                securePrefs.putString(key, "true")
            }
        }
    }

    /**
     * Turn the flows skill on for a device that onboarded before it existed.
     *
     * `agent.enabledSkills` is written **once**, at onboarding, from the skill ids that
     * were registered at that moment — so on every phone already in the field the set
     * can never contain `flows`, and `skillEnabledCheck` would block every flow tool
     * forever. This is the shape of bug ethOS `CLAUDE.md` §0.2 is about: shipping a new
     * skill by OTA is not enough, the persisted state has to be migrated to know about
     * it.
     *
     * Seeded once and recorded, so a user who later turns flows off in Settings does not
     * find them back on after a reboot.
     */
    private fun seedFlowSkillEnabled() {
        if (securePrefs.getString(FLOWS_SEEDED_KEY) == "true") return
        val enabled = securePrefs.enabledSkills.value
        // An empty set means onboarding has not run yet; it will include flows itself.
        if (enabled.isNotEmpty() && FlowSkill.SKILL_ID !in enabled) {
            Log.i(TAG, "enabling the '${FlowSkill.SKILL_ID}' skill for a pre-existing install")
            securePrefs.setSkillEnabled(FlowSkill.SKILL_ID, true)
        }
        securePrefs.putString(FLOWS_SEEDED_KEY, "true")
    }

    /**
     * An app was replaced — every flow compiled against it is suspect until it proves
     * otherwise.
     *
     * Registered at runtime rather than in the manifest: `ACTION_PACKAGE_REPLACED` is
     * an implicit broadcast, and a manifest receiver for it is not guaranteed delivery
     * on modern Android. The broadcast is the fast path, not the mechanism — the real
     * check is the version pin the interpreter applies on every replay, so a missed
     * broadcast costs one aborted flow, never a blind replay into a changed UI.
     */
    private fun registerFlowPackageReceiver() {
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_PACKAGE_REPLACED)
            .apply { addDataScheme("package") }
        registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    appScope.launch {
                        try {
                            flowRepository.onPackageReplaced(pkg)
                        } catch (e: Exception) {
                            Log.w(TAG, "flow staleness update for $pkg failed: ${e.message}")
                        }
                    }
                }
            },
            filter,
            android.content.Context.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Sync ClawHub-installed skills into [NativeSkillRegistry].
     *
     * Removes stale ClawHub adapters, then registers fresh ones for every
     * SKILL.md currently on disk.  Skills that declare
     * `metadata.openclaw.execution.type: termux` are wrapped in a
     * [ClawHubTermuxSkillAdapter] (real tool invocations); all others get
     * the instruction-only [ClawHubSkillAdapter].
     *
     * Called on startup and after every install/uninstall/update via the
     * [SkillRegistry.onReloadRequested] callback.
     */
    private fun syncClawHubSkills() {
        // Remove all existing clawhub: adapters so we get a clean slate
        val stale = nativeSkillRegistry.getAll().filter { it.id.startsWith("clawhub:") }
        for (skill in stale) {
            nativeSkillRegistry.unregister(skill.id)
        }

        // Create fresh adapters — executable (Termux) or instruction-only
        val adapters = ClawHubTermuxSkillAdapter.createAdaptersForInstalledSkills(
            managedDir = clawHubSkillsDir,
            runner = termuxCommandRunner,
            sync = termuxSkillSync,
        )
        for (adapter in adapters) {
            nativeSkillRegistry.register(adapter)
        }

        // Clean up Termux-side files for skills that were uninstalled
        val activeTermuxSlugs = adapters
            .filterIsInstance<ClawHubTermuxSkillAdapter>()
            .map { it.slug }
            .toSet()
        appScope.launch {
            termuxSkillSync.cleanOrphans(activeTermuxSlugs)
        }

        val execCount = adapters.count { it is ClawHubTermuxSkillAdapter }
        val instrCount = adapters.size - execCount
        Log.i(TAG, "Synced ${adapters.size} ClawHub skill(s) " +
            "($execCount executable, $instrCount instruction-only)")
    }

    /**
     * Sync AI-created skills into [NativeSkillRegistry].
     *
     * Removes stale `ai:` adapters, then registers fresh ones for every
     * SKILL.md found in [aiSkillsDir]. All AI-created skills are
     * instruction-only (the agent reads and follows the SKILL.md body).
     *
     * Called on startup and after every create/delete via the
     * [SkillCreatorSkill.onSkillsChanged] callback.
     */
    private fun syncAiSkills() {
        val stale = nativeSkillRegistry.getAll().filter { it.id.startsWith("ai:") }
        for (skill in stale) {
            nativeSkillRegistry.unregister(skill.id)
        }

        val adapters = SkillCreatorSkill.createAdaptersFromDir(aiSkillsDir)
        for (adapter in adapters) {
            nativeSkillRegistry.register(adapter)
        }

        Log.i(TAG, "Synced ${adapters.size} AI-created skill(s)")
    }

    /**
     * Sync LLM-created custom executable tools into [NativeSkillRegistry].
     *
     * Removes stale `custom:` adapters, then registers fresh ones for every
     * tool JSON found in [customToolsDir].
     *
     * Called on startup and after every create/delete via the
     * [CustomToolCreatorSkill.onToolsChanged] callback.
     */
    private fun syncCustomTools() {
        val stale = nativeSkillRegistry.getAll().filter { it.id.startsWith("custom:") }
        for (skill in stale) {
            nativeSkillRegistry.unregister(skill.id)
        }

        val tools = customToolStore.loadAll()
        for (tool in tools) {
            nativeSkillRegistry.register(
                org.ethereumphone.andyclaw.skills.customtools.CustomToolAdapter(tool, customToolExecutor)
            )
        }

        Log.i(TAG, "Synced ${tools.size} custom tool(s)")
    }
}
