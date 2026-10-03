package org.ethereumphone.andyclaw.flows

import kotlinx.coroutines.CancellationException
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

    /**
     * `AccessibilityNodeInfo.ACTION_CLICK` on the node with this view id, and what is known about
     * whether it happened. [FlowDispatch.NOT_DISPATCHED] is for a certain "no" only — the replay
     * reads it as "nothing was done", which may let the task be done another way.
     */
    suspend fun clickNode(viewId: String, index: Int): FlowDispatch

    /** Set text on an editable node; answered the way [clickNode] is. */
    suspend fun setNodeText(viewId: String, text: String): FlowDispatch
}

/** Asked to cross a `checkpoint:`. False means the user did not agree, and the flow stops. */
fun interface FlowCheckpointHandler {
    suspend fun confirm(flow: Flow, checkpoint: String, params: Map<String, String>): Boolean

    /**
     * Whether the user approved this very replay on its card — never a standing "don't ask me".
     * Asked before the step that commits a flow taps a button nobody here can read
     * ([FlowTargetGuard.unreadableControl]); by default nobody did.
     */
    suspend fun approvedThisReplay(flow: Flow): Boolean = false
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

/**
 * Told about each tap and each typed value as it goes out and once the screen has settled after
 * it, so a replay can be shown like an autopilot run ([FlowReplayEvents]). Observes only: nothing
 * it does changes the replay.
 */
fun interface FlowActionListener {
    /** [stepIndex] into `flow.steps`; [opcode] `tap` or `type`; [settled] false before, true after. */
    fun onAction(stepIndex: Int, opcode: String, viewId: String, settled: Boolean)

    companion object {
        val NONE = FlowActionListener { _, _, _, _ -> }
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
         * The flow may already have done its task — it acted past its checkpoint, performed an
         * irreversible step, or sent out its last action — so the task must not be repeated by
         * any other route: not the autopilot, not the model. A send that could not be confirmed
         * is still probably a send. An action that certainly never reached the app does not count.
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
    private val actions: FlowActionListener = FlowActionListener.NONE,
) {

    suspend fun run(
        flow: Flow,
        params: Map<String, String>,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): FlowRunResult {
        val started = clock()
        val trace = mutableListOf<String>()
        // Acting past the checkpoint is the moment the flow may have changed the world for good,
        // and so is an irreversible step or sending out the flow's last action, whatever its target
        // is called: a flow that ends on "Save" has done its task there although that is no commit
        // token ([FlowRunAccounting.commitsAt]). Past it, no failure may be answered by doing the
        // task again — a postcondition that misses a slow screen included.
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

        /** The listener only watches: whatever it does, the replay goes on as it would without it. */
        fun observe(step: Int, opcode: String, viewId: String, settled: Boolean) {
            try {
                actions.onAction(step, opcode, viewId, settled)
            } catch (_: Exception) {
            }
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
        // A blank value is no value: typed, it sends nothing; asserted, it named nobody and let the
        // identity check that proves the right row was hit pass for anyone.
        val missing = flow.params.filter { params[it].isNullOrBlank() }
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

        /**
         * The screen after a tap or a typed value. The accessibility service reports window
         * changes on a 100 ms idle timeout, so a read [SETTLE_TAP_MS] after a tap routinely still
         * returns the screen the tap was made on — and the next step's target check or identity
         * assert then passed against a list the tap had already left (the recipient's name is on
         * the list too). So the screen is read again until it differs from [before], for
         * [CHANGE_WAIT_MS] at most; an action that genuinely changes nothing costs that wait and
         * keeps the last read.
         */
        suspend fun readTreeAfterAction(before: String, settleMs: Long): String? {
            sleep(settleMs)
            var read = readTree() ?: return null
            var polls = 0
            val deadline = clock() + CHANGE_WAIT_MS
            while (read == before && polls < MAX_CHANGE_POLLS && clock() < deadline) {
                if (stopped()) return read
                sleep(POLL_MS)
                polls++
                read = readTree() ?: return null
            }
            return read
        }

        /**
         * Reads the screen again until it [shows] what the step needs — for [waitMs] at most and
         * a read per [POLL_MS] of it, so a frozen clock cannot keep it here — checking STOP before
         * every read. It leaves `tree` on the last screen read, which is the one the step then
         * checks and acts on.
         */
        suspend fun awaitScreen(step: Int?, waitMs: Long = SCREEN_WAIT_MS, shows: (String) -> Boolean): ScreenWait {
            var polls = 0
            val maxPolls = (waitMs / POLL_MS).toInt() + 1
            val deadline = clock() + waitMs
            while (!shows(tree)) {
                if (polls >= maxPolls || clock() >= deadline) return ScreenWait.NotSeen
                if (stopped()) return ScreenWait.Ended(abort(FlowAbortReason.STOPPED, "stopped by the user", step))
                sleep(POLL_MS)
                polls++
                tree = readTree()
                    ?: return ScreenWait.Ended(abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "the screen could not be read", step))
            }
            return ScreenWait.Held
        }

        /**
         * Waits for a step's target to be on the screen. Zero matches proves nothing — the previous
         * action's screen may not be up yet — and both target checks pass vacuously on it, after
         * which the node action would land on whatever the live screen has under that id: a "Pay"
         * button, somebody else's row. So the screen is read again, and a target that never shows
         * ends the replay. Nothing is acted on that the checks did not see.
         */
        suspend fun awaitTarget(step: Int, viewId: String, position: Int): FlowRunResult.Aborted? =
            when (val wait = awaitScreen(step) { NodeTreeChecksum.nodesWithViewId(it, viewId).size > position }) {
                ScreenWait.Held -> null
                ScreenWait.NotSeen -> abort(FlowAbortReason.STEP_FAILED, "$viewId is not on the screen", step)
                is ScreenWait.Ended -> wait.abort
            }

        // ── Preconditions ─────────────────────────────────────────────
        // Read until they hold, for a few seconds: the app has just been started from its launcher
        // activity, and one read of a cold start, or of a start screen still coming up, failed them
        // — and marked a good flow stale.
        when (val wait = awaitScreen(null, PRECONDITION_WAIT_MS) { t -> flow.preconditions.all { holds(it, t, params) } }) {
            ScreenWait.Held -> Unit
            ScreenWait.NotSeen -> {
                val failed = flow.preconditions.first { !holds(it, tree, params) }
                return abort(FlowAbortReason.PRECONDITION_FAILED, "precondition ${describe(failed)} does not hold")
            }
            is ScreenWait.Ended -> return wait.abort
        }
        trace += "preconditions:ok"

        // An exception once the flow may have done its task is reported as exactly that — a
        // committed abort — and never as a failure, which is what invites doing it again. Before
        // that point it propagates, as it always has.
        try {
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
                        val position = step.index ?: 0
                        awaitTarget(index, viewId, position)?.let { return it }
                        // Only what was typed before the tap can have picked the row it lands on.
                        val typed = flow.steps.take(index).filterIsInstance<TypeStep>().map { substitute(it.value, params) }
                        // Against the tree the target was just seen in: the last read before the tap.
                        checkTarget(flow, index, step, viewId, tree, typing = false, typed = typed)?.let { (reason, message) ->
                            return abort(reason, message, index)
                        }
                        // A button in a script no check here can read, on the tap that commits the
                        // flow: its "Send" and a "Pay" look alike to every word list, so it is tapped
                        // only on the user's approval of this replay, and otherwise done another way.
                        if (FlowRunAccounting.commitsAt(flow, index)) {
                            FlowTargetGuard.unreadableControl(tree, viewId)?.let { words ->
                                if (!checkpoints.approvedThisReplay(flow)) {
                                    return abort(
                                        FlowAbortReason.AMBIGUOUS_TARGET,
                                        "$viewId reads \"$words\", which nothing here can check, on the step that cannot be undone",
                                        index,
                                    )
                                }
                            }
                        }
                        if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                        observe(index, step.opcode, viewId, settled = false)
                        val sent = dispatch { driver.clickNode(viewId, position) }
                        if (sent.mayHaveHappened && FlowRunAccounting.commitsAt(flow, index)) committed = true
                        failure(sent, "tap on $viewId")?.let {
                            // Refused because STOP had just latched the display: the user's, not the flow's.
                            if (stop.stopRequested()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                            return abort(FlowAbortReason.STEP_FAILED, it, index)
                        }
                        trace += "tap:$viewId"
                        tree = readTreeAfterAction(tree, SETTLE_TAP_MS)
                            ?: return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "the screen could not be read after tapping $viewId", index)
                        observe(index, step.opcode, viewId, settled = true)
                    }

                    is TypeStep -> {
                        val viewId = step.target.viewId
                            ?: return abort(FlowAbortReason.UNSUPPORTED, "type without a view_id", index)
                        awaitTarget(index, viewId, 0)?.let { return it }
                        checkTarget(flow, index, step, viewId, tree, typing = true, typed = emptyList())?.let { (reason, message) ->
                            return abort(reason, message, index)
                        }
                        if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                        val value = substitute(step.value, params)
                        observe(index, step.opcode, viewId, settled = false)
                        val sent = dispatch { driver.setNodeText(viewId, value) }
                        if (sent.mayHaveHappened && FlowRunAccounting.commitsAt(flow, index)) committed = true
                        failure(sent, "typing into $viewId")?.let {
                            if (stop.stopRequested()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                            return abort(FlowAbortReason.STEP_FAILED, it, index)
                        }
                        trace += "type:$viewId"
                        tree = readTreeAfterAction(tree, SETTLE_TYPE_MS)
                            ?: return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "the screen could not be read after typing into $viewId", index)
                        observe(index, step.opcode, viewId, settled = true)
                    }

                    is WaitForStep -> {
                        val deadline = clock() + step.timeoutMs
                        var seen = false
                        var read = false
                        var polls = 0
                        while (true) {
                            if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user", index)
                            // Only a read that succeeded can satisfy the wait: the old tree is the
                            // screen before whatever this step is waiting for.
                            val fresh = driver.uiTree()
                            if (fresh != null) {
                                read = true
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
                        // Not one read of the screen worked: that says nothing about the flow.
                        if (!read) return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "the screen could not be read", index)
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
                        val expected = step.nodeTextContains?.let { substitute(it, params) }
                        // An assert that asks for something and is left with nothing proves nothing.
                        if (!step.nodeTextContains.isNullOrBlank() && expected.isNullOrBlank()) {
                            return abort(FlowAbortReason.ASSERT_FAILED, "${step.viewId} has nothing to show", index)
                        }
                        if (!expected.isNullOrBlank()) {
                            // The assert that proves a list row was the right one reads a screen the
                            // tap has only just asked for, so it gets the grace a target gets. And it
                            // must find the value itself, not a longer word around it: "Hanna"
                            // contains "Anna" and is somebody else.
                            val viewId = step.viewId
                            when (val wait = awaitScreen(index) { viewId != null && FlowTargetGuard.names(it, viewId, expected) }) {
                                ScreenWait.Held -> Unit
                                ScreenWait.NotSeen -> return abort(
                                    FlowAbortReason.ASSERT_FAILED,
                                    "$viewId does not show '$expected'",
                                    index,
                                )
                                is ScreenWait.Ended -> return wait.abort
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
            var read = false
            val deadline = clock() + POSTCONDITION_WAIT_MS
            while (true) {
                if (stopped()) return abort(FlowAbortReason.STOPPED, "stopped by the user")
                val fresh = driver.uiTree()
                if (fresh != null) read = true
                val failed = if (fresh == null) flow.postconditions.firstOrNull()
                else flow.postconditions.firstOrNull { !holds(it, fresh, params) }
                if (failed == null) break
                if (polls >= MAX_POSTCONDITION_POLLS || clock() >= deadline) {
                    // Not one read worked: the display failed, not the flow. Still committed, if it was.
                    if (!read) return abort(FlowAbortReason.DISPLAY_UNAVAILABLE, "the screen could not be read to confirm it")
                    return abort(
                        FlowAbortReason.ASSERT_FAILED,
                        "postcondition ${describe(failed)} does not hold — the flow ran but cannot " +
                            "prove it worked",
                    )
                }
                sleep(POLL_MS)
                polls++
            }
        } catch (e: Exception) {
            if (e is CancellationException || !committed) throw e
            return abort(FlowAbortReason.STEP_FAILED, "the replay failed after acting: ${e.message ?: e.javaClass.simpleName}")
        }
        trace += "postconditions:ok"

        return FlowRunResult.Completed(flow.steps.size, elapsed(), trace.toList())
    }

    /** How a wait for the screen to show something ended. */
    private sealed interface ScreenWait {
        /** It showed; the tree read last is the one it showed on. */
        data object Held : ScreenWait

        /** It did not show within the bound. */
        data object NotSeen : ScreenWait

        /** STOP, or a screen that could not be read. */
        class Ended(val abort: FlowRunResult.Aborted) : ScreenWait
    }

    /**
     * A node action. An exception once the call is out is "may have happened", never "did not":
     * the binder may have delivered it before it failed.
     */
    private suspend fun dispatch(action: suspend () -> FlowDispatch): FlowDispatch = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FlowDispatch.UNKNOWN
    }

    /** Why [sent] ends the replay, or null when the action went through. */
    private fun failure(sent: FlowDispatch, what: String): String? = when (sent) {
        FlowDispatch.DONE -> null
        FlowDispatch.NOT_DISPATCHED -> "$what failed"
        FlowDispatch.UNKNOWN -> "$what got no clear answer, so it may still have happened"
    }

    /**
     * Why the step at [index] must not touch [viewId] on this screen, or null.
     *
     * Checked against the live screen, not the compiled IR: a flow compiled when the button
     * said "Next" must not tap it once it says "Pay €49", and a list row is only safe to take
     * by position when something afterwards proves it was the right one. [tree] is the screen
     * the target was just found on, read immediately before the action — never an older one
     * that happened not to show it. [typed] are the values typed before this step.
     */
    private fun checkTarget(
        flow: Flow,
        index: Int,
        step: FlowStep,
        viewId: String,
        tree: String?,
        typing: Boolean,
        typed: Collection<String>,
    ): Pair<FlowAbortReason, String>? {
        FlowTargetGuard.privateAppOn(tree)?.let {
            return FlowAbortReason.SENSITIVE_TARGET to "$it is a private app"
        }
        FlowTargetGuard.sensitiveReason(tree, viewId, typing)?.let {
            return FlowAbortReason.SENSITIVE_TARGET to "$viewId $it"
        }
        val matches = NodeTreeChecksum.nodesWithViewId(tree, viewId).size
        // Nothing to check is not a pass: the action would resolve against a screen nobody read.
        if (matches == 0) return FlowAbortReason.STEP_FAILED to "$viewId is not on the screen"
        // An irreversible step on a repeated id is never safe: no later assert can take it back.
        if (matches > 1 && (acts(step) || !FlowTargetGuard.identityAssertedAfter(flow.steps, index))) {
            return FlowAbortReason.AMBIGUOUS_TARGET to
                "$viewId matches $matches nodes and nothing before the next irreversible step proves which one was meant"
        }
        // A lone search hit that is only a near miss for the value the flow was given is somebody
        // else. Checked on every tap, not just compiled ones with an identity assert, because
        // flows already on devices predate those asserts.
        if (!typing) {
            FlowTargetGuard.partialValueMatch(tree, viewId, typed)?.let {
                return FlowAbortReason.AMBIGUOUS_TARGET to "$viewId shows '$it' only inside a longer word"
            }
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
            // Every text contains nothing, a missing node's included: an empty value proves nothing.
            is NodeTextContains -> substitute(condition.value, params).let { value ->
                value.isNotBlank() && NodeTreeChecksum.textOf(tree, viewId).contains(value, ignoreCase = true)
            }
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

        /**
         * How long a step's target, or the text an assert looks for, may take to show. The settle
         * after an action is a few dozen milliseconds; a slow app's next screen is not.
         */
        const val SCREEN_WAIT_MS = 1_500L
        /** How long a read after an action waits for the screen to differ from the one acted on. */
        const val CHANGE_WAIT_MS = 1_000L
        const val MAX_CHANGE_POLLS = (CHANGE_WAIT_MS / POLL_MS).toInt() + 1
        const val MAX_SCREEN_POLLS = (SCREEN_WAIT_MS / POLL_MS).toInt() + 1

        /** How long the result of the last step may take to show. */
        const val POSTCONDITION_WAIT_MS = 3_000L
        const val MAX_POSTCONDITION_POLLS = (POSTCONDITION_WAIT_MS / POLL_MS).toInt() + 1

        /** How long the app's start screen may take to show the preconditions after its launch. */
        const val PRECONDITION_WAIT_MS = 3_000L

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
