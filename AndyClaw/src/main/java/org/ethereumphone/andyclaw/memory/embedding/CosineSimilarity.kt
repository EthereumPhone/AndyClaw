package org.ethereumphone.andyclaw.memory.embedding

import kotlin.math.sqrt

/**
 * Vector math utilities for embedding-based search.
 */
object CosineSimilarity {

    /**
     * Computes cosine similarity between two vectors.
     *
     * @return A value in [-1, 1] where 1 = identical direction,
     *         0 = orthogonal, -1 = opposite.
     *         Returns 0 if either vector has zero magnitude.
     */
    fun compute(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) {
            "Dimension mismatch: ${a.size} vs ${b.size}"
        }

        var dot = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    /**
     * Normalises a cosine similarity value from [-1, 1] to [0, 1].
     */
    fun normalise(similarity: Float): Float = (similarity + 1f) / 2f
}
