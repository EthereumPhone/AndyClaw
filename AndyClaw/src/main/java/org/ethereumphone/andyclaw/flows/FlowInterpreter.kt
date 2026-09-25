package org.ethereumphone.andyclaw.flows

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * The device surface a flow is replayed against — the same `IAgentDisplayService`
 * methods `AgentDisplaySkill` calls, behind an interface so the interpreter itself has
 * no Android dependency and can be tested against a fake screen.
 *
 * Node actions only. There is deliberately no `tap(x, y)` here: a flow that could reach
 * coordinates would be a flow that could be compiled with them.
 */
interface FlowDisplayDriver {

    /** `versionName` of an installed package, or null when it is not installed. */
    suspend fun installedVersion(packageName: String): String?

    /** Create the display if needed and bring [packageName] up on it. */
    suspend fun ensureApp(packageName: String): Boolean

    /** The accessibility tree as JSON, or null when it cannot be read. */
    suspend fun uiTree(): String?

    /** `AccessibilityNodeInfo.ACTION_CLICK` on the node with this view id. */
    suspend fun clickNode(viewId: String, index: Int): Boolean

    /** Set text on an editable node. */
    suspend fun setNodeText(viewId: String, text: String): Boolean
}

/** Asked to cross a `checkpoint:`. False means the user did not agree, and the flow stops. */
fun interface FlowCheckpointHandler {
    suspend fun confirm(flow: Flow, checkpoint: String, params: Map<String, String>): Boolean
}

/**
 * Whether the user pressed STOP. Asked before every step and every driver action, and inside
 * every wait — a replay is fast, but it is also the one thing on the display nobody watches.
 */
fun interface FlowStopSignal {
    fun stopRequested(): Boolean

    companion object {
        val NEVER = FlowStopSignal { false }
    }
}

enum class FlowAbortReason {
    APP_NOT_INSTALLED,
    APP_VERSION_MISMATCH,
    MISSING_PARAM,
    DISPLAY_UNAVAILABLE,
    PRECONDITION_FAILED,
    CHECKSUM_MISMATCH,
    STEP_FAILED,
    WAIT_TIMEOUT,
    ASSERT_FAILED,
    CHECKPOINT_REFUSED,
    BUDGET_EXCEEDED,
    UNSUPPORTED,

    /**
     * The step's view id matched more than one node — a list row — and nothing after it proves
     * the right one was hit before anything irreversible happens. The driver would take the first
     * match, which after a re-sort is somebody else.
     */
    AMBIGUOUS_TARGET,

    /**
     * The live target reads as payment, a password or a sign-in, or a private app is on screen.
     * Never automated, and never handed to another way of automating it.
     */
    SENSITIVE_TARGET,

    /** The user pressed STOP. Not the flow's fault: never counted, never retried. */
    STOPPED,
}

sealed interface FlowRunResult {
    val trace: List<String>
    val durationMs: Long

    data class Completed(
        val stepsRun: Int,
        override val durationMs: Long,
        override val trace: List<String>,
    ) : FlowRunResult

    data class Aborted(
        val reason: FlowAbortReason,
        val message: String,
        val stepIndex: Int?,
        override val durationMs: Long,
        override val trace: List<String>,
        /**
         * The flow may already have done its irreversible thing — it crossed its checkpoint and
         * acted — so the task must not be repeated by any other route: not the autopilot, not
         * the model. A send that could not be confirmed is still probably a send.
         */
        val committed: Boolean = false,
    ) : FlowRunResult

    val isCompleted: Boolean get() = this is Completed
}

/**
 * Rung 3. Replays a compiled flow with **no model in the loop**.
 *
 * The whole value of the rung is that it costs nothing per run: no screenshot, no token,
 * no round trip. What buys that is being willing to stop. Every step asserts the screen
 * it expects before it acts, every wait has a deadline, and every failure aborts back to
 * the caller so the VLM path can take over and recompile. `agent-os-design.md` §3:
 * "Mismatch → abort, fall back to Rung 4, recompile. **Never guess.**"
 *
 * The interpreter never decides that an irreversible step is fine. It refuses to cross a
 * `checkpoint:` unless [FlowCheckpointHandler] says the user agreed.
 */
class FlowInterpreter(
    private val driver: FlowDisplayDriver,
    private val checkpoints: FlowCheckpointHandler,
    /** Injected so tests do not sleep. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val stop: FlowStopSignal = FlowStopSignal.NEVER,
) {

    suspend fun run(
        flow: Flow,
        params: Map<String, String>,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): FlowRunResult {
        val started = clock()
        val trace = mutableListOf<String>()
        // Crossing the checkpoint and acting after it is the moment the flow may have changed
        // the world for good. Past it, no failure may be answered by doing the task again.
        var crossedCheckpoint = false
        var committed = false

        fun elapsed() = clock() - started
        fun abort(reason: FlowAbortReason, message: String, step: Int? = null): FlowRunResult.Aborted {
            trace += "abort:$reason ${step?.let { "step=$it " } ?: ""}$message"
            return FlowRunResult.Aborted(reason, message, step, elapsed(), trace.toList(), committed)
        }

        suspend fun stopped(): Boolean {
            currentCoroutineContext().ensureActive()
            return stop.stopRequested()
        }

        /** A fresh tree, read again a couple of times before giving up. Never the stale one. */
        suspend fun readTree(): String? {
            repeat(READ_ATTEMPTS) { attempt ->
                driver.uiTree()?.let { return it }
                if (attempt < READ_ATTEMPTS - 1) sleep(POLL_MS)
            }
            return null
        }

        if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user")

        // ── Version pin ───────────────────────────────────────────────
        val installed = driver.installedVersion(flow.app)
            ?: return abort(FlowAbortReason.APP_NOT_INSTALLED, "${flow.app} is not installed")
        if (!AppVersionRange.contains(flow.appVersionRange, installed)) {
            return abort(
                FlowAbortReason.APP_VERSION_MISMATCH,
                "${flow.app} is $installed, outside the compiled range ${flow.appVersionRange}",
            )
        }
        trace += "version:$installed in ${flow.appVersionRange}"

        // ── Params ────────────────────────────────────────────────────
        val missing = flow.params.filter { params[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            return abort(FlowAbortReason.MISSING_PARAM, "missing ${missing.joinToString()}")
        }

        // ── Bring the app up ──────────────────────────────────────────
        if (!driver.ensureApp(flow.app)) {
            return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "could not launch ${flow.app}")
        }
        if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user")
        var tree = readTree()
            ?: return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "no accessibility tree")
        FlowTargetGuard.privateAppOn(tree)?.let {
            return abort(FlowAbortReason.SENSITIVE_TARGET, "$it is a private app")
        }

        // ── Preconditions ─────────────────────────────────────────────
        for (condition in flow.preconditions) {
            if (!holds(condition, tree, params)) {
                return abort(
                    FlowAbortReason.PRECONDITION_FAILED,
                    "precondition ${describe(condition)} does not hold",
                )
            }
        }
        trace += "preconditions:ok"

        // ── Steps ─────────────────────────────────────────────────────
        flow.steps.forEachIndexed { index, step ->
            if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
            if (elapsed() > budgetMs) {
                return abort(
                    FlowAbortReason.BUDGET_EXCEEDED,
                    "flow exceeded its ${budgetMs}ms budget",
                    index,
                )
            }

            // Assert the screen is the one this step was compiled against, before
            // touching anything. This is the perceptual checksum doing its job — given a
            // moment first, because the previous action's screen may still be settling.
            step.expectChecksum?.let { expected ->
                var actual = NodeTreeChecksum.matching(expected, tree)
                var polls = 0
                val deadline = clock() + CHECKSUM_SETTLE_MS
                while (actual != expected && polls < MAX_CHECKSUM_POLLS && clock() < deadline) {
                    if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                    sleep(POLL_MS)
                    polls++
                    tree = readTree()
                        ?: return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "the screen could not be read", index)
                    actual = NodeTreeChecksum.matching(expected, tree)
                }
                if (actual != expected) {
                    return abort(
                        FlowAbortReason.CHECKSUM_MISMATCH,
                        "screen shape changed (expected $expected, found ${actual.ifEmpty { "unreadable" }})",
                        index,
                    )
                }
            }

            FlowTargetGuard.privateAppOn(tree)?.let {
                return abort(FlowAbortReason.SENSITIVE_TARGET, "$it is a private app", index)
            }

            when (step) {
                is TapStep -> {
                    val viewId = step.viewId
                        ?: return abort(FlowAbortReason.UNSUPPORTED, "tap without a view_id", index)
                    checkTarget(flow, index, step, viewId, tree, typing = false)?.let { (reason, message) ->
                        return abort(reason, message, index)
                    }
                    if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                    if (crossedCheckpoint || acts(step)) committed = true
                    val ok = driver.clickNode(viewId, step.index ?: 0)
                    if (!ok) return abort(FlowAbortReason.STEP_FAILED, "tap on $viewId failed", index)
                    trace += "tap:$viewId"
                    sleep(SETTLE_TAP_MS)
                    tree = readTree()
                        ?: return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "the screen could not be read after tapping $viewId", index)
                }

                is TypeStep -> {
                    val viewId = step.target.viewId
                        ?: return abort(FlowAbortReason.UNSUPPORTED, "type without a view_id", index)
                    checkTarget(flow, index, step, viewId, tree, typing = true)?.let { (reason, message) ->
                        return abort(reason, message, index)
                    }
                    if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                    if (crossedCheckpoint || acts(step)) committed = true
                    val value = substitute(step.value, params)
                    val ok = driver.setNodeText(viewId, value)
                    if (!ok) return abort(FlowAbortReason.STEP_FAILED, "typing into $viewId failed", index)
                    trace += "type:$viewId"
                    sleep(SETTLE_TYPE_MS)
                    tree = readTree()
                        ?: return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "the screen could not be read after typing into $viewId", index)
                }

                is WaitForStep -> {
                    val deadline = clock() + step.timeoutMs
                    var seen = false
                    var polls = 0
                    while (true) {
                        if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                        // Only a read that succeeded can satisfy the wait: the old tree is the
                        // screen before whatever this step is waiting for.
                        val fresh = driver.uiTree()
                        if (fresh != null) {
                            tree = fresh
                            if (waitSatisfied(step, fresh, params)) {
                                seen = true
                                break
                            }
                        }
                        if (clock() >= deadline || polls >= maxPolls(step.timeoutMs)) break
                        sleep(POLL_MS)
                        polls++
                    }
                    if (!seen) {
                        return abort(
                            FlowAbortReason.WAIT_TIMEOUT,
                            "${step.viewId} did not appear within ${step.timeoutMs}ms",
                            index,
                        )
                    }
                    trace += "wait_for:${step.viewId}"
                }

                is AssertStep -> {
                    val expectedText = step.nodeTextContains?.let { substitute(it, params) }
                    if (expectedText != null) {
                        val actual = NodeTreeChecksum.textOf(tree, step.viewId)
                        if (!actual.contains(expectedText, ignoreCase = true)) {
                            return abort(
                                FlowAbortReason.ASSERT_FAILED,
                                "${step.viewId} does not contain '$expectedText'",
                                index,
                            )
                        }
                    }
                    trace += "assert:${step.viewId}"
                }

                is CheckpointStep -> {
                    if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                    val agreed = checkpoints.confirm(flow, step.name, params)
                    if (!agreed) {
                        return abort(
                            FlowAbortReason.CHECKPOINT_REFUSED,
                            "checkpoint '${step.name}' was not confirmed",
                            index,
                        )
                    }
                    crossedCheckpoint = true
                    trace += "checkpoint:${step.name}"
                }
            }
        }

        // ── Postconditions ────────────────────────────────────────────
        // Read until they hold, for a few seconds: a slow app shows the sent bubble late, and
        // reading once, straight after the last tap, called a send that worked a failure — and
        // a failure is what invited somebody to send it again. Bounded by a poll count as well
        // as the clock, so a frozen clock cannot keep it here.
        var polls = 0
        val deadline = clock() + POSTCONDITION_WAIT_MS
        while (true) {
            if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user")
            val fresh = driver.uiTree()
            val failed = if (fresh == null) flow.postconditions.firstOrNull()
            else flow.postconditions.firstOrNull { !holds(it, fresh, params) }
            if (failed == null) break
            if (polls >= MAX_POSTCONDITION_POLLS || clock() >= deadline) {
                return abort(
                    FlowAbortReason.ASSERT_FAILED,
                    "postcondition ${describe(failed)} does not hold — the flow ran but cannot " +
                        "prove it worked",
                )
            }
            sleep(POLL_MS)
            polls++
        }
        trace += "postconditions:ok"

        return FlowRunResult.Completed(flow.steps.size, elapsed(), trace.toList())
    }

    /**
     * Why the step at [index] must not touch [viewId] on this screen, or null.
     *
     * Checked against the live screen, not the compiled IR: a flow compiled when the button
     * said "Next" must not tap it once it says "Pay €49", and a list row is only safe to take
     * by position when something afterwards proves it was the right one.
     */
    private fun checkTarget(
        flow: Flow,
        index: Int,
        step: FlowStep,
        viewId: String,
        tree: String?,
        typing: Boolean,
    ): Pair<FlowAbortReason, String>? {
        FlowTargetGuard.sensitiveReason(tree, viewId, typing)?.let {
            return FlowAbortReason.SENSITIVE_TARGET to "$viewId $it"
        }
        val matches = NodeTreeChecksum.nodesWithViewId(tree, viewId).size
        // An irreversible step on a repeated id is never safe: no later assert can take it back.
        if (matches > 1 && (acts(step) || !FlowTargetGuard.identityAssertedAfter(flow.steps, index))) {
            return FlowAbortReason.AMBIGUOUS_TARGET to
                "$viewId matches $matches nodes and nothing before the next irreversible step proves which one was meant"
        }
        return null
    }

    /** A step that is itself irreversible by its target, checkpoint or not. */
    private fun acts(step: FlowStep): Boolean = FlowStepEffects.of(step).ordinal >= ToolEffect.IRREVERSIBLE.ordinal

    private fun maxPolls(timeoutMs: Long): Int = (timeoutMs / POLL_MS).toInt() + 1

    // ── Helpers ───────────────────────────────────────────────────────

    private fun waitSatisfied(step: WaitForStep, tree: String?, params: Map<String, String>): Boolean {
        val viewId = step.viewId ?: return false
        if (viewId !in NodeTreeChecksum.viewIdsOf(tree)) return false
        val text = step.nodeTextContains?.let { substitute(it, params) } ?: return true
        return NodeTreeChecksum.textOf(tree, viewId).contains(text, ignoreCase = true)
    }

    private fun holds(condition: Condition, tree: String?, params: Map<String, String>): Boolean {
        val viewId = condition.viewId ?: return false
        return when (condition) {
            is NodeExists -> viewId in NodeTreeChecksum.viewIdsOf(tree)
            is NodeTextContains -> NodeTreeChecksum.textOf(tree, viewId)
                .contains(substitute(condition.value, params), ignoreCase = true)
        }
    }

    private fun describe(condition: Condition): String = "${condition.opcode}(${condition.viewId})"

    companion object {
        /**
         * `agent-os-design.md` §6: every task carries a hard budget. A flow that has not
         * finished in this long has lost the screen it was compiled against.
         */
        const val DEFAULT_BUDGET_MS = 15_000L

        /** Mirrors `AgentDisplaySkill`'s own settle times — a11y actions commit fast. */
        const val SETTLE_TAP_MS = 80L
        const val SETTLE_TYPE_MS = 40L
        const val POLL_MS = 60L

        /** How long a step's expected screen may take to settle before it counts as changed. */
        const val CHECKSUM_SETTLE_MS = 1_500L
        const val MAX_CHECKSUM_POLLS = (CHECKSUM_SETTLE_MS / POLL_MS).toInt() + 1

        /** How long the result of the last step may take to show. */
        const val POSTCONDITION_WAIT_MS = 3_000L
        const val MAX_POSTCONDITION_POLLS = (POSTCONDITION_WAIT_MS / POLL_MS).toInt() + 1

        /** Reads of the tree before an unreadable screen aborts, rather than acting on an old one. */
        const val READ_ATTEMPTS = 3

        /** `{{name}}` -> the value bound to `name`. Unbound placeholders are left alone. */
        fun substitute(template: String, params: Map<String, String>): String =
            PLACEHOLDER.replace(template) { match ->
                params[match.groupValues[1]] ?: match.value
            }

        private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}")
    }
}
