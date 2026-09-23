package org.ethereumphone.andyclaw.autopilot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The device surface the autopilot drives. The app implements it over the agent display. */
interface AutopilotDevice {
    /** Create the display if needed and bring [packageName] to the front of it. */
    suspend fun ensureApp(packageName: String): Boolean

    /** The current screen, read after the UI has settled. Null when it cannot be read. */
    suspend fun snapshot(): ScreenSnapshot?

    /** Perform [option] on [screen] and wait for the UI to settle. */
    suspend fun perform(option: StepOption, screen: ScreenSnapshot, plan: AutopilotPlan): ActionOutcome

    /** Wait longer for a screen that is still loading. */
    suspend fun waitForSettle(): Unit = Unit

    /** Grab a frame for the replay; runs in parallel with the Jev call. Optional. */
    suspend fun captureFrame(step: Int): Unit = Unit

    /** The user asked the agent to stop (rear-screen hold, or the app's STOP button). */
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
    data class Abort(val reason: String, val say: String? = null) : PlannerDecision
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
) {
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

    /** What goes back to the model as the tool result: small on purpose. */
    fun toToolResultJson(): String = buildJsonObject {
        put("status", status.name.lowercase())
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
 */
class AutopilotExecutor(
    private val device: AutopilotDevice,
    private val jev: JevClient?,
    private val planner: AutopilotPlanner?,
    private val config: AutopilotConfig = AutopilotConfig(),
    private val events: AutopilotEventSink = AutopilotEventSink { },
    private val clock: () -> Long = System::currentTimeMillis,
    private val runId: String = "ap-" + java.lang.Long.toHexString(System.nanoTime() and 0xffffff),
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

        fun elapsed() = clock() - start
        fun emit(kind: AutopilotEvent.Kind, block: AutopilotEvent.() -> AutopilotEvent = { this }) =
            events.onEvent(AutopilotEvent(kind, runId, steps, subgoal, plan.steps.map { it.doText },
                elapsedMs = elapsed(), plannerCalls = plannerCalls).block())

        fun finish(status: AutopilotResult.Status, reason: String?, say: String? = null): AutopilotResult {
            emit(if (status == AutopilotResult.Status.SUCCESS) AutopilotEvent.Kind.DONE else AutopilotEvent.Kind.FAILED) {
                copy(reason = reason)
            }
            return AutopilotResult(
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
        }

        emit(AutopilotEvent.Kind.STARTED)
        if (SensitiveApps.isSensitive(plan.packageName)) {
            return finish(AutopilotResult.Status.FAILED, "sensitive_app", SensitiveApps.refusal(plan.packageName))
        }
        if (!device.ensureApp(plan.packageName)) {
            return finish(AutopilotResult.Status.FAILED, "app_unavailable")
        }

        while (true) {
            if (device.stopRequested) return finish(AutopilotResult.Status.FAILED, "stopped_by_user", "Stopped.")
            if (steps >= plan.maxSteps) return finish(AutopilotResult.Status.NEEDS_PLANNER, "step_budget")
            if (elapsed() >= config.wallClockBudgetMs) return finish(AutopilotResult.Status.NEEDS_PLANNER, "time_budget")
            if (guard.subgoalBudgetExceeded()) return finish(AutopilotResult.Status.NEEDS_PLANNER, "subgoal_budget")

            val stepStart = clock()
            val screen = device.snapshot() ?: return finish(AutopilotResult.Status.FAILED, "screen_unreadable")
            // Before the screen is kept anywhere: lastScreen ends up in the tool result's summary.
            if (SensitiveApps.isSensitive(screen.packageName)) {
                return finish(AutopilotResult.Status.FAILED, "sensitive_app", SensitiveApps.refusal(screen.packageName))
            }
            lastScreen = screen
            if (arrivedByAction) {
                guard.visit(screen)?.let { loop -> return finish(AutopilotResult.Status.NEEDS_PLANNER, loop) }
            }
            arrivedByAction = false

            val prompt = StepPromptBuilder.build(plan, subgoal, screen, history, guard.triedOn(screen))

            // Ask Jev (unless this sub-goal needs reasoning Jev cannot do).
            var jevMs = 0L
            val decision: StepDecision = if (plan.steps[subgoal].needsPlanner || jev == null) {
                StepDecision.Escalate(if (jev == null) "jev_unavailable" else "needs_planner")
            } else if (jevFailures >= config.maxJevFailures) {
                StepDecision.Escalate("jev_unavailable")
            } else {
                var failure = "jev_error"
                val response = try {
                    coroutineScope {
                        val frame = async { runCatching { device.captureFrame(steps) } }
                        val r = jev.evaluate(prompt.request)
                        frame.await()
                        r
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: JevUnavailableException) {
                    jevFailures = config.maxJevFailures
                    failure = "jev_unavailable"
                    null
                } catch (e: Exception) {
                    jevFailures++
                    null
                }
                if (response == null) {
                    StepDecision.Escalate(failure)
                } else {
                    jevFailures = 0
                    jevMs = response.rttMs
                    jevTimes += response.rttMs
                    StepPolicy.decide(response, prompt, plan, subgoal, screen, lastActionCommitted,
                        guard.thresholdBump(screen), config)
                }
            }

            // Resolve escalations through the planner into a concrete decision.
            val resolved: StepDecision = when (decision) {
                is StepDecision.Escalate -> {
                    escalations += decision.reason
                    emit(AutopilotEvent.Kind.ESCALATED) { copy(reason = decision.reason) }
                    if (decision.reason == "jev_error") {
                        // One transient failure is not worth a planner round trip; re-read and retry.
                        if (jevFailures in 1 until config.maxJevFailures) continue
                    }
                    if (planner == null || plannerCalls >= config.maxPlannerEscalations) {
                        return finish(AutopilotResult.Status.NEEDS_PLANNER, decision.reason)
                    }
                    plannerCalls++
                    val options = prompt.options.mapValues { (_, o) -> StepPromptBuilder.describe(o, screen, plan) }
                    when (val pd = planner.decide(PlannerContext(plan, subgoal, screen, history, decision.reason, options))) {
                        is PlannerDecision.Done -> StepDecision.Done
                        is PlannerDecision.Abort -> return finish(AutopilotResult.Status.FAILED, pd.reason, pd.say)
                        is PlannerDecision.Replan -> {
                            if (pd.steps.isEmpty()) return finish(AutopilotResult.Status.NEEDS_PLANNER, "empty_replan")
                            plan = plan.copy(steps = pd.steps.take(AutopilotPlan.MAX_SUBGOALS))
                            subgoal = 0
                            guard.subgoalAdvanced()
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
                    emit(AutopilotEvent.Kind.SUBGOAL_DONE)
                    subgoal = (subgoal + 1).coerceAtMost(plan.steps.lastIndex)
                    guard.subgoalAdvanced()
                }
                StepDecision.Undo -> {
                    // The previous action did not do what it should. Go back, and make sure it
                    // is never picked again on the screen where it was taken.
                    lastActed?.let { (prevScreen, _) -> guard.miss(prevScreen) }
                    trace += "undo"
                    history += HistoryEntry("pressed Back to undo the previous action", true)
                    val outcome = device.perform(StepOption.Back, screen, plan)
                    steps++
                    arrivedByAction = true
                    lastActed = null
                    lastActionCommitted = false
                    guard.acted(screen, StepOption.Back, outcome.changedScreen)
                }
                is StepDecision.Act -> {
                    if (resolved.advancesSubgoal) {
                        emit(AutopilotEvent.Kind.SUBGOAL_DONE)
                        subgoal = (subgoal + 1).coerceAtMost(plan.steps.lastIndex)
                        guard.subgoalAdvanced()
                    }
                    val option = resolved.option
                    val target = option.elementId?.let { screen.byId(it) }
                    val source = if (decision is StepDecision.Escalate) AutopilotEvent.Source.PLANNER else AutopilotEvent.Source.JEV
                    val verb = option.key.substringBefore(':')
                    emit(AutopilotEvent.Kind.ACTING) {
                        copy(action = verb, target = target, confidence = resolved.confidence, source = source)
                    }
                    val outcome = device.perform(option, screen, plan)
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
                        return finish(AutopilotResult.Status.NEEDS_PLANNER, "action_failed:${outcome.error ?: verb}")
                    }
                    guard.acted(screen, option, outcome.changedScreen)?.let {
                        return finish(AutopilotResult.Status.NEEDS_PLANNER, it)
                    }
                }
                is StepDecision.Escalate -> error("escalations are resolved above")
            }
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
                StepOption.Back -> "pressed Back"
                StepOption.Wait -> "waited"
                StepOption.None -> "nothing"
            }
        }

        /** One screen in a few hundred characters, for handing control back to the planner. */
        fun summarize(screen: ScreenSnapshot): String {
            val items = screen.elements
                .filter { it.clickable || it.editable }
                .take(15)
                .joinToString("; ") { "[${it.id}] ${it.type} ${it.name?.take(30).orEmpty()}".trim() }
            return "${screen.packageName} › ${screen.title.orEmpty()} (${screen.elements.size} elements): $items"
        }
    }
}
