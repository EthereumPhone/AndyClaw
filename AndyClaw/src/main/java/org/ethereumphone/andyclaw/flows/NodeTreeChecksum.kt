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

    fun of(treeJson: String?): String {
        val shape = shapeOf(treeJson) ?: return UNKNOWN
        return FlowCodec.sha256Hex(shape.toByteArray(Charsets.UTF_8)).take(LENGTH)
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
