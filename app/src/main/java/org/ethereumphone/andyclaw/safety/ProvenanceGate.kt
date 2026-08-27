package org.ethereumphone.andyclaw.safety

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
    ): PreflightVerdict {
        val effect = ToolEffects.of(call.name, toolDef)

        if (provenance == Provenance.UNTRUSTED) {
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
    fun allowsUnattended(provenance: Provenance, effect: ToolEffect): Boolean =
        matrix(provenance, effect) is PreflightVerdict.Pass

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

        return if (sameConversation(target, triggerConversationId)) {
            // Replying to the thread the message arrived on is explicitly allowed.
            PreflightVerdict.Pass
        } else {
            PreflightVerdict.Block(
                "[Provenance] This request came from a message in conversation " +
                    "'$triggerConversationId', so '${call.name}' may only reply there — " +
                    "not to '$target'. Blocked."
            )
        }
    }

    /**
     * Whether two conversation identifiers name the same thread.
     *
     * Wallet addresses differ only in EIP-55 casing and phone numbers pick up spaces,
     * dashes and a country-code prefix on the way through an SMS stack, so neither
     * survives a raw `==`.
     */
    internal fun sameConversation(a: String, b: String): Boolean {
        val x = a.trim()
        val y = b.trim()
        if (x.equals(y, ignoreCase = true)) return true

        val dx = x.filter { it.isDigit() }
        val dy = y.filter { it.isDigit() }
        // Only treat them as phone numbers when both are digits-and-punctuation.
        val phoneLike = { s: String -> s.isNotEmpty() && s.all { it.isDigit() || it in "+ -()." } }
        if (phoneLike(x) && phoneLike(y) && dx.length >= 7 && dy.length >= 7) {
            val n = minOf(dx.length, dy.length)
            return dx.takeLast(n) == dy.takeLast(n)
        }
        return false
    }
}
