package org.ethereumphone.andyclaw.autopilot

import org.ethereumphone.andyclaw.flows.FlowStepEffects

/**
 * Thresholds for acting on Jev's answers. Jev returns *calibrated* probabilities, so these are
 * meaningful numbers rather than vibes — but they are first guesses, to be re-fit from shadow
 * mode data (recorded fixtures) before being trusted on a new app.
 */
data class AutopilotConfig(
    /** Act on Jev's pick at or above this confidence... */
    val actConfidence: Double = 0.80,
    /** ...and only if it beats the runner-up by this much. */
    val actMargin: Double = 0.15,
    /** Actions that send, post, buy or delete need more certainty. */
    val commitConfidence: Double = 0.92,
    val subgoalDone: Double = 0.80,
    /** "The whole goal is done" on the last sub-goal. */
    val goalDoneLast: Double = 0.85,
    /** ...and before the last sub-goal, where "done" is more likely to be premature. */
    val goalDoneEarly: Double = 0.95,
    val blocker: Double = 0.70,
    /** Below this, the last action is judged not to have worked: undo it and try another. */
    val lastProgressFailed: Double = 0.30,
    val commitsLikely: Double = 0.50,
    /** Raise the bar on a state each time an action there turned out wrong. */
    val thresholdBumpPerMiss: Double = 0.05,
    val maxStepsPerSubgoal: Int = 8,
    val maxWaitsPerState: Int = 3,
    val maxVisitsPerState: Int = 3,
    val maxNoEffectStreak: Int = 2,
    val maxPlannerEscalations: Int = 3,
    val maxJevFailures: Int = 2,
    val wallClockBudgetMs: Long = 60_000,
)

sealed interface StepDecision {
    /** Perform [option]. [advancesSubgoal] means the current sub-goal was judged done first. */
    data class Act(
        val option: StepOption,
        val confidence: Double,
        val advancesSubgoal: Boolean = false,
        val commits: Boolean = false,
    ) : StepDecision

    /** The sub-goal is done but Jev is unsure what comes next: move on and ask again. */
    data object AdvanceAndReask : StepDecision

    data object Wait : StepDecision
    data object Done : StepDecision

    /** The last action did the wrong thing: go back and never try it on that screen again. */
    data object Undo : StepDecision

    data class Escalate(val reason: String) : StepDecision
}

object StepPolicy {

    fun decide(
        response: JevResponse,
        prompt: StepPrompt,
        plan: AutopilotPlan,
        subgoalIndex: Int,
        screen: ScreenSnapshot,
        lastActionCommitted: Boolean,
        thresholdBump: Double,
        config: AutopilotConfig,
    ): StepDecision {
        val isLastSubgoal = subgoalIndex == plan.steps.lastIndex

        // A blocker is the one answer that overrides everything else.
        response.choice(Questions.BLOCKER)?.let { b ->
            if (b.choice != "none" && b.confidence >= config.blocker) {
                return StepDecision.Escalate("blocker:${b.choice}")
            }
        }

        val goalDone = response.noul(Questions.GOAL_DONE) ?: 0.0
        if (goalDone >= (if (isLastSubgoal) config.goalDoneLast else config.goalDoneEarly)) {
            return StepDecision.Done
        }

        val lastProgress = response.noul(Questions.LAST_PROGRESS)
        if (lastProgress != null && lastProgress < config.lastProgressFailed && !lastActionCommitted) {
            return StepDecision.Undo
        }

        val subgoalDone = response.noul(Questions.SUBGOAL_DONE) ?: 0.0
        if (subgoalDone >= config.subgoalDone) {
            if (isLastSubgoal) return StepDecision.Done
            val following = response.choice(Questions.NEXT_FOLLOWING)
            val act = following?.let { actOn(it, prompt, plan, screen, response, thresholdBump, config, advances = true) }
            return if (act is StepDecision.Act) act else StepDecision.AdvanceAndReask
        }

        val next = response.choice(Questions.NEXT) ?: return StepDecision.Escalate("jev_no_answer")
        return actOn(next, prompt, plan, screen, response, thresholdBump, config, advances = false)
    }

    private fun actOn(
        answer: JevAnswer.Choice,
        prompt: StepPrompt,
        plan: AutopilotPlan,
        screen: ScreenSnapshot,
        response: JevResponse,
        thresholdBump: Double,
        config: AutopilotConfig,
        advances: Boolean,
    ): StepDecision {
        val option = prompt.options[answer.choice] ?: return StepDecision.Escalate("jev_unknown_option")
        when (option) {
            StepOption.Wait -> return StepDecision.Wait
            StepOption.None -> return StepDecision.Escalate("no_option")
            else -> Unit
        }

        val target = option.elementId?.let { screen.byId(it) }
        // agent-os-design.md §6: never automate an auth or payment step. Not a confirmation —
        // the autopilot simply does not do it, and hands the decision to the planner.
        if (target != null && isSensitive(target)) return StepDecision.Escalate("sensitive")

        val commits = (response.noul(Questions.COMMITS) ?: 0.0) >= config.commitsLikely ||
            (target != null && isCommitLike(target))
        val needed = (if (commits) config.commitConfidence else config.actConfidence) + thresholdBump
        if (answer.confidence < needed || answer.margin < config.actMargin) {
            return StepDecision.Escalate(if (commits) "low_confidence_commit" else "low_confidence")
        }
        return StepDecision.Act(option, answer.confidence, advancesSubgoal = advances, commits = commits)
    }

    fun isSensitive(e: ScreenElement): Boolean {
        if (e.password) return true
        val tokens = FlowStepEffects.tokenize(listOfNotNull(e.label, e.hint, e.viewId).joinToString(" "))
        return tokens.any { it in FlowStepEffects.SENSITIVE_TOKENS }
    }

    fun isCommitLike(e: ScreenElement): Boolean {
        val tokens = FlowStepEffects.tokenize(listOfNotNull(e.label, e.viewId).joinToString(" "))
        return tokens.any { it in FlowStepEffects.COMMIT_TOKENS }
    }
}

/**
 * Remembers what has been tried where, so the autopilot neither repeats a mistake nor circles.
 */
class LoopGuard(private val config: AutopilotConfig) {

    private val visits = HashMap<String, Int>()
    private val waits = HashMap<String, Int>()
    private val tried = HashMap<String, MutableSet<String>>()
    private val bumps = HashMap<String, Double>()
    private var noEffectStreak = 0
    private var stepsInSubgoal = 0

    /** Returns a loop reason, or null. Call once per fresh snapshot. */
    fun visit(screen: ScreenSnapshot): String? {
        val n = (visits[screen.stateSignature] ?: 0) + 1
        visits[screen.stateSignature] = n
        return if (n > config.maxVisitsPerState) "loop" else null
    }

    fun triedOn(screen: ScreenSnapshot): Set<String> = tried[screen.stateSignature].orEmpty()

    fun thresholdBump(screen: ScreenSnapshot): Double = bumps[screen.stateSignature] ?: 0.0

    /** Records an action; returns "stuck" after too many that changed nothing. */
    fun acted(screen: ScreenSnapshot, option: StepOption, changedScreen: Boolean): String? {
        tried.getOrPut(screen.stateSignature) { HashSet() } += option.actionSignature(screen)
        stepsInSubgoal++
        noEffectStreak = if (changedScreen) 0 else noEffectStreak + 1
        return if (noEffectStreak >= config.maxNoEffectStreak) "stuck" else null
    }

    /** The action taken on [screen] was wrong: never offer it there again, and demand more. */
    fun miss(screen: ScreenSnapshot) {
        bumps[screen.stateSignature] = (bumps[screen.stateSignature] ?: 0.0) + config.thresholdBumpPerMiss
    }

    fun waited(screen: ScreenSnapshot): String? {
        val n = (waits[screen.stateSignature] ?: 0) + 1
        waits[screen.stateSignature] = n
        return if (n > config.maxWaitsPerState) "stuck_loading" else null
    }

    fun subgoalAdvanced() {
        stepsInSubgoal = 0
    }

    fun subgoalBudgetExceeded(): Boolean = stepsInSubgoal >= config.maxStepsPerSubgoal
}
