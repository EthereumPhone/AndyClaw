package org.ethereumphone.andyclaw.flows

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.ethereumphone.andyclaw.autopilot.SensitiveApps
import org.ethereumphone.andyclaw.skills.ToolEffect

/**
 * What a replay checks about the node it is about to touch, on the live screen.
 *
 * The compiled flow names a view id and nothing else, so everything the IR cannot say — what the
 * button reads *now*, whether the id repeats — has to be read off the screen at replay time. A flow
 * compiled while a button said "Next" must not tap it once the same id says "Pay €49".
 */
object FlowTargetGuard {

    /** Semantic types that are controls, so their words describe what tapping them does. */
    private val CONTROL_TYPES = setOf(
        "button", "icon_button", "nav_button", "menu_item", "link", "toggle", "checkbox",
        "radio_button", "tab", "spinner", "text_field", "search_bar",
    )

    /** Longer than this, a non-control's label is content (a chat row's preview), not a verb. */
    private const val MAX_CONTROL_WORDS = 4

    /**
     * The private app the screen belongs to, if it is one — a replay never acts inside one. The
     * screen's own package, not every package in the tree: SystemUI is on the private list and
     * shows toasts on the display, and a toast is nothing a replay could tap. A tree without a
     * `screen` (the legacy format) falls back to looking at every window.
     */
    fun privateAppOn(tree: String?): String? {
        val top = NodeTreeChecksum.packageOf(tree) ?: return SensitiveApps.sensitivePackageIn(tree)
        return top.takeIf(SensitiveApps::isSensitive)
    }

    /**
     * Why the live node(s) carrying [viewId] must not be touched, or null: a password field, or
     * a control whose words say payment or sign-in. When [typing], only the field's label, hint
     * and description count — its value is what is being typed, not what the field is for.
     */
    fun sensitiveReason(tree: String?, viewId: String, typing: Boolean): String? {
        for (node in NodeTreeChecksum.nodesWithViewId(tree, viewId)) {
            if ((node["password"] as? JsonPrimitive)?.booleanOrNull == true) return "is a password field"
            val type = node.str("type") ?: node.str("cls")
            val control = type != null && (type in CONTROL_TYPES || type.endsWith("Button") || type.contains("EditText"))
            val keys = if (typing) listOf("label", "hint", "desc") else listOf("label", "hint", "desc", "value", "summary", "text")
            for (key in keys) {
                val words = node.str(key) ?: continue
                // A chat row that says "can you pay me back?" is not a Pay button.
                if (!control && words.trim().split(WHITESPACE).size > MAX_CONTROL_WORDS) continue
                if (FlowStepEffects.tokenize(words).any { it in FlowStepEffects.SENSITIVE_TOKENS }) {
                    return "now reads \"${words.take(40)}\""
                }
            }
        }
        return null
    }

    /**
     * Whether something after the step at [index] proves the right node was hit before the flow
     * does anything that cannot be undone: an `assert` with `node_text_contains` (the toolbar
     * now names Anna) before the next checkpoint or irreversible step.
     */
    fun identityAssertedAfter(steps: List<FlowStep>, index: Int): Boolean {
        for (i in index + 1 until steps.size) {
            val step = steps[i]
            if (step is AssertStep && !step.nodeTextContains.isNullOrBlank()) return true
            if (step is CheckpointStep) return false
            if (FlowStepEffects.of(step).ordinal >= ToolEffect.IRREVERSIBLE.ordinal) return false
        }
        return false
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private val WHITESPACE = Regex("\\s+")
}
