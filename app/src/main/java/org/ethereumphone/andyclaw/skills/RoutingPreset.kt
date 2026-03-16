package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.Serializable

/**
 * A named routing preset that defines which skills are always included
 * (bypassing the LLM router) and optional per-skill tool restrictions.
 *
 * Stock presets ([isStock] = true) can be reverted to their defaults but
 * not deleted. Custom presets ([isStock] = false) can be freely deleted.
 */
@Serializable
data class RoutingPreset(
    /** Unique identifier (UUID for custom, "stock_*" for built-ins). */
    val id: String,
    /** Human-readable name shown in the UI. */
    val name: String,
    /** Stock presets can be reverted to defaults; custom ones can be deleted. */
    val isStock: Boolean,
    /** Skill IDs always included regardless of the router's decision. */
    val coreSkillIds: Set<String>,
    /** Skill IDs always included on the PRIVILEGED (dGEN1) tier. */
    val coreDgen1SkillIds: Set<String>,
    /**
     * Per-skill tool allow-list. When a skill ID appears as a key, only the
     * listed tool names are included for that skill instead of all its tools.
     * Example: `"wallet" to setOf("get_user_wallet_address")`.
     */
    val alwaysIncludeTools: Map<String, Set<String>> = emptyMap(),
) {
    companion object {
        const val defaultPresetId: String = "stock_minimal"

        /** Returns the three built-in stock presets. */
        fun defaults(): List<RoutingPreset> = listOf(
            RoutingPreset(
                id = "stock_full_llm",
                name = "Full LLM Routing",
                isStock = true,
                coreSkillIds = emptySet(),
                coreDgen1SkillIds = emptySet(),
                alwaysIncludeTools = emptyMap(),
            ),
            RoutingPreset(
                id = "stock_minimal",
                name = "Minimal Core",
                isStock = true,
                coreSkillIds = setOf("code_execution", "memory"),
                coreDgen1SkillIds = setOf("terminal_text"),
                alwaysIncludeTools = emptyMap(),
            ),
            RoutingPreset(
                id = "stock_expanded",
                name = "Expanded Core",
                isStock = true,
                coreSkillIds = setOf(
                    "device_info",
                    "apps",
                    "code_execution",
                    "shell",
                    "memory",
                    "clipboard",
                    "settings",
                ),
                coreDgen1SkillIds = setOf(
                    "led_matrix",
                    "terminal_text",
                    "agent_display",
                ),
                alwaysIncludeTools = emptyMap(),
            ),
        )
    }
}
