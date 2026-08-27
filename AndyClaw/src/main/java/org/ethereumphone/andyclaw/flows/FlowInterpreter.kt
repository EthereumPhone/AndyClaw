package org.ethereumphone.andyclaw.flows

import kotlinx.coroutines.delay

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
) {

    suspend fun run(
        flow: Flow,
        params: Map<String, String>,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): FlowRunResult {
        val started = clock()
        val trace = mutableListOf<String>()

        fun elapsed() = clock() - started
        fun abort(reason: FlowAbortReason, message: String, step: Int? = null): FlowRunResult.Aborted {
            trace += "abort:$reason ${step?.let { "step=$it " } ?: ""}$message"
            return FlowRunResult.Aborted(reason, message, step, elapsed(), trace.toList())
        }

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
        var tree = driver.uiTree()
            ?: return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "no accessibility tree")

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
            if (elapsed() > budgetMs) {
                return abort(
                    FlowAbortReason.BUDGET_EXCEEDED,
                    "flow exceeded its ${budgetMs}ms budget",
                    index,
                )
            }

            // Assert the screen is the one this step was compiled against, before
            // touching anything. This is the perceptual checksum doing its job.
            step.expectChecksum?.let { expected ->
                val actual = NodeTreeChecksum.of(tree)
                if (actual != expected) {
                    return abort(
                        FlowAbortReason.CHECKSUM_MISMATCH,
                        "screen shape changed (expected $expected, found ${actual.ifEmpty { "unreadable" }})",
                        index,
                    )
                }
            }

            when (step) {
                is TapStep -> {
                    val viewId = step.viewId
                        ?: return abort(FlowAbortReason.UNSUPPORTED, "tap without a view_id", index)
                    val ok = driver.clickNode(viewId, step.index ?: 0)
                    if (!ok) return abort(FlowAbortReason.STEP_FAILED, "tap on $viewId failed", index)
                    trace += "tap:$viewId"
                    sleep(SETTLE_TAP_MS)
                    tree = driver.uiTree() ?: tree
                }

                is TypeStep -> {
                    val viewId = step.target.viewId
                        ?: return abort(FlowAbortReason.UNSUPPORTED, "type without a view_id", index)
                    val value = substitute(step.value, params)
                    val ok = driver.setNodeText(viewId, value)
                    if (!ok) return abort(FlowAbortReason.STEP_FAILED, "typing into $viewId failed", index)
                    trace += "type:$viewId"
                    sleep(SETTLE_TYPE_MS)
                    tree = driver.uiTree() ?: tree
                }

                is WaitForStep -> {
                    val deadline = clock() + step.timeoutMs
                    var seen = false
                    while (true) {
                        tree = driver.uiTree() ?: tree
                        if (waitSatisfied(step, tree, params)) {
                            seen = true
                            break
                        }
                        if (clock() >= deadline) break
                        sleep(POLL_MS)
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
                    val agreed = checkpoints.confirm(flow, step.name, params)
                    if (!agreed) {
                        return abort(
                            FlowAbortReason.CHECKPOINT_REFUSED,
                            "checkpoint '${step.name}' was not confirmed",
                            index,
                        )
                    }
                    trace += "checkpoint:${step.name}"
                }
            }
        }

        // ── Postconditions ────────────────────────────────────────────
        tree = driver.uiTree() ?: tree
        for (condition in flow.postconditions) {
            if (!holds(condition, tree, params)) {
                return abort(
                    FlowAbortReason.ASSERT_FAILED,
                    "postcondition ${describe(condition)} does not hold — the flow ran but cannot " +
                        "prove it worked",
                )
            }
        }
        trace += "postconditions:ok"

        return FlowRunResult.Completed(flow.steps.size, elapsed(), trace.toList())
    }

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

        /** `{{name}}` -> the value bound to `name`. Unbound placeholders are left alone. */
        fun substitute(template: String, params: Map<String, String>): String =
            PLACEHOLDER.replace(template) { match ->
                params[match.groupValues[1]] ?: match.value
            }

        private val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}")
    }
}
