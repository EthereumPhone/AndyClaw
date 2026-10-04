package org.ethereumphone.andyclaw.llm.reflex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Every case in `~/dgen1-llm/gen/resolve.py`'s `__main__`, plus what the Kotlin side adds. */
class ReflexResolverTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)
    private val fri2114 = at(2026, 10, 2, 21, 14)   // a Friday
    private val wed1400 = at(2026, 9, 30, 14, 0)    // a Wednesday
    private val wed2330 = at(2026, 9, 30, 23, 30)

    private val device = object : ReflexResolver.Device {
        override fun streamMax(stream: String) = 15
        override fun streamVolume(stream: String) = 7
        override fun resolveApp(name: String) = if (name.equals("telegram", true)) "org.telegram.messenger" else null
    }
    private val r = ReflexResolver(device)

    private fun o(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun call(intent: String, args: String, text: String, now: ZonedDateTime) = r.resolveIntent(intent, o(args), text, now)
    private fun local(c: ReflexResolver.Call?): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(c!!.input["time"]!!.jsonPrimitive.long), zone)

    @Test fun alarmWithoutAmPmIsTheNextOccurrence() {
        assertEquals(o("""{"hour":7,"minutes":0}"""), call("set_alarm", """{"h":7,"m":0,"ap":"?"}""", "alarm at 7", fri2114)!!.input)
        assertEquals(o("""{"hour":11,"minutes":0}"""), call("set_alarm", """{"h":11,"m":0,"ap":"?"}""", "alarm at 11", wed2330)!!.input)
        assertEquals(o("""{"hour":17,"minutes":40}"""),
            call("set_alarm", """{"h":5,"m":40,"ap":"?"}""", "alarm at twenty to six", at(2026, 9, 29, 10, 0))!!.input)
    }

    @Test fun alarmInMinutes() {
        assertEquals(o("""{"hour":23,"minutes":44}"""), call("set_alarm", """{"in":150}""", "alarm in two and a half hours", fri2114)!!.input)
    }

    /** Kotlin-only: a repeating alarm has no next occurrence to choose by. */
    @Test fun repeatingAlarmWithoutAmPmIsDaytime() {
        val c = call("set_alarm", """{"h":7,"m":15,"ap":"?","days":[2,3,4,5,6]}""", "quarter past seven on weekdays", at(2026, 10, 4, 8, 30))
        assertEquals(o("""{"hour":7,"minutes":15,"days":[2,3,4,5,6]}"""), c!!.input)
        val five = call("set_alarm", """{"h":5,"m":0,"ap":"?","days":[1,7]}""", "5 on weekends", at(2026, 10, 4, 8, 30))
        assertEquals(5, five!!.input["hour"]!!.jsonPrimitive.long.toInt())
        val two = call("set_alarm", """{"h":2,"m":0,"ap":"?","days":[1]}""", "2 on sundays", at(2026, 10, 4, 8, 30))
        assertEquals(14, two!!.input["hour"]!!.jsonPrimitive.long.toInt())
    }

    @Test fun volumeGuardRefusesALevelNobodySaid() {
        assertNull(call("set_volume", """{"stream":"music","to":100}""", "too loud", wed1400))
        assertEquals(o("""{"stream":"music","level":15}"""), call("set_volume", """{"stream":"music","to":100}""", "max volume", wed1400)!!.input)
        assertEquals(o("""{"stream":"ring","level":6}"""), call("set_volume", """{"stream":"ring","to":40}""", "ring volume 40%", wed1400)!!.input)
    }

    @Test fun volumeStepIsOneFromTheCurrentLevel() {
        assertEquals(o("""{"stream":"music","level":6}"""), call("set_volume", """{"stream":"music","step":"down"}""", "too loud", wed1400)!!.input)
        assertEquals(o("""{"stream":"music","level":8}"""), call("set_volume", """{"stream":"music","step":"up"}""", "louder", wed1400)!!.input)
    }

    @Test fun reminders() {
        assertEquals(LocalDateTime.of(2026, 9, 30, 17, 0),
            local(call("create_reminder", """{"h":5,"m":0,"ap":"?","text":"pick up the kids"}""", "", wed1400)))
        assertEquals(LocalDateTime.of(2026, 10, 2, 15, 0),
            local(call("create_reminder", """{"day":"fri","h":3,"m":0,"ap":"?","text":"pay rent"}""", "", wed1400)))
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 0),
            local(call("create_reminder", """{"day":"tomorrow","h":9,"m":0,"ap":"?","text":"x"}""", "", wed1400)))
        // A named weekday is the next one, never today.
        assertEquals(LocalDateTime.of(2026, 10, 7, 9, 0),
            local(call("create_reminder", """{"day":"wed","h":9,"m":0,"text":"x"}""", "", wed1400)))
        // "today at 9" said at 14:00 cannot be honoured without asking.
        assertNull(call("create_reminder", """{"day":"today","h":9,"m":0,"text":"x"}""", "", wed1400))
        assertEquals("check the oven", call("create_reminder", """{"in":20,"text":"check the oven"}""", "", wed1400)!!.input["message"]!!.jsonPrimitive.content)
    }

    @Test fun invalidModelOutputIsRefused() {
        assertNull(call("set_alarm", """{"h":7,"m":0,"extra":1}""", "alarm at 7", wed1400))
        assertNull(call("set_alarm", """{"h":25,"m":0}""", "alarm", wed1400))
    }

    @Test fun appsResolveOrHandOver() {
        assertEquals(o("""{"package_name":"org.telegram.messenger"}"""), call("launch_app", """{"app":"Telegram"}""", "open telegram", wed1400)!!.input)
        assertNull(call("launch_app", """{"app":"Nonexistent"}""", "open nonexistent", wed1400))
    }

    @Test fun leds() {
        assertEquals(o("""{"color":"#0000FF","duration_ms":5000}"""), call("led_color", """{"color":"blue","secs":5}""", "", wed1400)!!.input)
        assertEquals(ReflexResolver.Call("led_display_pattern", o("""{"pattern":"chad"}""")), call("led_pattern", """{"pattern":"chad"}""", "", wed1400))
    }

    @Test fun actions() {
        assertEquals(ReflexResolver.Call("toggle_wifi", o("""{"enabled":false}""")), r.resolveAction("wifi_off", wed1400))
        assertEquals(ReflexResolver.Call("set_dnd_mode", o("""{"enabled":true,"priority_only":false}""")), r.resolveAction("dnd_on", wed1400))
        assertEquals(ReflexResolver.Call("get_token_price", o("""{"token":"ETH","chain_id":1}""")), r.resolveAction("price_eth", wed1400))
        val today = r.resolveAction("events_today", wed1400)!!
        assertEquals("list_events", today.tool)
        assertEquals(at(2026, 9, 30, 0, 0).toInstant().toEpochMilli(), today.input["start_time"]!!.jsonPrimitive.long)
        assertEquals(at(2026, 10, 1, 0, 0).toInstant().toEpochMilli(), today.input["end_time"]!!.jsonPrimitive.long)
        assertNull(r.resolveAction("needs_agent", wed1400))
    }
}
