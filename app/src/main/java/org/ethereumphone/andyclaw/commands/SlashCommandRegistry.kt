package org.ethereumphone.andyclaw.commands

/**
 * Central registry of every slash command the app supports.
 *
 * To add a new command, just add an entry here and handle it in
 * [SlashCommandExecutor]. The UI picks up the list automatically.
 */
object SlashCommandRegistry {

    // ── Toggle commands ─────────────────────────────────────────────────
    private val toggles = listOf(
        SlashCommand.Toggle(
            id = "yolo",
            label = "YOLO Mode",
            description = "Toggle auto-approve for all tool executions",
        ),
        SlashCommand.Toggle(
            id = "safety",
            label = "Safety",
            description = "Toggle security checks on inbound/outbound data",
        ),
        SlashCommand.Toggle(
            id = "notify",
            label = "Auto-Reply",
            description = "Toggle agent auto-reply to notifications",
        ),
        SlashCommand.Toggle(
            id = "memory",
            label = "Memory",
            description = "Toggle automatic memory storage",
        ),
    )

    // ── Cycle commands ──────────────────────────────────────────────────
    private val cycles = listOf(
        SlashCommand.Cycle(
            id = "provider",
            label = "Provider",
            description = "Cycle through LLM providers",
        ),
        SlashCommand.Cycle(
            id = "heartbeat",
            label = "Heartbeat",
            description = "Cycle heartbeat interval: OFF → 15m → 30m → 1h → 2h",
        ),
    )

    // ── Action commands ─────────────────────────────────────────────────
    private val actions = listOf(
        SlashCommand.Action(
            id = "model",
            label = "Model",
            description = "Open model selector in settings",
        ),
        SlashCommand.Action(
            id = "compact",
            label = "Compact",
            description = "Summarize older messages to free up context window",
        ),
        SlashCommand.Action(
            id = "clear",
            label = "Clear",
            description = "Start a fresh conversation",
        ),
        SlashCommand.Action(
            id = "help",
            label = "Help",
            description = "List all available slash commands",
        ),
        SlashCommand.Action(
            id = "reindex",
            label = "Reindex",
            description = "Trigger memory reindexing",
        ),
        SlashCommand.Action(
            id = "settings",
            label = "Settings",
            description = "Open the full settings screen",
        ),
    )

    /** Every registered command, in display order. */
    val all: List<SlashCommand> = toggles + cycles + actions

    /** Lookup by id (case-insensitive). */
    fun find(id: String): SlashCommand? =
        all.find { it.id.equals(id, ignoreCase = true) }

    /** All commands whose id starts with [prefix] (for autocomplete). */
    fun matching(prefix: String): List<SlashCommand> {
        if (prefix.isBlank()) return all
        val lower = prefix.lowercase()
        return all.filter { it.id.startsWith(lower) }
    }
}
