package org.ethereumphone.andyclaw.safety

import android.util.Log
import org.ethereumphone.andyclaw.skills.ToolDefinition

/**
 * Trust level for a skill, mirroring IronClaw's trust model.
 *
 * - [TRUSTED]: User-created or built-in skills — full tool access.
 * - [INSTALLED]: Downloaded from ClawHub registry — read-only tools only.
 */
enum class SkillTrust { TRUSTED, INSTALLED }

data class AttenuationResult(
    val tools: List<ToolDefinition>,
    val minimumTrust: SkillTrust,
    val removedTools: List<String>,
    val explanation: String,
)

/**
 * When safety is enabled and an INSTALLED (ClawHub) skill is active, restrict
 * the tool list to read-only tools only. This prevents a malicious ClawHub skill
 * from tricking the LLM into running destructive commands — the LLM never sees
 * dangerous tools in its tool list when untrusted skills are in the prompt.
 *
 * When safety is disabled, all tools are always available (YOLO).
 */
object ToolAttenuation {

    private const val TAG = "ToolAttenuation"

    val READ_ONLY_TOOLS = setOf(
        "memory_search", "memory_read", "memory_tree",
        "get_device_info", "get_clipboard",
        "clawhub_search", "clawhub_list_installed",
        "list_dir", "read_file",
        "web_search", "fetch_url",
    )

    val PROTECTED_TOOL_NAMES = setOf(
        "run_shell_command", "write_file", "delete_file",
        "send_sms", "make_call", "send_message",
        "send_transaction", "get_wallet_info",
        "install_app", "uninstall_app",
        "take_screenshot", "record_screen",
        "memory_write", "memory_delete",
        "create_reminder", "set_alarm", "create_cronjob", "delete_cronjob",
        "clawhub_install", "clawhub_uninstall",
        "execute_code", "run_termux_command",
        "agent_display_create", "agent_display_click", "agent_display_type",
        "set_led_pattern", "set_led_color",
        "toggle_wifi", "toggle_bluetooth", "toggle_mobile_data",
        "set_volume", "set_brightness",
    )

    fun attenuate(
        tools: List<ToolDefinition>,
        activeSkillTrusts: List<SkillTrust>,
        safetyEnabled: Boolean,
    ): AttenuationResult {
        if (!safetyEnabled || activeSkillTrusts.isEmpty()) {
            return AttenuationResult(
                tools = tools,
                minimumTrust = SkillTrust.TRUSTED,
                removedTools = emptyList(),
                explanation = "All tools available (safety disabled or no active skills)",
            )
        }

        val allTrusted = activeSkillTrusts.all { it == SkillTrust.TRUSTED }
        if (allTrusted) {
            return AttenuationResult(
                tools = tools,
                minimumTrust = SkillTrust.TRUSTED,
                removedTools = emptyList(),
                explanation = "All tools available (all active skills are trusted)",
            )
        }

        val allowed = tools.filter { it.name in READ_ONLY_TOOLS }
        val removed = tools.filter { it.name !in READ_ONLY_TOOLS }.map { it.name }

        Log.i(TAG, "Attenuated tools: kept ${allowed.size}, removed ${removed.size} " +
                "(installed skill active)")

        return AttenuationResult(
            tools = allowed,
            minimumTrust = SkillTrust.INSTALLED,
            removedTools = removed,
            explanation = "[Safety] Tool access restricted to read-only because an installed " +
                    "(ClawHub) skill is active. Remove untrusted skills or disable safety mode " +
                    "in Settings to restore full access.",
        )
    }

    /**
     * Check if a tool name is protected from being shadowed by external registrations.
     */
    fun isProtected(toolName: String): Boolean = toolName in PROTECTED_TOOL_NAMES
}
