package org.ethereumphone.andyclaw.agenttx.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.ethereumphone.andyclaw.agenttx.db.entity.AgentTxEntity

@Database(
    entities = [AgentTxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AgentTxDatabase : RoomDatabase() {

    abstract fun agentTxDao(): AgentTxDao

    companion object {
        private const val DB_NAME = "andyclaw_agent_tx.db"

        @Volatile
        private var INSTANCE: AgentTxDatabase? = null

        fun getInstance(context: Context): AgentTxDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentTxDatabase::class.java,
                    DB_NAME,
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
