package org.ethereumphone.andyclaw.flows

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The canonical bytes and content address of a flow, pinned.
 *
 * A flow's filename is the sha256 of its canonical JSON, and [FlowStore] refuses any file whose
 * bytes do not hash to its name. So a change to field order, a default, or `explicitNulls`
 * silently orphans every flow already on the devices — they stop loading and nothing says why.
 * If one of these fails, the IR's serialised form changed: that needs a migration, not a new
 * golden value.
 */
class FlowIrGoldenTest {

    private val designDocFlow = Flow(
        flow = "signal.send_to_existing_thread",
        version = 3,
        app = "org.thoughtcrime.securesms",
        appVersionRange = ">=7.2,<8.0",
        params = listOf("contact_name", "body"),
        preconditions = listOf(NodeExists(viewId = "conversation_list")),
        steps = listOf(
            TapStep(viewId = "search_button"),
            TypeStep(target = Selector(viewId = "search_input"), value = "{{contact_name}}"),
            WaitForStep(viewId = "search_result_item", timeoutMs = 1500),
            TapStep(viewId = "search_result_item", index = 0),
            AssertStep(viewId = "toolbar_title", nodeTextContains = "{{contact_name}}"),
            TypeStep(target = Selector(viewId = "compose_text"), value = "{{body}}"),
            CheckpointStep("send"),
            TapStep(viewId = "send_button", expectChecksum = "0123456789abcdef"),
        ),
        postconditions = listOf(NodeExists(viewId = "conversation_item_sent")),
    )

    private val withIntent = designDocFlow.copy(
        intent = FlowIntent(
            goal = "Send {{body}} to {{contact_name}}",
            steps = listOf(
                FlowIntentStep("Open the conversation with {{contact_name}}", "the thread is open"),
                FlowIntentStep("Send {{body}}", type = listOf("body")),
            ),
        ),
    )

    @Test
    fun `canonical bytes of a flow without an intent are pinned`() {
        assertEquals(GOLDEN_JSON, FlowCodec.canonicalJson(designDocFlow))
        assertEquals(GOLDEN_HASH, FlowCodec.contentHash(designDocFlow))
    }

    @Test
    fun `canonical bytes of a flow with an intent are pinned`() {
        assertEquals(GOLDEN_INTENT_HASH, FlowCodec.contentHash(withIntent))
    }

    private companion object {
        const val GOLDEN_JSON = """{"flow":"signal.send_to_existing_thread","version":3,"app":"org.thoughtcrime.securesms","app_version_range":">=7.2,<8.0","params":["contact_name","body"],"preconditions":[{"node_exists":{"view_id":"conversation_list"}}],"steps":[{"tap":{"view_id":"search_button"}},{"type":{"target":{"view_id":"search_input"},"value":"{{contact_name}}"}},{"wait_for":{"view_id":"search_result_item","timeout_ms":1500}},{"tap":{"view_id":"search_result_item","index":0}},{"assert":{"view_id":"toolbar_title","node_text_contains":"{{contact_name}}"}},{"type":{"target":{"view_id":"compose_text"},"value":"{{body}}"}},{"checkpoint":"send"},{"tap":{"view_id":"send_button","expect_checksum":"0123456789abcdef"}}],"postconditions":[{"node_exists":{"view_id":"conversation_item_sent"}}]}"""
        const val GOLDEN_HASH = "386a60a87787b4436dcf68af27591863f1c614d3d1e7ff72760ea03cfefd3d03"
        const val GOLDEN_INTENT_HASH = "bc15a0d0adc124c7ccdd3cb4d3943bfb0053be272afbcaa6cc05cf3e0ab3ee74"
    }
}
