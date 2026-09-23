package org.ethereumphone.andyclaw.autopilot

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JevModelsTest {

    private val questions = mapOf(
        "next" to JevQuestion.Choice("Which?", mapOf("tap:1" to "Tap [1]", "back" to "Back")),
        "done" to JevQuestion.Noul("Done?"),
    )

    @Test
    fun `request serialises to the System One wire format`() {
        val json = JevRequest("screen", questions).toJson()
        assertEquals("screen", json["state"]!!.jsonPrimitive.content)
        val q = json["questions"]!!.jsonObject
        assertEquals("choice", q["next"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Tap [1]", (q["next"]!!.jsonObject["criteria"] as JsonObject)["tap:1"]!!.jsonPrimitive.content)
        assertEquals("noul", q["done"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("a noul question carries no criteria", q["done"]!!.jsonObject.containsKey("criteria"))
    }

    @Test
    fun `parses choice and noul answers with usage and timing`() {
        val body = """{"id":"x","model":"jev-1.13","answers":{
            "next":{"choice":"tap:1","probabilities":{"tap:1":0.9,"back":0.1},"confidence":0.88},
            "done":{"noul":0.12}},
            "usage":{"input_tokens":321,"output_tokens":4},"jev":{"upstream_ms":71,"total_ms":80},"extra":true}"""
        val r = JevResponseParser.parse(body, questions, rttMs = 150)
        val next = r.choice("next")!!
        assertEquals("tap:1", next.choice)
        assertEquals(0.88, next.confidence, 1e-9)
        assertEquals(0.8, next.margin, 1e-9)
        assertEquals(0.12, r.noul("done")!!, 1e-9)
        assertEquals(321, r.inputTokens)
        assertEquals(71L, r.upstreamMs)
        assertEquals(150L, r.rttMs)
    }

    @Test
    fun `an answer naming an option we never offered is dropped`() {
        val body = """{"answers":{"next":{"choice":"tap:99","confidence":0.99}}}"""
        assertNull(JevResponseParser.parse(body, questions, 1).choice("next"))
    }

    @Test
    fun `confidence falls back to the chosen option's probability`() {
        val body = """{"answers":{"next":{"choice":"back","probabilities":{"back":0.7,"tap:1":0.3}}}}"""
        assertEquals(0.7, JevResponseParser.parse(body, questions, 1).choice("next")!!.confidence, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a choice cannot exceed Jev's option limit`() {
        JevQuestion.Choice("x", (0..255).associate { "o$it" to "o" })
    }

    @Test
    fun `plan parsing validates typed keys against values`() {
        val ok = AutopilotPlan.fromToolInput(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"package_name":"com.msg","goal":"g","values":{"body":"hi"},
               "steps":[{"do":"write","type":"body"},{"do":"send","done_when":"sent"}],"max_steps":99}""").jsonObject)
        assertTrue(ok.isSuccess)
        val plan = ok.getOrThrow()
        assertEquals(listOf("body"), plan.steps[0].typeKeys)
        assertEquals(AutopilotPlan.HARD_MAX_STEPS, plan.maxSteps)

        val bad = AutopilotPlan.fromToolInput(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"package_name":"com.msg","goal":"g","steps":[{"do":"write","type":"body"}]}""").jsonObject)
        assertTrue(bad.isFailure)
        assertTrue(bad.exceptionOrNull()!!.message!!.contains("body"))
    }

    @Test
    fun `a plan without steps uses the goal as its only sub-goal`() {
        val plan = AutopilotPlan.fromToolInput(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"package_name":"com.android.settings","goal":"Turn on dark mode"}""").jsonObject).getOrThrow()
        assertEquals(listOf("Turn on dark mode"), plan.steps.map { it.doText })
    }
}
