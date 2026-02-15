package org.ethereumphone.andyclaw.sessions.model

/**
 * Domain model for a chat session.
 *
 * Carries metadata about the session (title, model, timestamps, token usage)
 * without exposing Room internals to consumers. Inspired by OpenClaw's
 * `SessionEntry` but tailored for Android.
 */
data class Session(
    /** Unique session ID (UUID). */
    val id: String,
    /** Agent that owns this session. Enables multi-agent isolation. */
    val agentId: String,
    /** Human-readable title (auto-generated from the first message when empty). */
    val title: String,
    /** LLM model identifier used in this session (e.g. "claude-sonnet-4-20250514"). */
    val model: String? = null,
    /** Epoch millis when the session was first created. */
    val createdAt: Long,
    /** Epoch millis of the most recent activity. */
    val updatedAt: Long,
    /** Canonical session key for gateway routing (e.g. "agent:main:main"). */
    val sessionKey: String? = null,
    /** Optional display label (user-assigned or derived). */
    val label: String? = null,
    /** Current thinking/reasoning level override. */
    val thinkingLevel: String? = null,
    /** Cumulative input tokens consumed. */
    val inputTokens: Int = 0,
    /** Cumulative output tokens consumed. */
    val outputTokens: Int = 0,
    /** Cumulative total tokens consumed. */
    val totalTokens: Int = 0,
    /** Whether the last run was user-aborted. */
    val isAborted: Boolean = false,
)
