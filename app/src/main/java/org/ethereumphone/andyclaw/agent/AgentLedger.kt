package org.ethereumphone.andyclaw.agent

import org.ethereumphone.andyclaw.ledger.LedgerSink

/** Per-million-token prices for one model, as the registry stores them. */
data class ModelPrice(val promptPerToken: Double, val completionPerToken: Double)

/**
 * Everything a run needs in order to write itself down.
 *
 * Bundled rather than passed as five parameters through `ExecutionEngineFactory.create`,
 * which is called **once per tool call** inside the hot loop — a wider signature there is
 * paid for on every tool the agent uses.
 *
 * Both lookups are functions rather than objects for the same reason the rest of this
 * subsystem is: neither the price registry nor the flow store belongs to the ledger, and
 * taking them by reference would put a network-refreshed cache and a keystore-backed file
 * store behind every ledger row.
 */
class AgentLedger(
    val sink: LedgerSink,
    /** The conversation this run belongs to. Frames and ledger rows join on it. */
    val sessionId: String,
    private val priceOf: (String) -> ModelPrice? = { null },
    private val flowRefOf: (String) -> String? = { null },
) {

    /**
     * What a run cost, or null when it cannot be known.
     *
     * Null rather than zero for an unpriced model, and the distinction is the point: the
     * ledger is the screen whose entire job is being trustworthy, and rendering an unknown
     * cost as free is the kind of small lie that makes the rest of it worth less. ethOS
     * Premium models, local models and anything the OpenRouter registry has not seen all
     * land here.
     */
    fun costOf(modelIds: Collection<String>, inputTokens: Int, outputTokens: Int): Double? {
        val price = modelIds.firstNotNullOfOrNull { priceOf(it) } ?: return null
        return inputTokens * price.promptPerToken + outputTokens * price.completionPerToken
    }

    /** `flow.id@version` when [toolName] is a compiled flow, else null. */
    fun flowRef(toolName: String): String? = flowRefOf(toolName)
}
