package org.ethereumphone.andyclaw.skills

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val requiresApproval: Boolean = false,
    val requiredPermissions: List<String> = emptyList(),
    /**
     * Capability phrase for keyword search discovery (3-10 words, no trailing period).
     * Prefer terms not already in [name] — e.g. "jupyter notebook cell editing" for a
     * tool named `notebook_edit`. Indexed with higher weight than [description].
     */
    val searchHint: String? = null,
    /**
     * What running this tool does to the world — see [ToolEffect].
     *
     * `null` means "not declared". It is **not** a synonym for [ToolEffect.READ]:
     * `ToolEffects.of()` resolves an undeclared tool through the seed table and then
     * falls back to [ToolEffect.IRREVERSIBLE], so forgetting to classify a tool makes
     * it more restricted, never less.
     */
    val effect: ToolEffect? = null,
)
