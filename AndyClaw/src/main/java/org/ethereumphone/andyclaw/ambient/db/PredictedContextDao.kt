package org.ethereumphone.andyclaw.ambient.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.ethereumphone.andyclaw.ambient.db.entity.PredictedContextEntity

@Dao
interface PredictedContextDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: PredictedContextEntity)

    @Update
    suspend fun update(entry: PredictedContextEntity)

    @Query("SELECT * FROM predicted_context WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun findBySourceKey(sourceKey: String): PredictedContextEntity?

    @Query("SELECT * FROM predicted_context WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PredictedContextEntity?

    /**
     * Everything that could plausibly be relevant between [from] and [to].
     *
     * A window query rather than a scored one: relevance is a per-kind curve
     * ([org.ethereumphone.andyclaw.ambient.PredictedContextScorer]) and expressing it in
     * SQL would put the ranking in two places, only one of which has tests. SQL narrows,
     * Kotlin ranks.
     */
    @Query(
        """
        SELECT * FROM predicted_context
        WHERE (startMs BETWEEN :from AND :to)
           OR (endMs IS NOT NULL AND endMs BETWEEN :from AND :to)
           OR (startMs <= :from AND endMs IS NOT NULL AND endMs >= :to)
        ORDER BY startMs ASC
        """
    )
    suspend fun inWindow(from: Long, to: Long): List<PredictedContextEntity>

    @Query("SELECT * FROM predicted_context ORDER BY startMs ASC")
    suspend fun getAll(): List<PredictedContextEntity>

    @Query("SELECT * FROM predicted_context ORDER BY startMs ASC")
    fun observeAll(): Flow<List<PredictedContextEntity>>

    @Query("UPDATE predicted_context SET dismissedMs = :atMs WHERE id = :id")
    suspend fun dismiss(id: String, atMs: Long)

    /** Retention: a thing that is over and whose tail has run out is not context any more. */
    @Query("DELETE FROM predicted_context WHERE COALESCE(endMs, startMs) < :beforeMs")
    suspend fun deleteEndedBefore(beforeMs: Long)

    @Query("DELETE FROM predicted_context")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM predicted_context")
    suspend fun count(): Int
}
