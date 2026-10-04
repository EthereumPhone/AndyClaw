package org.ethereumphone.andyclaw.llm.reflex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one-line reply for an action the on-device path ran. Templates, not a model: what it says
 * is exactly what was done. Read-only labels have none — their result goes to the agent, which
 * phrases it (see [READS]).
 */
object ReflexReplies {

    /** Labels whose result needs phrasing: run on the device, then answered by the agent. */
    val READS = setOf(
        "device_info", "connectivity_status", "storage_info", "list_reminders",
        "events_today", "events_tomorrow", "events_week",
        "price_eth", "price_pol", "price_bnb", "price_avax",
    )

    private val FIXED = mapOf(
        "wifi_on" to "Wi-Fi is on.", "wifi_off" to "Wi-Fi is off.",
        "bluetooth_on" to "Bluetooth is on.", "bluetooth_off" to "Bluetooth is off.",
        "airplane_on" to "Airplane mode is on.", "airplane_off" to "Airplane mode is off.",
        "mobile_data_on" to "Mobile data is on.", "mobile_data_off" to "Mobile data is off.",
        "hotspot_on" to "Hotspot is on.", "hotspot_off" to "Hotspot is off.",
        "dnd_on" to "Do Not Disturb is on.", "dnd_priority_on" to "Do Not Disturb is on (priority only).",
        "dnd_off" to "Do Not Disturb is off.",
        "ringer_normal" to "Ringer is on.", "ringer_silent" to "Phone is silent.", "ringer_vibrate" to "Phone is on vibrate.",
        "dark_mode_on" to "Dark mode is on.", "dark_mode_off" to "Dark mode is off.",
        "night_light_on" to "Night light is on.", "night_light_off" to "Night light is off.",
        "lock_screen" to "Locked.", "led_clear" to "LEDs off.",
        "led_pattern" to "Done.", "led_flash" to "Done.", "led_color" to "Done.",
    )

    private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** The reply for [label] after [call] succeeded, or null when there is no template. */
    fun confirm(label: String, call: ReflexResolver.Call, appLabel: (String) -> String? = { null }, zone: ZoneId = ZoneId.systemDefault()): String? {
        FIXED[label]?.let { return it }
        val i = call.input
        val text = when (label) {
            "set_alarm" -> {
                val h = i["hour"]?.jsonPrimitive?.intOrNull ?: return null
                val m = i["minutes"]?.jsonPrimitive?.intOrNull ?: return null
                val days = (i["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                val repeat = when {
                    days == null -> ""
                    days.sorted() == listOf(2, 3, 4, 5, 6) -> " on weekdays"
                    days.sorted() == listOf(1, 7) -> " on weekends"
                    days.size == 7 -> " every day"
                    else -> " on " + days.sorted().joinToString(", ") { DAY_NAMES.getOrElse(it - 1) { "?" } }
                }
                "Alarm set for %02d:%02d%s.".format(Locale.ROOT, h, m, repeat)
            }
            "create_reminder" -> {
                val at = i["time"]?.jsonPrimitive?.longOrNull ?: return null
                val msg = i["message"]?.jsonPrimitive?.contentOrNull ?: return null
                val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
                val today = ZonedDateTime.now(zone).toLocalDate()
                val day = when (t.toLocalDate()) {
                    today -> "today"
                    today.plusDays(1) -> "tomorrow"
                    else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                }
                "I'll remind you $day at %02d:%02d: %s".format(Locale.ROOT, t.hour, t.minute, msg)
            }
            "set_volume" -> {
                val stream = i["stream"]?.jsonPrimitive?.contentOrNull ?: return null
                val level = i["level"]?.jsonPrimitive?.intOrNull ?: return null
                "${stream.replaceFirstChar { it.uppercase() }.replace('_', ' ')} volume set to $level."
            }
            "launch_app" -> {
                val pkg = i["package_name"]?.jsonPrimitive?.contentOrNull ?: return null
                "Opening ${appLabel(pkg) ?: pkg}."
            }
            else -> return null
        }
        return text
    }
}
