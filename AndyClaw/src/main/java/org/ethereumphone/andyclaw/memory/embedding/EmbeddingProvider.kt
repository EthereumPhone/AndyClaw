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
 *
 * @param statusCode The HTTP status of a refused request, when there was one.
 */
class EmbeddingException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : RuntimeException(message, cause) {
    /**
     * The request was refused for what it carried, not for who sent it or when: sending the
     * same texts again fails again, while other texts may go through.
     */
    val rejectsInput: Boolean get() = statusCode == 400 || statusCode == 413 || statusCode == 422
}
