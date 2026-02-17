package org.ethereumphone.andyclaw

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.extensions.ExtensionEngine
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubManager
import org.ethereumphone.andyclaw.extensions.clawhub.ClawHubSkillAdapter
import org.ethereumphone.andyclaw.extensions.toSkillAdapters
import org.ethereumphone.andyclaw.llm.AnthropicClient
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

    var permissionRequester: PermissionRequester? = null

    // ── Memory subsystem ───────────────────────────────────────────────

    val memoryManager: MemoryManager by lazy {
        MemoryManager(this, agentId = DEFAULT_AGENT_ID)
    }

    private fun resolveApiKey(): String {
        val prefsKey = securePrefs.apiKey.value
        return prefsKey.ifEmpty { BuildConfig.OPENROUTER_API_KEY }
    }

    private val embeddingProvider: OpenAiEmbeddingProvider by lazy {
        OpenAiEmbeddingProvider(apiKey = ::resolveApiKey)
    }

    // ── Extension subsystem ────────────────────────────────────────────

    val extensionEngine: ExtensionEngine by lazy {
        ExtensionEngine(this)
    }

    // ── ClawHub subsystem ───────────────────────────────────────────────

    /** Directory where ClawHub-installed skills are stored. */
    val clawHubSkillsDir by lazy {
        java.io.File(filesDir, "clawhub-skills").also { it.mkdirs() }
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
        }
    }

    val anthropicClient: AnthropicClient by lazy {
        AnthropicClient(apiKey = ::resolveApiKey)
    }

    override fun onCreate() {
        super.onCreate()
        OsCapabilities.init(this)

        // Register SDK wakeup handler so Messenger can wake us on new XMTP messages
        MessengerSDK.setNewMessageWakeupHandler { ctx, count ->
            val intent = android.content.Intent(ctx, NodeForegroundService::class.java)
                .putExtra(NodeForegroundService.EXTRA_XMTP_MESSAGE_COUNT, count)
            ctx.startForegroundService(intent)
        }

        // Wire up the embedding provider for semantic memory search
        memoryManager.setEmbeddingProvider(embeddingProvider)

        // Wire ClawHub reload: when ClawHubManager installs/uninstalls a skill,
        // re-sync all ClawHub adapters into the NativeSkillRegistry.
        skillRegistry.onReloadRequested = { syncClawHubSkills() }

        // Load any previously installed ClawHub skills on startup
        syncClawHubSkills()

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
     * SKILL.md currently on disk. Called on startup and after every
     * install/uninstall/update via the [SkillRegistry.onReloadRequested] callback.
     */
    private fun syncClawHubSkills() {
        // Remove all existing clawhub: adapters so we get a clean slate
        val stale = nativeSkillRegistry.getAll().filter { it.id.startsWith("clawhub:") }
        for (skill in stale) {
            nativeSkillRegistry.unregister(skill.id)
        }

        // Create fresh adapters from whatever is on disk right now
        val adapters = ClawHubSkillAdapter.fromInstalledSkills(clawHubSkillsDir)
        for (adapter in adapters) {
            nativeSkillRegistry.register(adapter)
        }
        Log.i(TAG, "Synced ${adapters.size} ClawHub skill(s) into NativeSkillRegistry")
    }
}
