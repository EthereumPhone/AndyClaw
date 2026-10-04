package org.ethereumphone.andyclaw.llm.reflex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The reflex models' label space, mirrored from `~/dgen1-llm/gen/spec.py`. The models were
 * trained on exactly these labels and schemas; change one side and the other is wrong.
 *
 * - [ACTIONS]: closed arguments. M1 picks the label, the arguments are fixed by it.
 * - [INTENTS]: open arguments. M1 picks the intent, M2 writes compact JSON in the model schema
 *   below, [validate] checks it, [ReflexResolver] turns it into AndyClaw's own tool input.
 * - Anything else is [NEEDS_AGENT]: the request goes to the agent exactly as before.
 */
object ReflexSpec {

    const val NEEDS_AGENT = "needs_agent"

    /** A fixed AndyClaw call: the tool and its input. */
    data class FixedCall(val tool: String, val input: Map<String, Any>)

    val ACTIONS: Map<String, FixedCall> = linkedMapOf(
        "wifi_on" to FixedCall("toggle_wifi", mapOf("enabled" to true)),
        "wifi_off" to FixedCall("toggle_wifi", mapOf("enabled" to false)),
        "bluetooth_on" to FixedCall("toggle_bluetooth", mapOf("enabled" to true)),
        "bluetooth_off" to FixedCall("toggle_bluetooth", mapOf("enabled" to false)),
        "airplane_on" to FixedCall("toggle_airplane_mode", mapOf("enabled" to true)),
        "airplane_off" to FixedCall("toggle_airplane_mode", mapOf("enabled" to false)),
        "mobile_data_on" to FixedCall("toggle_mobile_data", mapOf("enabled" to true)),
        "mobile_data_off" to FixedCall("toggle_mobile_data", mapOf("enabled" to false)),
        "hotspot_on" to FixedCall("toggle_hotspot", mapOf("enabled" to true)),
        "hotspot_off" to FixedCall("toggle_hotspot", mapOf("enabled" to false)),
        "dnd_on" to FixedCall("set_dnd_mode", mapOf("enabled" to true, "priority_only" to false)),
        "dnd_priority_on" to FixedCall("set_dnd_mode", mapOf("enabled" to true, "priority_only" to true)),
        "dnd_off" to FixedCall("set_dnd_mode", mapOf("enabled" to false)),
        "ringer_normal" to FixedCall("set_ringer_mode", mapOf("mode" to "normal")),
        "ringer_silent" to FixedCall("set_ringer_mode", mapOf("mode" to "silent")),
        "ringer_vibrate" to FixedCall("set_ringer_mode", mapOf("mode" to "vibrate")),
        "dark_mode_on" to FixedCall("write_secure_setting", mapOf("name" to "ui_night_mode", "value" to "2")),
        "dark_mode_off" to FixedCall("write_secure_setting", mapOf("name" to "ui_night_mode", "value" to "1")),
        "night_light_on" to FixedCall("write_secure_setting", mapOf("name" to "night_display_activated", "value" to "1")),
        "night_light_off" to FixedCall("write_secure_setting", mapOf("name" to "night_display_activated", "value" to "0")),
        "lock_screen" to FixedCall("lock_screen", emptyMap()),
        "led_clear" to FixedCall("led_clear", emptyMap()),
        "device_info" to FixedCall("get_device_info", emptyMap()),
        "connectivity_status" to FixedCall("get_connectivity_status", emptyMap()),
        "storage_info" to FixedCall("get_storage_info", emptyMap()),
        "list_reminders" to FixedCall("list_reminders", emptyMap()),
        // list_events takes epoch millis; the resolver turns the range into start/end.
        "events_today" to FixedCall("list_events", mapOf("range" to "today")),
        "events_tomorrow" to FixedCall("list_events", mapOf("range" to "tomorrow")),
        "events_week" to FixedCall("list_events", mapOf("range" to "week")),
        "price_eth" to FixedCall("get_token_price", mapOf("token" to "ETH", "chain_id" to 1)),
        "price_pol" to FixedCall("get_token_price", mapOf("token" to "MATIC", "chain_id" to 137)),
        "price_bnb" to FixedCall("get_token_price", mapOf("token" to "BNB", "chain_id" to 56)),
        "price_avax" to FixedCall("get_token_price", mapOf("token" to "AVAX", "chain_id" to 43114)),
    )

    /** Intent label → AndyClaw tool. */
    val INTENTS: Map<String, String> = linkedMapOf(
        "set_alarm" to "set_alarm",
        "create_reminder" to "create_reminder",
        "set_volume" to "set_volume",
        "launch_app" to "launch_app",
        "led_pattern" to "led_display_pattern",
        "led_flash" to "led_flash_pattern",
        "led_color" to "led_set_all",
    )

    /** Every label's AndyClaw tool, for the gate and for the shadow comparison. */
    fun toolFor(label: String): String? = ACTIONS[label]?.tool ?: INTENTS[label]

    val STREAMS = listOf("music", "ring", "notification", "alarm", "voice_call", "system")
    val DAYS = listOf("today", "tomorrow", "mon", "tue", "wed", "thu", "fri", "sat", "sun")
    val LED_PATTERNS = listOf(
        "chad", "plus", "minus", "success", "error", "warning", "info", "arrowup", "arrowdown", "swap", "sign",
    )
    val LED_FLASH = listOf("success", "error", "warning", "info")
    val LED_COLORS = linkedMapOf(
        "red" to "#FF0000", "green" to "#00FF00", "blue" to "#0000FF", "white" to "#FFFFFF",
        "yellow" to "#FFFF00", "orange" to "#FF8000", "purple" to "#8000FF", "pink" to "#FF40A0",
        "cyan" to "#00FFFF", "magenta" to "#FF00FF",
    )

    const val MAX_TEXT = 120

    /** M2's prompt. Gemma turn markers, so Llamatik passes it through untouched. */
    fun m2Prompt(intent: String, utterance: String): String =
        "<start_of_turn>user\n$intent|${utterance.trim()}<end_of_turn>\n<start_of_turn>model\n"

    /**
     * Null if [args] is a valid M2 output for [intent], else the reason. Exact key sets: an extra
     * key means the model invented something, and that is a reject too. Port of `spec.validate`.
     */
    fun validate(intent: String, args: JsonObject): String? {
        val keys = args.keys
        when (intent) {
            "set_alarm" -> {
                if ("in" in args) {
                    if (!(keys subsetOf setOf("in", "label")) || !int(args["in"], 1, 60 * 24)) return "in"
                } else if (!(setOf("h", "m") subsetOf keys) || !(keys subsetOf setOf("h", "m", "ap", "days", "label"))) {
                    return "keys"
                } else if (!(int(args["h"], 0, 23) && int(args["m"], 0, 59))) {
                    return "time"
                } else if ("ap" in args && (str(args["ap"]) != "?" || !int(args["h"], 1, 12))) {
                    return "ap"
                }
                if ("days" in args) {
                    val d = args["days"] as? JsonArray ?: return "days"
                    if (d.isEmpty() || !d.all { int(it, 1, 7) } || d.map { intOf(it) }.toSet().size != d.size) return "days"
                }
                if ("label" in args && !text(args["label"])) return "label"
            }
            "create_reminder" -> {
                if (keys == setOf("in", "text")) {
                    if (!int(args["in"], 1, 60 * 24 * 7)) return "in"
                } else if (setOf("h", "m", "text") subsetOf keys && keys subsetOf setOf("day", "h", "m", "ap", "text")) {
                    if ("day" in args && str(args["day"]) !in DAYS) return "day"
                    if (!(int(args["h"], 0, 23) && int(args["m"], 0, 59))) return "when"
                    if ("ap" in args && (str(args["ap"]) != "?" || !int(args["h"], 1, 12))) return "ap"
                } else {
                    return "keys"
                }
                if (!text(args["text"])) return "text"
            }
            "set_volume" -> {
                if (str(args["stream"]) !in STREAMS) return "stream"
                when (keys) {
                    setOf("stream", "to") -> if (!int(args["to"], 0, 100)) return "to"
                    setOf("stream", "step") -> if (str(args["step"]) !in setOf("up", "down")) return "step"
                    else -> return "keys"
                }
            }
            "launch_app" -> {
                val app = str(args["app"])
                if (keys != setOf("app") || !text(args["app"]) || app == null || app.length > 40) return "app"
            }
            "led_pattern" -> if (keys != setOf("pattern") || str(args["pattern"]) !in LED_PATTERNS) return "pattern"
            "led_flash" -> if (keys != setOf("pattern") || str(args["pattern"]) !in LED_FLASH) return "pattern"
            "led_color" -> {
                if (!(setOf("color") subsetOf keys && keys subsetOf setOf("color", "secs")) ||
                    str(args["color"]) !in LED_COLORS
                ) return "color"
                if ("secs" in args && !int(args["secs"], 1, 3600)) return "secs"
            }
            else -> return "unknown intent"
        }
        return null
    }

    private infix fun Set<String>.subsetOf(other: Set<String>) = other.containsAll(this)

    /** A JSON integer (not a bool, not a float, not a string) within [lo, hi]. */
    internal fun int(e: JsonElement?, lo: Int, hi: Int): Boolean {
        val v = intOf(e) ?: return false
        return v in lo..hi
    }

    internal fun intOf(e: JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString || p.booleanOrNull != null) return null
        if (p.content.contains('.') || p.content.contains('e', ignoreCase = true)) return null
        return p.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    internal fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun text(e: JsonElement?): Boolean {
        val s = str(e) ?: return false
        return s.trim().isNotEmpty() && s.length <= MAX_TEXT
    }
}
