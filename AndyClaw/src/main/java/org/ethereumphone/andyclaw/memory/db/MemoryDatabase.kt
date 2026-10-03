package org.ethereumphone.andyclaw.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.ethereumphone.andyclaw.memory.db.entity.MemoryChunkEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryChunkFts
import org.ethereumphone.andyclaw.memory.db.entity.MemoryEntryEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryEntryTagCrossRef
import org.ethereumphone.andyclaw.memory.db.entity.MemoryMetaEntity
import org.ethereumphone.andyclaw.memory.db.entity.MemoryTagEntity

/**
 * Standalone Room database for the memory subsystem.
 *
 * Separate from the main [AndyClawDatabase] so the memory layer
 * can evolve its schema independently and be tested in isolation.
 *
 * File location: `<app-data>/databases/andyclaw_memory.db`
 */
@Database(
    entities = [
        MemoryEntryEntity::class,
        MemoryChunkEntity::class,
        MemoryChunkFts::class,
        MemoryTagEntity::class,
        MemoryEntryTagCrossRef::class,
        MemoryMetaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    // ── FTS maintenance ─────────────────────────────────────────────

    /**
     * Rebuilds the FTS4 index from the [memory_chunks] content table.
     *
     * Must be called after any batch insert/update/delete on chunks
     * because the external-content FTS4 table does not auto-sync.
     */
    fun rebuildFtsIndex() {
        openHelper.writableDatabase.execSQL(
            "INSERT INTO memory_chunks_fts(memory_chunks_fts) VALUES('rebuild')"
        )
    }

    companion object {
        private const val DB_NAME = "andyclaw_memory.db"

        /** v1 → v2: Add nullable `type` column for memory taxonomy. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_entries ADD COLUMN type TEXT DEFAULT NULL")
            }
        }

        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        /** The schema this build writes; [build] leaves a newer one alone. */
        private const val SCHEMA_VERSION = 2

        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * Memories written by a newer build, met after a rollback, are left as they are, the way
         * `LedgerDatabase` does it: Room has no migration down and would throw on first use. This
         * build keeps memories in memory then, and the roll-forward finds the file untouched.
         */
        private fun build(context: Context): MemoryDatabase {
            val file = context.getDatabasePath(DB_NAME)
            val onDisk = if (file.exists()) {
                runCatching {
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    ).use { it.version }
                }.getOrDefault(0)
            } else 0
            if (onDisk > SCHEMA_VERSION) {
                android.util.Log.w("MemoryDatabase", "memory schema $onDisk is newer than $SCHEMA_VERSION; not opening it")
                return Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java).build()
            }
            return Room.databaseBuilder(context, MemoryDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
