package org.ethereumphone.andyclaw.skills

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.ln

/**
 * Client-side tool search service that replaces [SmartRouter]'s routing logic.
 *
 * Instead of pre-filtering tools before the LLM call, this service exposes a
 * `search_available_tools` tool that the model can call on demand to discover
 * tools it needs. Discovered tools persist across conversation turns, naturally
 * solving multi-turn context issues (e.g. user says "yes" after a weather query).
 *
 * Works with all LLM providers (Anthropic, OpenRouter, Tinfoil, OpenAI, Local).
 */
class ToolSearchService(
    private val skillRegistry: NativeSkillRegistry,
    private val tier: Tier,
    private val enabledSkillIds: Set<String>,
    private val presetProvider: (() -> RoutingPreset)? = null,
) {
    companion object {
        private const val TAG = "ToolSearchService"
        const val TOOL_NAME = "search_available_tools"
        private const val MAX_RESULTS = 5
        private val DEFAULT_CORE_SKILL_IDS = setOf("code_execution", "memory")
        private val DEFAULT_DGEN1_CORE_SKILL_IDS = emptySet<String>()
    }

    /** Skills always included regardless of search. */
    private val coreSkillIds: Set<String>
        get() = presetProvider?.invoke()?.coreSkillIds ?: DEFAULT_CORE_SKILL_IDS

    /** Skills always included on PRIVILEGED tier. */
    private val dgen1CoreSkillIds: Set<String>
        get() = presetProvider?.invoke()?.coreDgen1SkillIds ?: DEFAULT_DGEN1_CORE_SKILL_IDS

    /** Per-skill tools that are always included (user-configured "always on" tools). */
    private val alwaysIncludeTools: Map<String, Set<String>>
        get() = presetProvider?.invoke()?.alwaysIncludeTools ?: emptyMap()

    // ── Catalog ──────────────────────────────────────────────────────

    data class CatalogEntry(
        val toolName: String,
        val effectiveName: String,
        val description: String,
        val skillId: String,
        val skillName: String,
    )

    /**
     * Pre-computed search index entry. All tokenization and TF computation
     * happens once at build time, not per-query.
     */
    data class IndexedEntry(
        val catalog: CatalogEntry,
        val nameTokens: Set<String>,
        val descTokens: List<String>,
        val allTokens: List<String>,
        val docLength: Double,
        /** Pre-computed weighted term frequencies (name tokens weighted 3x). */
        val weightedTf: Map<String, Double>,
    )

    /** Registry version at which the index was last built. */
    private var indexedVersion: Long = -1

    /** Full catalog of all discoverable (non-CORE) tools. */
    private var catalog: List<CatalogEntry> = emptyList()

    /** Pre-computed search index. */
    private var searchIndex: List<IndexedEntry> = emptyList()

    /** IDF scores for BM25 ranking. */
    private var idfScores: Map<String, Double> = emptyMap()

    /** Average document length across the index. */
    private var avgDocLength: Double = 0.0

    /** Tools the model has discovered in this conversation session. */
    private val discoveredToolNames = mutableSetOf<String>()

    /**
     * Ensures the search index is up to date with the skill registry.
     * Rebuilds if the registry version has changed (skills added/removed).
     */
    private fun ensureIndexCurrent() {
        val currentVersion = skillRegistry.version
        if (currentVersion != indexedVersion) {
            catalog = buildCatalog()
            searchIndex = buildSearchIndex()
            idfScores = buildIdf()
            avgDocLength = searchIndex.sumOf { it.docLength } /
                searchIndex.size.toDouble().coerceAtLeast(1.0)
            indexedVersion = currentVersion
        }
    }

    /**
     * Eagerly initializes the search index. Call this during setup (e.g. when
     * building the system prompt) so the first search doesn't pay the init cost.
     */
    fun warmUp() {
        ensureIndexCurrent()
    }

    /** Compiled regex for tokenization — avoids recompilation per call. */
    private val nonAlphanumRegex = Regex("[^a-z0-9_]")
    private val whitespaceRegex = Regex("\\s+")

    private fun buildCatalog(): List<CatalogEntry> {
        val startMs = System.currentTimeMillis()
        val alwaysSkillIds = coreSkillIds +
            if (tier == Tier.PRIVILEGED) dgen1CoreSkillIds else emptySet()

        // Collect always-on tool names so we skip them in the catalog
        val alwaysOnToolNames = alwaysIncludeTools.values.flatten().toSet()

        val entries = mutableListOf<CatalogEntry>()
        for (skill in skillRegistry.getEnabled(enabledSkillIds)) {
            if (skill.id in alwaysSkillIds) continue // CORE skill — all tools always present, skip
            val skillAlwaysOnTools = alwaysIncludeTools[skill.id]
            for (tool in skill.baseManifest.tools) {
                // Skip tools that are always-on (they're already in the tool list)
                if (skillAlwaysOnTools != null && tool.name in skillAlwaysOnTools) continue
                entries.add(CatalogEntry(
                    toolName = tool.name,
                    effectiveName = skillRegistry.getEffectiveName(skill.id, tool.name),
                    description = tool.description,
                    skillId = skill.id,
                    skillName = skill.name,
                ))
            }
            if (tier == Tier.PRIVILEGED) {
                skill.privilegedManifest?.tools?.forEach { tool ->
                    if (skillAlwaysOnTools != null && tool.name in skillAlwaysOnTools) return@forEach
                    entries.add(CatalogEntry(
                        toolName = tool.name,
                        effectiveName = skillRegistry.getEffectiveName(skill.id, tool.name),
                        description = tool.description,
                        skillId = skill.id,
                        skillName = skill.name,
                    ))
                }
            }
        }
        Log.i(TAG, "Built catalog: ${entries.size} discoverable tools from " +
            "${entries.map { it.skillId }.distinct().size} skills in ${System.currentTimeMillis() - startMs}ms")
        return entries
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(nonAlphanumRegex, " ")
            .split(whitespaceRegex)
            .filter { it.length > 1 }

    /**
     * Pre-computes tokenization, document lengths, and weighted TF for all catalog entries.
     * This runs once and eliminates per-query tokenization overhead.
     */
    private fun buildSearchIndex(): List<IndexedEntry> {
        val startMs = System.currentTimeMillis()
        val nameWeight = 3.0
        val index = catalog.map { entry ->
            val nameTokens = tokenize(entry.toolName)
            val descTokens = tokenize(entry.description)
            val allTokens = nameTokens + descTokens
            val nameTokenSet = nameTokens.toSet()

            // Pre-compute weighted term frequencies
            val tf = mutableMapOf<String, Double>()
            for (token in allTokens) {
                val weight = if (token in nameTokenSet) nameWeight else 1.0
                tf[token] = (tf[token] ?: 0.0) + weight
            }

            IndexedEntry(
                catalog = entry,
                nameTokens = nameTokenSet,
                descTokens = descTokens,
                allTokens = allTokens,
                docLength = allTokens.size.toDouble(),
                weightedTf = tf,
            )
        }
        Log.i(TAG, "Built search index: ${index.size} entries in ${System.currentTimeMillis() - startMs}ms")
        return index
    }

    private fun buildIdf(): Map<String, Double> {
        val startMs = System.currentTimeMillis()
        val docCount = searchIndex.size.toDouble()
        val df = mutableMapOf<String, Int>()
        for (entry in searchIndex) {
            val uniqueTokens = (entry.nameTokens + entry.descTokens).toSet()
            for (token in uniqueTokens) {
                df[token] = (df[token] ?: 0) + 1
            }
        }
        val idf = df.mapValues { (_, count) -> ln((docCount - count + 0.5) / (count + 0.5) + 1.0) }
        Log.i(TAG, "Built IDF: ${idf.size} terms in ${System.currentTimeMillis() - startMs}ms")
        return idf
    }

    // ── BM25 Search ──────────────────────────────────────────────────

    /**
     * Scores a pre-indexed entry against a query using BM25.
     * All tokenization and TF are pre-computed — this just does the dot product.
     */
    private fun bm25Score(entry: IndexedEntry, queryTokens: List<String>): Double {
        val k1 = 1.2
        val b = 0.75

        var score = 0.0
        for (qt in queryTokens) {
            val idf = idfScores[qt] ?: continue // skip unknown terms
            val freq = entry.weightedTf[qt] ?: continue // skip unmatched terms
            score += idf * (freq * (k1 + 1)) / (freq + k1 * (1 - b + b * entry.docLength / avgDocLength))
        }
        return score
    }

    /**
     * Searches the catalog and returns the top [maxResults] matching entries.
     */
    fun search(query: String, maxResults: Int = MAX_RESULTS): List<CatalogEntry> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyList()

        // Rebuild index if skills have changed since last build
        ensureIndexCurrent()

        return searchIndex
            .map { it to bm25Score(it, queryTokens) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first.catalog }
    }

    // ── Tool execution (called by AgentLoop) ─────────────────────────

    /**
     * Executes the `search_available_tools` tool call.
     * Returns a formatted text result and adds discovered tools to the session.
     */
    fun executeSearch(params: JsonObject): String {
        val query = params["query"]?.jsonPrimitive?.contentOrNull ?: ""
        if (query.isBlank()) {
            return "Please provide a search query to find relevant tools."
        }

        val results = search(query)
        if (results.isEmpty()) {
            return "No tools found matching '$query'. Try a different search query."
        }

        // Track discovered tools
        for (entry in results) {
            discoveredToolNames.add(entry.toolName)
        }

        Log.i(TAG, "search_available_tools('$query') -> ${results.size} results: " +
            results.joinToString { it.toolName })

        // Format results for the model
        val sb = StringBuilder()
        sb.appendLine("Found ${results.size} tool(s) matching '$query':")
        sb.appendLine()
        for (entry in results) {
            sb.appendLine("- **${entry.effectiveName}** (${entry.skillName})")
            sb.appendLine("  ${entry.description.take(200)}")
            sb.appendLine()
        }
        sb.appendLine("These tools are now available for you to call directly.")
        return sb.toString()
    }

    // ── Tool list building ───────────────────────────────────────────

    /**
     * Builds the JSON tool definition for the `search_available_tools` meta-tool.
     */
    fun buildSearchToolJson(): JsonObject = buildJsonObject {
        put("name", TOOL_NAME)
        put("description", buildString {
            append("Search for available tools by keyword or description. ")
            append("Use this when you need a tool that isn't in your current set. ")
            append("Returns matching tools which become available for immediate use. ")
            append("You only need to search once — discovered tools remain available for the rest of the conversation.")
        })
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Search query describing the tool you need (e.g. 'send SMS', 'ENS resolve', 'wallet balance', 'wifi toggle')")
                }
            }
            putJsonArray("required") {
                add(kotlinx.serialization.json.JsonPrimitive("query"))
            }
        }
    }

    /**
     * Builds the full tool list for an API request:
     * CORE tools + always-on tools + discovered tools + search meta-tool.
     *
     * @param nameResolver maps (skillId, toolName) to the effective name the LLM sees
     * @return list of tool JSON objects ready for the API request
     */
    fun buildToolList(
        nameResolver: (String, String) -> String = { _, name -> name },
    ): List<JsonObject> {
        val tools = mutableListOf<JsonObject>()
        val includedToolNames = mutableSetOf<String>()

        // 1. Always-included CORE skills (all their tools)
        val alwaysSkillIds = coreSkillIds.filter { it in enabledSkillIds }.toMutableSet()
        if (tier == Tier.PRIVILEGED) {
            alwaysSkillIds.addAll(dgen1CoreSkillIds.filter { it in enabledSkillIds })
        }

        val coreSkills = skillRegistry.getEnabled(alwaysSkillIds)
        val coreToolJsons = PromptAssembler.assembleTools(coreSkills, tier, nameResolver)
        tools.addAll(coreToolJsons)
        coreToolJsons.forEach { json ->
            json["name"]?.jsonPrimitive?.contentOrNull?.let { includedToolNames.add(it) }
        }

        // 2. Always-on tools from preset (user-configured per-skill tool allow-list)
        val alwaysOn = alwaysIncludeTools
        var alwaysOnCount = 0
        if (alwaysOn.isNotEmpty()) {
            for (skill in skillRegistry.getEnabled(enabledSkillIds)) {
                val allowedTools = alwaysOn[skill.id] ?: continue
                for (tool in skill.baseManifest.tools) {
                    if (tool.name in allowedTools) {
                        val effectiveName = nameResolver(skill.id, tool.name)
                        if (effectiveName !in includedToolNames) {
                            tools.add(buildJsonObject {
                                put("name", effectiveName)
                                put("description", tool.description)
                                put("input_schema", tool.inputSchema)
                            })
                            includedToolNames.add(effectiveName)
                            alwaysOnCount++
                        }
                    }
                }
                if (tier == Tier.PRIVILEGED) {
                    skill.privilegedManifest?.tools?.forEach { tool ->
                        if (tool.name in allowedTools) {
                            val effectiveName = nameResolver(skill.id, tool.name)
                            if (effectiveName !in includedToolNames) {
                                tools.add(buildJsonObject {
                                    put("name", effectiveName)
                                    put("description", tool.description)
                                    put("input_schema", tool.inputSchema)
                                })
                                includedToolNames.add(effectiveName)
                                alwaysOnCount++
                            }
                        }
                    }
                }
            }
        }

        // 3. Discovered tools (from previous search_available_tools calls)
        if (discoveredToolNames.isNotEmpty()) {
            val discoveredToolJsons = buildDiscoveredToolsJson(nameResolver)
            for (json in discoveredToolJsons) {
                val name = json["name"]?.jsonPrimitive?.contentOrNull
                if (name != null && name !in includedToolNames) {
                    tools.add(json)
                    includedToolNames.add(name)
                }
            }
        }

        // 4. The search meta-tool itself
        tools.add(buildSearchToolJson())

        val coreCount = coreToolJsons.size
        val discoveredCount = tools.size - coreCount - alwaysOnCount - 1 // -1 for meta
        Log.d(TAG, "buildToolList: ${tools.size} tools " +
            "(core=$coreCount, alwaysOn=$alwaysOnCount, " +
            "discovered=$discoveredCount, meta=1)")

        return tools
    }

    /**
     * Returns the set of skill IDs that have always-on tools (for system prompt inclusion).
     */
    fun getAlwaysOnSkillIds(): Set<String> {
        val ids = coreSkillIds.filter { it in enabledSkillIds }.toMutableSet()
        if (tier == Tier.PRIVILEGED) {
            ids.addAll(dgen1CoreSkillIds.filter { it in enabledSkillIds })
        }
        ids.addAll(alwaysIncludeTools.keys.filter { it in enabledSkillIds })
        return ids
    }

    /**
     * Builds JSON for all discovered (non-CORE) tools.
     */
    private fun buildDiscoveredToolsJson(
        nameResolver: (String, String) -> String,
    ): List<JsonObject> {
        val tools = mutableListOf<JsonObject>()
        for (skill in skillRegistry.getEnabled(enabledSkillIds)) {
            for (tool in skill.baseManifest.tools) {
                if (tool.name in discoveredToolNames) {
                    tools.add(buildJsonObject {
                        put("name", nameResolver(skill.id, tool.name))
                        put("description", tool.description)
                        put("input_schema", tool.inputSchema)
                    })
                }
            }
            if (tier == Tier.PRIVILEGED) {
                skill.privilegedManifest?.tools?.forEach { tool ->
                    if (tool.name in discoveredToolNames) {
                        tools.add(buildJsonObject {
                            put("name", nameResolver(skill.id, tool.name))
                            put("description", tool.description)
                            put("input_schema", tool.inputSchema)
                        })
                    }
                }
            }
        }
        return tools
    }

    // ── Catalog summary for system prompt ────────────────────────────

    /**
     * Generates a brief summary of tool categories for the system prompt,
     * so the model knows what's searchable without seeing every tool definition.
     */
    fun buildCatalogSummary(): String {
        ensureIndexCurrent()
        // Group catalog entries by skill
        val bySkill = catalog.groupBy { it.skillId to it.skillName }

        val sb = StringBuilder()
        sb.appendLine("## Tool Discovery")
        sb.appendLine("Not all tools are loaded. Use `search_available_tools` to discover tools when needed.")
        sb.appendLine("Once discovered, tools remain available for the rest of this conversation.")
        sb.appendLine()
        sb.appendLine("Searchable tool categories:")
        for ((key, entries) in bySkill) {
            val (_, skillName) = key
            val toolNames = entries.take(3).joinToString(", ") { it.toolName }
            val more = if (entries.size > 3) " +${entries.size - 3} more" else ""
            sb.appendLine("- **$skillName**: $toolNames$more")
        }
        sb.appendLine()
        return sb.toString()
    }

    /** Returns whether a tool name is the search meta-tool. */
    fun isSearchTool(toolName: String): Boolean = toolName == TOOL_NAME

    /** Returns the set of currently discovered tool names. */
    fun getDiscoveredToolNames(): Set<String> = discoveredToolNames.toSet()

    /** Manually mark tools as discovered (e.g. for sub-agent pre-seeding). */
    fun addDiscoveredTools(toolNames: Set<String>) {
        discoveredToolNames.addAll(toolNames)
    }

    /** Reset discovered tools (e.g. for a new conversation). */
    fun resetSession() {
        discoveredToolNames.clear()
    }
}
