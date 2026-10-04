package org.ethereumphone.andyclaw.llm.reflex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The deterministic step after the models: a label (and M2's arguments) → the AndyClaw tool call.
 * Port of `~/dgen1-llm/gen/resolve.py`; `ReflexResolverTest` holds its cases.
 *
 * The model reads, this code computes: clock arithmetic, volume scaling and app lookup all happen
 * here. Nothing guesses — anything it cannot resolve is null, and the request goes to the agent.
 */
class ReflexResolver(private val device: Device) {

    /** What the resolver needs from the phone. */
    interface Device {
        /** `AudioManager.getStreamMaxVolume`, or null for an unknown stream. */
        fun streamMax(stream: String): Int?

        /** `AudioManager.getStreamVolume`, or null for an unknown stream. */
        fun streamVolume(stream: String): Int?

        /**
         * The package of the one launchable app the user means by [name], or null when there is
         * none, more than one, or it is an app the agent may not open.
         */
        fun resolveApp(name: String): String?
    }

    data class Call(val tool: String, val input: JsonObject)

    /** An M1-only action → its call. Calendar ranges get their times here. */
    fun resolveAction(label: String, now: ZonedDateTime): Call? {
        val fixed = ReflexSpec.ACTIONS[label] ?: return null
        if (fixed.tool == "list_events") {
            val day = now.truncatedTo(ChronoUnit.DAYS)
            val (start, end) = when (fixed.input["range"]) {
                "today" -> day to day.plusDays(1)
                "tomorrow" -> day.plusDays(1) to day.plusDays(2)
                "week" -> now to now.plusDays(7)
                else -> return null
            }
            return Call(fixed.tool, buildJsonObject {
                put("start_time", start.toInstant().toEpochMilli())
                put("end_time", end.toInstant().toEpochMilli())
            })
        }
        return Call(fixed.tool, toJson(fixed.input))
    }

    /** M2's validated output → the call, or null to hand the request to the agent. */
    fun resolveIntent(intent: String, args: JsonObject, utterance: String, now: ZonedDateTime): Call? {
        if (ReflexSpec.validate(intent, args) != null) return null
        val tool = ReflexSpec.INTENTS[intent] ?: return null
        fun i(key: String) = ReflexSpec.intOf(args[key])!!
        fun s(key: String) = ReflexSpec.str(args[key])!!
        return when (intent) {
            "set_alarm" -> {
                val out = buildJsonObject {
                    if ("in" in args) {
                        val t = now.plusMinutes(i("in").toLong())
                        put("hour", t.hour); put("minutes", t.minute)
                    } else {
                        val hours = when {
                            args["ap"] == null -> listOf(i("h"))
                            // A repeating alarm has no "next occurrence" to disambiguate by: "7 on
                            // weekdays" said at 08:30 is still a morning alarm.
                            "days" in args -> listOf(alarmDaytime(i("h")))
                            else -> listOf(i("h") % 12, i("h") % 12 + 12)
                        }
                        val t = nextOccurrence(now, hours, i("m"))
                        put("hour", t.hour); put("minutes", t.minute)
                        (args["days"] as? JsonArray)?.let { put("days", it) }
                    }
                    if ("label" in args) put("label", s("label"))
                }
                Call(tool, out)
            }
            "create_reminder" -> {
                val t = when {
                    "in" in args -> now.plusMinutes(i("in").toLong())
                    "day" !in args -> nextOccurrence(now, hours(args), i("m"))
                    else -> {
                        val h = if (args["ap"] != null) daytime(i("h")) else i("h")
                        val base = when (val d = s("day")) {
                            "today" -> now
                            "tomorrow" -> now.plusDays(1)
                            else -> {
                                val target = DAY_INDEX.getValue(d)
                                val ahead = ((target - (now.dayOfWeek.value - 1)) % 7 + 7) % 7
                                now.plusDays((if (ahead == 0) 7 else ahead).toLong())
                            }
                        }
                        val t = base.withHour(h).withMinute(i("m")).truncatedTo(ChronoUnit.MINUTES)
                        // "today at 9" said at 10: not a time we can honour without asking.
                        if (!t.isAfter(now)) return null
                        t
                    }
                }
                Call(tool, buildJsonObject {
                    put("time", t.toInstant().toEpochMilli())
                    put("message", s("text"))
                })
            }
            "set_volume" -> {
                val stream = s("stream")
                val max = device.streamMax(stream) ?: return null
                val level = if ("to" in args) {
                    // A level the user never said ("too loud" → 100 %) is refused.
                    if (!VOLUME_EVIDENCE.containsMatchIn(utterance)) return null
                    Math.round(i("to") / 100.0 * max).toInt()
                } else {
                    val current = device.streamVolume(stream) ?: return null
                    (current + if (s("step") == "up") 1 else -1).coerceIn(0, max)
                }
                Call(tool, buildJsonObject { put("stream", stream); put("level", level) })
            }
            "launch_app" -> {
                val pkg = device.resolveApp(s("app")) ?: return null
                Call(tool, buildJsonObject { put("package_name", pkg) })
            }
            "led_pattern", "led_flash" -> Call(tool, buildJsonObject { put("pattern", s("pattern")) })
            "led_color" -> Call(tool, buildJsonObject {
                put("color", ReflexSpec.LED_COLORS.getValue(s("color")))
                if ("secs" in args) put("duration_ms", i("secs") * 1000)
            })
            else -> null
        }
    }

    companion object {
        val VOLUME_EVIDENCE = Regex(
            "\\d|\\b(max|maximum|full|half|mute|muted|zero|all the way|one|two|three|four|five|six|seven|eight|nine|ten|" +
                "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|percent)\\b",
            RegexOption.IGNORE_CASE,
        )

        private val DAY_INDEX = mapOf("mon" to 0, "tue" to 1, "wed" to 2, "thu" to 3, "fri" to 4, "sat" to 5, "sun" to 6)

        /** The earliest moment strictly after [now] at one of [hours]:[m]. */
        internal fun nextOccurrence(now: ZonedDateTime, hours: List<Int>, m: Int): ZonedDateTime =
            hours.map { h ->
                val t = now.withHour(h).withMinute(m).truncatedTo(ChronoUnit.MINUTES)
                if (t.isAfter(now)) t else t.plusDays(1)
            }.minBy { it.toInstant() }

        private fun hours(args: JsonObject): List<Int> {
            val h = ReflexSpec.intOf(args["h"])!!
            return if (args["ap"] != null) listOf(h % 12, h % 12 + 12) else listOf(h)
        }

        /** A reminder's daytime default for an hour said without am/pm: 7–11 am, 12–6 pm. */
        internal fun daytime(h12: Int): Int = when {
            h12 in 7..11 -> h12 % 12
            h12 == 12 -> 12
            else -> h12 % 12 + 12
        }

        /** An alarm's: 4–11 am, 12 noon, 1–3 pm. People set early alarms, rarely evening ones. */
        internal fun alarmDaytime(h12: Int): Int = when {
            h12 in 4..11 -> h12
            h12 == 12 -> 12
            else -> h12 % 12 + 12
        }

        private fun toJson(m: Map<String, Any>): JsonObject = buildJsonObject {
            for ((k, v) in m) when (v) {
                is Boolean -> put(k, v)
                is Int -> put(k, v)
                is String -> put(k, v)
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }
    }
}
