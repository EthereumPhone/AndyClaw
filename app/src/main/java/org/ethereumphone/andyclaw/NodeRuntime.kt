package org.ethereumphone.andyclaw

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.agent.AgentRunner
import org.ethereumphone.andyclaw.agent.AgentResponse
import org.ethereumphone.andyclaw.heartbeat.HeartbeatConfig
import org.ethereumphone.andyclaw.heartbeat.HeartbeatOutcome
import org.ethereumphone.andyclaw.heartbeat.HeartbeatRunner
import org.ethereumphone.andyclaw.llm.AnthropicClient
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.SkillRegistry
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

/**
 * The core AndyClaw runtime — an AI with a heartbeat.
 *
 * Manages the lifecycle of:
 * - [HeartbeatRunner]: periodic AI self-check loop (the "pulse")
 * - [SkillRegistry]: loadable/installable skill definitions
 * - [AgentRunner]: the AI agent that processes prompts
 *
 * This mirrors OpenClaw's gateway + agent runner architecture,
 * simplified for on-device Android execution.
 */
class NodeRuntime(private val context: Context) {

    companion object {
        private const val TAG = "NodeRuntime"
        private const val MANAGED_SKILLS_DIR = "skills"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The SKILL.md skill registry - load, query, and invoke skills. */
    val skillRegistry = SkillRegistry()

    /** The native skill registry for tool_use agent loop. */
    var nativeSkillRegistry: NativeSkillRegistry? = null

    /** The Anthropic client for the agent loop. */
    var anthropicClient: AnthropicClient? = null

    /** The agent runner - override this to provide a real LLM backend. */
    var agentRunner: AgentRunner = NoOpAgentRunner()
        set(value) {
            field = value
            heartbeatRunner = createHeartbeatRunner()
        }

    /** Current heartbeat configuration. */
    var heartbeatConfig = HeartbeatConfig()
        set(value) {
            field = value
            heartbeatRunner?.updateConfig(value)
        }

    /** Callback for heartbeat alerts (actionable content from the AI). */
    var onHeartbeatAlert: ((text: String) -> Unit)? = null

    /** Callback for heartbeat errors. */
    var onHeartbeatError: ((error: String) -> Unit)? = null

    private var heartbeatRunner: HeartbeatRunner? = null
    private var initialized = false

    /** The workspace directory where HEARTBEAT.md and skill directories live. */
    val workspaceDir: File
        get() = context.filesDir

    /** The managed skills directory (~/.andyclaw/skills equivalent). */
    val managedSkillsDir: File
        get() = File(context.filesDir, MANAGED_SKILLS_DIR)

    /**
     * Initialize the runtime: load skills and prepare the heartbeat.
     * Call this once after setting up the agentRunner.
     */
    fun initialize() {
        if (initialized) return
        initialized = true

        // Ensure directories exist
        managedSkillsDir.mkdirs()

        // Load skills from available directories
        loadSkills()

        // Create heartbeat runner
        heartbeatRunner = createHeartbeatRunner()

        Log.i(TAG, "Runtime initialized: ${skillRegistry.skills.size} skills loaded")
    }

    /**
     * Start the heartbeat loop. The AI will periodically check HEARTBEAT.md
     * and relay actionable content via [onHeartbeatAlert].
     */
    fun startHeartbeat() {
        heartbeatRunner?.start()
        Log.i(TAG, "Heartbeat started")
    }

    /**
     * Stop the heartbeat loop.
     */
    fun stopHeartbeat() {
        heartbeatRunner?.stop()
        Log.i(TAG, "Heartbeat stopped")
    }

    /**
     * Trigger an immediate heartbeat run outside the normal schedule.
     */
    fun requestHeartbeatNow() {
        heartbeatRunner?.requestNow()
    }

    /**
     * Reload skills from all configured directories.
     */
    fun loadSkills(
        bundledSkillsDir: File? = null,
        extraDirs: List<File> = emptyList(),
    ) {
        skillRegistry.load(
            workspaceDir = workspaceDir,
            managedSkillsDir = managedSkillsDir,
            bundledSkillsDir = bundledSkillsDir,
            extraDirs = extraDirs,
        )
    }

    /**
     * Install a skill from a directory into the managed skills directory.
     */
    fun installSkill(skillDir: File): Boolean {
        val result = skillRegistry.installSkill(skillDir, managedSkillsDir)
        if (result) loadSkills() // Reload after install
        return result
    }

    /**
     * Uninstall a skill by name from the managed skills directory.
     */
    fun uninstallSkill(skillName: String): Boolean {
        val result = skillRegistry.uninstallSkill(skillName, managedSkillsDir)
        if (result) loadSkills() // Reload after uninstall
        return result
    }

    /**
     * Create an AgentLoop instance for the tool_use flow.
     * Returns null if no API key is configured.
     */
    fun createAgentLoop(
        model: AnthropicModels = AnthropicModels.MINIMAX_M25,
        aiName: String? = null,
        userStory: String? = null,
        enabledSkillIds: Set<String> = emptySet(),
    ): AgentLoop? {
        val client = anthropicClient ?: return null
        val registry = nativeSkillRegistry ?: return null
        val tier = OsCapabilities.currentTier()
        return AgentLoop(client, registry, tier, enabledSkillIds, model, aiName, userStory)
    }

    /**
     * Process a user message/command. Handles /skill invocations and
     * regular messages sent to the agent.
     */
    suspend fun processMessage(message: String): AgentResponse {
        // Check for skill command invocation
        val invocation = skillRegistry.resolveCommandInvocation(message)
        if (invocation != null) {
            val skillContent = skillRegistry.getSkillContent(invocation.entry.skill.name)
            val prompt = buildString {
                append("The user invoked the skill '${invocation.entry.skill.name}'.")
                if (invocation.args != null) {
                    append(" Arguments: ${invocation.args}")
                }
                if (skillContent != null) {
                    append("\n\nFollow the skill instructions below:\n\n$skillContent")
                }
            }
            return agentRunner.run(
                prompt = prompt,
                skillsPrompt = skillRegistry.buildPrompt(),
            )
        }

        // Regular message - run through the agent with skills context
        return agentRunner.run(
            prompt = message,
            skillsPrompt = skillRegistry.buildPrompt(),
        )
    }

    private fun createHeartbeatRunner(): HeartbeatRunner {
        return HeartbeatRunner(
            scope = scope,
            agentRunner = agentRunner,
            workspaceDir = workspaceDir.absolutePath,
            onResult = { result ->
                when (result.outcome) {
                    HeartbeatOutcome.ALERT -> {
                        Log.i(TAG, "Heartbeat alert: ${result.text}")
                        result.text?.let { onHeartbeatAlert?.invoke(it) }
                    }
                    HeartbeatOutcome.OK -> {
                        Log.d(TAG, "Heartbeat OK")
                    }
                    HeartbeatOutcome.SKIPPED -> {
                        Log.d(TAG, "Heartbeat skipped")
                    }
                    HeartbeatOutcome.ERROR -> {
                        Log.w(TAG, "Heartbeat error: ${result.error}")
                        result.error?.let { onHeartbeatError?.invoke(it) }
                    }
                }
            },
        )
    }
}

/**
 * A no-op agent runner that returns a placeholder response.
 * Replace with a real implementation that calls an LLM API.
 */
private class NoOpAgentRunner : AgentRunner {
    override suspend fun run(
        prompt: String,
        systemPrompt: String?,
        skillsPrompt: String?,
    ): AgentResponse {
        return AgentResponse(
            text = "HEARTBEAT_OK",
            isError = false,
        )
    }
}
