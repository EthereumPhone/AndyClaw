package org.ethereumphone.andyclaw.memory.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 1,
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

        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    DB_NAME,
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
