package org.ethereumphone.andyclaw.services

import kotlinx.coroutines.runBlocking
import org.ethereumphone.andyclaw.agent.AgentLoop
import org.ethereumphone.andyclaw.agent.RecordingCallbacks
import org.ethereumphone.andyclaw.agent.ScriptedLlmClient
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.MessageContent
import org.ethereumphone.andyclaw.sessions.model.MessageRole
import org.ethereumphone.andyclaw.sessions.model.SessionMessage
import org.ethereumphone.andyclaw.skills.NativeSkillRegistry
import org.ethereumphone.andyclaw.skills.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** IPC-10 / CHAT-07: a home-screen conversation has one name, and it survives AndyClaw restarting. */
class LauncherConversationsTest {

    /** The Room sessions, as a map; survives a "restart" (a new LauncherConversations). */
    private class FakeStore : LauncherConversations.Store {
        val sessions = LinkedHashMap<String, MutableList<SessionMessage>>()
        var creates = 0

        override suspend fun getOrCreateSession(sessionId: String, model: String?, title: String) {
            if (sessionId !in sessions) { sessions[sessionId] = mutableListOf(); creates++ }
        }
        override suspend fun getMessages(sessionId: String): List<SessionMessage> = sessions[sessionId].orEmpty().toList()
        override suspend fun addMessage(sessionId: String, role: MessageRole, content: String) {
            val list = sessions[sessionId] ?: error("no session $sessionId")
            list += SessionMessage("m${list.size}", sessionId, role, content, timestamp = list.size.toLong(), orderIndex = list.size)
        }
        override suspend fun deleteSession(sessionId: String) { sessions.remove(sessionId) }
    }

    /** One launcher turn, the way LauncherBindingService.runAgentLoop runs it. */
    private suspend fun turn(conversations: LauncherConversations, sessionId: String, prompt: String, client: ScriptedLlmClient) {
        val history = conversations.history(sessionId)
        val db = conversations.dbSessionId(sessionId, "m", prompt.take(50))
        val callbacks = RecordingCallbacks()
        AgentLoop(client, NativeSkillRegistry(), Tier.OPEN, enabledSkillIds = emptySet()).run(prompt, history, callbacks)
        conversations.recordTurn(sessionId, db, prompt, callbacks.completed.orEmpty())
    }

    private fun textsSentTo(client: ScriptedLlmClient, call: Int): List<String> =
        client.requests[call].messages.map { m ->
            when (val c = m.content) {
                is MessageContent.Text -> c.value
                is MessageContent.Blocks -> c.blocks.filterIsInstance<ContentBlock.TextBlock>().joinToString("") { it.text }
            }
        }

    @Test
    fun `a conversation keeps its history across an AndyClaw restart, in one session`() = runBlocking {
        val store = FakeStore()
        val client = ScriptedLlmClient(listOf(
            listOf(ContentBlock.TextBlock("Noted: 4711")),
            listOf(ContentBlock.TextBlock("4711")),
        ))
        turn(LauncherConversations(store), "L1", "remember the number 4711", client)

        // AndyClaw's process dies; the launcher keeps its chat and its id.
        turn(LauncherConversations(store), "L1", "what number did I tell you?", client)

        val second = textsSentTo(client, 1)
        assertTrue("the first exchange must reach the model: $second", "remember the number 4711" in second)
        assertTrue("its answer too: $second", "Noted: 4711" in second)
        assertEquals(listOf("L1"), store.sessions.keys.toList())
        assertEquals(1, store.creates)
        assertEquals(4, store.sessions.getValue("L1").size)
    }

    @Test
    fun `the Room session of a home-screen conversation is the launcher's own id`() = runBlocking {
        val store = FakeStore()
        val conversations = LauncherConversations(store)
        assertEquals("L1", conversations.dbSessionId("L1", "m", "hello"))
        assertEquals(setOf("L1"), store.sessions.keys)
    }

    @Test
    fun `resumeSession works for a conversation started on the home screen`() = runBlocking {
        val store = FakeStore()
        val client = ScriptedLlmClient(listOf(listOf(ContentBlock.TextBlock("hi there"))))
        turn(LauncherConversations(store), "L1", "hello", client)

        val restarted = LauncherConversations(store)
        assertEquals(2, restarted.resume("L1"))
        assertEquals(2, restarted.history("L1").size)
    }

    @Test
    fun `deleting a conversation forgets it everywhere, and the next prompt starts clean`() = runBlocking {
        val store = FakeStore()
        val conversations = LauncherConversations(store)
        val client = ScriptedLlmClient(listOf(listOf(ContentBlock.TextBlock("a")), listOf(ContentBlock.TextBlock("b"))))
        turn(conversations, "L1", "first", client)

        conversations.delete("L1")
        assertTrue(store.sessions.isEmpty())
        assertTrue(conversations.history("L1").isEmpty())

        turn(conversations, "L1", "second", client)
        assertEquals(listOf("second"), textsSentTo(client, 1).filter { it == "first" || it == "second" })
        assertEquals(2, store.sessions.getValue("L1").size)
    }

    @Test
    fun `clearing forgets memory only, and the stored conversation comes back if the id is reused`() = runBlocking {
        val store = FakeStore()
        val conversations = LauncherConversations(store)
        turn(conversations, "L1", "first", ScriptedLlmClient(listOf(listOf(ContentBlock.TextBlock("a")))))
        conversations.clear("L1")
        assertEquals(2, conversations.history("L1").size)
        assertEquals(1, store.sessions.size)
    }

    @Test
    fun `a stored conversation becomes context without tools or summaries`() {
        fun m(role: MessageRole, text: String) = SessionMessage(text, "s", role, text, timestamp = 0, orderIndex = 0)
        val history = LauncherConversations.historyFrom(listOf(
            m(MessageRole.CONTEXT_SUMMARY, "summary"),
            m(MessageRole.USER, "q"),
            m(MessageRole.TOOL, "{}"),
            m(MessageRole.ASSISTANT, "a"),
            m(MessageRole.SYSTEM, "sys"),
        ))
        assertEquals(listOf("user", "assistant"), history.map { it.role })
    }
}
