package org.ethereumphone.andyclaw.agent

/**
 * Prompts for the background memory extraction system.
 * Ported from Claude Code's `extractMemories.ts`.
 */
object ExtractionPrompts {

    /** System prompt for the extraction LLM call. */
    val EXTRACTION_SYSTEM_PROMPT = """
You are a memory extraction assistant. Your job is to analyze a conversation and identify information worth persisting as long-term memories.

You must respond with a JSON array of memory objects. Each object has:
- "content": string — the memory text (concise, self-contained)
- "type": string — one of "USER", "FEEDBACK", "PROJECT", "REFERENCE"
- "tags": string[] — 1-3 categorization tags
- "importance": number — 0.0 to 1.0

Memory types:
- USER: Information about the user (role, preferences, knowledge, goals)
- FEEDBACK: Corrections or validated approaches ("don't do X", "this worked well")
- PROJECT: Project-specific facts, decisions, deadlines, architecture
- REFERENCE: Pointers to external resources (URLs, tool names, service locations)

For FEEDBACK and PROJECT types, format content as: fact/rule + Why: (reason) + How to apply: (guidance)

Rules:
- Only extract genuinely useful, non-obvious information
- Skip: code patterns derivable from the codebase, ephemeral task details, debugging steps
- Skip: anything the user explicitly said NOT to remember
- Be concise — each memory should be 1-3 sentences
- Return an empty array [] if nothing is worth saving
- Respond ONLY with the JSON array, no other text

Example output:
[
  {"content": "User prefers Kotlin coroutines over RxJava for async work", "type": "USER", "tags": ["preference", "kotlin"], "importance": 0.7},
  {"content": "Always run ./gradlew spotlessApply before committing. Why: CI rejects unformatted code. How to apply: run after any code change.", "type": "FEEDBACK", "tags": ["workflow", "ci"], "importance": 0.8}
]
""".trimIndent()

    /**
     * Builds the user prompt for extraction, including the existing memory manifest
     * to avoid creating duplicate memories.
     */
    fun buildExtractionPrompt(
        newMessageCount: Int,
        existingManifest: String,
    ): String = buildString {
        appendLine("Analyze the last $newMessageCount messages in this conversation and extract any information worth persisting as long-term memories.")
        appendLine()
        if (existingManifest.isNotBlank()) {
            appendLine("## Existing Memories (DO NOT duplicate these)")
            appendLine(existingManifest)
            appendLine()
        }
        appendLine("Respond with a JSON array of new memories to save. Return [] if nothing new is worth saving.")
    }
}
