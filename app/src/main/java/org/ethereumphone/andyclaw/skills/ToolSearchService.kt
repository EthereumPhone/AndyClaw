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
    /** When true, sibling tools get full schemas loaded. When false (default), siblings are listed by name + hint only. */
    private val autoLoadSiblings: Boolean = false,
) {
    companion object {
        private const val TAG = "ToolSearchService"
        const val TOOL_NAME = "search_available_tools"
        private const val MAX_RESULTS = 5
        /** Max sibling tools to auto-load schemas for. Rest are listed by name only. */
        private const val MAX_AUTO_LOAD_SIBLINGS = 5
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
        /** Optional search hint — extra keywords for discovery (higher weight than description). */
        val searchHint: String? = null,
    )

    /**
     * Pre-computed search index entry. All tokenization and TF computation
     * happens once at build time, not per-query.
     */
    data class IndexedEntry(
        val catalog: CatalogEntry,
        val nameTokens: Set<String>,
        val descTokens: List<String>,
        val hintTokens: Set<String>,
        val allTokens: List<String>,
        val docLength: Double,
        /** Pre-computed weighted term frequencies (name 3x, hint 2x, desc 1x). */
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

    /** Tools announced to the model in previous API calls (for delta tracking). */
    private val previouslyAnnouncedTools = mutableSetOf<String>()

    /**
     * Delta announcement: tools added/removed since last announcement.
     * Ported from Claude Code's deferred tools delta tracking.
     */
    data class DeltaToolAnnouncement(
        val addedNames: Set<String>,
        val removedNames: Set<String>,
    )

    /**
     * Computes the delta between current discovered tools and what was previously
     * announced. Returns null if nothing changed (no message needed).
     */
    fun getDeltaAnnouncement(): DeltaToolAnnouncement? {
        val current = discoveredToolNames.toSet()
        val added = current - previouslyAnnouncedTools
        val removed = previouslyAnnouncedTools - current
        if (added.isEmpty() && removed.isEmpty()) return null
        previouslyAnnouncedTools.clear()
        previouslyAnnouncedTools.addAll(current)
        return DeltaToolAnnouncement(added, removed)
    }

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
                    searchHint = tool.searchHint ?: SearchHints.forTool(tool.name),
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
                        searchHint = tool.searchHint ?: SearchHints.forTool(tool.name),
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
        val hintWeight = 2.0
        val index = catalog.map { entry ->
            val nameTokens = tokenize(entry.toolName)
            val descTokens = tokenize(entry.description)
            val hintTokens = entry.searchHint?.let { tokenize(it) } ?: emptyList()
            val allTokens = nameTokens + hintTokens + descTokens
            val nameTokenSet = nameTokens.toSet()
            val hintTokenSet = hintTokens.toSet()

            // Pre-compute weighted term frequencies (name 3x, hint 2x, desc 1x)
            val tf = mutableMapOf<String, Double>()
            for (token in allTokens) {
                val weight = when {
                    token in nameTokenSet -> nameWeight
                    token in hintTokenSet -> hintWeight
                    else -> 1.0
                }
                tf[token] = (tf[token] ?: 0.0) + weight
            }

            IndexedEntry(
                catalog = entry,
                nameTokens = nameTokenSet,
                descTokens = descTokens,
                hintTokens = hintTokenSet,
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
            val uniqueTokens = (entry.nameTokens + entry.hintTokens + entry.descTokens).toSet()
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

    // ── Query mode patterns ────────────────────────────────────────

    private val selectPattern = Regex("^select:(.+)$", RegexOption.IGNORE_CASE)
    private val requiredPrefix = "+"

    /**
     * Searches the catalog with support for multiple query modes:
     *
     * - **`select:Tool1,Tool2`** — Direct selection by exact tool name.
     * - **`+required keyword`** — `+`-prefixed terms must match in name, hint, or description.
     *   Remaining terms score normally. Example: `+wallet balance check`
     * - **Regular keywords** — BM25-ranked search across name (3x), hint (2x), description (1x).
     */
    fun search(query: String, maxResults: Int = MAX_RESULTS): List<CatalogEntry> {
        ensureIndexCurrent()

        // ── Mode 1: select:Tool1,Tool2 ──
        val selectMatch = selectPattern.find(query)
        if (selectMatch != null) {
            return selectByName(selectMatch.groupValues[1], maxResults)
        }

        // ── Fast path: exact tool name match ──
        val queryLower = query.trim().lowercase()
        val exactMatch = catalog.find { it.toolName.lowercase() == queryLower }
        if (exactMatch != null) {
            Log.d(TAG, "search: exact name match → ${exactMatch.toolName}")
            return listOf(exactMatch)
        }

        // ── Parse +required and optional terms ──
        val rawTokens = tokenize(query)
        if (rawTokens.isEmpty()) return emptyList()

        val queryWords = query.trim().split(whitespaceRegex).filter { it.isNotEmpty() }
        val requiredRaw = queryWords.filter { it.startsWith(requiredPrefix) && it.length > 1 }
            .map { it.removePrefix(requiredPrefix).lowercase() }
        val requiredTokens = requiredRaw.flatMap { tokenize(it) }.toSet()

        // ── Mode 2: +required pre-filter ──
        val candidates = if (requiredTokens.isNotEmpty()) {
            searchIndex.filter { entry ->
                requiredTokens.all { req ->
                    req in entry.nameTokens ||
                        entry.nameTokens.any { it.contains(req) } ||
                        req in entry.hintTokens ||
                        entry.descTokens.contains(req)
                }
            }.also { Log.d(TAG, "search: +required filter kept ${it.size}/${searchIndex.size} entries for $requiredTokens") }
        } else {
            searchIndex
        }

        // ── Mode 3: BM25 scoring over all terms ──
        val allQueryTokens = rawTokens // includes both required and optional
        return candidates
            .map { it to bm25Score(it, allQueryTokens) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { it.first.catalog }
    }

    /**
     * Direct selection by comma-separated tool names.
     */
    private fun selectByName(nameList: String, maxResults: Int): List<CatalogEntry> {
        val requested = nameList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val found = mutableListOf<CatalogEntry>()
        val missing = mutableListOf<String>()

        for (name in requested) {
            val nameLower = name.lowercase()
            val match = catalog.find { it.toolName.lowercase() == nameLower || it.effectiveName.lowercase() == nameLower }
            if (match != null) {
                if (found.none { it.toolName == match.toolName }) found.add(match)
            } else {
                missing.add(name)
            }
        }

        if (missing.isNotEmpty()) {
            Log.d(TAG, "select: found=${found.map { it.toolName }}, missing=$missing")
        } else {
            Log.d(TAG, "select: all found → ${found.map { it.toolName }}")
        }
        return found.take(maxResults)
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

        // Track discovered tools — primary results
        for (entry in results) {
            discoveredToolNames.add(entry.toolName)
        }

        // Skill-level auto-discovery: find sibling tools from the same skill(s).
        // Behavior depends on autoLoadSiblings setting:
        //   true  → siblings get full schema loaded (added to discoveredToolNames)
        //   false → siblings listed by name + searchHint only (model can select: to load)
        val siblingSkillIds = results.map { it.skillId }.toSet()
        val allSiblings = catalog.filter { it.skillId in siblingSkillIds && it.toolName !in discoveredToolNames }

        if (autoLoadSiblings) {
            val toLoad = allSiblings.take(MAX_AUTO_LOAD_SIBLINGS)
            for (entry in toLoad) {
                discoveredToolNames.add(entry.toolName)
            }
        }

        Log.i(TAG, "search_available_tools('$query') -> ${results.size} results: " +
            results.joinToString { it.toolName } +
            if (allSiblings.isNotEmpty()) " (${allSiblings.size} siblings, autoLoad=$autoLoadSiblings)" else "")

        // Format results for the model
        val sb = StringBuilder()
        sb.appendLine("Found ${results.size} tool(s) matching '$query':")
        sb.appendLine()
        for (entry in results) {
            sb.appendLine("- **${entry.effectiveName}** (${entry.skillName})")
            sb.appendLine("  ${entry.description.take(200)}")
            sb.appendLine()
        }
        if (allSiblings.isNotEmpty()) {
            if (autoLoadSiblings) {
                val loaded = allSiblings.take(MAX_AUTO_LOAD_SIBLINGS)
                val remaining = allSiblings.drop(MAX_AUTO_LOAD_SIBLINGS)
                sb.appendLine("Also loaded ${loaded.size} related tool(s) from the same skill(s):")
                for (entry in loaded) {
                    sb.appendLine("- **${entry.effectiveName}**: ${entry.searchHint ?: entry.description.take(80)}")
                }
                if (remaining.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("${remaining.size} more available (use select: to load):")
                    for (entry in remaining) {
                        sb.appendLine("- ${entry.effectiveName} — ${entry.searchHint ?: entry.description.take(60)}")
                    }
                }
            } else {
                sb.appendLine("Related tool(s) from the same skill(s) — use \"select:name\" to load:")
                for (entry in allSiblings) {
                    sb.appendLine("- ${entry.effectiveName} — ${entry.searchHint ?: entry.description.take(60)}")
                }
            }
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
            append("Search for available tools by keyword. ")
            append("Returns matching tools plus related tools from the same skill. ")
            append("Discovered tools stay available for the rest of the conversation — no need to search again. ")
            append("To load tools you've seen listed by name, use \"select:name1,name2\" as the query.")
        })
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Keyword query (e.g. 'send SMS') or 'select:name1,name2' to load specific tools by name.")
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
        previouslyAnnouncedTools.clear()
    }
}
