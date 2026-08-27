package org.ethereumphone.andyclaw.ExecutionEngine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Where the content that triggered an agent run came from.
 *
 * The agent reads content written by strangers (XMTP bodies, Telegram bodies, the
 * notification buffer, OCR of arbitrary screens) and holds authority that needs no
 * user prompt — the agent sub-account can sign, shell can run, messages can be sent.
 * Provenance is the dimension that separates "the user asked for this" from "a
 * stranger's message asked for this", and it is enforced as a pre-flight check, never
 * as a prompt instruction (a prompt line is not a mechanism).
 *
 * - [USER]      launcher chat, the in-app chat, the lockscreen voice prompt.
 * - [TRUSTED]   `HEARTBEAT.md`, reminders and cron jobs the user created, own calendar.
 * - [UNTRUSTED] XMTP and Telegram bodies, the notification buffer, screenshot OCR,
 *               mail bodies — anything an arbitrary sender can write.
 *
 * Sub-agents inherit their parent's provenance and **never widen it**.
 */
enum class Provenance {
    USER,
    TRUSTED,
    UNTRUSTED;

    val isUntrusted: Boolean get() = this == UNTRUSTED
}

/**
 * Carries the running agent's [provenance] down the suspend call chain so code that
 * the execution engine cannot see — most importantly `execute_code`'s `ToolBridge`,
 * which calls the skill registry directly — can apply the same gate.
 *
 * [conversationId] is the id of the conversation the trigger arrived on (an XMTP
 * sender address, a Telegram chat id). Under [Provenance.UNTRUSTED] outbound
 * messaging is confined to that conversation.
 */
class ProvenanceContext(
    val provenance: Provenance,
    val conversationId: String? = null,
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ProvenanceContext>

    override fun toString(): String =
        "ProvenanceContext($provenance${conversationId?.let { ", conv=$it" } ?: ""})"
}

/**
 * The provenance of the currently running agent, or [fallback] when nothing set one.
 *
 * The fallback is [Provenance.UNTRUSTED] on purpose: a call path that forgot to
 * declare its provenance is treated as the most restricted one, not the least.
 */
suspend fun currentProvenance(fallback: Provenance = Provenance.UNTRUSTED): Provenance =
    coroutineContext[ProvenanceContext]?.provenance ?: fallback

/** The conversation the current trigger arrived on, if one was declared. */
suspend fun currentTriggerConversationId(): String? =
    coroutineContext[ProvenanceContext]?.conversationId
