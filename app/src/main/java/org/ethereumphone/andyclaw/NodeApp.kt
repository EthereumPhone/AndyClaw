package org.ethereumphone.andyclaw

import android.app.Application
import android.util.Log
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
import org.ethereumphone.andyclaw.llm.LlamaCpp
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.llm.LocalLlmClient
import org.ethereumphone.andyclaw.llm.ModelDownloadManager
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
import org.ethereumphone.andyclaw.skills.builtin.ConnectivitySkill
import org.ethereumphone.andyclaw.skills.builtin.DevicePowerSkill
import org.ethereumphone.andyclaw.skills.builtin.PackageManagerSkill
import org.ethereumphone.andyclaw.skills.builtin.PhoneSkill
import org.ethereumphone.andyclaw.skills.builtin.ScreenTimeSkill
import org.ethereumphone.andyclaw.skills.builtin.StorageSkill
import org.ethereumphone.andyclaw.skills.builtin.ReminderSkill
import org.ethereumphone.andyclaw.skills.builtin.TermuxSkill
import org.ethereumphone.andyclaw.skills.builtin.WalletSkill
import org.ethereumphone.andyclaw.skills.builtin.AuroraStoreSkill
import org.ethereumphone.andyclaw.skills.builtin.LocationSkill
import org.ethereumphone.andyclaw.skills.builtin.SkillCreatorSkill
import org.ethereumphone.andyclaw.skills.builtin.SkillRefinementSkill
import org.ethereumphone.andyclaw.skills.builtin.TelegramSkill
import org.ethereumphone.andyclaw.skills.builtin.WebSearchSkill
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.onboarding.UserStoryManager
import org.ethereumhpone.messengersdk.MessengerSDK

class NodeApp : Application() {

    companion object {
        private const val TAG = "NodeApp"
        private const val DEFAULT_AGENT_ID = "default"
    }

    /** Application-scoped coroutine scope for background initialisation. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val runtime: NodeRuntime by lazy { NodeRuntime(this) }
    val securePrefs: SecurePrefs by lazy { SecurePrefs(this) }
    val userStoryManager: UserStoryManager by lazy { UserStoryManager(this) }
    val sessionManager: SessionManager by lazy { SessionManager(this) }
    val heartbeatLogStore: HeartbeatLogStore by lazy { HeartbeatLogStore(filesDir) }

    var permissionRequester: PermissionRequester? = null

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

    /** Skill registry for SKILL.md-based skills (ClawHub + local). */
    val skillRegistry: SkillRegistry by lazy { SkillRegistry() }

    val clawHubManager: ClawHubManager by lazy {
        ClawHubManager(
            managedSkillsDir = clawHubSkillsDir,
            skillRegistry = skillRegistry,
        )
    }

    // ── Skills ─────────────────────────────────────────────────────────

    val nativeSkillRegistry: NativeSkillRegistry by lazy {
        NativeSkillRegistry().apply {
            // Day 1 base skills
            register(DeviceInfoSkill(this@NodeApp))
            register(ClipboardSkill(this@NodeApp))
            register(ShellSkill(this@NodeApp))
            register(FileSystemSkill(this@NodeApp))
            // Day 2 tier-aware skills
            register(ContactsSkill(this@NodeApp))
            register(AppsSkill(this@NodeApp))
            register(NotificationSkill(this@NodeApp))
            register(SettingsSkill(this@NodeApp))
            register(CameraSkill(this@NodeApp))
            register(SMSSkill(this@NodeApp))
            // ethOS wallet skill
            register(WalletSkill(this@NodeApp))
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
            register(CodeExecutionSkill(this@NodeApp))
            // Reminders — schedule notifications at specific times
            register(ReminderSkill(this@NodeApp))
            // Termux integration — full Linux environment via Termux app
            register(TermuxSkill(this@NodeApp))
            // Aurora Store — download and install apps from Play Store
            register(AuroraStoreSkill(this@NodeApp))
            // Web Search — search the web and fetch webpage content
            register(WebSearchSkill(this@NodeApp))
            // Location — GPS position, nearby places, maps & navigation
            register(LocationSkill(this@NodeApp))
            // Telegram — send proactive messages to the user via Telegram bot
            register(TelegramSkill(
                chatStore = telegramChatStore,
                botToken = { securePrefs.telegramBotToken.value },
                botEnabled = { securePrefs.telegramBotEnabled.value },
            ))
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
        }
    }

    // ── LLM providers ────────────────────────────────────────────────

    val anthropicClient: AnthropicClient by lazy {
        if (OsCapabilities.hasPrivilegedAccess) {
            AnthropicClient(
                userId = { securePrefs.walletAddress.value },
                signature = { securePrefs.walletSignature.value },
            )
        } else {
            AnthropicClient(
                apiKey = { securePrefs.apiKey.value },
                baseUrl = "https://openrouter.ai/api/v1/messages",
            )
        }
    }

    private val tinfoilClient: TinfoilClient by lazy {
        TinfoilClient(apiKey = { securePrefs.tinfoilApiKey.value })
    }

    val tinfoilProxyClient: TinfoilProxyClient by lazy {
        TinfoilProxyClient(
            userId = { securePrefs.walletAddress.value },
            signature = { securePrefs.walletSignature.value },
        )
    }

    val llamaCpp: LlamaCpp by lazy { LlamaCpp() }

    val modelDownloadManager: ModelDownloadManager by lazy {
        ModelDownloadManager(this)
    }

    private val localLlmClient: LocalLlmClient by lazy {
        LocalLlmClient(llamaCpp, modelDownloadManager)
    }

    /**
     * Returns the appropriate [LlmClient] based on the user's selected provider.
     *
     * ethOS (privileged) devices route through the premium gateway
     * (`api.markushaas.com`) with wallet-signature billing.
     * Non-privileged devices use the provider selected in preferences.
     */
    fun getLlmClient(): LlmClient {
        if (OsCapabilities.hasPrivilegedAccess) {
            return when (securePrefs.selectedProvider.value) {
                LlmProvider.OPEN_ROUTER -> anthropicClient
                LlmProvider.TINFOIL -> tinfoilProxyClient
                LlmProvider.LOCAL -> tinfoilProxyClient // LOCAL not supported on ethOS
            }
        }
        return when (securePrefs.selectedProvider.value) {
            LlmProvider.OPEN_ROUTER -> anthropicClient
            LlmProvider.TINFOIL -> tinfoilClient
            LlmProvider.LOCAL -> localLlmClient
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
}
