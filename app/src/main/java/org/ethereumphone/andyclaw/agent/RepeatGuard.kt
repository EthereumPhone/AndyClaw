package org.ethereumphone.andyclaw.agent

import org.ethereumphone.andyclaw.llm.ContentBlock

/**
 * Notices the main loop making the same calls and getting the same answers, iteration after
 * iteration.
 *
 * A device run tapped an already-selected option 45 times in a row: the screen it got back
 * never changed, so the model kept expecting the next tap to do something, and nothing but
 * the iteration limit would have ended it. The autopilot has `LoopGuard` for this; the main
 * loop had nothing.
 *
 * An iteration's signature is every call it made with its exact input and its exact result.
 * From [warnAt] identical iterations in a row the model is told so; at [stopAt] the turn ends.
 * Any difference — another tool, another argument, a screen that moved — starts the count over.
 */
internal class RepeatGuard(private val warnAt: Int = 3, private val stopAt: Int = 6) {

    enum class Verdict { OK, WARN, STOP }

    private var last: String? = null

    /** Identical iterations in a row, this one included. */
    var streak = 0
        private set

    fun observe(calls: List<ContentBlock.ToolUseBlock>, results: List<ContentBlock>): Verdict {
        val byId = results.filterIsInstance<ContentBlock.ToolResult>().associateBy { it.toolUseId }
        val signature = calls.joinToString("\n") { call ->
            "${call.name}\u0000${call.input}\u0000${byId[call.id]?.content}"
        }
        streak = if (signature == last) streak + 1 else 1
        last = signature
        return when {
            streak >= stopAt -> Verdict.STOP
            streak >= warnAt -> Verdict.WARN
            else -> Verdict.OK
        }
    }

    /** Appended to the iteration's last tool result from [warnAt] on. */
    fun warning(): String =
        "\n\n[Agent runtime: this exact call has now returned this exact result $streak times in a row. " +
            "Repeating it will not change anything. Check the result: if the task is already done " +
            "(for example the option shows [checked] or [selected]), tell the user. Otherwise try a different action.]"

    companion object {
        const val STOPPED_REPLY = "I kept repeating the same step and nothing changed, so I stopped there."
    }
}
