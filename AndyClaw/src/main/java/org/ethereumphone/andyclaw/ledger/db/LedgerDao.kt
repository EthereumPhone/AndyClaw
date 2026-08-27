package org.ethereumphone.andyclaw.ledger.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.ethereumphone.andyclaw.ledger.db.entity.LedgerEntryEntity

/**
 * The ledger's only door.
 *
 * There is an insert and there are queries. There is **no update**, and the insert aborts
 * on conflict rather than replacing, so a row cannot be silently rewritten by re-inserting
 * its id. [deleteThroughSeq] exists for retention alone and takes a prefix, never a
 * middle: dropping the oldest rows leaves a chain that still verifies from where it
 * starts, while removing a row from the middle would break every link after it — which is
 * the property the store exists to have.
 */
@Dao
interface LedgerDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: LedgerEntryEntity)

    @Query("SELECT * FROM ledger_entries ORDER BY seq ASC")
    suspend fun getAll(): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries ORDER BY seq DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries ORDER BY seq DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_entries WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun getBySession(sessionId: String): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries ORDER BY seq DESC LIMIT 1")
    suspend fun getLast(): LedgerEntryEntity?

    @Query("SELECT COUNT(*) FROM ledger_entries")
    suspend fun count(): Int

    /** Retention only, and only ever a prefix — see the class comment. */
    @Query("DELETE FROM ledger_entries WHERE seq <= :seq")
    suspend fun deleteThroughSeq(seq: Long)
}
