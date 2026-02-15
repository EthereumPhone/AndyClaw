package org.ethereumphone.andyclaw.sessions.model

/**
 * Domain model for a single message within a chat session.
 *
 * This is the public-facing representation; the Room entity
 * ([SessionMessageEntity]) is an internal persistence detail.
 */
data class SessionMessage(
    /** Unique message ID (UUID). */
    val id: String,
    /** ID of the session this message belongs to. */
    val sessionId: String,
    /** Who produced this message. */
    val role: MessageRole,
    /** Text content of the message. */
    val content: String,
    /** Name of the tool that produced this message (only when [role] == [MessageRole.TOOL]). */
    val toolName: String? = null,
    /** Correlation ID linking a tool result back to the tool_use request. */
    val toolCallId: String? = null,
    /** Epoch millis when the message was created. */
    val timestamp: Long,
    /** Zero-based ordering index within the session. */
    val orderIndex: Int,
)
