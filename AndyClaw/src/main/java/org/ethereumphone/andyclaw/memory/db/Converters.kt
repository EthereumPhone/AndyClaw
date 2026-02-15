package org.ethereumphone.andyclaw.memory.db

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Room type converters for the memory database.
 */
object Converters {

    // ── FloatArray ↔ ByteArray (for embedding vectors) ──────────────

    /**
     * Packs a [FloatArray] into a little-endian [ByteArray].
     * Each float occupies 4 bytes.
     */
    @TypeConverter
    @JvmStatic
    fun fromFloatArray(value: FloatArray?): ByteArray? {
        if (value == null) return null
        val buffer = ByteBuffer.allocate(value.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (f in value) buffer.putFloat(f)
        return buffer.array()
    }

    /**
     * Unpacks a little-endian [ByteArray] back into a [FloatArray].
     */
    @TypeConverter
    @JvmStatic
    fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
    }
}
