package org.ethereumphone.andyclaw.memory.search

/**
 * Splits text into overlapping chunks for indexing.
 *
 * Mirrors OpenClaw's chunking strategy (default ≈ 400 tokens, 80 overlap)
 * but uses word boundaries for simplicity on-device.
 *
 * @property maxWords     Target maximum words per chunk (rough token proxy).
 * @property overlapWords Number of trailing words from one chunk that are
 *                        prepended to the next, preserving context across
 *                        chunk boundaries.
 */
class ChunkingEngine(
    private val maxWords: Int = DEFAULT_MAX_WORDS,
    private val overlapWords: Int = DEFAULT_OVERLAP_WORDS,
) {
    init {
        require(maxWords > 0) { "maxWords must be positive" }
        require(overlapWords in 0 until maxWords) {
            "overlapWords must be in [0, maxWords)"
        }
    }

    /**
     * A contiguous range of text extracted from a larger document.
     *
     * @property text        The chunk text.
     * @property startOffset Character index of the first char in the source.
     * @property endOffset   Character index past the last char in the source.
     */
    data class Chunk(
        val text: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    /**
     * Splits [content] into overlapping chunks.
     *
     * If the content is shorter than [maxWords] a single chunk covering
     * the whole text is returned.
     */
    fun chunk(content: String): List<Chunk> {
        if (content.isBlank()) return emptyList()

        // Build word-boundary index: list of (startChar, endChar) per word.
        val wordBounds = buildWordBounds(content)
        if (wordBounds.isEmpty()) return emptyList()

        // If short enough, single chunk
        if (wordBounds.size <= maxWords) {
            return listOf(
                Chunk(
                    text = content.trim(),
                    startOffset = 0,
                    endOffset = content.length,
                )
            )
        }

        val chunks = mutableListOf<Chunk>()
        var wordIdx = 0

        while (wordIdx < wordBounds.size) {
            val chunkEnd = (wordIdx + maxWords).coerceAtMost(wordBounds.size)
            val startChar = wordBounds[wordIdx].first
            val endChar = wordBounds[chunkEnd - 1].second

            chunks += Chunk(
                text = content.substring(startChar, endChar).trim(),
                startOffset = startChar,
                endOffset = endChar,
            )

            // Advance by (maxWords - overlap), ensuring progress
            val step = (maxWords - overlapWords).coerceAtLeast(1)
            wordIdx += step

            // Don't create a tiny trailing chunk
            if (wordIdx < wordBounds.size && wordBounds.size - wordIdx < overlapWords) {
                // Extend the last chunk to the end of content
                val lastStart = wordBounds[wordIdx].first.coerceAtMost(startChar)
                val lastEnd = wordBounds.last().second
                // Only add if this isn't a duplicate of the previous chunk
                if (chunks.last().endOffset < lastEnd) {
                    chunks += Chunk(
                        text = content.substring(chunks.last().startOffset, lastEnd).trim(),
                        startOffset = chunks.last().startOffset,
                        endOffset = lastEnd,
                    )
                    // Remove the shorter previous chunk since the new one extends it
                    chunks.removeAt(chunks.size - 2)
                }
                break
            }
        }

        return chunks
    }

    /**
     * Returns a list of (startCharIndex, endCharIndex) for every
     * whitespace-delimited word in [text].
     */
    private fun buildWordBounds(text: String): List<Pair<Int, Int>> {
        val bounds = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < text.length) {
            // Skip whitespace
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            // Consume word
            while (i < text.length && !text[i].isWhitespace()) i++
            bounds += start to i
        }
        return bounds
    }

    companion object {
        /**
         * ~400 tokens ≈ 300 words (using ≈1.33 tokens-per-word heuristic).
         * Slightly conservative to stay under context limits.
         */
        const val DEFAULT_MAX_WORDS = 300

        /**
         * ~80 tokens ≈ 60 words of overlap.
         */
        const val DEFAULT_OVERLAP_WORDS = 60
    }
}
