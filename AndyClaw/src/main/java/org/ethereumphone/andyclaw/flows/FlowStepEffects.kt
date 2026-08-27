package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * What one flow step does to the world.
 *
 * This is Phase 1's [ToolEffect] applied one level down. At the *tool* level a display
 * tap is classified `IRREVERSIBLE`, because from outside nobody can tell what a tap
 * does. Inside a flow there is more to go on — the step names the node it targets — and
 * a flow needs the finer answer for one reason only: `agent-os-design.md` §6 says a flow
 * with no `checkpoint:` before an irreversible step must not compile, and the design
 * doc's own canonical example puts its single checkpoint before `send_button` and
 * nowhere else. So navigating is not the same as sending, and this object is where that
 * distinction is drawn.
 *
 * Two rules keep the heuristic from becoming a hole:
 *
 * 1. **A declaration can only raise.** A compiler may mark a step `IRREVERSIBLE`; it
 *    cannot mark `send_button` reversible and walk past the checkpoint rule.
 * 2. **This is not the security gate.** The gate is at the tool level, where every flow
 *    that actuates anything at all is `IRREVERSIBLE` and needs the user's approval
 *    before it runs ([FlowToolEffect]). What this object decides is where the
 *    *checkpoint* has to sit, and whether the flow may compile at all.
 */
object FlowStepEffects {

    /**
     * Tokens that mean "this commits". Matched whole, against the tokens of a view id,
     * so `resend` does not match `send` and `com.sendbird.x` does not either.
     */
    val COMMIT_TOKENS: Set<String> = setOf(
        "send", "submit", "post", "publish", "share", "delete", "remove", "discard",
        "confirm", "accept", "approve", "transfer", "withdraw", "sign", "call", "dial",
        "book", "reserve", "install", "uninstall", "block", "report", "logout",
        "signout", "unfollow", "unsubscribe", "reply", "forward", "invite",
    )

    /**
     * Payment and authentication. `agent-os-design.md` §6: "**HARD RULE: never automate
     * an auth or payment confirmation step.** Not 'ask first' — never." A flow with one
     * of these does not compile, at all — see [FlowValidator].
     */
    val SENSITIVE_TOKENS: Set<String> = setOf(
        "pay", "payment", "purchase", "buy", "checkout", "card", "cvv", "cvc", "iban",
        "pin", "password", "passcode", "passphrase", "otp", "2fa", "mfa", "totp",
        "biometric", "fingerprint", "faceid", "authenticate", "auth", "login", "signin",
        "seed", "mnemonic", "privatekey", "private_key", "keystore", "unlock", "verify",
    )

    /** The effect of [step] — the declaration and the classification, whichever is higher. */
    fun of(step: FlowStep): ToolEffect {
        val classified = classify(step)
        val declared = declaredEffect(step)
        return if (declared != null && declared.ordinal > classified.ordinal) declared else classified
    }

    /** What the step itself claims, if anything. Only ever used to raise [classify]. */
    fun declaredEffect(step: FlowStep): ToolEffect? {
        val raw = (step as? TapStep)?.effect?.trim()?.uppercase() ?: return null
        return ToolEffect.entries.firstOrNull { it.name == raw }
    }

    /** What the step looks like from its opcode and its target, ignoring declarations. */
    fun classify(step: FlowStep): ToolEffect = when (step) {
        // Looking at the screen changes nothing, and a checkpoint is a boundary marker.
        is WaitForStep, is AssertStep, is CheckpointStep -> ToolEffect.READ

        // Text in a field is not text that has been sent.
        is TypeStep -> tokenEffect(step.target.viewId, ToolEffect.REVERSIBLE)

        is TapStep -> tokenEffect(step.viewId ?: step.text, ToolEffect.REVERSIBLE)
    }

    private fun tokenEffect(target: String?, floor: ToolEffect): ToolEffect {
        val tokens = tokenize(target)
        return when {
            tokens.any { it in SENSITIVE_TOKENS } -> ToolEffect.SENSITIVE
            tokens.any { it in COMMIT_TOKENS } -> ToolEffect.IRREVERSIBLE
            else -> floor
        }
    }

    /** `com.android.mms:id/send_button` -> `[com, android, mms, id, send, button]`. */
    fun tokenize(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
    }

    /** The highest effect any step in [flow] reaches. */
    fun highestOf(flow: Flow): ToolEffect =
        flow.steps.map { of(it) }.maxByOrNull { it.ordinal } ?: ToolEffect.READ
}

/**
 * The effect the *tool* a flow is registered as carries — the one Phase 1's
 * `ProvenanceGate` and the approval check act on.
 *
 * Deliberately blunter than [FlowStepEffects]: **anything that actuates is
 * `IRREVERSIBLE`.** No verb table, no heuristic. A flow that taps or types gets the
 * one-tap confirmation `agent-os-design.md` §6 mandates for irreversible actions, and a
 * flow that only waits and asserts is a read. The finer classification exists to place
 * the checkpoint, not to decide whether the user is asked.
 */
object FlowToolEffect {

    fun of(flow: Flow): ToolEffect {
        if (flow.steps.any { it is TapStep || it is TypeStep }) return ToolEffect.IRREVERSIBLE
        return ToolEffect.READ
    }

    /** Whether replaying [flow] must be confirmed by the user first. */
    fun requiresApproval(flow: Flow): Boolean = of(flow) != ToolEffect.READ
}
