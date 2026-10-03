package org.ethereumphone.andyclaw.memory

import org.ethereumphone.andyclaw.memory.embedding.EmbeddingException
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingProvider

/**
 * [delegate], while [allowed] says so — asked on every call, so a change of model takes effect
 * at once. Refused, it throws like an embedder that is down, and memory falls back to keyword
 * search, the way it does without one.
 *
 * The embedder is a remote service. A user who chose a model that keeps their words on the phone
 * or inside an enclave must not have every memory and every first message of a chat sent to it.
 */
class GatedEmbeddingProvider(
    private val delegate: EmbeddingProvider,
    private val allowed: () -> Boolean,
) : EmbeddingProvider {

    override val modelName: String get() = delegate.modelName

    override val dimensions: Int get() = delegate.dimensions

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (!allowed()) throw EmbeddingException("Embeddings are off for the selected model")
        return delegate.embed(texts)
    }
}
