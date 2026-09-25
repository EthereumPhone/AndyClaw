package org.ethereumphone.andyclaw.autopilot

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A private app's screen never reaches Jev, the planner or the tool result. */
class SensitiveAppsTest {

    private val seed = "abandon ability able about above absent absorb abstract absurd abuse access accident"

    private val list = T.screen("com.msg", "Chats",
        T.button(2, "Bob", y = 200, type = "list_item"),
        T.button(3, "Anna", y = 280, type = "list_item"),
    )
    private val wallet = T.screen("org.ethereumphone.walletmanager", "Recovery phrase",
        T.text(1, seed),
        T.button(2, "Done", y = 650),
    )

    private val noPlanner = AutopilotPlanner { throw AssertionError("the planner must not see a private app") }

    @Test
    fun `refuses a plan for a private app without launching it`() = runTest {
        val device = FakeDevice(mapOf("wallet" to wallet), emptyMap(), start = "wallet")
        val jev = ScriptedJev { throw AssertionError("Jev must not be asked") }
        val plan = T.plan(PlanStep("Open the recovery phrase")).copy(packageName = "org.ethereumphone.walletmanager")

        val result = AutopilotExecutor(device, jev, noPlanner).run(plan)

        assertEquals(AutopilotResult.Status.FAILED, result.status)
        assertEquals("sensitive_app", result.reason)
        assertNull(device.launched)
        assertTrue(jev.requests.isEmpty())
    }

    @Test
    fun `stops the moment a private app comes up, before anything reads it`() = runTest {
        val device = FakeDevice(
            screens = mapOf("list" to list, "wallet" to wallet),
            transitions = mapOf("list|tap:Anna" to "wallet"),
            start = "list",
        )
        val jev = ScriptedJev { req ->
            mapOf(Questions.NEXT to T.choice(ScriptedJev.keyFor(req, Questions.NEXT, "Tap", "Anna")!!, 0.95))
        }
        val plan = T.plan(PlanStep("Open the conversation with Anna"), PlanStep("Send it"))

        val result = AutopilotExecutor(device, jev, noPlanner).run(plan)

        assertEquals(AutopilotResult.Status.FAILED, result.status)
        assertEquals("sensitive_app", result.reason)
        assertEquals(1, jev.requests.size)
        assertFalse(jev.requests.any { it.state.contains("abandon") })
        val toolResult = result.toToolResultJson()
        assertFalse(toolResult.contains("abandon"))
        assertFalse(toolResult.contains("Recovery phrase"))
        assertEquals("com.msg", result.finalScreen?.packageName)
    }

    @Test
    fun `finds a private app in any window of a raw tree`() {
        val tree = """{"screen":{"package":"com.msg","title":"Chats"},"elements":[],""" +
            """"windows":[{"package":"com.x8bit.bitwarden"}]}"""
        assertEquals("com.x8bit.bitwarden", SensitiveApps.sensitivePackageIn(tree))
        assertEquals("org.ethereumphone.walletmanager",
            SensitiveApps.sensitivePackageIn("""{"screen": {"package" : "org.ethereumphone.walletmanager"}}"""))
        assertNull(SensitiveApps.sensitivePackageIn("""{"screen":{"package":"com.android.settings"}}"""))
        assertNull(SensitiveApps.sensitivePackageIn(null))
        // A label that merely mentions the package is escaped inside a JSON string, so it is not a key.
        assertNull(SensitiveApps.sensitivePackageIn(
            """{"screen":{"package":"com.msg"},"elements":[{"label":"see \"package\":\"io.metamask\""}]}"""))
    }

    @Test
    fun `the refusal names the app and nothing on its screen`() {
        val text = SensitiveApps.refusal("org.ethereumphone.walletmanager")
        assertTrue(text.contains("org.ethereumphone.walletmanager"))
        assertTrue(text.contains("user"))
    }

    @Test
    fun `a private app under another window still counts`() {
        assertEquals("org.ethereumphone.walletmanager",
            SensitiveApps.sensitiveAmong(listOf("com.android.chrome", "org.ethereumphone.walletmanager")))
        assertEquals(null, SensitiveApps.sensitiveAmong(listOf("com.android.chrome", null)))
    }
}
