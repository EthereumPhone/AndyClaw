package org.ethereumphone.andyclaw.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ChatRepository(private val dao: ChatDao) {

    fun getAllSessions(): Flow<List<ChatSessionEntity>> = dao.getAllSessions()

    suspend fun getSession(sessionId: String): ChatSessionEntity? = dao.getSession(sessionId)

    fun getMessages(sessionId: String): Flow<List<ChatMessageEntity>> = dao.getMessages(sessionId)

    suspend fun getMessagesList(sessionId: String): List<ChatMessageEntity> = dao.getMessagesList(sessionId)

    suspend fun createSession(model: String, title: String = "New Chat"): ChatSessionEntity {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
            model = model,
        )
        dao.insertSession(session)
        return session
    }

    suspend fun addMessage(
        sessionId: String,
        role: String,
        content: String,
        toolName: String? = null,
        toolCallId: String? = null,
    ): ChatMessageEntity {
        val orderIndex = dao.getMessageCount(sessionId)
        val message = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            content = content,
            toolName = toolName,
            toolCallId = toolCallId,
            timestamp = System.currentTimeMillis(),
            orderIndex = orderIndex,
        )
        dao.insertMessage(message)
        dao.updateSessionTimestamp(sessionId, System.currentTimeMillis())
        return message
    }

    suspend fun deleteSession(sessionId: String) = dao.deleteSession(sessionId)

    suspend fun updateSessionTitle(sessionId: String, title: String) {
        dao.updateSessionTitle(sessionId, title, System.currentTimeMillis())
    }
}
