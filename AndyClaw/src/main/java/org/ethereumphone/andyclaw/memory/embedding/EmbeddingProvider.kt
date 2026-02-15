package org.ethereumphone.andyclaw.memory.embedding

/**
 * Pluggable interface for generating text embedding vectors.
 *
 * Implementations might call OpenAI, Gemini, Voyage, or run
 * an on-device model (e.g. TF-Lite / ONNX).
 *
 * The memory subsystem works without an embedding provider
 * (falling back to keyword-only search), but quality improves
 * dramatically once semantic embeddings are available.
 */
interface EmbeddingProvider {

    /** Human-readable name of the underlying model (e.g. "text-embedding-3-small"). */
    val modelName: String

    /** Dimensionality of the vectors this provider produces. */
    val dimensions: Int

    /**
     * Generate embeddings for one or more texts.
     *
     * @param texts  Non-empty list of input strings.
     * @return       List of float vectors, same size and order as [texts].
     * @throws EmbeddingException on provider errors.
     */
    suspend fun embed(texts: List<String>): List<FloatArray>

    /**
     * Convenience overload for a single text.
     */
    suspend fun embed(text: String): FloatArray = embed(listOf(text)).first()
}

/**
 * Thrown when an [EmbeddingProvider] cannot produce vectors.
 */
class EmbeddingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
