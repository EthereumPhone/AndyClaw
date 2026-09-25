package org.ethereumphone.andyclaw.flows

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What is known about whether a node action reached the app.
 *
 * Only [NOT_DISPATCHED] means "certainly did not happen". A tap the OS stopped waiting for may
 * still land — a binder call cannot be interrupted — so the replay counts it as done: the one
 * thing worse than not finishing a task is doing it twice.
 */
enum class FlowDispatch {
    /** The app took the action. */
    DONE,

    /** The action never reached the app: no such node, refused, STOP latched, no display service. */
    NOT_DISPATCHED,

    /** It may or may not have happened: a timeout, an exception once the call was out, an unreadable answer. */
    UNKNOWN,
    ;

    /** Whether the world may have changed. Only a certain "no" is a no. */
    val mayHaveHappened: Boolean get() = this != NOT_DISPATCHED

    companion object {

        /**
         * `IAgentDisplayService`'s answer to a node action: `{"ok":true,"method":"a11y"}`, or
         * `{"ok":false,"error":"..."}` — with `"outcome":"unknown"` when the OS gave up waiting on a
         * call that may still be carried out.
         *
         * A failure is [NOT_DISPATCHED] only when it is one of the answers given before anything
         * reaches the app. Every other failure, and anything that cannot be read, is [UNKNOWN].
         */
        fun ofNodeActionResult(result: String?): FlowDispatch {
            if (result.isNullOrBlank()) return UNKNOWN
            val answer = try {
                FlowCodec.json.parseToJsonElement(result) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return UNKNOWN
            if (answer.string("outcome") == "unknown") return UNKNOWN
            return when ((answer["ok"] as? JsonPrimitive)?.booleanOrNull) {
                true -> DONE
                false -> if (neverReachedTheApp(answer.string("error").orEmpty())) NOT_DISPATCHED else UNKNOWN
                null -> UNKNOWN
            }
        }

        /**
         * The refusals that come before the action does: the OS's STOP latch, its full proxy pool
         * and its missing proxy; the accessibility service finding no such node, refusing a private
         * app, having nothing to fall back on after the node declined the action, or finding no
         * field with the focus to type into.
         */
        private fun neverReachedTheApp(error: String): Boolean =
            error.startsWith("Node not found") ||
                error == "stopped" ||
                error == "busy" ||
                error.startsWith("AccessibilityService not connected") ||
                error.contains("is a private app;") ||
                error.startsWith("Framework service unavailable") ||
                error == "field did not take focus"

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
