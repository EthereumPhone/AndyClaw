package org.ethereumphone.andyclaw.ledger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.logging.Logger

/**
 * Where a ledger row is handed in from code that cannot suspend.
 *
 * The engine's `PostProcessor` is an ordinary function and runs inside the tool loop, so
 * it can neither await a database write nor afford to. A sink takes the row and returns.
 */
fun interface LedgerSink {
    fun record(draft: LedgerDraft)
}

/**
 * The sink, backed by one writer coroutine.
 *
 * A channel with a single consumer buys two things at once: the hot path never touches
 * the disk, and rows keep the order they were handed in — which matters, because the order
 * *is* the chain. Launching a coroutine per row would give neither.
 *
 * **Overflow is recorded, not swallowed.** The buffer is bounded, because an unbounded one
 * turns a stalled writer into a memory leak. When it fills, the row is dropped and counted,
 * and the next successful write is preceded by a row saying how many were lost. A ledger
 * that quietly has holes in it is worse than one that admits to them: the whole claim it
 * makes is "this is everything I did".
 */
class LedgerRecorder(
    private val scope: CoroutineScope,
    private val repository: LedgerRepository,
    bufferSize: Int = DEFAULT_BUFFER,
) : LedgerSink {

    private sealed interface Op {
        data class Row(val draft: LedgerDraft) : Op
        data class Flush(val done: CompletableDeferred<Unit>) : Op
    }

    private val log = Logger.getLogger("LedgerRecorder")
    private val ops = Channel<Op>(capacity = bufferSize)
    private var dropped = 0

    init {
        scope.launch {
            for (op in ops) {
                when (op) {
                    is Op.Row -> writeRow(op.draft)
                    is Op.Flush -> op.done.complete(Unit)
                }
            }
        }
    }

    override fun record(draft: LedgerDraft) {
        if (ops.trySend(Op.Row(draft)).isSuccess) return
        synchronized(this) { dropped++ }
    }

    /** Wait until everything handed in so far has been written. Tests, and export. */
    suspend fun drain() {
        val done = CompletableDeferred<Unit>()
        if (ops.trySend(Op.Flush(done)).isSuccess) done.await()
    }

    private suspend fun writeRow(draft: LedgerDraft) {
        val lost = synchronized(this) { val n = dropped; dropped = 0; n }
        if (lost > 0) {
            runCatching {
                repository.append(
                    LedgerDraft(
                        sessionId = draft.sessionId,
                        kind = LedgerKind.TOOL,
                        intent = "ledger overflow",
                        provenance = draft.provenance,
                        outcome = LedgerOutcome.ERROR,
                        actions = listOf(
                            LedgerAction(
                                tool = "ledger",
                                ok = false,
                                durationMs = 0L,
                                note = "$lost row(s) were dropped because the writer fell behind",
                            )
                        ),
                    )
                )
            }
        }
        runCatching { repository.append(draft) }
            .onFailure { log.warning("ledger append failed: ${it.message}") }
    }

    companion object {
        private const val DEFAULT_BUFFER = 512
    }
}
