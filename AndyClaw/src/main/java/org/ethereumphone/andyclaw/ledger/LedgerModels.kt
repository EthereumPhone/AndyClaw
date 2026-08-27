package org.ethereumphone.andyclaw.ledger

/**
 * What kind of thing a ledger row records.
 *
 * There are two, and keeping them apart is what makes the ledger readable rather than a
 * log. A [TURN] is one agent run — what was asked, what came back, what it cost. A [TOOL]
 * is one step inside it — which tool, which rung of the ladder, what happened. The user's
 * question is "what did you do as me", and the answer is a turn with its steps under it.
 */
enum class LedgerKind { TURN, TOOL }

/**
 * How a step or a run ended.
 *
 * [BLOCKED] is deliberately not folded into [ERROR]: a tool the provenance gate or the
 * route gate stopped is the ledger's most interesting row, because it is the boundary
 * doing its job, and a user reading the ledger should be able to see that separately from
 * a tool that ran and failed.
 */
enum class LedgerOutcome { OK, ERROR, BLOCKED }

/**
 * One action inside a step, as it is stored.
 *
 * Deliberately small. The ledger records *that* a tool ran, how it ended and how long it
 * took — never its arguments or its output. Tool inputs routinely carry message bodies,
 * addresses and file contents, and `CLAUDE.md` §6's rule that nothing sensitive is
 * persisted anywhere new applies to a store the user is invited to read and export.
 */
data class LedgerAction(
    val tool: String,
    val ok: Boolean,
    val durationMs: Long,
    /** A short, non-sensitive note — a block reason, an abort reason. Never tool output. */
    val note: String? = null,
)

/**
 * A row before the chain has been applied to it.
 *
 * [LedgerRepository.append] is what assigns `seq`, `prevHash` and `hash`; nothing else may,
 * because those three are what make the store tamper-evident and they can only be decided
 * one writer at a time.
 */
data class LedgerDraft(
    val sessionId: String,
    val kind: LedgerKind,
    /** What the run was asked to do. Truncated; see [MAX_INTENT_CHARS]. */
    val intent: String,
    val provenance: String,
    val outcome: LedgerOutcome,
    val routeRung: Int? = null,
    /** `flow.id@version` when the step replayed a compiled flow. */
    val flowRef: String? = null,
    val actions: List<LedgerAction> = emptyList(),
    /** Frame ids from [org.ethereumphone.andyclaw.frames.SessionFrameStore], in order. */
    val frames: List<String> = emptyList(),
    val modelIds: List<String> = emptyList(),
    /**
     * What the step or run cost in USD, or null when it cannot be known.
     *
     * Null is a real state and is not the same as `0.0`: a model whose pricing the
     * registry has never seen has an unknown cost, and rendering that as free would be a
     * lie in the one screen whose whole job is being trustworthy.
     */
    val costUsd: Double? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val durationMs: Long = 0L,
    val ts: Long = 0L,
) {
    companion object {
        const val MAX_INTENT_CHARS = 256
    }
}
