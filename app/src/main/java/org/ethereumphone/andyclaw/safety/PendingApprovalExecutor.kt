package org.ethereumphone.andyclaw.safety

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.ExecutionEngine.ToolCallResult
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.agent.AgentRunToken
import org.ethereumphone.andyclaw.agent.TokenUsageSnapshot
import org.ethereumphone.andyclaw.skills.RunEnd
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.builtin.AgentDisplayLease
import java.util.UUID

/**
 * APPROVE on a pending request: run exactly the call that was refused.
 *
 * Not a prompt to a model that might do something else — the tool and the input the store
 * holds, once, through the same engine as any other call, so every gate still runs:
 * - under the **original** provenance, so an approved stranger's request is still that, and never
 *   compiles into a flow or passes for the owner's own;
 * - with [ExactCallCallbacks], which approve that one tool with that one input and nothing else;
 * - recorded in the ledger session the refused run wrote to.
 *
 * The launcher waits for the answer on a binder thread, so a call that takes longer than
 * [WAIT_MS] answers RUNNING and finishes in [scope]; the ledger shows how it ended.
 */
class PendingApprovalExecutor(
    private val store: PendingApprovalStore,
    private val scope: CoroutineScope,
    /** Runs one call through the engine and returns its result; wired in `NodeApp`. */
    private val runCall: suspend (entry: PendingApprovalStore.Entry, input: JsonObject, token: AgentRunToken) -> ToolCallResult,
    /** The run is over: give back what it took (the agent display). */
    private val onRunFinished: (runId: String, end: RunEnd) -> Unit = { _, _ -> },
    /** Writes the owner's decision to the ledger. */
    private val recordDecision: (entry: PendingApprovalStore.Entry, state: String, title: String) -> Unit = { _, _, _ -> },
) {

    data class Resolution(
        val state: String,
        val message: String,
        val ledgerSessionId: String? = null,
        val requestId: String? = null,
    ) {
        fun toJson(): String = buildJsonObject {
            put("state", state)
            put("message", message)
            ledgerSessionId?.let { put("ledgerSessionId", it) }
            requestId?.let { put("requestId", it) }
        }.toString()
    }

    companion object {
        private const val TAG = "PendingApprovalExec"
        /** How long the launcher is kept waiting before it is told the call is still running. */
        const val WAIT_MS = 20_000L
        /** How long an approved call may run at all. */
        const val EXEC_TIMEOUT_MS = 120_000L
        private const val MAX_REASON = 160
    }

    suspend fun resolve(id: String, approved: Boolean): Resolution =
        if (approved) approve(id) else decline(id)

    fun decline(id: String): Resolution {
        val entry = store.getAll().firstOrNull { it.id == id }
            ?: return alreadyHandled(id)
        if (entry.state == PendingApprovalStore.State.EXECUTING) {
            return Resolution("RUNNING", "It's already running. The ledger will show how it ends.", entry.ledgerSessionId)
        }
        if (!store.decline(id, "Declined. Nothing was run.")) return alreadyHandled(id)
        recordDecision(entry, "DECLINED", ApprovalSummaries.of(entry.toolName, null).title)
        return Resolution("DECLINED", "Declined. Nothing was run.", entry.ledgerSessionId)
    }

    suspend fun approve(id: String, waitMs: Long = WAIT_MS): Resolution {
        val claimed = when (val claim = store.claim(id)) {
            is PendingApprovalStore.Claim.Claimed -> claim
            PendingApprovalStore.Claim.Missing -> return alreadyHandled(id)
            PendingApprovalStore.Claim.Running ->
                return Resolution("RUNNING", "It's already running. The ledger will show how it ends.")
            PendingApprovalStore.Claim.Expired ->
                return Resolution("EXPIRED", "This request expired, so it didn't run.")
            PendingApprovalStore.Claim.NotExecutable -> return Resolution(
                "NOT_EXECUTABLE",
                "This request was saved without everything needed to run it exactly, so it can't be run " +
                    "from here. Decline it, and ask again if you still want it.",
            )
            PendingApprovalStore.Claim.Unsaved ->
                return Resolution("FAILED", "The approval couldn't be recorded, so nothing was run. Try again.")
        }
        val requestId = UUID.randomUUID().toString()
        val work: Deferred<Resolution> = scope.async { execute(claimed.entry, claimed.input, requestId) }
        return withTimeoutOrNull(waitMs) { work.await() }
            ?: Resolution("RUNNING", "It's still running. The ledger will show how it ends.",
                claimed.entry.ledgerSessionId, requestId)
    }

    private suspend fun execute(entry: PendingApprovalStore.Entry, input: JsonObject, requestId: String): Resolution {
        val title = ApprovalSummaries.of(entry.toolName, input).title
        val token = AgentRunToken(
            job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job],
            ledgerSessionId = entry.ledgerSessionId,
            provenance = entry.provenance,
        )
        var end = RunEnd.FAILED
        val session = entry.ledgerSessionId
        var recorded: String? = null
        try {
            val result = withTimeout(EXEC_TIMEOUT_MS) { runCall(entry, input, token) }
            val resolution = when {
                token.stopRequested || result.content == AgentDisplayLease.STOPPED ->
                    Resolution("STOPPED", "Stopped.", session, requestId).also { end = RunEnd.STOPPED }
                // The display tools answer "busy" as their own result, not as a pre-flight block:
                // nothing ran either way, and the card waits for a better moment.
                result.content == AgentDisplayLease.BUSY -> return retryLater(entry, result, session, requestId)
                result.phase == ToolCallResult.Phase.BLOCKED_PREFLIGHT -> blocked(entry, result, session, requestId)
                    ?: return retryLater(entry, result, session, requestId)
                result.isError -> Resolution("FAILED", "It ran, but it didn't work: ${reason(result.content)}", session, requestId)
                else -> Resolution("DONE", "Done: $title.", session, requestId).also { end = RunEnd.OK }
            }
            store.finish(entry.id, resolution.state, resolution.message, session, requestId)
            recorded = resolution.state
            return resolution
        } catch (e: TimeoutCancellationException) {
            val r = Resolution(PendingApprovalStore.UNKNOWN,
                "It took too long and was stopped, so it may have partly run. Check the ledger before trying again.",
                session, requestId)
            store.finish(entry.id, r.state, r.message, session, requestId)
            recorded = r.state
            return r
        } catch (e: CancellationException) {
            store.finish(entry.id, PendingApprovalStore.UNKNOWN,
                "It was interrupted, so it may have partly run. Check the ledger before trying again.", session, requestId)
            recorded = PendingApprovalStore.UNKNOWN
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "approved call ${entry.toolName} threw", e)
            val r = Resolution("FAILED", "It didn't work: ${reason(e.message.orEmpty())}", session, requestId)
            store.finish(entry.id, r.state, r.message, session, requestId)
            recorded = r.state
            return r
        } finally {
            // The display (and its recording) first, then the turn row: both are this run's.
            onRunFinished(token.id, end)
            AgentDisplayLease.release(token.id)
            // Nothing ran for a "try later": no turn to record, the card is still waiting.
            recorded?.let { recordDecision(entry, it, title) }
        }
    }

    /** A refusal that ends the request, or null for one that only means "not now". */
    private fun blocked(entry: PendingApprovalStore.Entry, result: ToolCallResult, session: String?, requestId: String): Resolution? {
        val text = result.content
        if (text == AgentDisplayLease.BUSY || text.contains("is rate-limited")) return null
        return Resolution("BLOCKED", "It was blocked, so it didn't run: ${reason(text)}", session, requestId)
    }

    /** The display was busy or the rate limit hit: nothing ran, and the card stays for later. */
    private fun retryLater(entry: PendingApprovalStore.Entry, result: ToolCallResult, session: String?, requestId: String): Resolution {
        store.release(entry.id)
        val why = if (result.content == AgentDisplayLease.BUSY) "The agent display is busy with another task."
        else "Too many actions in a short time."
        return Resolution("BLOCKED", "$why Nothing was run — try again in a moment.", session, requestId)
    }

    private fun alreadyHandled(id: String): Resolution {
        val done = store.outcome(id)
        return Resolution(
            "ALREADY_HANDLED",
            done?.message?.let { "Already handled: $it" } ?: "This request is no longer waiting.",
            done?.ledgerSessionId,
            done?.requestId,
        )
    }

    /** One line of a refusal or an error, without the internal "[Gate]" prefix. */
    private fun reason(text: String): String =
        text.lineSequence().firstOrNull().orEmpty()
            .replace(Regex("^\\[[A-Za-z]+\\]\\s*"), "")
            .trim()
            .let { if (it.length > MAX_REASON) it.take(MAX_REASON).trimEnd() + "…" else it }
            .ifEmpty { "no reason was given" }
}

/**
 * The callbacks for an approved call: the owner has already said yes to exactly one tool with
 * exactly one input, so that — and only that — is approved again when the engine asks. Anything
 * else (a different tool, a changed input) is refused. Nothing is streamed and nobody is asked.
 */
class ExactCallCallbacks(
    private val toolName: String,
    input: JsonObject,
    private val hasPermission: (String) -> Boolean = { false },
) : AgentLoop.Callbacks {

    private val canonicalInput = PendingApprovalStore.canonical(input)

    override fun onToken(text: String) {}
    override fun onToolExecution(toolName: String) {}
    override fun onToolResult(toolName: String, result: SkillResult, input: JsonObject?) {}

    override suspend fun onApprovalNeeded(description: String, toolName: String?, toolInput: JsonObject?): Boolean =
        toolName == this.toolName && toolInput != null && PendingApprovalStore.canonical(toolInput) == canonicalInput

    override suspend fun onPermissionsNeeded(permissions: List<String>): Boolean = permissions.all(hasPermission)

    override fun onComplete(fullText: String, tokenUsage: TokenUsageSnapshot?) {}
    override fun onError(error: Throwable) {}
}
