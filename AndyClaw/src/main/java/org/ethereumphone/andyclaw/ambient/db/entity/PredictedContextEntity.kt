package org.ethereumphone.andyclaw.ambient.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A predicted context on disk.
 *
 * [sourceKey] is unique, and that is the whole deduplication story: an airline sends a
 * booking confirmation, a schedule change and a check-in reminder for one flight, and all
 * three parse to the same key, so the third one updates the row the first one created
 * rather than stacking three boarding passes on the lockscreen.
 */
@Entity(
    tableName = "predicted_context",
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index("startMs"),
    ],
)
data class PredictedContextEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val subtitle: String?,
    val startMs: Long,
    val endMs: Long?,
    val location: String?,
    val payloadJson: String,
    val provenance: String,
    val source: String,
    val sourceKey: String,
    val createdMs: Long,
    val updatedMs: Long,
    val dismissedMs: Long?,
)
