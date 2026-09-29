package org.ethereumphone.andyclaw.ledger

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * Wait until everything handed in so far has been written — at most [timeoutMs]. Tests, and
     * export. A suspending send, not `trySend`: with the buffer full, `trySend` used to return at
     * once, and the export that trusted it went out without the rows still queued.
     */
    suspend fun drain(timeoutMs: Long = DRAIN_TIMEOUT_MS): Boolean {
        val done = CompletableDeferred<Unit>()
        return withTimeoutOrNull(timeoutMs) {
            ops.send(Op.Flush(done))
            done.await()
            true
        } ?: false
    }

    private suspend fun writeRow(draft: LedgerDraft) {
        // Read, don't reset: the count is cleared only once the overflow row is written. Zeroing
        // it first meant a failed append lost the record of the gap along with the rows.
        val lost = synchronized(this) { dropped }
        if (lost > 0) {
            runCatching {
                // Its own session: the rows that were lost could have belonged to any run, and
                // filed under the next row's session it read as a step of that run.
                repository.append(
                    LedgerDraft(
                        sessionId = LedgerDigest.SYSTEM_SESSION,
                        kind = LedgerKind.TOOL,
                        intent = "ledger overflow",
                        provenance = "TRUSTED",
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
            }.onSuccess {
                // Subtract, not zero: rows dropped while this append ran are still owed a record.
                synchronized(this) { dropped -= lost }
            }.onFailure { log.warning("ledger overflow row failed, will retry: ${it.message}") }
        }
        runCatching { repository.append(draft) }
            .onFailure { log.warning("ledger append failed: ${it.message}") }
    }

    companion object {
        private const val DEFAULT_BUFFER = 512
        const val DRAIN_TIMEOUT_MS = 2_000L
    }
}
