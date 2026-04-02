package org.ethereumphone.andyclaw.analyzer.model

import org.json.JSONArray
import org.json.JSONObject

data class Bounds(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x)
        put("y", y)
        put("w", w)
        put("h", h)
    }
}

data class UiElement(
    val id: Int,
    val type: SemanticType,
    val label: String?,
    val summary: String? = null,
    val hint: String? = null,
    val value: String? = null,
    val checked: Boolean? = null,
    val enabled: Boolean? = null, // only set when false
    val selected: Boolean? = null, // only set when true
    val password: Boolean? = null, // only set when true
    val actions: List<String>,
    val bounds: Bounds,
    val resourceId: String? = null,
    val viewId: String? = null // full resource name for a11y node actions (e.g. "com.android.settings:id/search_bar")
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.jsonName)
        label?.let { put("label", it) }
        summary?.let { put("summary", it) }
        hint?.let { put("hint", it) }
        value?.let { put("value", it) }
        checked?.let { put("checked", it) }
        enabled?.let { put("enabled", it) }
        selected?.let { put("selected", it) }
        password?.let { put("password", it) }
        viewId?.let { put("viewId", it) }
        put("actions", JSONArray(actions))
        put("center_x", bounds.x + bounds.w / 2)
        put("center_y", bounds.y + bounds.h / 2)
    }
}

data class ScreenContext(
    val packageName: String,
    val title: String?,
    val screenClass: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("package", packageName)
        title?.let { put("title", it) }
        screenClass?.let { put("screenClass", it) }
    }
}

data class ScreenResult(
    val screen: ScreenContext,
    val elements: List<UiElement>,
    val scrollable: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("screen", screen.toJson())
        put("elements", JSONArray().apply {
            elements.forEach { put(it.toJson()) }
        })
        put("scrollable", scrollable)
    }

    fun toJsonString(): String = toJson().toString(2)
}
