package org.ethereumphone.andyclaw.llm.reflex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.ethereumphone.andyclaw.llm.reflex.ReflexShadow.AgentCall
import org.ethereumphone.andyclaw.llm.reflex.ReflexShadow.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReflexShadowTest {
    private fun o(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun instant(label: String, tool: String, input: String) =
        ReflexRouter.Decision.Instant(ReflexResolver.Call(tool, o(input)), label, 0.99f, listOf(tool), 10, 0)
    private fun abstain(label: String = "needs_agent") = ReflexRouter.Decision.Agent(emptyList(), label, 0.99f, 10, "needs_agent")

    @Test fun verdicts() {
        val alarm = instant("set_alarm", "set_alarm", """{"hour":6,"minutes":30}""")
        assertEquals(Verdict.AGREE, ReflexShadow.judge(alarm, listOf(AgentCall("set_alarm", o("""{"hour":6,"minutes":30,"label":"x"}"""), true))))
        assertEquals(Verdict.DISAGREE, ReflexShadow.judge(alarm, listOf(AgentCall("set_alarm", o("""{"hour":18,"minutes":30}"""), true))))
        // A call that failed did not happen.
        assertEquals(Verdict.DISAGREE, ReflexShadow.judge(alarm, listOf(AgentCall("set_alarm", o("""{"hour":6,"minutes":30}"""), false))))
        assertEquals(Verdict.DISAGREE, ReflexShadow.judge(alarm, emptyList()))
        assertEquals(Verdict.MISSED, ReflexShadow.judge(abstain(), listOf(AgentCall("toggle_wifi", o("""{"enabled":true}"""), true))))
        assertEquals(Verdict.CORRECT_ABSTAIN, ReflexShadow.judge(abstain(), listOf(AgentCall("send_sms", null, true))))
        assertEquals(Verdict.CORRECT_ABSTAIN, ReflexShadow.judge(abstain(), emptyList()))
    }

    @Test fun tolerances() {
        val rem = instant("create_reminder", "create_reminder", """{"time":1000000,"message":"x"}""")
        assertEquals(Verdict.AGREE, ReflexShadow.judge(rem, listOf(AgentCall("create_reminder", o("""{"time":1050000,"message":"y"}"""), true))))
        assertEquals(Verdict.DISAGREE, ReflexShadow.judge(rem, listOf(AgentCall("create_reminder", o("""{"time":1070000,"message":"x"}"""), true))))
        val vol = instant("set_volume", "set_volume", """{"stream":"music","level":6}""")
        assertEquals(Verdict.AGREE, ReflexShadow.judge(vol, listOf(AgentCall("set_volume", o("""{"stream":"music","level":7}"""), true))))
        assertEquals(Verdict.DISAGREE, ReflexShadow.judge(vol, listOf(AgentCall("set_volume", o("""{"stream":"ring","level":6}"""), true))))
        val dnd = instant("dnd_on", "set_dnd_mode", """{"enabled":true,"priority_only":false}""")
        assertEquals(Verdict.AGREE, ReflexShadow.judge(dnd, listOf(AgentCall("set_dnd_mode", o("""{"enabled":true}"""), true))))
        val dark = instant("dark_mode_on", "write_secure_setting", """{"name":"ui_night_mode","value":"2"}""")
        assertEquals(Verdict.DISAGREE, ReflexShadow.judge(dark, listOf(AgentCall("write_secure_setting", o("""{"name":"ui_night_mode","value":"1"}"""), true))))
        val price = instant("price_eth", "get_token_price", """{"token":"ETH","chain_id":1}""")
        assertEquals(Verdict.AGREE, ReflexShadow.judge(price, listOf(AgentCall("get_token_price", o("""{"token":"eth"}"""), true))))
    }

    @Test fun storeCountsAndKeepsOnlyRecentDisagreements() {
        val f = File(Files.createTempDirectory("shadow").toFile(), "reflex_shadow.json")
        val s = ReflexShadow(f)
        val wifi = instant("wifi_off", "toggle_wifi", """{"enabled":false}""")
        s.record("wifi off", wifi, listOf(AgentCall("toggle_wifi", o("""{"enabled":false}"""), true)))
        repeat(ReflexShadow.MAX_RECENT + 5) { s.record("wifi off $it", wifi, emptyList()) }
        s.record("hi", abstain(), emptyList())
        val again = ReflexShadow(f).stats()   // read back from disk
        assertEquals(1, again.byLabel["wifi_off"]!!.agree)
        assertEquals(ReflexShadow.MAX_RECENT + 5, again.byLabel["wifi_off"]!!.disagree)
        assertEquals(1, again.correctAbstain)
        assertEquals(ReflexShadow.MAX_RECENT, again.recent.size)
        assertEquals("wifi off ${ReflexShadow.MAX_RECENT + 4}", again.recent.first().text)
        s.clear()
        assertEquals(0, ReflexShadow(f).stats().fired)
    }
}
