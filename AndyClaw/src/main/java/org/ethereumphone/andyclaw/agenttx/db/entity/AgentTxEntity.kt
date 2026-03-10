package org.ethereumphone.andyclaw.agenttx.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_transactions",
    indices = [Index("timestamp")],
)
data class AgentTxEntity(
    @PrimaryKey val id: String,
    val userOpHash: String,
    val chainId: Int,
    val to: String,
    val amount: String,
    val token: String,
    val toolName: String,
    val timestamp: Long,
)
