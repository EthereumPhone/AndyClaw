package org.ethereumphone.andyclaw.sessions.model

/**
 * Partial update for a [Session].
 *
 * Only non-null fields are applied, mirroring OpenClaw's PATCH semantics
 * where callers specify only the fields they want to change.
 */
data class SessionPatch(
    val title: String? = null,
    val model: String? = null,
    val label: String? = null,
    val sessionKey: String? = null,
    val thinkingLevel: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val isAborted: Boolean? = null,
)
