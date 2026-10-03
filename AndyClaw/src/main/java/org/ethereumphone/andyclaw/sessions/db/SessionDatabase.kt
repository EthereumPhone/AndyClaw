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

        /** The schema this build writes; [build] leaves a newer one alone. */
        private const val SCHEMA_VERSION = 2

        fun getInstance(context: Context): SessionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Sessions written by a newer build, met after a rollback, are left as they are, the way
         * `LedgerDatabase` does it: Room has no migration down and would throw on first use, and
         * the chat and the session list with it. This build keeps its sessions in memory then, and
         * the roll-forward finds the file untouched.
         */
        private fun build(context: Context): SessionDatabase {
            val file = context.getDatabasePath(DB_NAME)
            val onDisk = if (file.exists()) {
                runCatching {
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    ).use { it.version }
                }.getOrDefault(0)
            } else 0
            if (onDisk > SCHEMA_VERSION) {
                android.util.Log.w("SessionDatabase", "sessions schema $onDisk is newer than $SCHEMA_VERSION; not opening it")
                return Room.inMemoryDatabaseBuilder(context, SessionDatabase::class.java).build()
            }
            return Room.databaseBuilder(context, SessionDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
