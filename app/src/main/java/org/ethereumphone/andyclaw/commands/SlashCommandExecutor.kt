package org.ethereumphone.andyclaw.commands

import org.ethereumphone.andyclaw.SecurePrefs
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.navigation.Routes
import org.ethereumphone.andyclaw.skills.RoutingPreset

/**
 * Executes slash commands against [SecurePrefs] and returns a
 * [SlashCommandResult] describing what happened.
 *
 * Stateless — call [execute] with the raw user input and optional
 * cycle selection index.
 */
class SlashCommandExecutor(
    private val prefs: SecurePrefs,
    private val memoryManager: MemoryManager,
) {

    // ── Heartbeat interval steps ────────────────────────────────────────
    private val heartbeatSteps = listOf(
        -1 to "OFF",
        15 to "15 min",
        30 to "30 min",
        60 to "1 hour",
        120 to "2 hours",
    )

    /**
     * Try to parse [input] as a slash command and execute it.
     *
     * @return a [SlashCommandResult] if [input] matched a command, or
     *         `null` if [input] is not a slash command (should be sent to
     *         the LLM as a normal message).
     */
    fun execute(input: String): SlashCommandResult? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null

        val parts = trimmed.removePrefix("/").split(" ", limit = 2)
        val commandId = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim()

        val command = SlashCommandRegistry.find(commandId)
            ?: return SlashCommandResult.Error("Unknown command: /$commandId. Type /help for a list.")

        return when (command) {
            is SlashCommand.Toggle -> executeToggle(command)
            is SlashCommand.Cycle -> executeCycle(command, arg)
            is SlashCommand.Action -> executeAction(command)
        }
    }

    /**
     * Select an option for a cycle command by index.
     * Called when the user picks from the presented list.
     */
    fun selectCycleOption(commandId: String, index: Int): SlashCommandResult {
        return when (commandId) {
            "model" -> selectModel(index)
            "provider" -> selectProvider(index)
            "heartbeat" -> selectHeartbeat(index)
            "preset" -> selectPreset(index)
            else -> SlashCommandResult.Error("Unknown cycle command: $commandId")
        }
    }

    // ── Toggle implementations ──────────────────────────────────────────

    private fun executeToggle(cmd: SlashCommand.Toggle): SlashCommandResult {
        return when (cmd.id) {
            "yolo" -> {
                val newVal = !prefs.yoloMode.value
                prefs.setYoloMode(newVal)
                SlashCommandResult.Toggled(
                    message = "YOLO mode → ${onOff(newVal)}",
                    settingId = cmd.id,
                    newValue = newVal,
                )
            }
            "safety" -> {
                val newVal = !prefs.safetyEnabled.value
                prefs.setSafetyEnabled(newVal)
                SlashCommandResult.Toggled(
                    message = "Safety checks → ${onOff(newVal)}",
                    settingId = cmd.id,
                    newValue = newVal,
                )
            }
            "notify" -> {
                val newVal = !prefs.notificationReplyEnabled.value
                prefs.setNotificationReplyEnabled(newVal)
                SlashCommandResult.Toggled(
                    message = "Auto-reply → ${onOff(newVal)}",
                    settingId = cmd.id,
                    newValue = newVal,
                )
            }
            "memory" -> {
                val current = prefs.getString("memory.autoStore") != "false"
                val newVal = !current
                prefs.putString("memory.autoStore", if (newVal) "true" else "false")
                SlashCommandResult.Toggled(
                    message = "Memory storage → ${onOff(newVal)}",
                    settingId = cmd.id,
                    newValue = newVal,
                )
            }
            "routing" -> {
                val newVal = !prefs.smartRoutingEnabled.value
                prefs.setSmartRoutingEnabled(newVal)
                SlashCommandResult.Toggled(
                    message = "Smart routing → ${onOff(newVal)}",
                    settingId = cmd.id,
                    newValue = newVal,
                )
            }
            else -> SlashCommandResult.Error("Unknown toggle: ${cmd.id}")
        }
    }

    // ── Cycle implementations ───────────────────────────────────────────

    private fun executeCycle(cmd: SlashCommand.Cycle, arg: String?): SlashCommandResult {
        return when (cmd.id) {
            "model" -> {
                val models = AnthropicModels.forProvider(prefs.selectedProvider.value)
                val currentIdx = models.indexOfFirst { it.modelId == prefs.selectedModel.value }
                    .coerceAtLeast(0)
                if (arg != null) {
                    val idx = arg.toIntOrNull()
                    if (idx != null) return selectModel(idx)
                }
                SlashCommandResult.CycleOptions(
                    message = "Select a model (current: ${models.getOrNull(currentIdx)?.modelId ?: "unknown"}):",
                    commandId = cmd.id,
                    options = models.map { it.modelId },
                    currentIndex = currentIdx,
                )
            }
            "provider" -> {
                val providers = LlmProvider.entries
                val currentIdx = providers.indexOf(prefs.selectedProvider.value)
                    .coerceAtLeast(0)
                if (arg != null) {
                    val idx = arg.toIntOrNull()
                    if (idx != null) return selectProvider(idx)
                }
                SlashCommandResult.CycleOptions(
                    message = "Select a provider (current: ${prefs.selectedProvider.value.displayName}):",
                    commandId = cmd.id,
                    options = providers.map { it.displayName },
                    currentIndex = currentIdx,
                )
            }
            "heartbeat" -> {
                val currentMinutes = prefs.heartbeatIntervalMinutes.value
                val currentIdx = heartbeatSteps.indexOfFirst { it.first == currentMinutes }
                    .coerceAtLeast(0)
                if (arg != null) {
                    val idx = arg.toIntOrNull()
                    if (idx != null) return selectHeartbeat(idx)
                }
                SlashCommandResult.CycleOptions(
                    message = "Select heartbeat interval (current: ${heartbeatSteps[currentIdx].second}):",
                    commandId = cmd.id,
                    options = heartbeatSteps.map { it.second },
                    currentIndex = currentIdx,
                )
            }
            "preset" -> {
                val presets = prefs.routingPresets.value
                val currentIdx = presets.indexOfFirst { it.id == prefs.selectedRoutingPresetId.value }
                    .coerceAtLeast(0)
                if (arg != null) {
                    val idx = arg.toIntOrNull()
                    if (idx != null) return selectPreset(idx)
                }
                SlashCommandResult.CycleOptions(
                    message = "Select routing preset (current: ${presets.getOrNull(currentIdx)?.name ?: "unknown"}):",
                    commandId = cmd.id,
                    options = presets.map { it.name },
                    currentIndex = currentIdx,
                )
            }
            else -> SlashCommandResult.Error("Unknown cycle command: ${cmd.id}")
        }
    }

    private fun selectModel(index: Int): SlashCommandResult {
        val models = AnthropicModels.forProvider(prefs.selectedProvider.value)
        if (index !in models.indices) {
            return SlashCommandResult.Error("Invalid model index: $index (0..${models.lastIndex})")
        }
        val chosen = models[index]
        prefs.setSelectedModel(chosen.modelId)
        return SlashCommandResult.CycleSelected(
            message = "Model → ${chosen.modelId}",
            commandId = "model",
            selectedOption = chosen.modelId,
        )
    }

    private fun selectProvider(index: Int): SlashCommandResult {
        val providers = LlmProvider.entries
        if (index !in providers.indices) {
            return SlashCommandResult.Error("Invalid provider index: $index (0..${providers.lastIndex})")
        }
        val chosen = providers[index]
        prefs.setSelectedProvider(chosen)
        // Also set a sensible default model for the new provider
        val defaultModel = AnthropicModels.defaultForProvider(chosen)
        prefs.setSelectedModel(defaultModel.modelId)
        return SlashCommandResult.CycleSelected(
            message = "Provider → ${chosen.displayName} (model → ${defaultModel.modelId})",
            commandId = "provider",
            selectedOption = chosen.displayName,
        )
    }

    private fun selectHeartbeat(index: Int): SlashCommandResult {
        if (index !in heartbeatSteps.indices) {
            return SlashCommandResult.Error("Invalid heartbeat index: $index (0..${heartbeatSteps.lastIndex})")
        }
        val (minutes, label) = heartbeatSteps[index]
        prefs.setHeartbeatIntervalMinutes(minutes)
        return SlashCommandResult.CycleSelected(
            message = "Heartbeat interval → $label",
            commandId = "heartbeat",
            selectedOption = label,
        )
    }

    private fun selectPreset(index: Int): SlashCommandResult {
        val presets = prefs.routingPresets.value
        if (index !in presets.indices) {
            return SlashCommandResult.Error("Invalid preset index: $index (0..${presets.lastIndex})")
        }
        val chosen = presets[index]
        prefs.setSelectedRoutingPresetId(chosen.id)
        return SlashCommandResult.CycleSelected(
            message = "Routing preset → ${chosen.name}",
            commandId = "preset",
            selectedOption = chosen.name,
        )
    }

    // ── Action implementations ──────────────────────────────────────────

    private fun executeAction(cmd: SlashCommand.Action): SlashCommandResult {
        return when (cmd.id) {
            "compact" -> SlashCommandResult.ActionDone(
                message = "compact",
            )
            "clear" -> SlashCommandResult.ActionDone(
                message = "Conversation cleared.",
            )
            "help" -> {
                val commands = SlashCommandRegistry.all
                val lines = buildString {
                    appendLine("Available commands:")
                    for (c in commands) {
                        appendLine("  /${c.id} — ${c.description}")
                    }
                }
                SlashCommandResult.HelpList(
                    message = lines.trimEnd(),
                    commands = commands,
                )
            }
            "reindex" -> SlashCommandResult.ActionDone(
                message = "Memory reindex started.",
            )
            "model" -> SlashCommandResult.Navigate(
                message = "Opening model selector…",
                route = Routes.SETTINGS_MODEL,
            )
            "settings" -> SlashCommandResult.Navigate(
                message = "Opening settings…",
                route = Routes.SETTINGS,
            )
            else -> SlashCommandResult.Error("Unknown action: ${cmd.id}")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun onOff(value: Boolean) = if (value) "ON" else "OFF"
}
