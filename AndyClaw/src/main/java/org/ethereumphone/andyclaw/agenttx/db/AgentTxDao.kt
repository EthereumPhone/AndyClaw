package org.ethereumphone.andyclaw.agenttx.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.ethereumphone.andyclaw.agenttx.db.entity.AgentTxEntity

@Dao
interface AgentTxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: AgentTxEntity)

    @Query("SELECT * FROM agent_transactions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AgentTxEntity>>

    @Query("SELECT * FROM agent_transactions ORDER BY timestamp DESC")
    suspend fun getAll(): List<AgentTxEntity>

    @Query("DELETE FROM agent_transactions")
    suspend fun deleteAll()
}
