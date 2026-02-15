package org.ethereumphone.andyclaw.sessions

/**
 * Session key utilities for identifying and routing sessions.
 *
 * Mirrors a subset of OpenClaw's `session-key.ts` logic, providing
 * agent-scoped canonical keys and normalisation helpers for Android.
 *
 * Key format: `agent:<agentId>:<rest>` (e.g. `agent:main:main`).
 */
object SessionKey {

    const val DEFAULT_AGENT_ID = "main"
    const val DEFAULT_MAIN_KEY = "main"

    private val VALID_ID_RE = Regex("^[a-z0-9][a-z0-9_-]{0,63}$", RegexOption.IGNORE_CASE)
    private val INVALID_CHARS_RE = Regex("[^a-z0-9_-]+")
    private val LEADING_DASH_RE = Regex("^-+")
    private val TRAILING_DASH_RE = Regex("-+$")

    // ── Normalisation ────────────────────────────────────────────────

    /**
     * Normalise an agent ID to a path-safe, shell-friendly token.
     *
     * Empty/null values default to [DEFAULT_AGENT_ID].
     */
    fun normalizeAgentId(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_AGENT_ID
        if (VALID_ID_RE.matches(trimmed)) return trimmed.lowercase()
        return trimmed.lowercase()
            .replace(INVALID_CHARS_RE, "-")
            .replace(LEADING_DASH_RE, "")
            .replace(TRAILING_DASH_RE, "")
            .take(64)
            .ifEmpty { DEFAULT_AGENT_ID }
    }

    /**
     * Normalise the "main" portion of a session key.
     */
    fun normalizeMainKey(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return trimmed.lowercase().ifEmpty { DEFAULT_MAIN_KEY }
    }

    // ── Key building ─────────────────────────────────────────────────

    /**
     * Build the canonical main session key for an agent:
     * `agent:<agentId>:<mainKey>`
     */
    fun buildMainSessionKey(
        agentId: String,
        mainKey: String? = null,
    ): String {
        val nAgent = normalizeAgentId(agentId)
        val nMain = normalizeMainKey(mainKey)
        return "agent:$nAgent:$nMain"
    }

    /**
     * Build a peer (DM or group) session key.
     */
    fun buildPeerSessionKey(
        agentId: String,
        channel: String,
        peerKind: String = "direct",
        peerId: String,
    ): String {
        val nAgent = normalizeAgentId(agentId)
        val nChannel = channel.trim().lowercase().ifEmpty { "unknown" }
        val nPeerId = peerId.trim().lowercase().ifEmpty { "unknown" }
        return "agent:$nAgent:$nChannel:$peerKind:$nPeerId"
    }

    // ── Key parsing ──────────────────────────────────────────────────

    /**
     * Parse an `agent:<agentId>:<rest>` key.
     *
     * Returns null if [raw] doesn't start with `agent:`.
     */
    fun parse(raw: String?): ParsedSessionKey? {
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.startsWith("agent:", ignoreCase = true)) return null
        val parts = trimmed.split(":", limit = 3)
        if (parts.size < 3) return null
        val agentId = parts[1].trim()
        val rest = parts[2].trim()
        if (agentId.isEmpty() || rest.isEmpty()) return null
        return ParsedSessionKey(agentId = agentId, rest = rest)
    }

    /**
     * Resolve the agent ID from an arbitrary session key.
     *
     * Falls back to [DEFAULT_AGENT_ID] for unparseable keys.
     */
    fun resolveAgentId(sessionKey: String?): String {
        return parse(sessionKey)?.agentId?.let(::normalizeAgentId) ?: DEFAULT_AGENT_ID
    }

    /**
     * Check if a key follows the canonical `agent:*` format.
     */
    fun isCanonical(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        return trimmed == "global" || trimmed.startsWith("agent:")
    }

    data class ParsedSessionKey(
        val agentId: String,
        val rest: String,
    )
}
