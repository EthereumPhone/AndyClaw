package org.ethereumphone.andyclaw.llm.reflex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ReflexSpecTest {
    private fun v(intent: String, json: String) = ReflexSpec.validate(intent, Json.parseToJsonElement(json).jsonObject)

    @Test fun validOutputs() {
        assertNull(v("set_alarm", """{"h":6,"m":30}"""))
        assertNull(v("set_alarm", """{"h":7,"m":15,"ap":"?","days":[2,3,4,5,6],"label":"gym"}"""))
        assertNull(v("set_alarm", """{"in":20}"""))
        assertNull(v("create_reminder", """{"in":20,"text":"check the oven"}"""))
        assertNull(v("create_reminder", """{"day":"fri","h":3,"m":0,"ap":"?","text":"pay rent"}"""))
        assertNull(v("set_volume", """{"stream":"music","to":40}"""))
        assertNull(v("set_volume", """{"stream":"ring","step":"down"}"""))
        assertNull(v("launch_app", """{"app":"Telegram"}"""))
        assertNull(v("led_color", """{"color":"blue","secs":5}"""))
    }

    @Test fun invalidOutputs() {
        assertEquals("keys", v("set_alarm", """{"h":6}"""))
        assertEquals("keys", v("set_alarm", """{"h":6,"m":0,"x":1}"""))
        assertEquals("time", v("set_alarm", """{"h":6,"m":60}"""))
        assertEquals("time", v("set_alarm", """{"h":"6","m":0}"""))
        assertEquals("time", v("set_alarm", """{"h":6.5,"m":0}"""))
        assertEquals("time", v("set_alarm", """{"h":true,"m":0}"""))
        assertEquals("ap", v("set_alarm", """{"h":13,"m":0,"ap":"?"}"""))
        assertEquals("ap", v("set_alarm", """{"h":1,"m":0,"ap":"pm"}"""))
        assertEquals("days", v("set_alarm", """{"h":1,"m":0,"days":[1,1]}"""))
        assertEquals("days", v("set_alarm", """{"h":1,"m":0,"days":[]}"""))
        assertEquals("in", v("set_alarm", """{"in":0}"""))
        assertEquals("keys", v("create_reminder", """{"in":20}"""))
        assertEquals("text", v("create_reminder", """{"in":20,"text":"  "}"""))
        assertEquals("day", v("create_reminder", """{"day":"someday","h":1,"m":0,"text":"x"}"""))
        assertEquals("stream", v("set_volume", """{"stream":"bass","to":40}"""))
        assertEquals("keys", v("set_volume", """{"stream":"music","to":40,"step":"up"}"""))
        assertEquals("app", v("launch_app", """{"app":""}"""))
        assertEquals("app", v("launch_app", """{"app":"${"x".repeat(41)}"}"""))
        assertEquals("pattern", v("led_flash", """{"pattern":"chad"}"""))
        assertEquals("color", v("led_color", """{"color":"beige"}"""))
        assertEquals("unknown intent", v("make_coffee", """{}"""))
    }

    @Test fun promptFormatIsWhatM2WasTrainedOn() {
        assertEquals(
            "<start_of_turn>user\nset_alarm|wake me up at half six<end_of_turn>\n<start_of_turn>model\n",
            ReflexSpec.m2Prompt("set_alarm", "  wake me up at half six "),
        )
    }

    /** The shipped labels must name exactly the labels and tools this file defines. */
    @Test fun shippedLabelsMatchTheSpec() {
        val labels = ReflexLabels.parse(File("src/main/assets/reflex/${ReflexRuntime.LABELS}").readText())
        assertEquals(listOf(ReflexSpec.NEEDS_AGENT) + ReflexSpec.ACTIONS.keys + ReflexSpec.INTENTS.keys, labels.actions)
        for ((label, tool) in labels.actionTool) assertEquals(label, ReflexSpec.toolFor(label), tool)
        for (label in labels.actions.drop(1)) assert(ReflexSpec.toolFor(label) in labels.tools) { label }
    }
}
