package org.ethereumphone.andyclaw.services

/**
 * The pure parts of `ILauncherService.getSettings` / `setSetting`: what the launcher is told about
 * secrets, and what a provider change does to its model. The keys are additive; an older launcher
 * reads the ones it knows.
 */
internal object LauncherSettings {

    /**
     * The secrets `getSettings` carries. Each also gets `<key>Hint` (SET-26), which a launcher shows
     * as a placeholder instead of pre-filling the raw value; the raw values still go out this
     * release, for launchers that read them, and stop once none does.
     */
    val SECRET_KEYS = listOf(
        "apiKey",
        "tinfoilApiKey",
        "openaiApiKey",
        "veniceApiKey",
        "customApiKey",
        "claudeOauthRefreshToken",
        "googleOauthClientSecret",
        "telegramBotToken",
    )

    /**
     * The largest heartbeat interval AndyClaw honours (SET-02). The OS ticks at most hourly;
     * AndyClaw skips the ticks the chosen interval has not reached, so the full range is real.
     */
    const val HEARTBEAT_MAX_INTERVAL_MINUTES = 1440

    /** `""` when unset; "set" when too short to show any of it; else "sk-or-…a1b2". */
    fun secretHint(value: String): String {
        val v = value.trim()
        return when {
            v.isEmpty() -> ""
            v.length < 12 -> "set"
            else -> v.take(6) + "…" + v.takeLast(4)
        }
    }

    /**
     * Whether Telegram is set up the way it works: a bot token and a verified owner chat. A phone
     * set up before the owner was verified has a token and owner 0 — nobody is the owner there
     * until setup is redone, and "configured" would hide that.
     */
    fun telegramConfigured(botToken: String, ownerChatId: Long): Boolean =
        botToken.isNotBlank() && ownerChatId != 0L

    /**
     * The model after the provider changed (SET-08): the user's last choice for that provider, or
     * its default. Keeping the old one sent, say, an OpenRouter id to Venice, and every heartbeat
     * failed.
     */
    fun modelAfterProviderChange(lastChoice: String?, providerDefault: String): String =
        lastChoice?.trim()?.takeIf { it.isNotEmpty() } ?: providerDefault

    /** A custom provider's `/v1/models` URL from its chat-completions URL, however it was typed. */
    fun modelsUrlFromChatUrl(chatUrl: String): String {
        val t = chatUrl.trim().trimEnd('/')
        return when {
            t.endsWith("/chat/completions") -> t.removeSuffix("/chat/completions") + "/models"
            t.endsWith("/v1") -> "$t/models"
            t.contains("/v1/") -> t.substringBefore("/v1/") + "/v1/models"
            else -> "$t/v1/models"
        }
    }
}
