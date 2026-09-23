package org.ethereumphone.andyclaw.agent

import kotlinx.serialization.json.JsonObject
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessageContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PruneOldUiTreesTest {

    private val tree = "Tapped node.\n" + "[1] button \"Row\"\n".repeat(60)

    private fun turn(id: String, tool: String, content: String) = listOf(
        Message.assistant(listOf(ContentBlock.ToolUseBlock(id, tool, JsonObject(emptyMap())))),
        Message("user", MessageContent.Blocks(listOf(ContentBlock.ToolResult(id, content, isError = false)))),
    )

    private fun resultOf(messages: List<Message>, i: Int) =
        ((messages[i].content as MessageContent.Blocks).blocks.single() as ContentBlock.ToolResult).content

    @Test
    fun `only the newest display tree survives, as a stable one-line stub for the rest`() {
        val messages = (turn("a", "agent_display_create", tree) +
            turn("b", "agent_display_click_node", tree) +
            turn("c", "read_file", tree) +
            turn("d", "agent_display_click_node", tree)).toMutableList()

        AgentLoop.pruneOldUiTrees(messages)

        assertTrue(resultOf(messages, 1).startsWith("Tapped node."))
        assertTrue(resultOf(messages, 1).length < 300)
        assertTrue(resultOf(messages, 3).length < 300)
        assertEquals("non-display results are untouched", tree, resultOf(messages, 5))
        assertEquals("the newest screen stays whole", tree, resultOf(messages, 7))

        val once = messages.toList()
        AgentLoop.pruneOldUiTrees(messages)
        assertEquals("idempotent, so a prompt cache keeps hitting", once, messages)
    }

    @Test
    fun `autopilot results are compact already and never touched`() {
        val result = """{"status":"success","steps":6}""" + " ".repeat(500)
        val messages = (turn("a", "agent_display_autopilot", result) +
            turn("b", "agent_display_autopilot", result)).toMutableList()
        AgentLoop.pruneOldUiTrees(messages)
        assertEquals(result, resultOf(messages, 1))
    }
}
