package org.ethereumphone.andyclaw.autopilot

/**
 * How a run ended, as the user should hear it.
 *
 * The executor's reasons are codes for the model and for logs (`step_budget`,
 * `blocker:login`, `action_failed:tap`). Shown raw, a hand-over reads like a crash: the live
 * view used to say "Stopped: step_budget" in red while the agent was in fact carrying on. So
 * every surface — the live view, the launcher card, the rear HUD — gets the [Outcome] and one
 * short sentence from here, and never the code.
 */
object AutopilotOutcome {

    enum class Outcome {
        SUCCESS,
        /** The autopilot handed the task back to the model, which carries on. Not a failure. */
        HANDOFF,
        FAILED,
        /** The user pressed STOP. */
        STOPPED,
        /** The turn was cancelled underneath the run. */
        CANCELLED,
        ;

        /** The wire form, as `onAgentStep` and the tool result carry it. */
        val wire: String get() = name.lowercase()
    }

    const val REASON_STOPPED = "stopped_by_user"
    const val REASON_CANCELLED = "cancelled"

    fun of(status: AutopilotResult.Status, reason: String?): Outcome = when (status) {
        AutopilotResult.Status.SUCCESS -> Outcome.SUCCESS
        AutopilotResult.Status.NEEDS_PLANNER -> Outcome.HANDOFF
        AutopilotResult.Status.FAILED -> when (reason) {
            REASON_STOPPED -> Outcome.STOPPED
            REASON_CANCELLED -> Outcome.CANCELLED
            else -> Outcome.FAILED
        }
    }

    /**
     * One short sentence for [reason]. [say] is what the planner wrote for the user when it
     * aborted for a reason of its own; it is preferred over the generic sentence then.
     */
    fun message(status: AutopilotResult.Status, reason: String?, say: String? = null): String {
        if (status == AutopilotResult.Status.SUCCESS) return "Done"
        val r = reason.orEmpty()
        return when {
            r == REASON_STOPPED -> "Stopped"
            r == REASON_CANCELLED -> "Cancelled"
            r == "sensitive_app" -> "That's a private app, so I don't open or read it"
            r == "sensitive" -> "That step needs you (payment or sign-in)"
            r == "app_not_installed" -> "That app isn't installed"
            r == "app_unavailable" -> "Couldn't open the app"
            r == "screen_unreadable" -> "Couldn't read the screen"
            r == "internal_error" -> "Something went wrong on my side"
            r.startsWith("blocker:") -> when (r.removePrefix("blocker:")) {
                "login" -> "Needs you to sign in"
                "captcha" -> "There's a robot check to solve"
                "permission" -> "The app is asking for a permission"
                "payment" -> "This needs payment details, so it's over to you"
                "error" -> "The app showed an error"
                "update" -> "The app wants an update"
                "other_app" -> "Another app got in the way"
                else -> "Something on screen is in the way"
            }
            r == "step_budget" || r == "time_budget" || r == "subgoal_budget" ->
                "Taking longer than expected, handing over"
            r == "loop" -> "Going in circles, handing over"
            r == "stuck" -> "The app stopped reacting to taps, handing over"
            r == "stuck_loading" -> "The app kept loading, handing over"
            status == AutopilotResult.Status.NEEDS_PLANNER -> "Handing over to finish this"
            !say.isNullOrBlank() -> say.trim().take(MAX_MESSAGE)
            else -> "Couldn't finish this"
        }.take(MAX_MESSAGE)
    }

    private const val MAX_MESSAGE = 120
}
