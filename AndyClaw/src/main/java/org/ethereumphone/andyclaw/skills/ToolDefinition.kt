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
    /**
     * Which rung of `agent-os-design.md` §3's execution ladder this tool sits on.
     *
     * 0 native API · 1 intents/AppFunctions · 2 notification RemoteInput ·
     * 3 compiled flow · 4 VLM discovery on the shadow display.
     *
     * "Never skip a rung to reach a lower one" is enforced, not advised: the engine's
     * `routeGateCheck` blocks the display when a lower-numbered route exists for the
     * same app. `null` means the tool is not part of the ladder — most tools are not,
     * because they are not another way of doing the same thing.
     */
    val rung: Int? = null,
    /**
     * Android packages this tool is a route *into*, when it is one. Only meaningful
     * alongside [rung]: it is what lets the engine say "there is a rung-0 way to do
     * this in that app" without asking a model.
     */
    val targetPackages: List<String> = emptyList(),
)
