package org.ethereumphone.andyclaw.llm.reflex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ReflexHeadsTest {
    private val dir = File("src/main/assets/reflex")
    private val labels = ReflexLabels.parse(File(dir, ReflexRuntime.LABELS).readText())

    @Test fun shippedAssetsMatchTheirPins() {
        for (name in listOf(ReflexRuntime.ENCODER, ReflexRuntime.HEADS, ReflexRuntime.LABELS)) {
            val sha = MessageDigest.getInstance("SHA-256").digest(File(dir, name).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(name, ReflexRuntime.PINNED[name], sha)
        }
    }

    @Test fun headsParseAndMatchTheLabels() {
        val heads = ReflexHeads.parse(File(dir, ReflexRuntime.HEADS).readBytes(), labels)
        assertEquals(384, heads.dim)
        val r = heads.apply(FloatArray(384) { 0.01f * (it % 7) })
        assertEquals(labels.tools.size, r.tools.size)
        assertEquals(labels.actions.size, r.actions.size)
        assertEquals(1f, r.tools.sum(), 1e-4f)
        assertEquals(1f, r.actions.sum(), 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedHeadsAreRejected() {
        val bytes = File(dir, ReflexRuntime.HEADS).readBytes()
        ReflexHeads.parse(bytes.copyOf(bytes.size - 4), labels)
    }

    private fun result(action: String, p: Float, tools: List<String>): ReflexHeads.Result {
        val a = FloatArray(labels.actions.size) { (1f - p) / (labels.actions.size - 1) }
        a[labels.actions.indexOf(action)] = p
        val t = FloatArray(labels.tools.size) { 0.001f }
        tools.forEachIndexed { i, name -> t[labels.tools.indexOf(name)] = 0.5f - i * 0.1f }
        return ReflexHeads.Result(t, a)
    }

    @Test fun gateNeedsConfidenceAndAgreement() {
        assertTrue(labels.decide(result("wifi_off", 0.99f, listOf("toggle_wifi"))).fired)
        // Not confident enough.
        assertFalse(labels.decide(result("wifi_off", 0.90f, listOf("toggle_wifi"))).fired)
        // The tool head disagrees: toggle_wifi is not in its top 3.
        assertFalse(labels.decide(result("wifi_off", 0.99f, listOf("send_sms", "read_sms", "launch_app"))).fired)
        // Switched off for this turn.
        assertFalse(labels.decide(result("wifi_off", 0.99f, listOf("toggle_wifi")), enabled = { it != "wifi_off" }).fired)
        // needs_agent never fires.
        assertFalse(labels.decide(result("needs_agent", 0.99f, listOf("toggle_wifi"))).fired)
    }

    @Test fun topToolsSkipNone() {
        val r = result("needs_agent", 0.99f, listOf("none", "send_sms", "read_sms"))
        assertEquals(listOf("send_sms", "read_sms"), labels.topTools(r, k = 2, minProb = 0.01f))
    }
}
