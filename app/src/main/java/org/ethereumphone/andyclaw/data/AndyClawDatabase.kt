package org.ethereumphone.andyclaw.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AndyClawDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: AndyClawDatabase? = null

        fun getInstance(context: Context): AndyClawDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AndyClawDatabase::class.java,
                    "andyclaw.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
