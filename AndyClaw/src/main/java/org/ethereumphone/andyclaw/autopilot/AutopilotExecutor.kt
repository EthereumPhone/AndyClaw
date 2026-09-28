package org.ethereumphone.andyclaw.autopilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The device surface the autopilot drives. The app implements it over the agent display. */
interface AutopilotDevice {
    /** Create the display if needed and bring [packageName] to the front of it. */
    suspend fun ensureApp(packageName: String): Boolean

    /** Whether [packageName] is installed with something to launch. */
    suspend fun isLaunchable(packageName: String): Boolean = true

    /** The app's name as the user knows it ("Signal"), for messages and the rear HUD. */
    suspend fun appLabel(packageName: String): String? = null

    /** The current screen, read after the UI has settled. Null when it cannot be read. */
    suspend fun snapshot(): ScreenSnapshot?

    /** Perform [option] on [screen] and wait for the UI to settle. */
    suspend fun perform(option: StepOption, screen: ScreenSnapshot, plan: AutopilotPlan): ActionOutcome

    /** Wait longer for a screen that is still loading. */
    suspend fun waitForSettle(): Unit = Unit

    /** Grab a frame for the replay; runs in parallel with the Jev call. Optional. */
    suspend fun captureFrame(step: Int): Unit = Unit

    /**
     * The user asked this run to stop (rear-screen hold, the launcher's or the app's STOP).
     * Checked before every action and polled while Jev or the planner is thinking.
     */
    val stopRequested: Boolean get() = false
}

data class ActionOutcome(
    val ok: Boolean,
    /** Whether the UI visibly reacted (a11y events / new frames) — false means "did nothing". */
    val changedScreen: Boolean,
    val actMs: Long = 0,
    val settleMs: Long = 0,
    val error: String? = null,
)

/** Called when Jev is unsure. Gets a small context, answers with one decision. */
fun interface AutopilotPlanner {
    suspend fun decide(context: PlannerContext): PlannerDecision
}

data class PlannerContext(
    val plan: AutopilotPlan,
    val subgoalIndex: Int,
    val screen: ScreenSnapshot,
    val history: List<HistoryEntry>,
    val reason: String,
    /** The option list the planner may choose from, keyed like [StepOption.key]. */
    val options: Map<String, String>,
)

sealed interface PlannerDecision {
    data class Act(val optionKey: String) : PlannerDecision
    data class Replan(val steps: List<PlanStep>) : PlannerDecision
    data object Done : PlannerDecision

    /**
     * The current sub-goal is already met on this screen: move on. Without it a sub-goal the
     * autopilot had satisfied itself ("Open Settings" — it opens the app before the first step)
     * left the planner nothing true to say but "no option", which ended the run.
     */
    data object SubgoalDone : PlannerDecision
    data class Abort(val reason: String, val say: String? = null) : PlannerDecision

    /**
     * No usable answer: the call failed, or the reply made no sense. Unlike [Abort] this is not
     * the planner deciding the task cannot be done, so the run is handed back to the model
     * rather than failed.
     */
    data class Unusable(val reason: String) : PlannerDecision
}

/** Progress events, for the live view, the rear HUD and the replay. */
data class AutopilotEvent(
    val kind: Kind,
    val runId: String,
    val step: Int,
    val subgoalIndex: Int,
    val subgoals: List<String>,
    val action: String? = null,
    val target: ScreenElement? = null,
    val confidence: Double? = null,
    val source: Source? = null,
    val timings: StepTimings? = null,
    val elapsedMs: Long = 0,
    val plannerCalls: Int = 0,
    val reason: String? = null,
    /** On ESCALATED: Jev's own pick that was too unsure to act on. Logged only, never shown. */
    val jevPick: String? = null,
    /** On DONE and FAILED: how the run ended, as [AutopilotOutcome.Outcome.wire]. */
    val outcome: String? = null,
    /** On DONE and FAILED: one short sentence for the user, never a reason code. */
    val message: String? = null,
) {
    /**
     * SUBGOAL_DONE carries the sub-goal the run moved *to*, and after a replan the new list.
     * A hand-over ends in FAILED with outcome "handoff", so a consumer that only knows the kinds
     * still finishes its card; one that reads [outcome] can tell it from a failure.
     */
    enum class Kind { STARTED, ACTING, SETTLED, SUBGOAL_DONE, ESCALATED, DONE, FAILED }
    enum class Source { JEV, PLANNER }
}

data class StepTimings(val jevMs: Long = 0, val actMs: Long = 0, val settleMs: Long = 0, val stepMs: Long = 0)

fun interface AutopilotEventSink {
    fun onEvent(event: AutopilotEvent)
}

data class AutopilotResult(
    val status: Status,
    val steps: Int,
    val durationMs: Long,
    val say: String?,
    val reason: String?,
    val trace: List<String>,
    /** Compact description of the final screen — only for the planner when it must take over. */
    val screenSummary: String?,
    val plannerCalls: Int,
    val jevCalls: Int,
    val jevMsP50: Long,
    val escalations: List<String>,
    /** Every executed action with the element it hit, for flow compilation. */
    val actions: List<ExecutedAction>,
    /** The last screen read, for flow compilation's postcondition. */
    val finalScreen: ScreenSnapshot? = null,
    /** The plan as it stood at the end (after any replan). */
    val plan: AutopilotPlan? = null,
) {
    enum class Status { SUCCESS, NEEDS_PLANNER, FAILED }

    /** How the run ended, as the user should hear it. */
    val outcome: AutopilotOutcome.Outcome get() = AutopilotOutcome.of(status, reason)

    /** One short sentence for the user; never a reason code. */
    val message: String get() = AutopilotOutcome.message(status, reason, say)

    /** What goes back to the model as the tool result: small on purpose. */
    fun toToolResultJson(): String = buildJsonObject {
        put("status", status.name.lowercase())
        put("outcome", outcome.wire)
        put("steps", steps)
        put("ms", durationMs)
        say?.let { put("say", it) }
        reason?.let { put("reason", it) }
        put("trace", trace.takeLast(12).joinToString(" → "))
        if (status != Status.SUCCESS) screenSummary?.let { put("screen", it) }
    }.toString()
}

data class ExecutedAction(
    val option: StepOption,
    val target: ScreenElement?,
    val valueKey: String?,
    val subgoalIndex: Int,
    val screenBefore: ScreenSnapshot,
    val changedScreen: Boolean,
)

/**
 * Drives one app toward a planned goal with Jev choosing each action.
 *
 * The planner model wrote the plan once. From there each step is: read the settled screen, ask
 * Jev every question at once (~100 ms), act if it is confident, otherwise ask the planner — with
 * a small context, not the whole conversation. The planner is also the way out: anything this
 * loop cannot resolve within its budgets comes back as [AutopilotResult.Status.NEEDS_PLANNER]
 * with a compact description of where it stopped.
 *
 * STOP is honoured at every point where the run could still do something: before each action,
 * after each Jev or planner answer, and while either is still on the network — a call in flight
 * is abandoned rather than waited out, and the action it would have chosen is never performed.
 *
 * Every way out emits exactly one DONE or FAILED event, a cancellation and an unexpected
 * exception included, so no live view, launcher card or rear HUD is left saying "running".
 */
class AutopilotExecutor(
    private val device: AutopilotDevice,
    private val jev: JevClient?,
    private val planner: AutopilotPlanner?,
    private val config: AutopilotConfig = AutopilotConfig(),
    private val events: AutopilotEventSink = AutopilotEventSink { },
    private val clock: () -> Long = System::currentTimeMillis,
    private val runId: String = "ap-" + java.lang.Long.toHexString(System.nanoTime() and 0xffffff),
    /** Where Jev and planner calls run, so that STOP can walk away from one still in flight. */
    private val netDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    suspend fun run(initialPlan: AutopilotPlan): AutopilotResult {
        var plan = initialPlan
        val start = clock()
        val guard = LoopGuard(config)
        val history = mutableListOf<HistoryEntry>()
        val trace = mutableListOf<String>()
        val actions = mutableListOf<ExecutedAction>()
        val escalations = mutableListOf<String>()
        val jevTimes = mutableListOf<Long>()
        var subgoal = 0
        var steps = 0
        var plannerCalls = 0
        var jevFailures = 0
        var lastScreen: ScreenSnapshot? = null
        var lastActionCommitted = false
        var lastActed: Pair<ScreenSnapshot, StepOption>? = null
        // Only arriving at a screen by acting counts as a visit; re-reading it after a wait, a
        // retry or a finished sub-goal is not going in circles.
        var arrivedByAction = true
        // Pages scrolled through once this run, by PageScan.key. A page is scanned at most once.
        val scans = HashMap<String, PageScan>()
        // Network calls STOP can abandon. Not a child of this run on purpose: a blocking call
        // that ignores cancellation must not hold the run open after STOP.
        val netScope = CoroutineScope(SupervisorJob() + netDispatcher)

        fun elapsed() = clock() - start
        fun emit(kind: AutopilotEvent.Kind, block: AutopilotEvent.() -> AutopilotEvent = { this }) =
            events.onEvent(AutopilotEvent(kind, runId, steps, subgoal, plan.steps.map { it.doText },
                elapsedMs = elapsed(), plannerCalls = plannerCalls).block())

        fun finish(status: AutopilotResult.Status, reason: String?, say: String? = null): AutopilotResult {
            val result = AutopilotResult(
                status = status,
                steps = steps,
                durationMs = elapsed(),
                say = say ?: if (status == AutopilotResult.Status.SUCCESS) plan.say else null,
                reason = reason,
                trace = trace,
                screenSummary = lastScreen?.let(::summarize),
                plannerCalls = plannerCalls,
                jevCalls = jevTimes.size,
                jevMsP50 = jevTimes.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] },
                escalations = escalations,
                actions = actions,
                finalScreen = lastScreen,
                plan = plan,
            )
            emit(if (status == AutopilotResult.Status.SUCCESS) AutopilotEvent.Kind.DONE else AutopilotEvent.Kind.FAILED) {
                copy(reason = reason, outcome = result.outcome.wire, message = result.message)
            }
            return result
        }

        fun stopped() = finish(AutopilotResult.Status.FAILED, AutopilotOutcome.REASON_STOPPED, "Stopped.")

        /**
         * [block] on the network, polling STOP while it runs. Null when STOP came first: the call
         * is cancelled and its answer, whatever it would have been, is never acted on.
         */
        suspend fun <T> untilStopped(block: suspend () -> T): Result<T>? {
            val work = netScope.async { runCatching { block() } }
            try {
                while (!work.isCompleted) {
                    if (device.stopRequested) {
                        work.cancel()
                        return null
                    }
                    withTimeoutOrNull(STOP_POLL_MS) { work.join() }
                }
            } catch (e: CancellationException) {
                work.cancel()
                throw e
            }
            // A cancellation inside the result is the call's own failure unless this run is the
            // one being cancelled.
            currentCoroutineContext().ensureActive()
            return work.await()
        }

        /**
         * A screen caught mid-transition can read as nothing: no windows at all, or the outgoing
         * window already emptied of its elements. Neither is the screen to decide on — an empty
         * one went to Jev, which called it another app and sent the run back out of a correct
         * tap. Read again; only after that is an empty screen taken as what is there.
         */
        suspend fun readScreen(): ScreenSnapshot? {
            var empty: ScreenSnapshot? = null
            repeat(SNAPSHOT_ATTEMPTS - 1) {
                device.snapshot()?.let { if (it.elements.isNotEmpty()) return it else empty = it }
                if (device.stopRequested) return null
                device.waitForSettle()
            }
            return device.snapshot() ?: empty
        }

        /** The scan of the page [screen] is on, if it has been scanned. */
        fun scanOf(screen: ScreenSnapshot): PageScan? =
            PageScan.primaryList(screen)?.let { scans[PageScan.key(screen, it)] }
                // Scrolled to its end, the page's list can no longer scroll forward.
                ?: scans.values.firstOrNull { scan ->
                    scan.key.startsWith("${screen.packageName}|${screen.title.orEmpty()}|") &&
                        screen.elements.any { it.signature == scan.listSignature }
                }

        /**
         * Scrolls [screen]'s page to its end (or [AutopilotConfig.maxScanScrolls]) and records
         * every row it passes, so Jev can be told what is out of view. Scrolling changes nothing
         * in the app, so it needs no decision; the page is left where the scan ended and the
         * rows now above it are reported as such. Null when the page has no list to scroll.
         */
        suspend fun scanPage(screen: ScreenSnapshot): PageScan? {
            val list = PageScan.primaryList(screen) ?: return null
            val key = PageScan.key(screen, list)
            val rows = LinkedHashMap<String, PageScan.Row>()
            val seen = HashMap<String, Int>()
            var snapshots = 0
            fun absorb(s: ScreenSnapshot, index: Int) {
                snapshots++
                s.elements.filter { !it.scrollable && !it.name.isNullOrBlank() }.forEach {
                    rows.putIfAbsent(it.signature, PageScan.Row(it, index))
                    seen[it.signature] = (seen[it.signature] ?: 0) + 1
                }
            }
            absorb(screen, 0)
            var current = screen
            var scrolls = 0
            while (scrolls < config.maxScanScrolls && !device.stopRequested) {
                val l = current.elements.firstOrNull { it.signature == list.signature && "scroll_forward" in it.actions } ?: break
                val outcome = device.perform(StepOption.ScrollForward(l.id), current, plan)
                if (!outcome.ok) break
                scrolls++
                val next = readScreen() ?: break
                // A scroll that took the run off the page (or into a private app) ends the scan
                // there; nothing on that screen belongs to this page.
                if (next.packageName != screen.packageName || SensitiveApps.isSensitive(next.packageName)) break
                val before = rows.size
                absorb(next, scrolls)
                current = next
                if (rows.size == before) break
            }
            // Whatever showed at every scroll did not move with the list.
            if (snapshots > 1) rows.replaceAll { sig, row -> if (seen[sig] == snapshots) row.copy(sticky = true) else row }
            val scan = PageScan(key, list.signature, rows)
            scans[key] = scan
            trace += "scanned the page (${scrolls} scroll${if (scrolls == 1) "" else "s"}, ${rows.size} rows)"
            return scan
        }

        /**
         * Scrolls toward [option]'s row until it is on screen. The page was scanned, so the
         * direction is known; the count is not, since a scroll moves as far as the app decides.
         */
        suspend fun reveal(option: StepOption.Reveal, screen: ScreenSnapshot, scan: PageScan?): ActionOutcome {
            val started = clock()
            var current = screen
            var settle = 0L
            var forward = option.below
            var turned = false
            // Each way the page can go, as far as it goes: the direction is what the scan implies,
            // and a page that has stopped moving without showing the row is searched the other way.
            repeat(2 * (config.maxScanScrolls + 2)) {
                if (device.stopRequested) return ActionOutcome(ok = false, changedScreen = current !== screen)
                if (current.elements.any { it.signature == option.rowSignature }) {
                    return ActionOutcome(ok = true, changedScreen = current !== screen, actMs = clock() - started - settle, settleMs = settle)
                }
                val l = current.elements.firstOrNull { it.signature == scan?.listSignature }
                    ?: PageScan.primaryList(current)
                    ?: return ActionOutcome(ok = false, changedScreen = current !== screen, error = "no_list")
                val scroll = if (forward) StepOption.ScrollForward(l.id) else StepOption.ScrollBackward(l.id)
                val outcome = device.perform(scroll, current, plan)
                settle += outcome.settleMs
                if (!outcome.ok) return outcome.copy(changedScreen = current !== screen)
                val next = readScreen() ?: return ActionOutcome(ok = false, changedScreen = true, error = "screen_unreadable")
                if (next.packageName != screen.packageName) return ActionOutcome(ok = false, changedScreen = true, error = "left_the_app")
                if (next.elements.map { it.signature } == current.elements.map { it.signature }) {
                    if (turned) return ActionOutcome(ok = false, changedScreen = current !== screen, error = "row_not_found")
                    turned = true
                    forward = !forward
                }
                current = next
            }
            val found = current.elements.any { it.signature == option.rowSignature }
            return ActionOutcome(ok = found, changedScreen = current !== screen, actMs = clock() - started - settle,
                settleMs = settle, error = if (found) null else "row_not_found")
        }

        emit(AutopilotEvent.Kind.STARTED)
        try {
            if (SensitiveApps.isSensitive(plan.packageName)) {
                return finish(AutopilotResult.Status.FAILED, "sensitive_app", SensitiveApps.refusal(plan.packageName))
            }
            if (device.stopRequested) return stopped()
            if (!device.isLaunchable(plan.packageName)) {
                return finish(AutopilotResult.Status.FAILED, "app_not_installed",
                    "I can't find ${device.appLabel(plan.packageName) ?: plan.packageName} on this phone.")
            }
            if (!device.ensureApp(plan.packageName)) {
                return if (device.stopRequested) stopped() else finish(AutopilotResult.Status.FAILED, "app_unavailable")
            }
            // Jev reads only the state text: without this, a plan that begins "Open <app>" left it
            // unsure whether that had happened, and the first step cost a planner call every time.
            history += HistoryEntry("opened ${device.appLabel(plan.packageName) ?: plan.packageName}; " +
                "the app is on screen now", changedScreen = true)

            while (true) {
                if (device.stopRequested) return stopped()
                if (steps >= plan.maxSteps) return finish(AutopilotResult.Status.NEEDS_PLANNER, "step_budget")
                if (elapsed() >= config.wallClockBudgetMs) return finish(AutopilotResult.Status.NEEDS_PLANNER, "time_budget")
                if (guard.subgoalBudgetExceeded()) return finish(AutopilotResult.Status.NEEDS_PLANNER, "subgoal_budget")

                val stepStart = clock()
                val screen = readScreen()
                    ?: return if (device.stopRequested) stopped() else finish(AutopilotResult.Status.FAILED, "screen_unreadable")
                // Before the screen is kept anywhere: lastScreen ends up in the tool result's summary.
                if (SensitiveApps.isSensitive(screen.packageName)) {
                    return finish(AutopilotResult.Status.FAILED, "sensitive_app", SensitiveApps.refusal(screen.packageName))
                }
                lastScreen = screen
                if (arrivedByAction) {
                    guard.visit(screen)?.let { loop -> return finish(AutopilotResult.Status.NEEDS_PLANNER, loop) }
                }
                arrivedByAction = false

                val scan = scanOf(screen)
                val prompt = StepPromptBuilder.build(plan, subgoal, screen, history, guard.triedOn(screen),
                    offscreen = scan?.offscreen(screen).orEmpty())

                // With Jev out of the picture the planner drives every step. Its calls are small
                // next to a round trip of the main loop, so it gets the whole step budget rather
                // than the three escalations that make sense when Jev is doing the work.
                val jevDown = jev == null || jevFailures >= config.maxJevFailures
                val plannerBudget = if (jevDown) plan.maxSteps else config.maxPlannerEscalations

                // Ask Jev (unless this sub-goal needs reasoning Jev cannot do).
                var jevMs = 0L
                val decision: StepDecision = if (plan.steps[subgoal].needsPlanner || jev == null) {
                    StepDecision.Escalate(if (jev == null) "jev_unavailable" else "needs_planner")
                } else if (jevDown) {
                    StepDecision.Escalate("jev_unavailable")
                } else {
                    var failure = "jev_error"
                    val answer = coroutineScope {
                        val frame = async { runCatching { device.captureFrame(steps) } }
                        val r = untilStopped { jev.evaluate(prompt.request) }
                        frame.await()
                        r
                    } ?: return stopped()
                    val response = answer.getOrElse { e ->
                        if (e is JevUnavailableException) {
                            jevFailures = config.maxJevFailures
                            failure = "jev_unavailable"
                        } else {
                            jevFailures++
                        }
                        null
                    }
                    if (device.stopRequested) return stopped()
                    if (response == null) {
                        StepDecision.Escalate(failure)
                    } else {
                        jevFailures = 0
                        jevMs = response.rttMs
                        jevTimes += response.rttMs
                        StepPolicy.decide(response, prompt, plan, subgoal, screen, lastActionCommitted,
                            guard.thresholdBump(screen), config, undoable = lastActed != null)
                    }
                }

                // Resolve escalations through the planner into a concrete decision.
                val resolved: StepDecision = when (decision) {
                    is StepDecision.Escalate -> {
                        // One transient failure is not worth a planner round trip, nor worth
                        // telling anyone the model is being asked: re-read and retry.
                        if (decision.reason == "jev_error" && jevFailures in 1 until config.maxJevFailures) continue
                        // Jev unsure on a page it has only seen part of: show it the rest of the
                        // page and ask again, before paying for the planner. Once per page.
                        if (decision.reason in SCAN_REASONS && scan == null && !jevDown) {
                            val scanned = scanPage(screen)
                            if (device.stopRequested) return stopped()
                            if (scanned != null && scanned.rows.size > screen.elements.count { !it.scrollable && !it.name.isNullOrBlank() }) {
                                continue
                            }
                        }
                        escalations += decision.reason
                        emit(AutopilotEvent.Kind.ESCALATED) { copy(reason = decision.reason, jevPick = decision.jevPick) }
                        if (planner == null || plannerCalls >= plannerBudget) {
                            return finish(AutopilotResult.Status.NEEDS_PLANNER, decision.reason)
                        }
                        plannerCalls++
                        val options = prompt.options.mapValues { (_, o) -> StepPromptBuilder.describe(o, screen, plan) }
                        val context = PlannerContext(plan, subgoal, screen, history, decision.reason, options)
                        val answer = untilStopped { planner.decide(context) } ?: return stopped()
                        if (device.stopRequested) return stopped()
                        when (val pd = answer.getOrElse { PlannerDecision.Unusable("planner_error") }) {
                            is PlannerDecision.Unusable -> return finish(AutopilotResult.Status.NEEDS_PLANNER, pd.reason)
                            is PlannerDecision.Done -> StepDecision.Done
                            // The last sub-goal met is the goal met.
                            is PlannerDecision.SubgoalDone ->
                                if (subgoal >= plan.steps.lastIndex) StepDecision.Done else StepDecision.AdvanceAndReask
                            is PlannerDecision.Abort -> return finish(AutopilotResult.Status.FAILED, pd.reason, pd.say)
                            is PlannerDecision.Replan -> {
                                if (pd.steps.isEmpty()) return finish(AutopilotResult.Status.NEEDS_PLANNER, "empty_replan")
                                plan = plan.copy(steps = pd.steps.take(AutopilotPlan.MAX_SUBGOALS))
                                subgoal = 0
                                guard.subgoalAdvanced()
                                // Carries the new list, so every view redraws its sub-goals.
                                emit(AutopilotEvent.Kind.SUBGOAL_DONE)
                                continue
                            }
                            is PlannerDecision.Act -> {
                                val option = prompt.options[pd.optionKey]
                                    ?: return finish(AutopilotResult.Status.NEEDS_PLANNER, "planner_invalid_option")
                                val target = option.elementId?.let { screen.byId(it) }
                                if (target != null && StepPolicy.isSensitive(target)) {
                                    return finish(AutopilotResult.Status.NEEDS_PLANNER, "sensitive")
                                }
                                if (option == StepOption.None) return finish(AutopilotResult.Status.NEEDS_PLANNER, "no_option")
                                if (option == StepOption.Wait) StepDecision.Wait
                                else StepDecision.Act(option, confidence = 1.0,
                                    commits = target != null && StepPolicy.isCommitLike(target))
                            }
                        }
                    }
                    else -> decision
                }

                when (resolved) {
                    StepDecision.Done -> {
                        trace += "done"
                        return finish(AutopilotResult.Status.SUCCESS, null)
                    }
                    StepDecision.Wait -> {
                        guard.waited(screen)?.let { return finish(AutopilotResult.Status.NEEDS_PLANNER, it) }
                        device.waitForSettle()
                    }
                    StepDecision.AdvanceAndReask -> {
                        subgoal = (subgoal + 1).coerceAtMost(plan.steps.lastIndex)
                        guard.subgoalAdvanced()
                        emit(AutopilotEvent.Kind.SUBGOAL_DONE)
                    }
                    StepDecision.Undo -> {
                        if (device.stopRequested) return stopped()
                        // The previous action did not do what it should. Go back, and make sure it
                        // is never picked again on the screen where it was taken.
                        lastActed?.let { (prevScreen, _) -> guard.miss(prevScreen) }
                        trace += "undo"
                        history += HistoryEntry("pressed Back to undo the previous action", true)
                        emit(AutopilotEvent.Kind.ACTING) { copy(action = "back", source = AutopilotEvent.Source.JEV) }
                        val outcome = device.perform(StepOption.Back, screen, plan)
                        steps++
                        arrivedByAction = true
                        // Nothing to judge next time: the Back press is not an action that can
                        // itself be undone, or undoing walks the run out of the app.
                        lastActed = null
                        lastActionCommitted = false
                        emit(AutopilotEvent.Kind.SETTLED) {
                            copy(action = "back", source = AutopilotEvent.Source.JEV,
                                timings = StepTimings(jevMs, outcome.actMs, outcome.settleMs, clock() - stepStart))
                        }
                        if (!outcome.ok && device.stopRequested) return stopped()
                        guard.acted(screen, StepOption.Back, outcome.changedScreen)
                    }
                    is StepDecision.Act -> {
                        if (resolved.advancesSubgoal) {
                            subgoal = (subgoal + 1).coerceAtMost(plan.steps.lastIndex)
                            guard.subgoalAdvanced()
                            emit(AutopilotEvent.Kind.SUBGOAL_DONE)
                        }
                        if (device.stopRequested) return stopped()
                        val option = resolved.option
                        val target = option.elementId?.let { screen.byId(it) }
                        val source = if (decision is StepDecision.Escalate) AutopilotEvent.Source.PLANNER else AutopilotEvent.Source.JEV
                        val verb = option.key.substringBefore(':')
                        emit(AutopilotEvent.Kind.ACTING) {
                            copy(action = verb, target = target, confidence = resolved.confidence, source = source)
                        }
                        val outcome = if (option is StepOption.Reveal) reveal(option, screen, scan)
                            else device.perform(option, screen, plan)
                        steps++
                        arrivedByAction = true
                        val label = describeAction(option, target, plan)
                        trace += label
                        history += HistoryEntry(label, outcome.changedScreen)
                        actions += ExecutedAction(option, target, (option as? StepOption.Type)?.valueKey,
                            subgoal, screen, outcome.changedScreen)
                        lastActed = screen to option
                        lastActionCommitted = resolved.commits
                        val timings = StepTimings(jevMs, outcome.actMs, outcome.settleMs, clock() - stepStart)
                        emit(AutopilotEvent.Kind.SETTLED) {
                            copy(action = verb, target = target, confidence = resolved.confidence, source = source, timings = timings)
                        }
                        if (!outcome.ok) {
                            if (device.stopRequested) return stopped()
                            return finish(AutopilotResult.Status.NEEDS_PLANNER, "action_failed:${outcome.error ?: verb}")
                        }
                        guard.acted(screen, option, outcome.changedScreen)?.let {
                            return finish(AutopilotResult.Status.NEEDS_PLANNER, it)
                        }
                    }
                    is StepDecision.Escalate -> error("escalations are resolved above")
                }
            }
        } catch (e: CancellationException) {
            finish(AutopilotResult.Status.FAILED, AutopilotOutcome.REASON_CANCELLED)
            throw e
        } catch (e: Exception) {
            return finish(AutopilotResult.Status.FAILED, "internal_error")
        } finally {
            netScope.cancel()
        }
    }

    companion object {
        fun describeAction(option: StepOption, target: ScreenElement?, plan: AutopilotPlan): String {
            val what = target?.let { "${it.type} \"${it.name ?: it.viewId?.substringAfter('/') ?: "#${it.id}"}\"" } ?: ""
            return when (option) {
                is StepOption.Tap -> "tapped $what"
                is StepOption.LongPress -> "long-pressed $what"
                is StepOption.Type -> "typed ${option.valueKey} (\"${plan.values[option.valueKey].orEmpty().take(40)}\") into $what"
                is StepOption.ScrollForward -> "scrolled $what forward"
                is StepOption.ScrollBackward -> "scrolled $what back"
                is StepOption.Reveal -> "scrolled ${if (option.below) "down" else "up"} to \"${option.label.take(40)}\""
                StepOption.Back -> "pressed Back"
                StepOption.Wait -> "waited"
                StepOption.None -> "nothing"
            }
        }

        /**
         * One screen in a few hundred characters, for handing control back to the planner —
         * with each element's view id or centre, so the model can act on it straight away
         * instead of paying for another read of the screen.
         */
        fun summarize(screen: ScreenSnapshot): String {
            val items = screen.elements
                .filter { it.clickable || it.editable }
                .take(15)
                .joinToString("; ") { e ->
                    buildString {
                        append("[${e.id}] ${e.type}")
                        e.name?.take(30)?.let { append(" \"$it\"") }
                        e.viewId?.let { append(" viewId:$it") } ?: append(" @(${e.centerX},${e.centerY})")
                    }
                }
            return "${screen.packageName} › ${screen.title.orEmpty()} (${screen.elements.size} elements): $items"
        }

        /** How often a Jev or planner call in flight looks for STOP. */
        const val STOP_POLL_MS = 50L
        private const val SNAPSHOT_ATTEMPTS = 4
        /** Escalations a page scan may answer: Jev unsure, or seeing nothing that helps. */
        private val SCAN_REASONS = setOf("low_confidence", "no_option")
    }
}
