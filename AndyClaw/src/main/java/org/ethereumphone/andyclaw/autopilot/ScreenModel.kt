package org.ethereumphone.andyclaw.autopilot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One element of the screen the autopilot reasons about.
 *
 * A pure mirror of the app's analyzer output (`analyzer/model/ScreenJson.kt`), so everything
 * here runs in unit tests with no Android types. [id] is only stable within one snapshot; the
 * [viewId]/[label]/[type] triple is what identifies an element across snapshots.
 */
data class ScreenElement(
    val id: Int,
    val type: String,
    val label: String? = null,
    val summary: String? = null,
    val hint: String? = null,
    val value: String? = null,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val password: Boolean = false,
    val viewId: String? = null,
    val actions: List<String> = emptyList(),
    val centerX: Int = 0,
    val centerY: Int = 0,
    /** 0 = the topmost application window; higher = further down, or a system window. */
    val window: Int = 0,
    /** Bounds in display pixels when known (the in-process snapshot has them; JSON does not). */
    val left: Int? = null,
    val top: Int? = null,
    val right: Int? = null,
    val bottom: Int? = null,
) {
    val clickable get() = "click" in actions
    val editable get() = "set_text" in actions || type == "text_field" || type == "search_bar"
    val scrollable get() = "scroll_forward" in actions || "scroll_backward" in actions
    val longClickable get() = "long_click" in actions

    /** The best human-readable name this element has, if any. */
    val name: String? get() = label ?: hint ?: summary

    /** Identity across snapshots — never [id], which is reassigned on every read. */
    val signature: String get() = "$type|${label.orEmpty()}|${viewId.orEmpty()}"
}

data class ScreenSnapshot(
    val packageName: String,
    val title: String? = null,
    val elements: List<ScreenElement>,
    val scrollable: Boolean = false,
    val keyboardVisible: Boolean = false,
    /** Short description of the non-primary windows present, e.g. `["dialog"]`. */
    val windows: List<String> = emptyList(),
    val width: Int = 720,
    val height: Int = 720,
) {
    fun byId(id: Int): ScreenElement? = elements.firstOrNull { it.id == id }

    /**
     * What identifies "the same screen": app, title and the interactive elements. Used for
     * loop detection, so it must ignore element ids and volatile text such as clocks.
     */
    val stateSignature: String by lazy {
        val interactive = elements
            .filter { it.clickable || it.editable || it.scrollable }
            .map { it.signature }
            .sorted()
        "$packageName|${title.orEmpty()}|$keyboardVisible|${interactive.joinToString(";")}".hashCode().toString(16)
    }
}

/** Parses the analyzer's smart-tree JSON (`{screen, elements, scrollable}`) into a snapshot. */
object SmartTreeParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(treeJson: String?, width: Int = 720, height: Int = 720): ScreenSnapshot? {
        if (treeJson.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(treeJson).jsonObject }.getOrNull() ?: return null
        val screen = root["screen"] as? JsonObject ?: return null
        val elements = (root["elements"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            ScreenElement(
                id = o.int("id") ?: return@mapNotNull null,
                type = o.str("type") ?: "unknown",
                label = o.str("label"),
                summary = o.str("summary"),
                hint = o.str("hint"),
                value = o.str("value"),
                checked = o.bool("checked"),
                enabled = o.bool("enabled") ?: true,
                selected = o.bool("selected") ?: false,
                password = o.bool("password") ?: false,
                viewId = o.str("viewId"),
                actions = (o["actions"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                centerX = o.int("center_x") ?: 0,
                centerY = o.int("center_y") ?: 0,
                window = o.int("window") ?: 0,
            )
        }
        return ScreenSnapshot(
            packageName = screen.str("package") ?: "",
            title = screen.str("title"),
            elements = elements,
            scrollable = (root["scrollable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            keyboardVisible = (root["keyboardVisible"] as? JsonPrimitive)?.booleanOrNull ?: false,
            windows = (root["windows"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            width = width,
            height = height,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
