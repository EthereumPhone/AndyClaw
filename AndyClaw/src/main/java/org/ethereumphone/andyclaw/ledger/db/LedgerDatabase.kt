package org.ethereumphone.andyclaw.ledger.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity

/**
 * The ledger's database, patterned on `agenttx/db/AgentTxDatabase.kt` — a separate file
 * per concern, as every other store in this app does it, so a schema change to one cannot
 * migrate another.
 *
 * No `fallbackToDestructiveMigration`. Every other store here can afford to be rebuilt
 * from scratch on a schema change; a tamper-evident record the user is invited to export
 * cannot, and silently dropping it on an OTA is exactly the §0.2 failure mode. A future
 * schema change ships a real migration.
 */
@Database(
    entities = [LedgerEntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun ledgerDao(): LedgerDao

    companion object {
        private const val DB_NAME = "andyclaw_ledger.db"

        @Volatile
        private var INSTANCE: LedgerDatabase? = null

        fun getInstance(context: Context): LedgerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    DB_NAME,
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
