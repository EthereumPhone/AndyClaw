package org.ethereumphone.andyclaw.agent

import org.ethereumphone.andyclaw.memory.MemoryManager
import org.ethereumphone.andyclaw.memory.model.MemoryEntry
import org.ethereumphone.andyclaw.memory.model.MemoryType
import java.util.concurrent.TimeUnit

/**
 * Builds structured memory sections for the system prompt.
 * Ported from Claude Code's memory prompt system.
 *
 * Includes behavioral instructions (when/how to use memory) and
 * formats relevant memory search results with type and freshness.
 */
object MemoryPromptBuilder {

    /**
     * Builds the memory section for the system prompt.
     *
     * @param memoryManager The memory manager, or null if disabled.
     * @param relevantMemories Pre-formatted relevant memory results from
     *   [fetchMemoryContext] (auto-injected based on user's message).
     * @param hasMemoryTools Whether the model has memory_search/memory_store
     *   in its current tool set.
     */
    fun buildMemorySection(
        memoryManager: MemoryManager?,
        relevantMemories: String,
        hasMemoryTools: Boolean = true,
    ): String {
        if (memoryManager == null) return ""

        val sb = StringBuilder()

        // Behavioral instructions
        sb.append(if (hasMemoryTools) MEMORY_INSTRUCTIONS_WITH_TOOLS else MEMORY_INSTRUCTIONS_PASSIVE)
        sb.appendLine()

        // Relevant memories (auto-injected search results for this query)
        if (relevantMemories.isNotBlank()) {
            sb.appendLine("## Relevant Memories")
            sb.appendLine("These were automatically retrieved based on the user's message:")
            sb.appendLine()
            sb.append(relevantMemories)
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Formats a memory entry for display with type, age, and staleness caveat.
     */
    fun formatSearchResult(entry: MemoryEntry): String = buildString {
        val typeLabel = entry.type?.name ?: entry.source.name
        append("- [$typeLabel] ${entry.content.take(300)}")
        if (entry.tags.isNotEmpty()) {
            append(" [${entry.tags.joinToString(", ")}]")
        }
        val age = daysSince(entry.updatedAt)
        if (age > 7) {
            append(" ⚠️ ${age}d old — verify before acting on this")
        } else if (age > 0) {
            append(" (${age}d ago)")
        }
    }

    /** Days since a timestamp. */
    fun daysSince(epochMillis: Long): Int {
        val diff = System.currentTimeMillis() - epochMillis
        return TimeUnit.MILLISECONDS.toDays(diff).toInt().coerceAtLeast(0)
    }

    /**
     * Instructions when the model has memory tools (memory_search, memory_store).
     *
     * Key design:
     * - Tells the model that relevant memories are AUTO-INJECTED (prevents redundant searches)
     * - Only search when you need context the auto-injection didn't cover
     * - Clear guidance on when to store (prevents over-storing)
     */
    private val MEMORY_INSTRUCTIONS_WITH_TOOLS = """
## Long-Term Memory

You have a persistent memory system that spans across conversations.

### How It Works
- **On the first message of a conversation**, relevant memories are automatically retrieved and shown below. Check them first.
- **During the conversation**, memories are NOT auto-retrieved (the full chat history is already in context). Use `memory_search` if you need to recall something from a PREVIOUS conversation — like user preferences, past decisions, or project context the user references.
- **Use `memory_store`** to save information that will be useful in future conversations. Only save genuinely useful, non-obvious information.

### What to Store (by type)
- **USER**: User's role, preferences, knowledge, communication style.
  → Save when you learn who the user is or how they work.
- **FEEDBACK**: Corrections and validated approaches.
  → Save when the user corrects you or confirms a non-obvious approach.
  → Format: fact + **Why:** + **How to apply:**
- **PROJECT**: Project context, decisions, deadlines — things not derivable from the device.
  → Save when you learn about goals, constraints, or decisions.
  → Format: fact + **Why:** + **How to apply:**
- **REFERENCE**: Pointers to external resources (URLs, services, accounts).
  → Save when the user mentions where information lives.

### What NOT to Store
- Things you can look up (device info, app lists, file contents)
- Debugging steps or error messages (ephemeral)
- Raw conversation text — extract the key fact instead
- Information the user told you to forget

### Staleness
Memories older than a week may be outdated. Verify before acting on them.
""".trimIndent()

    /**
     * Passive instructions when memory tools aren't available
     * (e.g., sub-agents or restricted tool sets).
     */
    private val MEMORY_INSTRUCTIONS_PASSIVE = """
## Long-Term Memory
Relevant memories from past conversations are shown below (if any). These were automatically retrieved based on the user's message. Memories older than a week may be outdated.
""".trimIndent()
}
