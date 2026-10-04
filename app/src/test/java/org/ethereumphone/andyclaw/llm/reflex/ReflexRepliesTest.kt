package org.ethereumphone.andyclaw.llm.reflex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReflexRepliesTest {
    private fun call(tool: String, input: String) = ReflexResolver.Call(tool, Json.parseToJsonElement(input).jsonObject)

    @Test fun templates() {
        assertEquals("Wi-Fi is off.", ReflexReplies.confirm("wifi_off", call("toggle_wifi", """{"enabled":false}""")))
        assertEquals("Alarm set for 06:30.", ReflexReplies.confirm("set_alarm", call("set_alarm", """{"hour":6,"minutes":30}""")))
        assertEquals("Alarm set for 07:15 on weekdays.",
            ReflexReplies.confirm("set_alarm", call("set_alarm", """{"hour":7,"minutes":15,"days":[2,3,4,5,6]}""")))
        assertEquals("Music volume set to 6.", ReflexReplies.confirm("set_volume", call("set_volume", """{"stream":"music","level":6}""")))
        assertEquals("Opening Telegram.",
            ReflexReplies.confirm("launch_app", call("launch_app", """{"package_name":"org.telegram.messenger"}"""), { "Telegram" }))
        val zone = ZoneId.of("Europe/Berlin")
        val inAnHour = ZonedDateTime.now(zone).plusHours(1).withSecond(0).withNano(0)
        val r = ReflexReplies.confirm("create_reminder",
            call("create_reminder", """{"time":${inAnHour.toInstant().toEpochMilli()},"message":"call mom"}"""), zone = zone)!!
        assert(r.endsWith("%02d:%02d: call mom".format(inAnHour.hour, inAnHour.minute))) { r }
    }

    @Test fun readsHaveNoTemplate() {
        for (label in ReflexReplies.READS) assertNull(label, ReflexReplies.confirm(label, call("x", "{}")))
    }
}
