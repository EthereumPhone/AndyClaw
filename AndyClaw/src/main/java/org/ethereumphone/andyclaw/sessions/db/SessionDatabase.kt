package org.ethereumphone.andyclaw.sessions.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.ethereumphone.andyclaw.sessions.db.entity.SessionEntity
import org.ethereumphone.andyclaw.sessions.db.entity.SessionMessageEntity

/**
 * Room database for the session subsystem.
 *
 * Separate from any app-level database so the session layer
 * can evolve its schema independently (same pattern as [MemoryDatabase]).
 *
 * File location: `<app-data>/databases/andyclaw_sessions.db`
 */
@Database(
    entities = [SessionEntity::class, SessionMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SessionDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        private const val DB_NAME = "andyclaw_sessions.db"

        @Volatile
        private var INSTANCE: SessionDatabase? = null

        fun getInstance(context: Context): SessionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SessionDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
