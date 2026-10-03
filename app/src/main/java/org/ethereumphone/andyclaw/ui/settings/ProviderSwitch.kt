package org.ethereumphone.andyclaw.ui.settings

import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.SecurePrefs
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider

/**
 * Choosing the chat's provider, the same way from Settings and from `/provider`: the command
 * used to offer every provider and only half of what Settings does with one.
 */
object ProviderSwitch {

    /**
     * The providers the user may pick. OPENAI_OAUTH (ChatGPT via Codex Responses) is built but
     * hidden until its live round-trip is validated — it cannot use tools yet — and the ethOS
     * gateway exists only on ethOS. The enum entry, client, token manager, model entries and
     * Settings UI all stay in the tree; re-add OPENAI_OAUTH here to re-enable it.
     */
    fun choices(isPrivileged: Boolean): List<LlmProvider> = if (isPrivileged) {
        listOf(LlmProvider.ETHOS_PREMIUM, LlmProvider.OPEN_ROUTER, LlmProvider.CLAUDE_OAUTH, LlmProvider.OPENAI, LlmProvider.VENICE, LlmProvider.TINFOIL, LlmProvider.LOCAL, LlmProvider.CUSTOM)
    } else {
        listOf(LlmProvider.OPEN_ROUTER, LlmProvider.CLAUDE_OAUTH, LlmProvider.OPENAI, LlmProvider.VENICE, LlmProvider.TINFOIL, LlmProvider.LOCAL, LlmProvider.CUSTOM)
    }

    /**
     * Makes [provider] the chat's provider, with its default model — for CUSTOM the user's own
     * model id, which [SecurePrefs.setSelectedProvider] has just put there and the placeholder
     * default used to overwrite. Leaving LOCAL frees the model's memory, and with "apply to all"
     * on, the heartbeat and compaction follow.
     */
    fun select(app: NodeApp, provider: LlmProvider) {
        val prefs = app.securePrefs
        prefs.setSelectedProvider(provider)
        if (provider != LlmProvider.CUSTOM) {
            prefs.setSelectedModel(AnthropicModels.defaultForProvider(provider).modelId)
        }
        if (provider != LlmProvider.LOCAL && app.llamaCpp.isModelLoaded) {
            app.llamaCpp.unload()
        }
        if (prefs.syncProviderToAll.value) syncAll(prefs, provider)
    }

    /** The heartbeat and compaction on [provider], each with its own model for it. */
    fun syncAll(prefs: SecurePrefs, provider: LlmProvider) {
        prefs.setHeartbeatProvider(provider)
        // The user's previous selection for this provider if there is one, otherwise its default.
        prefs.setHeartbeatModel(prefs.getHeartbeatUserModelForProvider(provider) ?: modelFor(prefs, provider))
        // Also ensure heartbeat is set to use its own provider (not "same as main")
        prefs.setHeartbeatUseSameModel(false)

        prefs.setCompactionProvider(provider)
        prefs.setCompactionModel(modelFor(prefs, provider))
        prefs.setCompactionUseSameModel(false)
    }

    /** A provider's default model; for CUSTOM the one the user's server serves, not a placeholder. */
    private fun modelFor(prefs: SecurePrefs, provider: LlmProvider): String =
        prefs.customModelId.value.takeIf { provider == LlmProvider.CUSTOM && it.isNotBlank() }
            ?: AnthropicModels.defaultForProvider(provider).modelId
}
