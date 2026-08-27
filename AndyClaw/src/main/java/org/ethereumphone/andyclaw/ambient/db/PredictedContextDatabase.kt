package org.ethereumphone.andyclaw.ambient.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.ethereumphone.andyclaw.ambient.db.entity.PredictedContextEntity

/**
 * The predicted-context store.
 *
 * Separate from the ledger on purpose: one is a permanent record of what the agent did and
 * the other is a cache of what is about to happen, rebuilt from mail and calendar on every
 * ingest. Sharing a database would tie the ledger's schema — which must never be
 * destructively migrated — to a table whose contents are derived and disposable.
 */
@Database(
    entities = [PredictedContextEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PredictedContextDatabase : RoomDatabase() {

    abstract fun predictedContextDao(): PredictedContextDao

    companion object {
        private const val DB_NAME = "andyclaw_predicted_context.db"

        @Volatile
        private var INSTANCE: PredictedContextDatabase? = null

        fun getInstance(context: Context): PredictedContextDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PredictedContextDatabase::class.java,
                    DB_NAME,
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
