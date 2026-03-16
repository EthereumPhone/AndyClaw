package org.ethereumphone.andyclaw.skills

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.ContentBlock
import org.ethereumphone.andyclaw.llm.LlmClient
import org.ethereumphone.andyclaw.llm.Message
import org.ethereumphone.andyclaw.llm.MessagesRequest
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingProvider
import kotlin.math.sqrt

/**
 * Configuration for the LLM-based skill routing pass.
 * [client] is the LLM client to use, [model] identifies the cheap/fast model.
 */
data class RoutingConfig(val client: LlmClient, val model: AnthropicModels)

/**
 * Result of skill/tool routing. [skillIds] is the set of skills to include.
 * [allowedTools] is the set of tool names to include when assembling the request,
 * or null to include all tools from routed skills.
 */
data class RoutingResult(
    val skillIds: Set<String>,
    val allowedTools: Set<String>?,
)

/**
 * Routes user messages to the minimal set of skills and tools needed,
 * reducing token overhead by 50-70% on typical queries.
 *
 * Routing pipeline (synchronous LLM-first via [routeSkillsWithLlm]):
 * 1. CORE skills always included (all tools)
 * 2. dGEN1 CORE on PRIVILEGED tier (all tools)
 * 3. Conversation-aware: skills from previous turn's tools (all tools)
 * 4. Session tracking: skills with active sessions (all tools)
 * 5. LLM routing: exact cache hit -> fuzzy cache hit -> synchronous LLM call
 *    - Falls back to keywords only if LLM is unavailable/fails
 *    - Returns both skill IDs and specific tool names
 * 6. External skills: routed by LLM, or all included on LLM failure
 * 7. Smart fallback (embedding -> frequency -> all skills)
 *
 * Background LLM pipeline (via [routeSkills]):
 * - Cache miss: keywords now, LLM in background to populate cache
 * - Cache hit: use cached result instantly
 *
 * Additional optimizations:
 * - Skill catalog cached and rebuilt only when the enabled skill set changes
 * - Feedback loop: routing misses (skills/tools used but not routed) update the cache
 * - Heavy skills annotated in catalog so the LLM is conservative about including them
 * - Routing cache persisted across sessions
 * - Fuzzy cache matching via embeddings for similar (not identical) messages
 * - Tool-level routing: only specific tools from each skill are included
 */
class SkillRouter(
    context: Context? = null,
    private val skillRegistry: NativeSkillRegistry? = null,
    private val embeddingProvider: EmbeddingProvider? = null,
    private val routingClientProvider: (() -> RoutingConfig?)? = null,
    private val presetProvider: (() -> RoutingPreset)? = null,
) {
    companion object {
        private const val TAG = "SkillRouter"
        private const val PREFS_NAME = "andyclaw_skill_router"
        private const val EMBEDDING_TOP_K = 10
        private const val EMBEDDING_MIN_SIMILARITY = 0.2f
        private const val EMBEDDING_MIN_RESULTS = 3
        private const val FREQUENCY_TOP_K = 15
        private const val ROUTING_CACHE_MAX_SIZE = 50
        private const val CACHE_SIMILARITY_THRESHOLD = 0.85f
        private val DEFAULT_CORE_SKILL_IDS = setOf("code_execution", "memory")
        private val DEFAULT_DGEN1_CORE_SKILL_IDS = setOf("terminal_text")
    }

    /** Skills always sent regardless of user message — from the active preset. */
    private val CORE_SKILL_IDS: Set<String>
        get() = presetProvider?.invoke()?.coreSkillIds ?: DEFAULT_CORE_SKILL_IDS

    /** Skills always sent on dGEN1 (PRIVILEGED tier) — from the active preset. */
    private val DGEN1_CORE_SKILL_IDS: Set<String>
        get() = presetProvider?.invoke()?.coreDgen1SkillIds ?: DEFAULT_DGEN1_CORE_SKILL_IDS

    /** Per-skill tools that should always be included — from the active preset. */
    private val ALWAYS_INCLUDE_TOOLS: Map<String, Set<String>>
        get() = presetProvider?.invoke()?.alwaysIncludeTools ?: emptyMap()

    /**
     * Large/expensive skills — annotated as [heavy] in the LLM catalog so the
     * routing model is conservative about including them.
     */
    private val HEAVY_SKILL_IDS = setOf(
        "agent_display",
        "skill-creator",
        "skill-refinement",
        "clawhub",
        "bankr_trading",
        "cli-tool-manager",
    )

    /** Tools that start a session for a skill. */
    private val SESSION_START_TOOLS = mapOf(
        "agent_display_create" to "agent_display",
    )

    /** Tools that end a session for a skill. */
    private val SESSION_END_TOOLS = mapOf(
        "agent_display_destroy" to "agent_display",
    )

    /**
     * Map of keyword/phrase -> set of skill IDs that keyword should activate.
     * Keywords are matched case-insensitively against the user message.
     *
     * This map serves as the fallback when LLM routing is unavailable (no API key,
     * LOCAL provider, timeout, etc). When LLM routing succeeds, keywords are skipped.
     */
    private val KEYWORD_MAP: Map<String, Set<String>> = buildMap {
        // Connectivity
        val conn = setOf("connectivity")
        for (kw in listOf("wifi", "bluetooth", "mobile data", "airplane", "hotspot", "internet", "network", "connected", "connection", "cellular")) {
            put(kw, conn)
        }

        // Phone / calls
        val phone = setOf("phone")
        for (kw in listOf("call", "dial", "phone", "ring", "hang up", "incoming")) {
            put(kw, phone)
        }

        // SMS
        val sms = setOf("sms")
        for (kw in listOf("sms", "text message", "send text", "send a text")) {
            put(kw, sms)
        }

        // Contacts
        val contacts = setOf("contacts")
        for (kw in listOf("contact", "contacts", "phone number", "address book")) {
            put(kw, contacts)
        }

        // Calendar
        val calendar = setOf("calendar", "google_calendar")
        for (kw in listOf("calendar", "event", "meeting", "appointment", "schedule")) {
            put(kw, calendar)
        }

        // Notifications
        val notif = setOf("notifications")
        for (kw in listOf("notification", "notifications", "notify", "alert")) {
            put(kw, notif)
        }

        // Location
        val loc = setOf("location")
        for (kw in listOf("location", "gps", "where am i", "coordinates", "latitude", "longitude")) {
            put(kw, loc)
        }

        // Audio / volume
        val audio = setOf("audio")
        for (kw in listOf("volume", "audio", "sound", "mute", "unmute", "speaker", "ringtone", "music")) {
            put(kw, audio)
        }

        // Screen / brightness
        val screen = setOf("screen")
        for (kw in listOf("brightness", "screen", "display brightness", "dim", "auto-brightness")) {
            put(kw, screen)
        }

        // Camera
        val camera = setOf("camera")
        for (kw in listOf("camera", "photo", "picture", "selfie", "capture")) {
            put(kw, camera)
        }

        // Storage / files
        val storage = setOf("storage", "filesystem")
        for (kw in listOf("storage", "disk", "file", "files", "folder", "directory", "download", "downloads")) {
            put(kw, storage)
        }

        // Device power
        val power = setOf("device_power")
        for (kw in listOf("battery", "charging", "power", "shutdown", "reboot", "restart")) {
            put(kw, power)
        }

        // Reminders / alarms
        val reminders = setOf("reminders")
        for (kw in listOf("reminder", "remind", "alarm", "timer")) {
            put(kw, reminders)
        }

        // Cronjobs
        val cron = setOf("cronjobs")
        for (kw in listOf("cron", "cronjob", "schedule task", "recurring", "periodic")) {
            put(kw, cron)
        }

        // Web search
        val web = setOf("web_search")
        for (kw in listOf("search", "google", "look up", "find online", "web search", "browse",
            "weather", "news", "what time", "the time", "time in", "price of", "how to",
            "who is", "what is", "definition", "translate", "recipe", "directions to",
            "score", "stock", "flight")) {
            put(kw, web)
        }

        // Agent display (HEAVY -- but also dGEN1 CORE)
        val display = setOf("agent_display")
        for (kw in listOf("open app", "launch app", "open the app", "tap", "swipe", "screenshot", "screen", "virtual display",
            "navigate", "click", "type in", "use the app", "go to", "press button", "ui", "interface",
            "order", "book", "play", "watch", "listen", "stream", "download app",
            "uber", "lyft", "spotify", "youtube", "instagram", "whatsapp", "tiktok",
            "sign in", "log in", "sign up")) {
            put(kw, (this[kw] ?: emptySet()) + display)
        }

        // LED matrix (HEAVY)
        val led = setOf("led_matrix")
        for (kw in listOf("led", "matrix", "led matrix", "light", "pixel", "laser")) {
            put(kw, (this[kw] ?: emptySet()) + led)
        }

        // Terminal text (HEAVY)
        val terminal = setOf("terminal_text")
        for (kw in listOf("terminal", "status bar", "touch bar", "terminal text")) {
            put(kw, (this[kw] ?: emptySet()) + terminal)
        }

        // Skill creator / refinement (HEAVY)
        val skillCreate = setOf("skill-creator", "skill-refinement")
        for (kw in listOf("create skill", "new skill", "make a skill", "build skill", "skill creator", "refine skill", "edit skill", "improve skill")) {
            put(kw, skillCreate)
        }

        // ClawHub (HEAVY)
        val clawhub = setOf("clawhub")
        for (kw in listOf("clawhub", "claw hub", "skill store", "install skill", "skill marketplace")) {
            put(kw, clawhub)
        }

        // Bankr trading (HEAVY)
        val bankr = setOf("bankr_trading")
        for (kw in listOf("trade", "trading", "buy token", "sell token", "bankr", "portfolio", "position")) {
            put(kw, bankr)
        }

        // CLI tool manager (HEAVY)
        val cli = setOf("cli-tool-manager")
        for (kw in listOf("cli tool", "cli-tool", "command line tool", "install tool", "tool manager")) {
            put(kw, cli)
        }

        // Wallet / crypto
        val wallet = setOf("wallet", "swap", "token_lookup", "ens")
        for (kw in listOf("wallet", "eth", "ethereum", "token", "swap", "send eth", "balance", "transaction", "ens", "address", "crypto", "wei", "gwei")) {
            put(kw, (this[kw] ?: emptySet()) + wallet)
        }

        // Email
        val email = setOf("gmail")
        for (kw in listOf("email", "gmail", "mail", "inbox", "send email")) {
            put(kw, email)
        }

        // Drive
        val drive = setOf("drive")
        for (kw in listOf("drive", "google drive", "upload file", "cloud storage")) {
            put(kw, drive)
        }

        // Sheets
        val sheets = setOf("sheets")
        for (kw in listOf("spreadsheet", "sheets", "google sheets", "csv")) {
            put(kw, sheets)
        }

        // Telegram
        val telegram = setOf("telegram")
        for (kw in listOf("telegram", "tg message")) {
            put(kw, telegram)
        }

        // Messenger
        val messenger = setOf("messenger")
        for (kw in listOf("messenger", "facebook message", "fb message")) {
            put(kw, messenger)
        }

        // Aurora store
        val aurora = setOf("aurora_store")
        for (kw in listOf("aurora", "aurora store", "install app", "app store", "download app")) {
            put(kw, aurora)
        }

        // Package manager
        val pkg = setOf("package_manager")
        for (kw in listOf("package", "uninstall", "app info", "installed apps")) {
            put(kw, pkg)
        }

        // Termux
        val termux = setOf("termux")
        for (kw in listOf("termux", "linux terminal", "bash")) {
            put(kw, termux)
        }

        // Screen time
        val screenTime = setOf("screen_time")
        for (kw in listOf("screen time", "usage", "app usage", "how long")) {
            put(kw, screenTime)
        }

        // Proactive agent
        val proactive = setOf("proactive_agent")
        for (kw in listOf("proactive", "background task", "monitor")) {
            put(kw, proactive)
        }
    }

    // ── Mutable state ─────────────────────────────────────────────────

    /** Active skill sessions (e.g. a virtual display that hasn't been destroyed). */
    private val activeSessions = mutableSetOf<String>()

    /** Per-skill usage counts, persisted to SharedPreferences. */
    private val prefs: SharedPreferences? = try {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }
    private val usageCounts: MutableMap<String, Int> = loadUsageCounts()

    /** Cached skill embeddings for semantic fallback. */
    private var cachedEmbeddingSkillIds: Set<String>? = null
    private var cachedEmbeddings: Map<String, FloatArray>? = null

    // ── LLM routing cache ─────────────────────────────────────────────

    /** Serializable cache entry for persistence. */
    @Serializable
    data class CachedRoutingEntry(
        val skills: Set<String>,
        val tools: Set<String>,
    )

    /** In-memory cache entry with mutable sets for the feedback loop. */
    data class CachedRouting(
        val skills: MutableSet<String> = mutableSetOf(),
        val tools: MutableSet<String> = mutableSetOf(),
    )

    /**
     * LRU cache: normalized message -> LLM-routed skills and tools.
     * Repeat/similar queries hit this cache instead of making another LLM call.
     * Entries are mutable so the feedback loop can add missed skills/tools.
     * Persisted to SharedPreferences as JSON.
     */
    private val routingCache = object : LinkedHashMap<String, CachedRouting>(
        ROUTING_CACHE_MAX_SIZE + 1, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRouting>): Boolean =
            size > ROUTING_CACHE_MAX_SIZE
    }

    /** Cached embeddings of routing cache keys for fuzzy matching. */
    private val cacheKeyEmbeddings = mutableMapOf<String, FloatArray>()

    /** Cached skill catalog string for LLM routing (rebuilt when skill set changes). */
    private var cachedCatalog: String? = null
    private var cachedCatalogKey: Set<String>? = null

    /** Last routing context for the feedback loop. */
    private var lastRoutedMessageKey: String? = null
    private var lastRoutedSkillIds: Set<String>? = null

    /** Background scope for fire-and-forget LLM routing calls. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    init {
        loadRoutingCache()
    }

    // ── Main routing (synchronous LLM-first) ───────────────────────────

    /**
     * Routes skills and tools using the LLM synchronously before falling back to keywords.
     * Unlike [routeSkills], this waits for the LLM result on cache miss instead
     * of firing it in the background. This gives correct routing on the first
     * request at the cost of ~200-500ms added latency.
     *
     * Returns a [RoutingResult] containing both skill IDs and (optionally) the specific
     * tool names to include. When [RoutingResult.allowedTools] is null, all tools from
     * routed skills should be included. When non-null, only those specific tools.
     */
    suspend fun routeSkillsWithLlm(
        userMessage: String,
        allEnabledSkillIds: Set<String>,
        tier: Tier = Tier.OPEN,
        previousToolNames: Set<String> = emptySet(),
    ): RoutingResult {
        val messageLower = userMessage.lowercase()
        val matched = mutableSetOf<String>()

        // Track skill IDs that should have ALL their tools included (not filtered)
        val alwaysFullToolSkillIds = mutableSetOf<String>()

        // 1. Always include CORE skills that are enabled (all their tools)
        val coreEnabled = CORE_SKILL_IDS.filter { it in allEnabledSkillIds }
        matched.addAll(coreEnabled)
        alwaysFullToolSkillIds.addAll(coreEnabled)

        // 2. On dGEN1 (PRIVILEGED), always include hardware skills (all their tools)
        if (tier == Tier.PRIVILEGED) {
            val dgen1Enabled = DGEN1_CORE_SKILL_IDS.filter { it in allEnabledSkillIds }
            matched.addAll(dgen1Enabled)
            alwaysFullToolSkillIds.addAll(dgen1Enabled)
        }

        // 3. Conversation-aware: include skills that own tools used in the previous turn (all their tools)
        var hasConversationContext = false
        for (toolName in previousToolNames) {
            val skill = skillRegistry?.findSkillForTool(toolName, tier)
            if (skill != null && skill.id in allEnabledSkillIds) {
                matched.add(skill.id)
                alwaysFullToolSkillIds.add(skill.id)
                hasConversationContext = true
            }
        }

        // 4. Session tracking: always include skills with active sessions (all their tools)
        val hasActiveSessions = activeSessions.any { it in allEnabledSkillIds }
        for (skillId in activeSessions) {
            if (skillId in allEnabledSkillIds) {
                matched.add(skillId)
                alwaysFullToolSkillIds.add(skillId)
            }
        }

        // 4b. Always-include tools: add their skills
        for ((skillId, _) in ALWAYS_INCLUDE_TOOLS) {
            if (skillId in allEnabledSkillIds) {
                matched.add(skillId)
            }
        }

        // 5. LLM routing (synchronous): exact cache -> fuzzy cache -> LLM call
        val normalizedMessage = messageLower.trim()
        val cachedResult = synchronized(routingCache) {
            routingCache[normalizedMessage]?.let {
                CachedRouting(it.skills.toMutableSet(), it.tools.toMutableSet())
            }
        }

        var llmRouted = false
        var anyKeywordMatched = false
        var llmTools: Set<String>? = null // specific tools from LLM, null = no tool-level filtering

        if (cachedResult != null) {
            // Exact cache hit — instant
            val validSkills = cachedResult.skills.filter { it in allEnabledSkillIds }
            matched.addAll(validSkills)
            llmTools = cachedResult.tools.takeIf { it.isNotEmpty() }
            llmRouted = true
            Log.d(TAG, "Cache hit (exact): '${userMessage.take(60)}...' -> ${validSkills.size} skills, ${cachedResult.tools.size} tools")
        } else {
            // Try fuzzy cache match via embeddings
            val fuzzyResult = findSimilarCacheEntry(normalizedMessage)
            if (fuzzyResult != null) {
                val validSkills = fuzzyResult.skills.filter { it in allEnabledSkillIds }
                matched.addAll(validSkills)
                llmTools = fuzzyResult.tools.takeIf { it.isNotEmpty() }
                llmRouted = true
                Log.d(TAG, "Cache hit (fuzzy): '${userMessage.take(60)}...' -> ${validSkills.size} skills, ${fuzzyResult.tools.size} tools")
            } else {
                // Synchronous LLM call — wait for the result
                val llmResult = tryLlmRouting(userMessage, allEnabledSkillIds)
                if (llmResult != null) {
                    matched.addAll(llmResult.first)
                    llmTools = llmResult.second.takeIf { it.isNotEmpty() }
                    llmRouted = true
                    // Populate cache
                    addCacheEntry(normalizedMessage, CachedRouting(
                        llmResult.first.toMutableSet(),
                        llmResult.second.toMutableSet(),
                    ))
                    Log.d(TAG, "LLM sync routed: '${userMessage.take(60)}...' -> ${llmResult.first.size} skills, ${llmResult.second.size} tools")
                } else {
                    // LLM unavailable/failed — fall back to keywords (no tool-level filtering)
                    val keywordResults = computeKeywordMatches(messageLower, allEnabledSkillIds)
                    if (keywordResults.isNotEmpty()) {
                        matched.addAll(keywordResults)
                        anyKeywordMatched = true
                    }
                    Log.d(TAG, "LLM unavailable, keyword fallback: '${userMessage.take(60)}...'")
                }
            }
        }

        // 6. External/dynamic skills
        if (!llmRouted) {
            addExternalSkills(matched, allEnabledSkillIds)
        }

        // Track for feedback loop
        lastRoutedMessageKey = normalizedMessage
        lastRoutedSkillIds = matched.toSet()

        // 7. Smart fallback when nothing matched beyond CORE/sessions/conversation
        if (!anyKeywordMatched && !hasConversationContext && !hasActiveSessions && !llmRouted) {
            val embeddingResult = tryEmbeddingFallback(userMessage, allEnabledSkillIds, matched)
            if (embeddingResult != null) {
                Log.d(TAG, "Embedding fallback: '${userMessage.take(60)}...' -> ${embeddingResult.size} skills")
                return RoutingResult(embeddingResult, null)
            }

            val frequencyResult = frequencyBiasedFallback(allEnabledSkillIds, matched)
            if (frequencyResult != null) {
                Log.d(TAG, "Frequency fallback: '${userMessage.take(60)}...' -> ${frequencyResult.size} skills")
                return RoutingResult(frequencyResult, null)
            }

            Log.d(TAG, "Full fallback: '${userMessage.take(60)}...' -> all ${allEnabledSkillIds.size} skills")
            return RoutingResult(allEnabledSkillIds, null)
        }

        // Build the allowedTools set: LLM-specified tools + all tools from always-full skills
        val allowedTools = if (llmTools != null) {
            val allTools = llmTools.toMutableSet()
            // Add all tools from CORE, DGEN1_CORE, conversation, and session skills
            skillRegistry?.getAll()
                ?.filter { it.id in alwaysFullToolSkillIds }
                ?.forEach { skill ->
                    skill.baseManifest.tools.forEach { allTools.add(it.name) }
                    if (tier == Tier.PRIVILEGED) {
                        skill.privilegedManifest?.tools?.forEach { allTools.add(it.name) }
                    }
                }
            // Add always-include tools from the active preset
            for ((skillId, toolNames) in ALWAYS_INCLUDE_TOOLS) {
                if (skillId in allEnabledSkillIds) {
                    allTools.addAll(toolNames)
                    // Also ensure the skill is in the routed set
                    matched.add(skillId)
                }
            }
            allTools
        } else null

        Log.d(TAG, "Routed '${userMessage.take(60)}...' -> ${matched.size} skills" +
            (allowedTools?.let { ", ${it.size} tools" } ?: ", all tools") +
            ": ${matched.joinToString()}")
        return RoutingResult(matched, allowedTools)
    }

    // ── Main routing (background LLM) ────────────────────────────────

    /**
     * Given a user message, device tier, the full set of enabled skill IDs,
     * and optionally the tool names from the previous conversation turn,
     * returns the subset of skill IDs that should be sent to the LLM.
     *
     * This version fires LLM routing in the background and uses keywords
     * for the current request on cache miss.
     */
    suspend fun routeSkills(
        userMessage: String,
        allEnabledSkillIds: Set<String>,
        tier: Tier = Tier.OPEN,
        previousToolNames: Set<String> = emptySet(),
    ): Set<String> {
        val messageLower = userMessage.lowercase()
        val matched = mutableSetOf<String>()

        // 1. Always include CORE skills that are enabled
        matched.addAll(CORE_SKILL_IDS.filter { it in allEnabledSkillIds })

        // 2. On dGEN1 (PRIVILEGED), always include hardware skills
        if (tier == Tier.PRIVILEGED) {
            matched.addAll(DGEN1_CORE_SKILL_IDS.filter { it in allEnabledSkillIds })
        }

        // 3. Conversation-aware: include skills that own tools used in the previous turn
        var hasConversationContext = false
        for (toolName in previousToolNames) {
            val skill = skillRegistry?.findSkillForTool(toolName, tier)
            if (skill != null && skill.id in allEnabledSkillIds) {
                matched.add(skill.id)
                hasConversationContext = true
            }
        }

        // 4. Session tracking: always include skills with active sessions
        val hasActiveSessions = activeSessions.any { it in allEnabledSkillIds }
        for (skillId in activeSessions) {
            if (skillId in allEnabledSkillIds) {
                matched.add(skillId)
            }
        }

        // 5. LLM routing with cache
        val normalizedMessage = messageLower.trim()
        val cachedResult = synchronized(routingCache) {
            routingCache[normalizedMessage]?.let {
                CachedRouting(it.skills.toMutableSet(), it.tools.toMutableSet())
            }
        }

        var llmRouted = false
        var anyKeywordMatched = false
        if (cachedResult != null) {
            val validCached = cachedResult.skills.filter { it in allEnabledSkillIds }
            matched.addAll(validCached)
            llmRouted = true
            Log.d(TAG, "Cache hit: '${userMessage.take(60)}...' -> ${validCached.size} skills")
        } else {
            // Try fuzzy cache match
            val fuzzyResult = findSimilarCacheEntry(normalizedMessage)
            if (fuzzyResult != null) {
                val validSkills = fuzzyResult.skills.filter { it in allEnabledSkillIds }
                matched.addAll(validSkills)
                llmRouted = true
                Log.d(TAG, "Cache hit (fuzzy): '${userMessage.take(60)}...' -> ${validSkills.size} skills")
            } else {
                // Fire LLM routing in the background to populate cache for next time
                fireBackgroundLlmRouting(userMessage, allEnabledSkillIds, normalizedMessage)

                // Use keywords immediately (instant, no waiting)
                val keywordResults = computeKeywordMatches(messageLower, allEnabledSkillIds)
                if (keywordResults.isNotEmpty()) {
                    matched.addAll(keywordResults)
                    anyKeywordMatched = true
                }
            }
        }

        // 6. External/dynamic skills
        if (!llmRouted) {
            addExternalSkills(matched, allEnabledSkillIds)
        }

        // Track for feedback loop
        lastRoutedMessageKey = normalizedMessage
        lastRoutedSkillIds = matched.toSet()

        // 7. Smart fallback when nothing matched beyond CORE/sessions/conversation
        if (!anyKeywordMatched && !hasConversationContext && !hasActiveSessions && !llmRouted) {
            val embeddingResult = tryEmbeddingFallback(userMessage, allEnabledSkillIds, matched)
            if (embeddingResult != null) {
                Log.d(TAG, "Embedding fallback: '${userMessage.take(60)}...' -> ${embeddingResult.size} skills")
                return embeddingResult
            }

            val frequencyResult = frequencyBiasedFallback(allEnabledSkillIds, matched)
            if (frequencyResult != null) {
                Log.d(TAG, "Frequency fallback: '${userMessage.take(60)}...' -> ${frequencyResult.size} skills")
                return frequencyResult
            }

            Log.d(TAG, "Full fallback: '${userMessage.take(60)}...' -> all ${allEnabledSkillIds.size} skills")
            return allEnabledSkillIds
        }

        Log.d(TAG, "Routed '${userMessage.take(60)}...' -> ${matched.size} skills: ${matched.joinToString()}")
        return matched
    }

    // ── Keyword matching ──────────────────────────────────────────────

    /**
     * Pure keyword-based skill matching. Returns the set of skill IDs matched
     * by keywords in the message. This is the fallback path when LLM routing
     * is unavailable.
     */
    private fun computeKeywordMatches(
        messageLower: String,
        allEnabledSkillIds: Set<String>,
    ): Set<String> {
        val result = mutableSetOf<String>()
        for ((keyword, skillIds) in KEYWORD_MAP) {
            if (messageLower.contains(keyword)) {
                for (skillId in skillIds) {
                    if (skillId in allEnabledSkillIds) {
                        result.add(skillId)
                    }
                }
            }
        }
        return result
    }

    // ── Tool execution notifications ──────────────────────────────────

    /**
     * Called after each tool execution to track sessions, usage frequency,
     * and routing feedback. Updates the cache with missed skills/tools and
     * persists corrections.
     */
    fun notifyToolExecuted(toolName: String, tier: Tier) {
        // Session tracking
        SESSION_START_TOOLS[toolName]?.let { activeSessions.add(it) }
        SESSION_END_TOOLS[toolName]?.let { activeSessions.remove(it) }

        // Usage frequency (skip CORE skills -- they're always included anyway)
        val skill = skillRegistry?.findSkillForTool(toolName, tier)
        if (skill != null && skill.id !in CORE_SKILL_IDS) {
            usageCounts[skill.id] = (usageCounts[skill.id] ?: 0) + 1
            persistUsageCounts()
        }

        // Feedback loop: if this skill/tool wasn't in the routed set, it's a routing miss.
        // Update the cache so next time a similar message is seen, this skill/tool is included.
        if (skill != null && skill.id !in CORE_SKILL_IDS && skill.id !in DGEN1_CORE_SKILL_IDS) {
            val msgKey = lastRoutedMessageKey
            val routedIds = lastRoutedSkillIds
            if (msgKey != null && routedIds != null && skill.id !in routedIds) {
                Log.d(TAG, "Routing miss: skill '${skill.id}' tool '$toolName' used but not routed for '${msgKey.take(60)}...'")
                var updated = false
                synchronized(routingCache) {
                    routingCache[msgKey]?.let { entry ->
                        entry.skills.add(skill.id)
                        entry.tools.add(toolName)
                        updated = true
                    }
                }
                if (updated) persistRoutingCache()
            } else if (msgKey != null) {
                // Skill was routed but tool might not have been — add tool to cache
                synchronized(routingCache) {
                    routingCache[msgKey]?.let { entry ->
                        if (toolName !in entry.tools && entry.tools.isNotEmpty()) {
                            entry.tools.add(toolName)
                            persistRoutingCache()
                        }
                    }
                }
            }
        }
    }

    /** Clear all active sessions (called on cleanup). */
    fun clearSessions() {
        activeSessions.clear()
    }

    /** Get active session skill IDs (for testing/inspection). */
    fun getActiveSessions(): Set<String> = activeSessions.toSet()

    /** Get current usage counts (for testing/inspection). */
    fun getUsageCounts(): Map<String, Int> = usageCounts.toMap()

    /** Clear the LLM routing cache (for testing/when skills change). */
    fun clearRoutingCache() {
        synchronized(routingCache) { routingCache.clear() }
        synchronized(cacheKeyEmbeddings) { cacheKeyEmbeddings.clear() }
        cachedCatalog = null
        cachedCatalogKey = null
        prefs?.edit()?.remove("routing_cache")?.apply()
    }

    // ── LLM-based routing ───────────────────────────────────────────

    /**
     * Builds a compact text catalog of routable skills for the LLM prompt.
     * Includes tool names under each skill so the LLM can route at tool level.
     * Excludes only CORE and dGEN1_CORE (those are always included regardless).
     * External skills (clawhub:, ai:, ext:) are included so the LLM can route them.
     * Heavy skills are annotated with [heavy] so the LLM is conservative about them.
     *
     * The catalog is cached and rebuilt only when the enabled skill set changes.
     */
    private fun buildSkillCatalog(allEnabledSkillIds: Set<String>): String {
        // Return cached catalog if the skill set hasn't changed
        if (cachedCatalogKey == allEnabledSkillIds) {
            cachedCatalog?.let { return it }
        }

        val registry = skillRegistry ?: return ""
        val excludeIds = CORE_SKILL_IDS + DGEN1_CORE_SKILL_IDS
        val lines = registry.getAll()
            .filter { it.id in allEnabledSkillIds && it.id !in excludeIds }
            .map { skill ->
                val desc = skill.baseManifest.description.substringBefore('.').trim()
                val heavyTag = if (skill.id in HEAVY_SKILL_IDS) " [heavy]" else ""
                val toolNames = skill.baseManifest.tools.map { it.name } +
                    (skill.privilegedManifest?.tools?.map { it.name } ?: emptyList())
                "- ${skill.id}: $desc$heavyTag\n  tools: ${toolNames.joinToString(", ")}"
            }
        val result = lines.joinToString("\n")

        cachedCatalog = result
        cachedCatalogKey = allEnabledSkillIds.toSet()
        return result
    }

    /**
     * Constructs a [MessagesRequest] for the routing LLM call.
     * Asks for both skill IDs and specific tool names.
     */
    private fun buildRoutingRequest(
        userMessage: String,
        catalog: String,
        model: AnthropicModels,
    ): MessagesRequest {
        val systemPrompt = "You are a skill router for a phone assistant. " +
            "Given a user message, return a JSON object with:\n" +
            "- \"skills\": array of skill IDs needed to handle the request\n" +
            "- \"tools\": array of specific tool names needed from those skills\n" +
            "Example: {\"skills\":[\"sms\",\"contacts\"],\"tools\":[\"send_sms\",\"get_contact\"]}\n" +
            "Return {\"skills\":[],\"tools\":[]} if no skills beyond the defaults are needed.\n" +
            "Only include tools you expect will actually be called. " +
            "Skills marked [heavy] are expensive -- only include them when clearly relevant.\n" +
            "Available skills:\n$catalog"
        return MessagesRequest(
            model = model.modelId,
            maxTokens = 256,
            system = systemPrompt,
            messages = listOf(Message.user(userMessage)),
        )
    }

    /**
     * Parses the routing LLM response into skill IDs and tool names.
     * Supports both the new object format and the legacy array format.
     * Returns null on any parse failure.
     */
    private fun parseRoutingResponse(
        responseText: String,
        allEnabledSkillIds: Set<String>,
    ): Pair<Set<String>, Set<String>>? {
        return try {
            val cleaned = responseText
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            // Try to parse as JSON object with skills + tools (new format)
            if (cleaned.contains("\"skills\"")) {
                val skillsMatch = Regex("\"skills\"\\s*:\\s*\\[([^\\]]*)\\]").find(cleaned)
                val toolsMatch = Regex("\"tools\"\\s*:\\s*\\[([^\\]]*)\\]").find(cleaned)

                val skillIds = skillsMatch?.groupValues?.get(1)?.let { arr ->
                    Regex("\"([^\"]+)\"").findAll(arr)
                        .map { it.groupValues[1] }
                        .filter { it in allEnabledSkillIds }
                        .toSet()
                } ?: emptySet()

                val toolNames = toolsMatch?.groupValues?.get(1)?.let { arr ->
                    Regex("\"([^\"]+)\"").findAll(arr)
                        .map { it.groupValues[1] }
                        .toSet()
                } ?: emptySet()

                return skillIds to toolNames
            }

            // Fall back to array format (legacy / backward compat)
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start < 0 || end < 0 || end <= start) return null

            val arrayStr = cleaned.substring(start, end + 1)
            val ids = Regex("\"([^\"]+)\"").findAll(arrayStr)
                .map { it.groupValues[1] }
                .filter { it in allEnabledSkillIds }
                .toSet()

            ids to emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse routing response: ${e.message}")
            null
        }
    }

    /**
     * Fires an LLM routing call in the background. When the result arrives,
     * it populates the routing cache so subsequent similar messages are instant.
     * This is fire-and-forget — the current request uses keywords, not the LLM result.
     */
    private fun fireBackgroundLlmRouting(
        userMessage: String,
        allEnabledSkillIds: Set<String>,
        normalizedMessage: String,
    ) {
        // Don't fire if no routing provider is configured
        val config = try {
            routingClientProvider?.invoke()
        } catch (_: Exception) {
            null
        } ?: return

        backgroundScope.launch {
            val result = tryLlmRouting(userMessage, allEnabledSkillIds)
            if (result != null) {
                addCacheEntry(normalizedMessage, CachedRouting(
                    result.first.toMutableSet(),
                    result.second.toMutableSet(),
                ))
            }
        }
    }

    /**
     * Orchestrates the LLM routing pass. Returns matched skill IDs and tool names
     * on success, or null on any failure (timeout, parse error, no routing model).
     * Logs token usage for the routing call.
     */
    private suspend fun tryLlmRouting(
        userMessage: String,
        allEnabledSkillIds: Set<String>,
    ): Pair<Set<String>, Set<String>>? {
        val config = try {
            routingClientProvider?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "LLM routing config unavailable: ${e.message}")
            null
        } ?: return null

        return try {
            val catalog = buildSkillCatalog(allEnabledSkillIds)
            if (catalog.isBlank()) return null

            val request = buildRoutingRequest(userMessage, catalog, config.model)
            val response = config.client.sendMessage(request)

            // Log routing call token usage
            response.usage?.let { u ->
                Log.d(TAG, "TokenStats | routing_call input=${u.inputTokens} output=${u.outputTokens} total=${u.inputTokens + u.outputTokens}")
            }

            val text = response.content
                .filterIsInstance<ContentBlock.TextBlock>()
                .joinToString("") { it.text }

            val result = parseRoutingResponse(text, allEnabledSkillIds)
            if (result != null) {
                val (ids, tools) = result
                Log.d(TAG, "LLM routed '${userMessage.take(60)}...' -> ${ids.size} skills, ${tools.size} tools: ${ids.joinToString()}")
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "LLM routing failed: ${e.message}")
            null
        }
    }

    // ── Routing cache persistence ────────────────────────────────────

    /**
     * Adds or merges a cache entry and persists the updated cache.
     * Also computes and caches the embedding for fuzzy matching in the background.
     */
    private fun addCacheEntry(normalizedMessage: String, entry: CachedRouting) {
        synchronized(routingCache) {
            val existing = routingCache[normalizedMessage]
            if (existing != null) {
                existing.skills.addAll(entry.skills)
                existing.tools.addAll(entry.tools)
            } else {
                routingCache[normalizedMessage] = entry
            }
        }
        persistRoutingCache()

        // Cache the embedding for fuzzy matching (background, non-blocking)
        backgroundScope.launch {
            try {
                val emb = embeddingProvider?.embed(normalizedMessage)
                if (emb != null) {
                    synchronized(cacheKeyEmbeddings) {
                        cacheKeyEmbeddings[normalizedMessage] = emb
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** Persists the routing cache to SharedPreferences as JSON. */
    private fun persistRoutingCache() {
        val snapshot = synchronized(routingCache) {
            routingCache.mapValues { (_, v) ->
                CachedRoutingEntry(v.skills.toSet(), v.tools.toSet())
            }
        }
        try {
            val json = jsonCodec.encodeToString(snapshot)
            prefs?.edit()?.putString("routing_cache", json)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist routing cache: ${e.message}")
        }
    }

    /** Loads the routing cache from SharedPreferences. */
    private fun loadRoutingCache() {
        val json = prefs?.getString("routing_cache", null) ?: return
        try {
            val map: Map<String, CachedRoutingEntry> = jsonCodec.decodeFromString(json)
            synchronized(routingCache) {
                for ((key, entry) in map) {
                    routingCache[key] = CachedRouting(
                        entry.skills.toMutableSet(),
                        entry.tools.toMutableSet(),
                    )
                }
            }
            Log.d(TAG, "Loaded ${map.size} routing cache entries from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load routing cache: ${e.message}")
        }
    }

    // ── Fuzzy cache matching ─────────────────────────────────────────

    /**
     * Finds a similar cached routing entry using embedding cosine similarity.
     * Returns null if no similar entry is found above [CACHE_SIMILARITY_THRESHOLD],
     * or if the embedding provider is unavailable.
     */
    private suspend fun findSimilarCacheEntry(normalizedMessage: String): CachedRouting? {
        val provider = embeddingProvider ?: return null
        val keyEmbeddings = synchronized(cacheKeyEmbeddings) { cacheKeyEmbeddings.toMap() }
        if (keyEmbeddings.isEmpty()) return null

        val messageEmb = try {
            provider.embed(normalizedMessage)
        } catch (_: Exception) {
            null
        } ?: return null

        var bestKey: String? = null
        var bestSimilarity = CACHE_SIMILARITY_THRESHOLD
        for ((key, emb) in keyEmbeddings) {
            val sim = cosineSimilarity(messageEmb, emb)
            if (sim > bestSimilarity) {
                bestSimilarity = sim
                bestKey = key
            }
        }

        return bestKey?.let { key ->
            synchronized(routingCache) {
                routingCache[key]?.let {
                    CachedRouting(it.skills.toMutableSet(), it.tools.toMutableSet())
                }
            }
        }?.also {
            Log.d(TAG, "Fuzzy cache match: similarity=${String.format("%.3f", bestSimilarity)} for '${normalizedMessage.take(60)}...'")
        }
    }

    // ── Embedding-based fallback ──────────────────────────────────────

    /**
     * Uses semantic embeddings to find the most relevant skills for the message.
     * Returns null if embedding provider is unavailable or embedding fails.
     */
    private suspend fun tryEmbeddingFallback(
        userMessage: String,
        allEnabledSkillIds: Set<String>,
        coreMatched: Set<String>,
    ): Set<String>? {
        val provider = embeddingProvider ?: return null
        val registry = skillRegistry ?: return null

        return try {
            val embeddings = getOrBuildEmbeddings(allEnabledSkillIds, registry)
            if (embeddings.isEmpty()) return null

            val messageEmbedding = provider.embed(userMessage)
            val similarities = embeddings
                .filter { it.key in allEnabledSkillIds }
                .mapValues { (_, emb) -> cosineSimilarity(messageEmbedding, emb) }
            val ranked = similarities.entries.sortedByDescending { it.value }

            val result = coreMatched.toMutableSet()
            var added = 0
            for ((skillId, similarity) in ranked) {
                if (added >= EMBEDDING_TOP_K) break
                if (similarity >= EMBEDDING_MIN_SIMILARITY || added < EMBEDDING_MIN_RESULTS) {
                    result.add(skillId)
                    added++
                }
            }

            addExternalSkills(result, allEnabledSkillIds)
            result
        } catch (e: Exception) {
            Log.w(TAG, "Embedding fallback failed: ${e.message}")
            null
        }
    }

    private suspend fun getOrBuildEmbeddings(
        allSkillIds: Set<String>,
        registry: NativeSkillRegistry,
    ): Map<String, FloatArray> {
        cachedEmbeddings?.let { cached ->
            if (cachedEmbeddingSkillIds == allSkillIds) return cached
        }

        val provider = embeddingProvider ?: return emptyMap()
        val skills = registry.getAll().filter { it.id in allSkillIds && it.id !in CORE_SKILL_IDS }
        if (skills.isEmpty()) return emptyMap()

        val descriptions = skills.map { "${it.name}: ${it.baseManifest.description}" }
        val embeddings = provider.embed(descriptions)
        val result = skills.mapIndexed { i, skill -> skill.id to embeddings[i] }.toMap()

        cachedEmbeddingSkillIds = allSkillIds.toSet()
        cachedEmbeddings = result
        return result
    }

    // ── Frequency-biased fallback ─────────────────────────────────────

    /**
     * When no keywords match, prefer skills the user has historically used most.
     * Returns null if no usage history exists (falls through to full fallback).
     */
    private fun frequencyBiasedFallback(
        allEnabledSkillIds: Set<String>,
        coreMatched: Set<String>,
    ): Set<String>? {
        val relevantCounts = usageCounts.filter { it.key in allEnabledSkillIds && it.value > 0 }
        if (relevantCounts.isEmpty()) return null

        val topSkills = relevantCounts.entries
            .sortedByDescending { it.value }
            .take(FREQUENCY_TOP_K)
            .map { it.key }
            .toSet()

        val result = coreMatched.toMutableSet()
        result.addAll(topSkills)
        addExternalSkills(result, allEnabledSkillIds)
        return result
    }

    // ── Usage persistence ─────────────────────────────────────────────

    private fun loadUsageCounts(): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        val all = prefs?.all ?: return map
        for ((key, value) in all) {
            if (key.startsWith("usage_") && value is Int) {
                map[key.removePrefix("usage_")] = value
            }
        }
        return map
    }

    private fun persistUsageCounts() {
        val editor = prefs?.edit() ?: return
        for ((skillId, count) in usageCounts) {
            editor.putInt("usage_$skillId", count)
        }
        editor.apply()
    }

    // ── Utilities ─────────────────────────────────────────────────────

    /** Add all external/dynamic skills (clawhub:, ai:, ext:) to the result set. */
    private fun addExternalSkills(result: MutableSet<String>, allEnabledSkillIds: Set<String>) {
        for (skillId in allEnabledSkillIds) {
            if (skillId.startsWith("clawhub:") || skillId.startsWith("ai:") || skillId.startsWith("ext:")) {
                result.add(skillId)
            }
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }
}
