package org.ethereumphone.andyclaw.heartbeat

/**
 * Heartbeat prompt utilities.
 * Handles the HEARTBEAT_OK token detection and stripping, mirroring OpenClaw's heartbeat.ts.
 */
object HeartbeatPrompt {

    const val TOKEN = "HEARTBEAT_OK"

    const val DEFAULT_PROMPT =
        "Read HEARTBEAT.md if it exists (workspace context). Follow it strictly. " +
        "Do not infer or repeat old tasks from prior chats. " +
        "If nothing needs attention, reply HEARTBEAT_OK."

    data class StripResult(
        /** Whether the response should be suppressed (nothing actionable). */
        val shouldSkip: Boolean,
        /** Remaining text after stripping the token. */
        val text: String,
        /** Whether the token was found and stripped. */
        val didStrip: Boolean,
    )

    /**
     * Check if HEARTBEAT.md content is "effectively empty" - only whitespace, headers, or empty list items.
     * Matches OpenClaw's isHeartbeatContentEffectivelyEmpty().
     */
    fun isContentEffectivelyEmpty(content: String?): Boolean {
        if (content == null) return false
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Skip markdown ATX headers (# followed by space or EOL)
            if (trimmed.matches(Regex("^#+(\\s|$).*"))) continue
            // Skip empty markdown list items like "- [ ]" or "* [ ]" or just "- "
            if (trimmed.matches(Regex("^[-*+]\\s*(\\[[\\sXx]?]\\s*)?$"))) continue
            // Found actionable content
            return false
        }
        return true
    }

    /**
     * Strip the HEARTBEAT_OK token from agent response text.
     * Returns a StripResult indicating whether the response should be suppressed.
     */
    fun stripToken(
        raw: String?,
        maxAckChars: Int = HeartbeatConfig.DEFAULT_ACK_MAX_CHARS,
    ): StripResult {
        if (raw.isNullOrBlank()) {
            return StripResult(shouldSkip = true, text = "", didStrip = false)
        }

        val trimmed = raw.trim()
        // Strip lightweight markup so HEARTBEAT_OK wrapped in markdown still matches
        val normalized = trimmed
            .replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace(Regex("^[*`~_]+"), "")
            .replace(Regex("[*`~_]+$"), "")

        val hasToken = trimmed.contains(TOKEN) || normalized.contains(TOKEN)
        if (!hasToken) {
            return StripResult(shouldSkip = false, text = trimmed, didStrip = false)
        }

        // Strip token from edges
        val stripped = stripTokenAtEdges(trimmed)
        val strippedNormalized = stripTokenAtEdges(normalized)

        val picked = if (stripped.didStrip && stripped.text.isNotEmpty()) stripped else strippedNormalized
        if (!picked.didStrip) {
            return StripResult(shouldSkip = false, text = trimmed, didStrip = false)
        }

        if (picked.text.isEmpty()) {
            return StripResult(shouldSkip = true, text = "", didStrip = true)
        }

        val rest = picked.text.trim()
        // If remaining text is short enough, treat as an ack and suppress
        if (rest.length <= maxAckChars) {
            return StripResult(shouldSkip = true, text = "", didStrip = true)
        }

        return StripResult(shouldSkip = false, text = rest, didStrip = true)
    }

    private data class EdgeStripResult(val text: String, val didStrip: Boolean)

    private fun stripTokenAtEdges(input: String): EdgeStripResult {
        var text = input.trim()
        if (text.isEmpty()) return EdgeStripResult("", false)
        if (!text.contains(TOKEN)) return EdgeStripResult(text, false)

        var didStrip = false
        var changed = true
        while (changed) {
            changed = false
            val next = text.trim()
            if (next.startsWith(TOKEN)) {
                text = next.removePrefix(TOKEN).trimStart()
                didStrip = true
                changed = true
                continue
            }
            if (next.endsWith(TOKEN)) {
                text = next.removeSuffix(TOKEN).trimEnd()
                didStrip = true
                changed = true
            }
        }
        return EdgeStripResult(text.replace(Regex("\\s+"), " ").trim(), didStrip)
    }
}
