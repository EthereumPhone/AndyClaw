package org.ethereumphone.andyclaw.flows

import org.ethereumphone.andyclaw.ExecutionEngine.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowFirstTest {

    private fun stored(version: Int = 1, stale: Boolean = false, params: List<String> = listOf("body"), steps: List<FlowStep>? = null) =
        StoredFlow(
            hash = "h$version",
            flow = Flow(
                flow = "messenger.send_body_to_anna", version = version, app = "org.msg", appVersionRange = "*",
                params = params,
                steps = steps ?: listOf(TapStep(viewId = "row"), CheckpointStep("send"), TapStep(viewId = "send_button")),
            ),
            meta = FlowMeta(stale = stale),
        )

    private fun select(
        flows: List<StoredFlow>,
        values: Map<String, String> = mapOf("body" to "hi"),
        noConfirm: Boolean = true,
        provenance: Provenance = Provenance.USER,
    ) = FlowFirst.select("messenger.send_body_to_anna", values, flows, noConfirm, provenance)

    @Test
    fun `the same task with its values replays the newest flow`() {
        assertEquals(2, select(listOf(stored(1), stored(2)))?.flow?.version)
    }

    @Test
    fun `a stale flow or a missing value leaves it to the autopilot`() {
        assertNull(select(listOf(stored(stale = true))))
        assertNull(select(listOf(stored()), values = emptyMap()))
        assertNull(select(listOf(stored(params = listOf("body", "subject")))))
    }

    @Test
    fun `a flow that needs its approval card is not replayed from inside the autopilot`() {
        assertNull("confirmations are on", select(listOf(stored()), noConfirm = false))
        assertNull("a message's request", select(listOf(stored()), provenance = Provenance.UNTRUSTED))
    }

    @Test
    fun `a flow that only reads needs no card`() {
        val readOnly = stored(steps = listOf(WaitForStep(viewId = "x")), params = emptyList())
        assertEquals(readOnly, select(listOf(readOnly), noConfirm = false))
    }

    @Test
    fun `another task's flow is never taken`() {
        assertNull(FlowFirst.select("messenger.other", mapOf("body" to "hi"), listOf(stored()), true, Provenance.USER))
    }
}
