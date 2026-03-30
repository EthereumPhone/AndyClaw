package org.ethereumphone.andyclaw.sessions.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = true,
)
abstract class SessionDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        private const val DB_NAME = "andyclaw_sessions.db"

        /** v1 → v2: add context window tracking columns to sessions. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN lastContextUsed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN contextLimit INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: SessionDatabase? = null

        fun getInstance(context: Context): SessionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SessionDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
