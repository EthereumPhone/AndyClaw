package org.ethereumphone.andyclaw.safety

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.ethereumphone.andyclaw.ExecutionEngine.PreflightVerdict
import org.ethereumphone.andyclaw.ExecutionEngine.ToolCall
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * The trust boundary: what a run may do, given where the content that triggered it
 * came from.
 *
 * The agent is reachable from XMTP bodies, Telegram bodies and a 50-deep notification
 * buffer — all written by arbitrary senders — and it holds authority that asks for no
 * confirmation (the agent sub-account signs, shell runs, messages go out). This object
 * is the mechanism that separates the two. It is a pre-flight check, never a line in a
 * prompt: `execute_code`'s ToolBridge invokes tools by name regardless of the tool
 * list, and in ToolSearch mode the model discovers tools mid-run, so nothing that
 * lives in the prompt can be an enforcement point.
 *
 * The asymmetry that makes this safe rather than crippling: the **user's** wallet path
 * is already protected by the SystemUI confirmation and is untouched here. It is the
 * **agent's** sub-account — deliberately promptless — that a stranger's message can
 * currently reach.
 */
object ProvenanceGate {

    /**
     * The matrix, exactly as specified. Pure: same inputs, same verdict, no lookups.
     *
     * | Provenance | READ | REVERSIBLE | IRREVERSIBLE   | SENSITIVE      |
     * |------------|------|------------|----------------|----------------|
     * | USER       | Pass | Pass       | Pass           | NeedsApproval  |
     * | TRUSTED    | Pass | Pass       | Pass           | NeedsApproval  |
     * | UNTRUSTED  | Pass | Pass       | NeedsApproval  | Block          |
     *
     * `Pass` for USER/TRUSTED + IRREVERSIBLE means "this gate has no opinion" — the
     * pre-existing `requiresApproval` rules still run a few checks later.
     */
    fun matrix(provenance: Provenance, effect: ToolEffect, toolName: String = "this tool"): PreflightVerdict =
        when (effect) {
            ToolEffect.READ, ToolEffect.REVERSIBLE -> PreflightVerdict.Pass

            ToolEffect.IRREVERSIBLE -> when (provenance) {
                Provenance.USER, Provenance.TRUSTED -> PreflightVerdict.Pass
                Provenance.UNTRUSTED -> PreflightVerdict.NeedsApproval(
                    "'$toolName' cannot be undone, and this request came from content " +
                        "written by someone other than you. Approve it to let it run."
                )
            }

            ToolEffect.SENSITIVE -> when (provenance) {
                Provenance.USER, Provenance.TRUSTED -> PreflightVerdict.NeedsApproval(
                    "'$toolName' touches payment or authentication and always needs your approval."
                )
                Provenance.UNTRUSTED -> PreflightVerdict.Block(
                    "[Provenance] '$toolName' touches payment or authentication and this " +
                        "request originated in untrusted content (an incoming message, a " +
                        "notification, or a page). It is blocked. Tell the user what you " +
                        "would have done and let them do it themselves."
                )
            }
        }

    /**
     * The full decision for one tool call, including the reply-to-sender rule.
     *
     * [triggerConversationId] is the conversation the trigger arrived on — an XMTP
     * sender address, a Telegram chat id. Under [Provenance.UNTRUSTED] a message may
     * only be addressed back to it.
     */
    fun evaluate(
        call: ToolCall,
        provenance: Provenance,
        triggerConversationId: String?,
        toolDef: ToolDefinition?,
        /** Who hears this run's reply; null when nobody does. See [ReplyAudience]. */
        audience: ReplyAudience? = null,
        /** Whether this run has already read the owner's private data. */
        readPrivateData: Boolean = false,
        /** Whether this run has read something another person wrote. */
        readThirdPartyContent: Boolean = false,
    ): PreflightVerdict {
        val effect = ToolEffects.of(call.name, toolDef)

        standingInstructionVerdict(call.name, readThirdPartyContent)?.let { return it }

        // Before the approval below, which returns early: a calendar invitation is irreversible
        // and needs approval, and that early Pass used to skip the block meant for it.
        taintedTrustedEgressVerdict(provenance, call.name, readPrivateData, readThirdPartyContent, call.input)?.let { return it }

        if (taintedTrustedRunNeedsApproval(provenance, effect, call.name, readThirdPartyContent)) {
            // The approval check a few steps on raises this anyway for such a tool.
            if (toolDef?.requiresApproval == true) return PreflightVerdict.Pass
            return PreflightVerdict.NeedsApproval(
                "'${call.name}' cannot be undone, and this run has read something written by " +
                    "someone other than you (a message, a notification, a page). Approve it to let it run."
            )
        }

        if (provenance == Provenance.UNTRUSTED) {
            privacyVerdict(call.name, audience, readPrivateData, call.input)?.let { return it }

            // Fixed-recipient tools reach the device owner and nobody else. This is
            // the "it can raise a card for the user" leg of the model — keep it open
            // whatever the effect table says about them.
            if (call.name in ToolEffects.OWNER_ONLY_MESSAGE_TOOLS) return PreflightVerdict.Pass

            val targetKeys = ToolEffects.OUTBOUND_MESSAGE_TARGETS[call.name]
            if (targetKeys != null) {
                return outboundVerdict(call, targetKeys, triggerConversationId)
            }
        }

        val verdict = matrix(provenance, effect, call.name)

        // Collapse a NeedsApproval that the pre-existing approvalCheck is about to
        // raise anyway — otherwise one tool call shows the user two identical
        // dialogs. The guarantee is unchanged: an approval is still required, it is
        // just raised once.
        if (verdict is PreflightVerdict.NeedsApproval && toolDef?.requiresApproval == true) {
            return PreflightVerdict.Pass
        }
        return verdict
    }

    /**
     * True when [effect] may run under [provenance] with nobody watching.
     *
     * Used on paths that have no way to prompt — `execute_code`'s ToolBridge calls
     * the registry directly from a BeanShell thread, so "ask the user" is not
     * available there and anything short of Pass has to be a refusal.
     */
    fun allowsUnattended(
        provenance: Provenance,
        effect: ToolEffect,
        toolName: String = "",
        audience: ReplyAudience? = null,
        readPrivateData: Boolean = false,
        readThirdPartyContent: Boolean = false,
        /** The call's input, for the checks that depend on it; null counts as the worst case. */
        input: JsonObject? = null,
    ): Boolean =
        matrix(provenance, effect) is PreflightVerdict.Pass &&
            standingInstructionVerdict(toolName, readThirdPartyContent) == null &&
            !taintedTrustedRunNeedsApproval(provenance, effect, toolName, readThirdPartyContent) &&
            taintedTrustedEgressVerdict(provenance, toolName, readPrivateData, readThirdPartyContent, input) == null &&
            (provenance != Provenance.UNTRUSTED || privacyVerdict(toolName, audience, readPrivateData, input) == null)

    /**
     * The soul is read into every trusted run as the owner's own standing instructions, so a
     * line injected into it outlives the run that wrote it: every heartbeat after acts on it. A
     * run that has read another person's words — a page, a notification, a message — may not
     * rewrite it, whoever set the run off. A block, not an approval: the launcher approves
     * everything it is asked, and a heartbeat's card would ask the owner to proof-read a whole
     * soul for one planted sentence. The owner changes it from a fresh message instead.
     *
     * Untrusted runs never set the flag; their writes already need approval from the matrix.
     */
    fun standingInstructionVerdict(toolName: String, readThirdPartyContent: Boolean): PreflightVerdict.Block? {
        if (!readThirdPartyContent || toolName !in ToolEffects.STANDING_INSTRUCTION_WRITES) return null
        return PreflightVerdict.Block(
            "[Provenance] This turn has read something written by someone other than the owner, so " +
                "'$toolName' cannot run in it: the result would become standing instructions for every " +
                "later run. Tell the user what you would change and let them ask for it in a new message."
        )
    }

    /**
     * A trusted run nobody watches — a heartbeat, the owner's own cron job — acts on its own
     * authority only until it reads something another person wrote. After that an irreversible
     * step needs the owner, like an untrusted run's: the heartbeat reads a notification as part
     * of its task, and a notification is a stranger's text. Messages to the owner stay open —
     * they are how the run tells the owner what it found. The user's own chat (USER) is not
     * affected: somebody is watching it.
     */
    fun taintedTrustedRunNeedsApproval(
        provenance: Provenance,
        effect: ToolEffect,
        toolName: String,
        readThirdPartyContent: Boolean,
    ): Boolean =
        provenance == Provenance.TRUSTED && readThirdPartyContent &&
            effect == ToolEffect.IRREVERSIBLE && toolName !in ToolEffects.OWNER_ONLY_MESSAGE_TOOLS

    /**
     * The egress half of [taintedTrustedRunNeedsApproval]. A web fetch is READ, so the taint rule
     * never saw it: a notification telling a heartbeat to read the SMS inbox and fetch
     * `evil?d=<code>` got the code out with nobody watching. Once a trusted run nobody watches has
     * read both someone else's words and the owner's private data, it may not reach the web — a
     * block, not an approval, because the heartbeat answers approvals by itself.
     */
    fun taintedTrustedEgressVerdict(
        provenance: Provenance,
        toolName: String,
        readPrivateData: Boolean,
        readThirdPartyContent: Boolean,
        input: JsonObject? = null,
    ): PreflightVerdict.Block? {
        if (provenance != Provenance.TRUSTED || !readPrivateData || !readThirdPartyContent) return null
        if (!ToolEffects.isNetworkEgress(toolName, input)) return null
        return PreflightVerdict.Block(
            "[Provenance] This run has read something written by someone else and the owner's " +
                "private data, so it cannot send anything to the web. Finish without it."
        )
    }

    // ── What an untrusted run may read, and what it may do after ──────

    /**
     * The privacy half of the gate, for [Provenance.UNTRUSTED] runs. Null when it has nothing
     * to say.
     *
     * READ was open to every run on the grounds that reading changes nothing. It changes
     * nothing *on the device*; what matters is where the answer goes. A stranger on Telegram
     * asking "what's in the clipboard" got the clipboard back, and a notification carrying
     * instructions could have a background run read the SMS inbox and put it in a URL.
     */
    fun privacyVerdict(
        toolName: String,
        audience: ReplyAudience?,
        readPrivateData: Boolean,
        input: JsonObject? = null,
    ): PreflightVerdict.Block? {
        if (toolName in ToolEffects.CLIPBOARD_READS) {
            return PreflightVerdict.Block(
                "[Provenance] The clipboard often holds a password or a code the owner just copied, " +
                    "and this request came from content written by someone else. Reading it is blocked."
            )
        }
        if (toolName in ToolEffects.PRIVATE_DATA_TOOLS && audience?.ownerOnly == false) {
            return PreflightVerdict.Block(
                "[Provenance] Your reply goes to someone other than the phone's owner, so " +
                    "'$toolName' cannot be used here: it reads the owner's private data. Answer " +
                    "without it."
            )
        }
        if (readPrivateData && audience == null && ToolEffects.isNetworkEgress(toolName, input)) {
            return PreflightVerdict.Block(
                "[Provenance] This run was set off by content written by someone else and has read " +
                    "the owner's private data, so it cannot send anything to the web. Finish without it."
            )
        }
        return null
    }

    // ── Reply-to-sender ──────────────────────────────────────────────

    private fun outboundVerdict(
        call: ToolCall,
        targetKeys: Set<String>,
        triggerConversationId: String?,
    ): PreflightVerdict {
        if (targetKeys.isEmpty()) {
            return PreflightVerdict.Block(
                "[Provenance] '${call.name}' sends to recipients it does not name, so it " +
                    "cannot be confined to the conversation this request came from. " +
                    "Blocked. Reply in this conversation instead."
            )
        }
        if (triggerConversationId.isNullOrBlank()) {
            return PreflightVerdict.Block(
                "[Provenance] '${call.name}' sends a message and this request came from " +
                    "untrusted content with no conversation to reply to. Blocked."
            )
        }
        val target = targetKeys
            .firstNotNullOfOrNull { call.input[it]?.jsonPrimitive?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
            ?: return PreflightVerdict.Block(
                "[Provenance] '${call.name}' was called without a recipient, so it cannot " +
                    "be shown to stay in the conversation this request came from. Blocked."
            )

        val same = if (call.name in PHONE_TARGET_TOOLS) {
            sameSmsConversation(target, triggerConversationId)
        } else {
            sameConversation(target, triggerConversationId)
        }
        return if (same) {
            // Replying to the thread the message arrived on is explicitly allowed.
            PreflightVerdict.Pass
        } else {
            // The refused recipient stays out of the reason: it is recorded in the ledger,
            // which never holds a tool's input.
            PreflightVerdict.Block(
                "[Provenance] This request came from a message in conversation " +
                    "'$triggerConversationId', so '${call.name}' may only reply there, not to " +
                    "anyone else. Blocked."
            )
        }
    }

    /**
     * The prefix an SMS trigger's conversation id carries (`sms:+4917…`). Nothing raises an
     * untrusted run from an SMS today; one that does must say so this way, or `send_sms` has no
     * thread to reply into.
     */
    const val SMS_TRIGGER_PREFIX = "sms:"

    /** Outbound tools whose target is a phone number. They reply only into an SMS trigger. */
    private val PHONE_TARGET_TOOLS = setOf("send_sms")

    /**
     * Whether two conversation identifiers name the same thread. Wallet addresses differ only in
     * EIP-55 casing, so the compare ignores case; nothing else is loosened.
     */
    internal fun sameConversation(a: String, b: String): Boolean =
        a.trim().equals(b.trim(), ignoreCase = true)

    /**
     * Whether [target], a phone number, is the sender of the SMS trigger [trigger]. Phone numbers
     * pick up spaces, dashes and a country-code prefix on the way through an SMS stack, so they
     * are compared by their trailing digits — but only against a trigger that is an SMS. A
     * Telegram chat id is digits too: matched the same way, a stranger's chat id `5123456789` let
     * the run text `+1 512-345-6789` without approval.
     */
    internal fun sameSmsConversation(target: String, trigger: String): Boolean {
        val t = trigger.trim()
        if (!t.startsWith(SMS_TRIGGER_PREFIX, ignoreCase = true)) return false
        val x = target.trim()
        val y = t.substring(SMS_TRIGGER_PREFIX.length).trim()
        val phoneLike = { s: String -> s.isNotEmpty() && s.all { it.isDigit() || it in "+ -()." } }
        if (!phoneLike(x) || !phoneLike(y)) return false
        val dx = x.filter { it.isDigit() }
        val dy = y.filter { it.isDigit() }
        if (dx.length < 7 || dy.length < 7) return false
        val n = minOf(dx.length, dy.length)
        return dx.takeLast(n) == dy.takeLast(n)
    }
}
