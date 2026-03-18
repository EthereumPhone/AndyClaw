package org.ethereumphone.andyclaw.agenttx

import android.content.Context
import kotlinx.coroutines.flow.Flow
import org.ethereumphone.andyclaw.agenttx.db.AgentTxDatabase
import org.ethereumphone.andyclaw.agenttx.db.entity.AgentTxEntity
import java.util.UUID

class AgentTxRepository(context: Context) {

    private val dao = AgentTxDatabase.getInstance(context).agentTxDao()

    fun observeAll(): Flow<List<AgentTxEntity>> = dao.observeAll()

    suspend fun getAll(): List<AgentTxEntity> = dao.getAll()

    suspend fun save(
        userOpHash: String,
        chainId: Int,
        to: String,
        amount: String,
        token: String,
        toolName: String,
    ) {
        dao.insert(
            AgentTxEntity(
                id = UUID.randomUUID().toString(),
                userOpHash = userOpHash,
                chainId = chainId,
                to = to,
                amount = amount,
                token = token,
                toolName = toolName,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    suspend fun clearAll() {
        dao.deleteAll()
    }
}
