package org.ethereumphone.andyclaw.services

import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.sessions.SessionManager
import org.ethereumphone.andyclaw.sessions.model.MessageRole
import org.ethereumphone.andyclaw.sessions.model.SessionMessage
import java.util.concurrent.ConcurrentHashMap

/**
 * The launcher's conversations, keyed by the id the launcher gave them (IPC-10, CHAT-07).
 *
 * The launcher names a conversation with its own UUID and assumes AndyClaw keeps it under that
 * name. AndyClaw kept two things for it, both only in memory: the model's context, and a map to a
 * Room session created with a *new* id. After a restart the context came back empty (the model
 * forgot everything the chat still showed) and a second session started; and because the session
 * list named the Room id, the launcher could not recognise, open or delete the conversation it was
 * in. Now the Room session **is** the launcher's id — created with it on first use, looked up
 * first so a restart finds it again — and the context is rebuilt from what was stored whenever
 * memory has none. The ledger already keys the conversation's runs by the same id.
 *
 * On-disk that is only rows with a caller-chosen id in the existing sessions table: no schema
 * change, and an older build reads them like any other session.
 */
internal class LauncherConversations(private val store: Store) {

    /** What this needs of the session store; [SessionManager] in the app, a map in tests. */
    interface Store {
        suspend fun getOrCreateSession(sessionId: String, model: String?, title: String)
        suspend fun getMessages(sessionId: String): List<SessionMessage>
        suspend fun addMessage(sessionId: String, role: MessageRole, content: String)
        suspend fun deleteSession(sessionId: String)
    }

    /** The model's context per launcher session, in memory. */
    private val histories = ConcurrentHashMap<String, MutableList<Message>>()

    /** Launcher session → the Room session its turns are saved to; the same id since IPC-10. */
    private val dbSessionIds = ConcurrentHashMap<String, String>()

    /**
     * The context for [sessionId]'s next turn: what memory has, or else what was stored — after a
     * restart, or for a conversation the launcher reopened without asking to resume it.
     */
    suspend fun history(sessionId: String): MutableList<Message> {
        histories[sessionId]?.let { return it }
        val rebuilt = historyFrom(store.getMessages(dbSessionIds[sessionId] ?: sessionId))
        return histories.putIfAbsent(sessionId, rebuilt) ?: rebuilt
    }

    /** The Room session [sessionId]'s turns are saved to, created with that id if it is new. */
    suspend fun dbSessionId(sessionId: String, model: String?, title: String): String {
        dbSessionIds[sessionId]?.let { return it }
        store.getOrCreateSession(sessionId, model, title)
        return dbSessionIds.putIfAbsent(sessionId, sessionId) ?: sessionId
    }

    /** A finished turn: into the context for the next one, and into the stored conversation. */
    suspend fun recordTurn(sessionId: String, dbSessionId: String, prompt: String, reply: String) {
        val history = history(sessionId)
        history.add(Message.user(prompt))
        if (reply.isNotEmpty()) history.add(Message.assistant(listOf(ContentBlock.TextBlock(reply))))
        store.addMessage(dbSessionId, MessageRole.USER, prompt)
        if (reply.isNotEmpty()) store.addMessage(dbSessionId, MessageRole.ASSISTANT, reply)
    }

    /** `resumeSession`: [sessionId]'s stored conversation becomes the context, whatever memory had. */
    suspend fun resume(sessionId: String): Int {
        val history = historyFrom(store.getMessages(sessionId))
        histories[sessionId] = history
        dbSessionIds[sessionId] = sessionId
        return history.size
    }

    /** `clearSession`: forget the context in memory. The stored conversation stays in the list. */
    fun clear(sessionId: String) {
        histories.remove(sessionId)
        dbSessionIds.remove(sessionId)
    }

    /**
     * `deleteSession`: the stored conversation, and everything in memory for it — under its own id
     * and under any launcher id still mapped to it from before sessions shared the launcher's id.
     */
    suspend fun delete(sessionId: String) {
        try {
            store.deleteSession(sessionId)
        } finally {
            clear(sessionId)
            for (launcherId in dbSessionIds.filterValues { it == sessionId }.keys) clear(launcherId)
        }
    }

    companion object {
        /** The model's context from a stored conversation: what was said, not tools or summaries. */
        fun historyFrom(messages: List<SessionMessage>): MutableList<Message> {
            val history = mutableListOf<Message>()
            for (m in messages) {
                when (m.role) {
                    MessageRole.USER -> history.add(Message.user(m.content))
                    MessageRole.ASSISTANT -> history.add(Message.assistant(listOf(ContentBlock.TextBlock(m.content))))
                    else -> {} // skip system/tool for agent loop reconstruction
                }
            }
            return history
        }

        /** The store backed by the app's Room sessions. */
        fun storeOf(sessions: SessionManager) = object : Store {
            override suspend fun getOrCreateSession(sessionId: String, model: String?, title: String) {
                sessions.getOrCreateSession(sessionId, model, title)
            }
            override suspend fun getMessages(sessionId: String) = sessions.getMessages(sessionId)
            override suspend fun addMessage(sessionId: String, role: MessageRole, content: String) {
                sessions.addMessage(sessionId, role, content)
            }
            override suspend fun deleteSession(sessionId: String) = sessions.deleteSession(sessionId)
        }
    }
}
