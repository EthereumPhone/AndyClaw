package org.ethereumphone.andyclaw.agent

import org.ethereumphone.andyclaw.ledger.LedgerAction
import org.ethereumphone.andyclaw.ledger.LedgerDraft
import org.ethereumphone.andyclaw.ledger.LedgerKind
import org.ethereumphone.andyclaw.ledger.LedgerOutcome
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Where a run's steps are written, for code that reaches tools without the engine.
 *
 * `execute_code`'s ToolBridge calls the registry directly, so the engine's ledger processor never
 * saw those calls: code could send a message, and the run's record showed only "execute_code".
 * The bridge writes one row per call through this.
 */
class LedgerContext(
    private val ledger: AgentLedger,
    private val provenance: String,
    private val intent: String,
) : AbstractCoroutineContextElement(Key) {

    fun recordStep(tool: String, outcome: LedgerOutcome, durationMs: Long, note: String? = null) {
        runCatching {
            ledger.sink.record(
                LedgerDraft(
                    sessionId = ledger.sessionId,
                    kind = LedgerKind.TOOL,
                    intent = intent,
                    provenance = provenance,
                    outcome = outcome,
                    flowRef = ledger.flowRef(tool),
                    actions = listOf(LedgerAction(tool, outcome == LedgerOutcome.OK, durationMs, note)),
                    durationMs = durationMs,
                )
            )
        }
    }

    companion object Key : CoroutineContext.Key<LedgerContext>
}
