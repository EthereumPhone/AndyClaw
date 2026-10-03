package org.ethereumphone.andyclaw.flows

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The "perceptual checksum per step" from `agent-os-design.md` §3: a cheap hash of the
 * node-tree **shape**, so a replay can tell "the screen I compiled against" from "a
 * screen that has changed under me" without a model looking at it.
 *
 * Shape means structure, not content. The package, the scrollability, and the ordered
 * list of `(type, view_id)` pairs go in; labels, values, text and bounds stay out.
 * That is the line that makes the checksum useful rather than merely strict:
 *
 * - a conversation list with different messages in it is the **same** screen — a
 *   checksum over labels would abort on every replay;
 * - an extra dialog, a missing button, a re-laid-out screen after an app update is a
 *   **different** screen — which is exactly when replaying blind would do damage.
 *
 * Reads both tree formats the accessibility proxy emits: the `ScreenAnalyzer` one
 * (`screen` + `elements[].type/.viewId`) and the legacy one (`elements[].cls/.id`).
 */
object NodeTreeChecksum {

    /** Returned when the tree could not be parsed at all. Never equal to a real checksum. */
    const val UNKNOWN = ""

    /** Number of hex characters kept. 16 hex = 64 bits, plenty to spot a changed screen. */
    private const val LENGTH = 16

    /** Marks a [ofV2] checksum. A value without it is v1 ([of]), as every flow before it has. */
    const val V2_PREFIX = "2:"

    /** v1: the ordered `(type, view_id)` list — so a list that gained a row is a new screen. */
    fun of(treeJson: String?): String {
        val shape = shapeOf(treeJson) ?: return UNKNOWN
        return FlowCodec.sha256Hex(shape.toByteArray(Charsets.UTF_8)).take(LENGTH)
    }

    /**
     * v2: the set of distinct `(type, view_id)` pairs, sorted. A conversation list with four
     * rows and one with nine are the same screen, which v1 said they were not — so a flow
     * recorded on one aborted on the other for no reason. A missing button, a new dialog or a
     * different app still changes it.
     */
    fun ofV2(treeJson: String?): String {
        val shape = shapeV2Of(treeJson) ?: return UNKNOWN
        return V2_PREFIX + FlowCodec.sha256Hex(shape.toByteArray(Charsets.UTF_8)).take(LENGTH)
    }

    /** The checksum of [treeJson] in whichever version [expected] was recorded in. */
    fun matching(expected: String, treeJson: String?): String =
        if (expected.startsWith(V2_PREFIX)) ofV2(treeJson) else of(treeJson)

    /**
     * The canonical shape string [ofV2] is taken over. Exposed for diagnostics.
     *
     * Unlike v1 it never falls back to a smart-format element's numeric `id`: that is the
     * element's position on screen, so it counts rows by another name.
     */
    fun shapeV2Of(treeJson: String?): String? {
        val root = parse(treeJson) ?: return null
        val screen = root["screen"] as? JsonObject
        val pkg = screen?.get("package")?.jsonPrimitive?.contentOrNull.orEmpty()
        val scrollable = (root["scrollable"] as? JsonPrimitive)?.content ?: "?"
        val elements = root["elements"] as? JsonArray ?: JsonArray(emptyList())
        val pairs = elements.mapNotNull { element ->
            val el = element as? JsonObject ?: return@mapNotNull null
            val type = el["type"]?.jsonPrimitive?.contentOrNull
                ?: el["cls"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val viewId = el["viewId"]?.jsonPrimitive?.contentOrNull
                ?: (el["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: ""
            "$type|$viewId"
        }.distinct().sorted()
        return buildString {
            append(pkg).append('\n').append(scrollable).append('\n')
            pairs.forEach { append(it).append('\n') }
        }
    }

    /** The canonical shape string the checksum is taken over. Exposed for diagnostics. */
    fun shapeOf(treeJson: String?): String? {
        if (treeJson.isNullOrBlank()) return null
        val root = try {
            FlowCodec.json.parseToJsonElement(treeJson) as? JsonObject ?: return null
        } catch (e: Exception) {
            return null
        }

        val screen = root["screen"] as? JsonObject
        val pkg = screen?.get("package")?.jsonPrimitive?.contentOrNull.orEmpty()
        val scrollable = (root["scrollable"] as? JsonPrimitive)?.content ?: "?"
        val elements = root["elements"] as? JsonArray ?: JsonArray(emptyList())

        val sb = StringBuilder()
        sb.append(pkg).append('\n').append(scrollable).append('\n')
        for (element in elements) {
            val el = element as? JsonObject ?: continue
            // Smart format first, legacy second.
            val type = el["type"]?.jsonPrimitive?.contentOrNull
                ?: el["cls"]?.jsonPrimitive?.contentOrNull
                ?: ""
            val viewId = el["viewId"]?.jsonPrimitive?.contentOrNull
                ?: el["id"]?.jsonPrimitive?.contentOrNull
                ?: ""
            sb.append(type).append('|').append(viewId).append('\n')
        }
        return sb.toString()
    }

    /** The package the tree says is on screen, or null. */
    fun packageOf(treeJson: String?): String? {
        val root = parse(treeJson) ?: return null
        return (root["screen"] as? JsonObject)?.get("package")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Whether [treeJson] is a read of the screen at all. The OS and the accessibility service
     * answer a read they could not do with `{"error":…}` or `{"ok":false,…}`; taken for a screen,
     * that was one on which nothing the flow needed existed, and a fault of the display counted
     * against the flow.
     */
    fun isScreen(treeJson: String?): Boolean {
        val root = parse(treeJson) ?: return false
        if ("elements" in root || "screen" in root || "windows" in root) return true
        return "error" !in root && (root["ok"] as? JsonPrimitive)?.content != "false"
    }

    /** The elements carrying [viewId], in tree order. More than one means the id repeats. */
    fun nodesWithViewId(treeJson: String?, viewId: String): List<JsonObject> {
        val root = parse(treeJson) ?: return emptyList()
        val elements = root["elements"] as? JsonArray ?: return emptyList()
        return elements.mapNotNull { element ->
            val el = element as? JsonObject ?: return@mapNotNull null
            val id = el["viewId"]?.jsonPrimitive?.contentOrNull
                ?: (el["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            el.takeIf { id == viewId }
        }
    }

    private fun parse(treeJson: String?): JsonObject? {
        if (treeJson.isNullOrBlank()) return null
        return try {
            FlowCodec.json.parseToJsonElement(treeJson) as? JsonObject
        } catch (e: Exception) {
            null
        }
    }

    /** Every `view_id` present in a tree, for condition checks. */
    fun viewIdsOf(treeJson: String?): List<String> {
        if (treeJson.isNullOrBlank()) return emptyList()
        val root = try {
            FlowCodec.json.parseToJsonElement(treeJson) as? JsonObject ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val elements = root["elements"] as? JsonArray ?: return emptyList()
        return elements.mapNotNull { element ->
            val el = element as? JsonObject ?: return@mapNotNull null
            (el["viewId"]?.jsonPrimitive?.contentOrNull ?: el["id"]?.jsonPrimitive?.contentOrNull)
                ?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * The visible text carried by the node with [viewId], or by the whole screen when
     * [viewId] is null. Labels, summaries, hints and values — everything a person would
     * read off the screen.
     */
    fun textOf(treeJson: String?, viewId: String?): String {
        if (treeJson.isNullOrBlank()) return ""
        val root = try {
            FlowCodec.json.parseToJsonElement(treeJson) as? JsonObject ?: return ""
        } catch (e: Exception) {
            return ""
        }
        val elements = root["elements"] as? JsonArray ?: return ""
        val sb = StringBuilder()
        for (element in elements) {
            val el = element as? JsonObject ?: continue
            if (viewId != null) {
                val id = el["viewId"]?.jsonPrimitive?.contentOrNull
                    ?: el["id"]?.jsonPrimitive?.contentOrNull
                if (id != viewId) continue
            }
            for (key in TEXT_KEYS) {
                el[key]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { sb.append(it).append('\n') }
            }
        }
        return sb.toString()
    }

    private val TEXT_KEYS = listOf("label", "summary", "hint", "value", "text", "desc")
}
