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

        /** The schema this build writes. Frozen: new data goes in a sibling database. */
        private const val SCHEMA_VERSION = 1

        fun getInstance(context: Context): LedgerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        /**
         * A ledger written by a newer build — this one reached by a rollback — is left exactly
         * as it is: Room would refuse to open it and throw on every write, and the only
         * "migration" it offers is dropping the chain. This build records into memory instead,
         * and the next roll-forward finds the chain intact.
         */
        private fun build(context: Context): LedgerDatabase {
            val file = context.getDatabasePath(DB_NAME)
            val onDisk = if (file.exists()) {
                runCatching {
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    ).use { it.version }
                }.getOrDefault(0)
            } else 0
            if (onDisk > SCHEMA_VERSION) {
                android.util.Log.w("LedgerDatabase", "ledger schema $onDisk is newer than $SCHEMA_VERSION; not opening it")
                return Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
            }
            return Room.databaseBuilder(context, LedgerDatabase::class.java, DB_NAME).build()
        }
    }
}
