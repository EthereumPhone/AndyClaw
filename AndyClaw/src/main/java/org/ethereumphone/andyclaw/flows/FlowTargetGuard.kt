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
 *
 * Every check here is only as good as the screen it is given: a target that is not on it yet
 * passes all of them. The interpreter therefore waits for the target and checks the tree it found
 * it in, immediately before acting.
 */
object FlowTargetGuard {

    /** Semantic types that are controls, so their words describe what tapping them does. */
    internal val CONTROL_TYPES = setOf(
        "button", "icon_button", "nav_button", "link", "toggle", "checkbox",
        "radio_button", "tab", "spinner", "text_field",
    )

    /**
     * What `ScreenAnalyzer` calls every clickable container with text — a chat, a contact, a search
     * hit (`search_bar` when its id says search), a settings row. Tappable, but its words are who
     * or what the row is and a preview of it: content, not a verb. The analyzer's types feed the
     * flow checksums, so they are read here as they are rather than renamed.
     */
    internal val ROW_TYPES = setOf("menu_item", "search_bar")

    /** Whether a node of [type] is a control, whose words say what tapping it does. */
    internal fun isControl(type: String?): Boolean =
        type != null && type !in ROW_TYPES &&
            (type in CONTROL_TYPES || type.endsWith("Button") || type.contains("EditText"))

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
     * and description count — its value is what is being typed, not what the field is for. A
     * row is judged by its name alone: its summary is a preview of somebody's message.
     */
    fun sensitiveReason(tree: String?, viewId: String, typing: Boolean): String? {
        for (node in NodeTreeChecksum.nodesWithViewId(tree, viewId)) {
            if ((node["password"] as? JsonPrimitive)?.booleanOrNull == true) return "is a password field"
            val type = node.str("type") ?: node.str("cls")
            val control = isControl(type)
            val keys = if (typing || type in ROW_TYPES) listOf("label", "hint", "desc")
                else listOf("label", "hint", "desc", "value", "summary", "text")
            for (key in keys) {
                val words = node.str(key) ?: continue
                // A chat row that says "can you pay me back?" is not a Pay button.
                if (!control && words.trim().split(WHITESPACE).size > MAX_CONTROL_WORDS) continue
                if (FlowStepEffects.isSensitiveText(words)) {
                    return "now reads \"${words.take(40)}\""
                }
            }
        }
        return null
    }

    /**
     * The words of a live control carrying [viewId] that are written in a script the word lists
     * cannot read, or null. Nothing here can tell such a button's "Send" from its "Pay", so the
     * step that commits a replay may not tap it unattended.
     */
    fun unreadableControl(tree: String?, viewId: String): String? {
        for (node in NodeTreeChecksum.nodesWithViewId(tree, viewId)) {
            if (!isControl(node.str("type") ?: node.str("cls"))) continue
            for (key in listOf("label", "desc", "hint", "text")) {
                val words = node.str(key) ?: continue
                if (FlowStepEffects.unreadable(words)) return words.take(40)
            }
        }
        return null
    }

    /**
     * Whether a node carrying [viewId] names [expected]: one of its texts is [expected], or has it
     * as a whole word or phrase. "Anna" is named by "Anna", "Anna Schmidt" and "Chat with Anna" —
     * never by "Hanna" or "Annabel", which a substring match let through. Case is ignored.
     *
     * This is what an `assert` checks, and an assert is what proves a list row was the right one
     * ([identityAssertedAfter]), so a looser match is a wrong recipient that passes.
     */
    fun names(tree: String?, viewId: String, expected: String): Boolean {
        val wanted = expected.trim()
        if (wanted.isEmpty()) return false
        val whole = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(wanted) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        return NodeTreeChecksum.textOf(tree, viewId).lineSequence().any { text ->
            text.trim().equals(wanted, ignoreCase = true) || whole.containsMatchIn(text)
        }
    }

    /**
     * A value the live row carrying [viewId] names only as the start of a longer word — "Bobby"
     * for "Bob", the way a search's fuzzy hit looks — and never as a whole word, or null. A search
     * that finds one such hit leaves the id unique, so no ambiguity check fires, and a flow
     * compiled before taps carried an identity assert would open the wrong chat.
     *
     * Narrow on purpose, since a false hit retires a good flow: only non-controls (a button's
     * words are what it does, "Done" is no near miss for "Do"), only the row's name — not a
     * `summary` or `value`, where a chat preview "this is fine" would read as a near miss for a
     * message "this" — and only word prefixes ("hi" inside "this" is nobody). [values] are the
     * ones typed before the tap: only those can have picked the row.
     */
    fun partialValueMatch(tree: String?, viewId: String, values: Collection<String>): String? {
        val wanted = values.map { it.trim() }.filter { it.length >= 2 }
        if (wanted.isEmpty()) return null
        for (node in NodeTreeChecksum.nodesWithViewId(tree, viewId)) {
            if (isControl(node.str("type") ?: node.str("cls"))) continue
            val texts = listOf("label", "text", "desc").mapNotNull { node.str(it) }
            for (value in wanted) {
                val prefix = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(value) + "(?=[\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                if (texts.none { prefix.containsMatchIn(it) }) continue
                val whole = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(value) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                if (texts.none { whole.containsMatchIn(it) }) return value
            }
        }
        return null
    }

    /**
     * Whether something after the step at [index] proves the right node was hit before the flow
     * does anything that cannot be undone: an `assert` with `node_text_contains` (the toolbar
     * now names Anna) before the next checkpoint or irreversible step. The interpreter checks
     * that assert with [names], and gives it as long to hold as it gives a target to appear.
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
