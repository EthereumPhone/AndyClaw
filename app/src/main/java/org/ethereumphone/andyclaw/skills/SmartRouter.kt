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
import org.ethereumphone.andyclaw.llm.ReasoningConfig
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingProvider
import java.io.File
import kotlin.math.sqrt

/**
 * Configuration for the LLM-based skill routing pass.
 * [client] is the LLM client to use, [model] identifies the cheap/fast model.
 */
data class RoutingConfig(val client: LlmClient, val modelId: String)

/**
 * Controls how aggressively the router filters tools.
 * - [STANDARD]: Routes at skill level, includes ALL tools from matched skills, uses dependency expansion.
 *   Most reliable — good default for users who prioritize correctness.
 * - [BALANCED]: Routes at skill level, includes all tools from matched skills but uses
 *   tool-level dependency expansion across skills (more precise than STANDARD's skill-level deps).
 * - [STRICT]: (Experimental) Routes at skill level AND filters to only the specific tools the LLM
 *   selected. Skips dependency graph expansion. Maximum token savings but may miss tools the main
 *   LLM needs (e.g. picking get_user_wallet_address instead of get_agent_wallet_address).
 */
enum class RoutingMode {
    STANDARD,
    BALANCED,
    STRICT;

    companion object {
        fun fromString(value: String?): RoutingMode = when (value?.lowercase()?.trim()) {
            "strict", "aggressive" -> STRICT
            "balanced", "moderate_filtered" -> BALANCED
            "standard", "moderate" -> STANDARD
            else -> STANDARD
        }
    }
}

/**
 * Budget hint from the skill router: how much output the query likely needs.
 * Used by [BudgetConfig] to set dynamic max_tokens instead of a char-length heuristic.
 */
enum class RoutingBudget {
    /** Tool-only or very brief answer — minimal output tokens. */
    LOW,
    /** Moderate task — medium output expected. */
    MEDIUM,
    /** Complex/creative/long-form — full output budget. */
    HIGH;

    companion object {
        fun fromString(value: String?): RoutingBudget? = when (value?.lowercase()?.trim()) {
            "low" -> LOW
            "medium", "med" -> MEDIUM
            "high" -> HIGH
            else -> null
        }
    }
}

/** Classifies the intent behind a user message for routing decisions. */
enum class MessageIntent {
    /** Greeting, thanks, social — no skills needed beyond CORE. */
    CONVERSATIONAL,
    /** Direct command (turn on wifi, send sms). */
    COMMAND,
    /** Information query (what's the weather). */
    QUERY,
    /** Multi-step task requiring multiple skills. */
    MULTI_STEP,
}

/**
 * Result of skill/tool routing. [skillIds] is the set of skills to include.
 * [allowedTools] is the set of tool names to include when assembling the request,
 * or null to include all tools from routed skills.
 * [budget] is a hint for how much output the query likely needs (null = unknown).
 * [modelTier] is the classified difficulty tier (null = unknown / fallback path).
 * [modelIdOverride] is the resolved model ID to use instead of the user's default (null = use default).
 * [maxTokensOverride] is the resolved max tokens for the overridden model (null = use default).
 */
data class RoutingResult(
    val skillIds: Set<String>,
    val allowedTools: Set<String>?,
    val budget: RoutingBudget? = null,
    val modelTier: org.ethereumphone.andyclaw.llm.ModelTier? = null,
    val modelIdOverride: String? = null,
    val maxTokensOverride: Int? = null,
)

/** Routing performance metrics, persisted across sessions. */
data class RoutingMetrics(
    var cacheHits: Int = 0,
    var cacheMisses: Int = 0,
    var fuzzyCacheHits: Int = 0,
    var llmRoutingCalls: Int = 0,
    var llmRoutingFailures: Int = 0,
    var keywordFallbacks: Int = 0,
    var embeddingFallbacks: Int = 0,
    var frequencyFallbacks: Int = 0,
    var fullFallbacks: Int = 0,
    var conversationalShortCircuits: Int = 0,
    var routingMisses: Int = 0,
    var toolMisses: Int = 0,
    var overRoutes: Int = 0,
    var totalRoutingTimeMs: Long = 0,
)

/**
 * Routes user messages to the minimal set of skills and tools needed,
 * reducing token overhead by 50-70% on typical queries.
 *
 * Routing pipeline (synchronous LLM-first via [routeSkillsWithLlm]):
 * 1. Intent classification: conversational messages short-circuit to CORE only
 * 2. CORE skills always included (all tools)
 * 3. dGEN1 CORE on PRIVILEGED tier (all tools)
 * 4. Conversation-aware: skills from previous turns' tools (all tools)
 * 5. Session tracking: skills with active sessions (all tools)
 * 6. LLM routing: exact cache hit -> fuzzy cache hit -> synchronous LLM call
 *    - Falls back to keywords only if LLM is unavailable/fails
 *    - Returns both skill IDs and specific tool names
 *    - Supports exclusion categories
 * 7. External skills: routed by LLM, or selectively included on LLM failure
 * 8. Smart fallback (embedding -> frequency -> reduced set, not all skills)
 *
 * Additional optimizations:
 * - Skill catalog cached and rebuilt only when the enabled skill set changes
 * - Feedback loop: routing misses (skills/tools used but not routed) update the cache
 * - Heavy skills annotated in catalog so the LLM is conservative about including them
 * - Routing cache persisted across sessions with TTL and correction limits
 * - Fuzzy cache matching via embeddings with content-word overlap guard
 * - Tool-level routing: only specific tools from each skill are included
 * - Word-boundary keyword matching prevents substring false positives
 * - Cache key normalization clusters similar queries
 * - Auto-generated keywords from skill descriptions
 * - Routing metrics for observability
 */
/**
 * Configuration for difficulty-based model routing.
 * When [enabled] is true, the router classifies task difficulty and resolves
 * the appropriate model from the registry or static mappings.
 *
 * @param enabled Whether model routing is active.
 * @param registry OpenRouter model registry for dynamic model selection (nullable for non-OpenRouter providers).
 * @param providerProvider Returns the user's current LLM provider.
 * @param defaultModelIdProvider Returns the user's currently selected model ID.
 * @param tierModelOverrideProvider Returns the user's per-tier model override (empty string = auto).
 */
data class ModelRoutingConfig(
    val enabled: Boolean,
    val registry: org.ethereumphone.andyclaw.llm.OpenRouterModelRegistry? = null,
    val providerProvider: (() -> org.ethereumphone.andyclaw.llm.LlmProvider)? = null,
    val defaultModelIdProvider: (() -> String)? = null,
    val tierModelOverrideProvider: ((org.ethereumphone.andyclaw.llm.ModelTier) -> String)? = null,
)

class SmartRouter(
    context: Context? = null,
    private val skillRegistry: NativeSkillRegistry? = null,
    private val embeddingProvider: EmbeddingProvider? = null,
    private val routingClientProvider: (() -> RoutingConfig?)? = null,
    private val presetProvider: (() -> RoutingPreset)? = null,
    private val routingModeProvider: (() -> RoutingMode)? = null,
    private val modelRoutingConfigProvider: (() -> ModelRoutingConfig)? = null,
    private val filesDir: File? = null,
) {
    companion object {
        private const val TAG = "SmartRouter"
        private const val PREFS_NAME = "andyclaw_smart_router"
        private const val EMBEDDING_TOP_K = 10
        private const val EMBEDDING_MIN_SIMILARITY = 0.35f
        private const val EMBEDDING_MIN_RESULTS = 1
        private const val EMBEDDING_GAP_THRESHOLD = 0.15f
        private const val FREQUENCY_TOP_K = 15
        private const val ROUTING_CACHE_MAX_SIZE = 150
        private const val CACHE_SIMILARITY_THRESHOLD = 0.85f
        private const val CACHE_TTL_MS = 604_800_000L // 7 days
        private const val MAX_CORRECTIONS = 3
        private val DEFAULT_CORE_SKILL_IDS = setOf("code_execution", "memory")
        private val DEFAULT_DGEN1_CORE_SKILL_IDS = setOf("terminal_text")

        private val CONVERSATIONAL_PATTERNS = listOf(
            Regex("\\b(hi|hello|hey|howdy|hola|greetings|sup|yo)\\b"),
            Regex("\\bgood (morning|afternoon|evening|night)\\b"),
            Regex("\\b(thanks|thank you|thx|ty|cheers)\\b"),
            Regex("\\b(how are you|how's it going|what's up|whats up)\\b"),
            Regex("\\b(bye|goodbye|see you|later|goodnight|cya)\\b"),
            Regex("^(ok|okay|sure|alright|got it|understood|cool|nice|great|awesome|perfect|yes|no|yeah|yep|nope|nah)[.!]?$"),
        )

        private val EXCLUSION_CATEGORY_MAP: Map<String, Set<String>> = mapOf(
            "crypto" to setOf("wallet", "swap", "token_lookup", "ens", "bankr_trading"),
            "messaging" to setOf("sms", "telegram", "messenger", "gmail"),
            "media" to setOf("camera", "audio", "agent_display"),
            "hardware" to setOf("led_matrix", "terminal_text", "device_power"),
            "files" to setOf("storage", "filesystem", "drive"),
        )

        /**
         * Normalizes a message for cache key usage. Strips common prefixes,
         * punctuation, and articles so "can you turn on the wifi?" and
         * "turn on wifi" map to the same key.
         */
        fun normalizeForCache(message: String): String {
            return message.lowercase().trim()
                .replace(Regex("^(please |can you |could you |hey |ok |okay |hi |yo )+"), "")
                .replace(Regex("[?.!]+$"), "")
                .replace(Regex("\\b(the|a|an|my|this|that)\\b"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
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

    /**
     * Skill dependency graph: when a skill (key) is routed, its dependencies
     * (values) are automatically included. Dependencies are unidirectional
     * and resolved transitively (A→B→C includes both B and C).
     * Only dependencies present in the enabled skill set are added.
     */
    private val SKILL_DEPENDENCIES: Map<String, Set<String>> = mapOf(
        // Messaging needs contact lookup
        "sms" to setOf("contacts"),
        "phone" to setOf("contacts"),
        "gmail" to setOf("contacts"),
        "telegram" to setOf("contacts"),
        "messenger" to setOf("contacts"),

        // Crypto operations are co-dependent
        "swap" to setOf("wallet", "token_lookup"),
        "bankr_trading" to setOf("wallet", "token_lookup"),
        "ens" to setOf("wallet"),

        // Calendar sync
        "google_calendar" to setOf("calendar"),

        // File operations span both skills
        "filesystem" to setOf("storage"),
        "storage" to setOf("filesystem"),

        // Skill lifecycle
        "skill-refinement" to setOf("skill-creator"),

        // App interaction may need package info
        "agent_display" to setOf("apps"),
    )

    /**
     * Tool-level dependency graph: when a specific tool is routed, its dependency
     * tools are automatically included. Used only in MODERATE_FILTERED mode.
     */
    private val TOOL_DEPENDENCIES: Map<String, Set<String>> = mapOf(
        "send_sms" to setOf("get_contacts"),
        "make_call" to setOf("get_contacts"),
        "send_email" to setOf("get_contacts"),
        "send_telegram_message" to setOf("get_contacts"),
        "execute_swap" to setOf("get_user_wallet_address", "lookup_token"),
        "execute_trade" to setOf("get_user_wallet_address", "lookup_token"),
        "send_eth" to setOf("get_user_wallet_address", "resolve_ens"),
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
     * Keywords are matched with word-boundary regex (not substring) against the user message.
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
        for (kw in listOf("brightness", "screen", "screenshot", "display brightness", "dim", "auto-brightness")) {
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

    /** Pre-compiled word-boundary regexes for accurate keyword matching. */
    private val keywordRegexMap: List<Pair<Regex, Set<String>>> = KEYWORD_MAP.map { (keyword, skills) ->
        Regex("\\b${Regex.escape(keyword)}\\b") to skills
    }

    /** Auto-generated keywords from skill descriptions (populated on init when registry available). */
    private var generatedKeywordMap: Map<String, Set<String>> = emptyMap()

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

    /** Routing performance metrics. */
    private val metrics = RoutingMetrics()

    /** Last known enabled skill IDs for cache invalidation on skill set change. */
    private var lastKnownEnabledSkillIds: Set<String>? = null

    // ── LLM routing cache ─────────────────────────────────────────────

    /** Serializable cache entry for persistence. */
    @Serializable
    data class CachedRoutingEntry(
        val skills: Set<String>,
        val tools: Set<String>,
        val budget: String? = null,
        val difficulty: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val correctionCount: Int = 0,
    )

    /** In-memory cache entry with mutable sets for the feedback loop. */
    data class CachedRouting(
        val skills: MutableSet<String> = mutableSetOf(),
        val tools: MutableSet<String> = mutableSetOf(),
        val budget: RoutingBudget? = null,
        val modelTier: org.ethereumphone.andyclaw.llm.ModelTier? = null,
        val createdAt: Long = System.currentTimeMillis(),
        var correctionCount: Int = 0,
    ) {
        /** Convert to serializable DTO. */
        fun toEntry(): CachedRoutingEntry = CachedRoutingEntry(
            skills = skills.toSet(),
            tools = tools.toSet(),
            budget = budget?.name?.lowercase(),
            difficulty = modelTier?.name?.lowercase(),
            createdAt = createdAt,
            correctionCount = correctionCount,
        )
    }

    /** Convert serializable DTO back to mutable in-memory form. */
    private fun CachedRoutingEntry.toMutable(): CachedRouting = CachedRouting(
        skills = skills.toMutableSet(),
        tools = tools.toMutableSet(),
        budget = RoutingBudget.fromString(budget),
        modelTier = org.ethereumphone.andyclaw.llm.ModelTier.fromString(difficulty),
        createdAt = createdAt,
        correctionCount = correctionCount,
    )

    /**
     * LRU cache: normalized message -> LLM-routed skills and tools.
     * Repeat/similar queries hit this cache instead of making another LLM call.
     * Entries are mutable so the feedback loop can add missed skills/tools.
     * Entries have TTL and correction count limits.
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

    /** Cached skill catalog string for LLM routing (rebuilt when skill set or tier changes). */
    private var cachedCatalog: String? = null
    private var cachedCatalogKey: Set<String>? = null
    private var cachedCatalogTier: Tier? = null

    /** Last routing context for the feedback loop. */
    private var lastRoutedMessageKey: String? = null
    private var lastRoutedSkillIds: Set<String>? = null

    /** Background scope for fire-and-forget tasks. */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val jsonCodec = Json { ignoreUnknownKeys = true }

    init {
        loadRoutingCache()
        loadEmbeddingCache()
        loadMetrics()
        autoGenerateKeywords()
    }

    // ── Cache utilities ───────────────────────────────────────────────

    /**
     * Guards against false-positive fuzzy cache matches by checking that the
     * normalized messages share at least one content word (after removing stopwords).
     */
    private fun contentWordsOverlap(a: String, b: String): Boolean {
        val stopwords = setOf("turn", "on", "off", "set", "get", "the", "a", "an", "my",
            "please", "can", "you", "it", "is", "to", "for", "and", "or", "in", "me",
            "do", "how", "what", "show", "check", "make", "i", "want", "send")
        val wordsA = a.split(" ").filter { it !in stopwords && it.length > 2 }.toSet()
        val wordsB = b.split(" ").filter { it !in stopwords && it.length > 2 }.toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return true // can't verify, allow
        return wordsA.intersect(wordsB).isNotEmpty()
    }

    // ── Intent classification ─────────────────────────────────────────

    /**
     * Classifies the intent of a message. Returns [MessageIntent.CONVERSATIONAL]
     * when the message matches social/greeting patterns AND no skill keywords match.
     */
    private fun classifyIntent(messageLower: String, allEnabledSkillIds: Set<String>): MessageIntent {
        val keywordMatches = computeKeywordMatches(messageLower, allEnabledSkillIds)
        if (keywordMatches.isEmpty() && CONVERSATIONAL_PATTERNS.any { it.containsMatchIn(messageLower) }) {
            return MessageIntent.CONVERSATIONAL
        }
        return MessageIntent.COMMAND
    }

    // ── Main routing (synchronous LLM-first) ───────────────────────────

    /**
     * Routes skills and tools using the LLM synchronously before falling back to keywords.
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
        val startTime = System.currentTimeMillis()
        val messageLower = userMessage.lowercase()

        // ── Cache invalidation on skill set change ──
        val previousEnabledIds = lastKnownEnabledSkillIds
        if (previousEnabledIds != null && previousEnabledIds != allEnabledSkillIds) {
            evictStaleEntries(allEnabledSkillIds)
            // Regenerate auto-keywords so newly installed skills get keyword coverage
            autoGenerateKeywords()
        }
        lastKnownEnabledSkillIds = allEnabledSkillIds

        // ── Intent classification (conversational fast-path) ──
        val intent = classifyIntent(messageLower, allEnabledSkillIds)
        if (intent == MessageIntent.CONVERSATIONAL) {
            metrics.conversationalShortCircuits++
            val coreOnly = CORE_SKILL_IDS.filter { it in allEnabledSkillIds }.toMutableSet()
            if (tier == Tier.PRIVILEGED) {
                coreOnly.addAll(DGEN1_CORE_SKILL_IDS.filter { it in allEnabledSkillIds })
            }
            for (skillId in activeSessions) {
                if (skillId in allEnabledSkillIds) coreOnly.add(skillId)
            }
            val (convModelId, convMaxTokens) = resolveModelForTier(org.ethereumphone.andyclaw.llm.ModelTier.LIGHT)
            metrics.totalRoutingTimeMs += System.currentTimeMillis() - startTime
            persistMetrics()
            Log.d(TAG, "Conversational short-circuit: '${userMessage.take(60)}...' -> ${coreOnly.size} skills" +
                (convModelId?.let { ", modelOverride=$it" } ?: ""))
            return RoutingResult(coreOnly, null, RoutingBudget.LOW, org.ethereumphone.andyclaw.llm.ModelTier.LIGHT, convModelId, convMaxTokens)
        }

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
        val normalizedMessage = normalizeForCache(userMessage)
        val cachedResult = synchronized(routingCache) {
            routingCache[normalizedMessage]?.let { entry ->
                // TTL check: skip stale or over-corrected entries
                val age = System.currentTimeMillis() - entry.createdAt
                if (age > CACHE_TTL_MS || entry.correctionCount > MAX_CORRECTIONS) {
                    routingCache.remove(normalizedMessage)
                    null
                } else {
                    CachedRouting(
                        skills = entry.skills.toMutableSet(),
                        tools = entry.tools.toMutableSet(),
                        budget = entry.budget,
                        modelTier = entry.modelTier,
                        createdAt = entry.createdAt,
                        correctionCount = entry.correctionCount,
                    )
                }
            }
        }

        var llmRouted = false
        var anyKeywordMatched = false
        var llmTools: Set<String>? = null // specific tools from LLM, null = no tool-level filtering
        var routedBudget: RoutingBudget? = null // budget hint from LLM or cache
        var routedModelTier: org.ethereumphone.andyclaw.llm.ModelTier? = null // difficulty-based model tier from LLM or cache
        var excludedSkills: Set<String> = emptySet()

        if (cachedResult != null) {
            // Exact cache hit — instant
            val validSkills = cachedResult.skills.filter { it in allEnabledSkillIds }
            matched.addAll(validSkills)
            llmTools = cachedResult.tools.takeIf { it.isNotEmpty() }
            routedBudget = cachedResult.budget
            routedModelTier = cachedResult.modelTier
            llmRouted = true
            metrics.cacheHits++
            Log.d(TAG, "Cache hit (exact): '${userMessage.take(60)}...' -> ${validSkills.size} skills, ${cachedResult.tools.size} tools, budget=${cachedResult.budget}, tier=${cachedResult.modelTier}")
        } else {
            // Try fuzzy cache match via embeddings
            val fuzzyResult = findSimilarCacheEntry(normalizedMessage)
            if (fuzzyResult != null) {
                val validSkills = fuzzyResult.skills.filter { it in allEnabledSkillIds }
                matched.addAll(validSkills)
                llmTools = fuzzyResult.tools.takeIf { it.isNotEmpty() }
                routedBudget = fuzzyResult.budget
                routedModelTier = fuzzyResult.modelTier
                llmRouted = true
                metrics.fuzzyCacheHits++
                Log.d(TAG, "Cache hit (fuzzy): '${userMessage.take(60)}...' -> ${validSkills.size} skills, ${fuzzyResult.tools.size} tools, budget=${fuzzyResult.budget}, tier=${fuzzyResult.modelTier}")
            } else {
                metrics.cacheMisses++
                // Synchronous LLM call — wait for the result
                val llmCallStart = System.currentTimeMillis()
                val llmResult = tryLlmRouting(userMessage, allEnabledSkillIds, tier)
                Log.i(TAG, "LLM routing call took ${System.currentTimeMillis() - llmCallStart}ms")
                if (llmResult != null) {
                    matched.addAll(llmResult.skills)
                    llmTools = llmResult.tools.takeIf { it.isNotEmpty() }
                    routedBudget = llmResult.budget
                    routedModelTier = llmResult.modelTier
                    excludedSkills = llmResult.excluded
                    llmRouted = true
                    // Populate cache
                    addCacheEntry(normalizedMessage, CachedRouting(
                        llmResult.skills.toMutableSet(),
                        llmResult.tools.toMutableSet(),
                        llmResult.budget,
                        llmResult.modelTier,
                    ))
                    Log.d(TAG, "LLM sync routed: '${userMessage.take(60)}...' -> ${llmResult.skills.size} skills, ${llmResult.tools.size} tools, budget=${llmResult.budget}, tier=${llmResult.modelTier}")
                } else {
                    // LLM unavailable/failed — fall back to keywords (no tool-level filtering)
                    metrics.keywordFallbacks++
                    val keywordResults = computeKeywordMatches(messageLower, allEnabledSkillIds)
                    if (keywordResults.isNotEmpty()) {
                        matched.addAll(keywordResults)
                        anyKeywordMatched = true
                    }
                    Log.d(TAG, "LLM unavailable, keyword fallback: '${userMessage.take(60)}...' (no modelTier — LLM routing failed)")
                }
            }
        }

        // 6. External/dynamic skills — trust LLM routing when it succeeds (external
        // skills are in the catalog, so the LLM already had a chance to include them).
        // On fallback paths, use description-word overlap to selectively include.
        if (!llmRouted) {
            addExternalSkills(matched, allEnabledSkillIds, messageLower)
        }
        // On LLM-routed paths, external skills the LLM selected are already in `matched`.

        // Apply exclusions from LLM response
        if (excludedSkills.isNotEmpty()) {
            val toExclude = excludedSkills.flatMap { category ->
                EXCLUSION_CATEGORY_MAP[category] ?: emptySet()
            }.toSet()
            matched.removeAll(toExclude)
            // Never exclude CORE or active session skills
            matched.addAll(coreEnabled)
            if (tier == Tier.PRIVILEGED) {
                matched.addAll(DGEN1_CORE_SKILL_IDS.filter { it in allEnabledSkillIds })
            }
            for (skillId in activeSessions) {
                if (skillId in allEnabledSkillIds) matched.add(skillId)
            }
        }

        // 7a. Expand skill dependencies (only in non-AGGRESSIVE modes)
        val mode = routingModeProvider?.invoke() ?: RoutingMode.STANDARD
        if (mode != RoutingMode.STRICT) {
            expandDependencies(matched, allEnabledSkillIds)
        }

        // Track for feedback loop
        lastRoutedMessageKey = normalizedMessage
        lastRoutedSkillIds = matched.toSet()

        // 7b. Smart fallback when nothing matched beyond CORE/sessions/conversation
        if (!anyKeywordMatched && !hasConversationContext && !hasActiveSessions && !llmRouted) {
            val embeddingResult = tryEmbeddingFallback(userMessage, allEnabledSkillIds, matched)
            if (embeddingResult != null) {
                metrics.embeddingFallbacks++
                metrics.totalRoutingTimeMs += System.currentTimeMillis() - startTime
                persistMetrics()
                Log.d(TAG, "Embedding fallback: '${userMessage.take(60)}...' -> ${embeddingResult.size} skills (no modelTier — fallback path)")
                return RoutingResult(embeddingResult, null)
            }

            val frequencyResult = frequencyBiasedFallback(allEnabledSkillIds, matched, messageLower)
            if (frequencyResult != null) {
                metrics.frequencyFallbacks++
                metrics.totalRoutingTimeMs += System.currentTimeMillis() - startTime
                persistMetrics()
                Log.d(TAG, "Frequency fallback: '${userMessage.take(60)}...' -> ${frequencyResult.size} skills (no modelTier — fallback path)")
                return RoutingResult(frequencyResult, null)
            }

            // Last resort: send everything — better to spend extra tokens than give a bad answer
            metrics.fullFallbacks++
            metrics.totalRoutingTimeMs += System.currentTimeMillis() - startTime
            persistMetrics()
            Log.d(TAG, "Full fallback: '${userMessage.take(60)}...' -> all ${allEnabledSkillIds.size} skills (no modelTier — fallback path)")
            return RoutingResult(allEnabledSkillIds, null)
        }

        // Build the allowedTools set based on routing mode.
        val allowedTools = buildAllowedTools(mode, llmTools, matched, alwaysFullToolSkillIds, allEnabledSkillIds, tier)

        // ── Model routing: resolve model for the classified difficulty tier ──
        val modelResolveStart = System.currentTimeMillis()
        val (resolvedModelId, resolvedMaxTokens) = resolveModelForTier(routedModelTier)
        val modelResolveMs = System.currentTimeMillis() - modelResolveStart
        if (modelResolveMs > 50) {
            Log.w(TAG, "ModelRouting | resolveModelForTier took ${modelResolveMs}ms (tier=$routedModelTier)")
        } else {
            Log.d(TAG, "ModelRouting | resolveModelForTier took ${modelResolveMs}ms (tier=$routedModelTier)")
        }

        val totalRoutingMs = System.currentTimeMillis() - startTime
        metrics.totalRoutingTimeMs += totalRoutingMs
        persistMetrics()
        Log.i(TAG, "Total routing pipeline took ${totalRoutingMs}ms")
        Log.d(TAG, "Routed '${userMessage.take(60)}...' -> ${matched.size} skills" +
            (allowedTools?.let { ", ${it.size} tools" } ?: ", all tools") +
            ", budget=$routedBudget, modelTier=$routedModelTier" +
            (resolvedModelId?.let { ", modelOverride=$it" } ?: "") +
            ": ${matched.joinToString()}")
        return RoutingResult(matched, allowedTools, routedBudget, routedModelTier, resolvedModelId, resolvedMaxTokens)
    }

    // ── Model routing resolution ─────────────────────────────────────

    /**
     * Resolves a concrete model ID + maxTokens for the given difficulty [tier].
     * Returns (null, null) when model routing is disabled, the tier is null,
     * or the user's default model already matches the requested tier.
     *
     * Resolution order:
     * 1. User per-tier override from settings (highest priority)
     * 2. OpenRouter dynamic selection via [OpenRouterModelRegistry] (for OpenRouter/ethOS providers)
     * 3. Static tier mapping via [AnthropicModels.forTier] (for other providers)
     */
    private suspend fun resolveModelForTier(
        tier: org.ethereumphone.andyclaw.llm.ModelTier?,
    ): Pair<String?, Int?> {
        if (tier == null) return null to null

        val config = modelRoutingConfigProvider?.invoke()
        if (config == null || !config.enabled) {
            Log.d(TAG, "ModelRouting | disabled or no config, skipping model resolution")
            return null to null
        }

        val provider = config.providerProvider?.invoke()
        val defaultModelId = config.defaultModelIdProvider?.invoke() ?: ""

        // 1. Check user per-tier override
        val userOverride = config.tierModelOverrideProvider?.invoke(tier) ?: ""
        if (userOverride.isNotBlank()) {
            val overrideModel = AnthropicModels.fromModelId(userOverride)
            val maxTokens = overrideModel?.maxTokens ?: 8192
            Log.i(TAG, "ModelRouting | tier=$tier -> user override: $userOverride " +
                "(${overrideModel?.name ?: "unknown"}, maxTokens=$maxTokens)")
            return userOverride to maxTokens
        }

        // 2. For OpenRouter / ethOS Premium: use dynamic model registry
        if (provider == org.ethereumphone.andyclaw.llm.LlmProvider.OPEN_ROUTER ||
            provider == org.ethereumphone.andyclaw.llm.LlmProvider.ETHOS_PREMIUM
        ) {
            val registry = config.registry
            if (registry == null) {
                Log.d(TAG, "ModelRouting | tier=$tier but no registry available, using default")
                return null to null
            }

            // Ensure the registry has models loaded (fetches from API if cache is stale/empty)
            val refreshStart = System.currentTimeMillis()
            try {
                registry.refreshIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "ModelRouting | registry refresh failed: ${e.message}")
            }
            val refreshMs = System.currentTimeMillis() - refreshStart
            Log.d(TAG, "ModelRouting | registry.refreshIfNeeded() took ${refreshMs}ms")

            val recommended = registry.getBestModelForTier(tier, defaultModelId)
            if (recommended != null && recommended.modelId != defaultModelId) {
                val defaultModel = AnthropicModels.fromModelId(defaultModelId)
                val maxTokens = recommended.maxCompletionTokens.coerceAtMost(defaultModel?.maxTokens ?: 8192)
                Log.i(TAG, "ModelRouting | tier=$tier -> ${recommended.modelId} " +
                    "(${recommended.displayName}, \$${String.format("%.2f", recommended.promptPricePerM)}/M prompt, maxTokens=$maxTokens)")
                return recommended.modelId to maxTokens
            }
            if (recommended == null) {
                Log.w(TAG, "ModelRouting | tier=$tier but registry has no models for this tier, using default")
            } else {
                Log.d(TAG, "ModelRouting | tier=$tier, default $defaultModelId already optimal for tier")
            }
            return null to null
        }

        // 3. For other providers: use static tier mappings
        if (provider != null) {
            val tieredModel = AnthropicModels.forTier(tier, provider)
            val defaultModel = AnthropicModels.fromModelId(defaultModelId)
            if (tieredModel != null && tieredModel != defaultModel) {
                Log.i(TAG, "ModelRouting | tier=$tier, provider=$provider -> ${tieredModel.modelId} (${tieredModel.name})")
                return tieredModel.modelId to tieredModel.maxTokens
            }
            Log.d(TAG, "ModelRouting | tier=$tier, provider=$provider, default $defaultModelId already matches tier")
        }

        return null to null
    }

    /**
     * Builds the allowedTools set based on routing mode.
     * STANDARD: all tools from matched skills + SKILL_DEPENDENCIES expansion.
     * BALANCED: all tools from matched skills + TOOL_DEPENDENCIES for cross-skill expansion
     *   (safe within a skill, precise across skills).
     * STRICT: only LLM tool picks + CORE/session tools, no expansion.
     */
    private fun buildAllowedTools(
        mode: RoutingMode,
        llmTools: Set<String>?,
        matched: MutableSet<String>,
        alwaysFullToolSkillIds: Set<String>,
        allEnabledSkillIds: Set<String>,
        tier: Tier,
    ): Set<String>? {
        if (llmTools == null) return null

        return when (mode) {
            RoutingMode.STRICT -> {
                // Only LLM tool picks + always-on tools
                val filtered = llmTools.toMutableSet()
                skillRegistry?.getAll()
                    ?.filter { it.id in alwaysFullToolSkillIds }
                    ?.forEach { skill ->
                        skill.baseManifest.tools.forEach { filtered.add(it.name) }
                        if (tier == Tier.PRIVILEGED) {
                            skill.privilegedManifest?.tools?.forEach { filtered.add(it.name) }
                        }
                    }
                for ((skillId, toolNames) in ALWAYS_INCLUDE_TOOLS) {
                    if (skillId in allEnabledSkillIds) {
                        filtered.addAll(toolNames)
                        matched.add(skillId)
                    }
                }
                filtered
            }
            RoutingMode.BALANCED -> {
                // All tools from matched skills (safe within a skill — the routing LLM
                // picks skills correctly but can't distinguish between similar tools
                // like get_user_wallet_address vs get_agent_wallet_address).
                // Cross-skill expansion uses TOOL_DEPENDENCIES (more precise than
                // SKILL_DEPENDENCIES — pulls in specific tools, not entire skills).
                val allTools = llmTools.toMutableSet()
                // Include all tools from every matched skill
                skillRegistry?.getAll()
                    ?.filter { it.id in matched }
                    ?.forEach { skill ->
                        skill.baseManifest.tools.forEach { allTools.add(it.name) }
                        if (tier == Tier.PRIVILEGED) {
                            skill.privilegedManifest?.tools?.forEach { allTools.add(it.name) }
                        }
                    }
                // Expand cross-skill tools via TOOL_DEPENDENCIES
                val expanded = mutableSetOf<String>()
                for (tool in allTools.toSet()) {
                    TOOL_DEPENDENCIES[tool]?.let { expanded.addAll(it) }
                }
                allTools.addAll(expanded)
                // For each dependency tool, also include the skill that owns it
                for (depTool in expanded) {
                    val skill = skillRegistry?.findSkillForTool(depTool, tier)
                    if (skill != null && skill.id in allEnabledSkillIds) {
                        matched.add(skill.id)
                    }
                }
                for ((skillId, toolNames) in ALWAYS_INCLUDE_TOOLS) {
                    if (skillId in allEnabledSkillIds) {
                        allTools.addAll(toolNames)
                        matched.add(skillId)
                    }
                }
                allTools
            }
            RoutingMode.STANDARD -> {
                // Include all tools from every matched skill (broadest)
                val allTools = llmTools.toMutableSet()
                skillRegistry?.getAll()
                    ?.filter { it.id in matched }
                    ?.forEach { skill ->
                        skill.baseManifest.tools.forEach { allTools.add(it.name) }
                        if (tier == Tier.PRIVILEGED) {
                            skill.privilegedManifest?.tools?.forEach { allTools.add(it.name) }
                        }
                    }
                for ((skillId, toolNames) in ALWAYS_INCLUDE_TOOLS) {
                    if (skillId in allEnabledSkillIds) {
                        allTools.addAll(toolNames)
                        matched.add(skillId)
                    }
                }
                allTools
            }
        }
    }

    // ── Keyword matching ──────────────────────────────────────────────

    /**
     * Word-boundary keyword-based skill matching. Returns the set of skill IDs
     * matched by keywords in the message. Uses pre-compiled regexes to avoid
     * substring false positives (e.g. "power" no longer matches "powerful").
     */
    private fun computeKeywordMatches(
        messageLower: String,
        allEnabledSkillIds: Set<String>,
    ): Set<String> {
        val result = mutableSetOf<String>()
        // Check manual keyword regexes (word-boundary matching)
        for ((regex, skillIds) in keywordRegexMap) {
            if (regex.containsMatchIn(messageLower)) {
                for (skillId in skillIds) {
                    if (skillId in allEnabledSkillIds) {
                        result.add(skillId)
                    }
                }
            }
        }
        // Check auto-generated keywords from skill descriptions
        for ((keyword, skillIds) in generatedKeywordMap) {
            if (Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(messageLower)) {
                for (skillId in skillIds) {
                    if (skillId in allEnabledSkillIds && skillId !in result) {
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
                metrics.routingMisses++
                Log.d(TAG, "Routing miss: skill '${skill.id}' tool '$toolName' used but not routed for '${msgKey.take(60)}...'")
                var updated = false
                synchronized(routingCache) {
                    routingCache[msgKey]?.let { entry ->
                        entry.skills.add(skill.id)
                        entry.tools.add(toolName)
                        entry.correctionCount++
                        updated = true
                    }
                }
                if (updated) persistRoutingCache()
            } else if (msgKey != null) {
                // Skill was routed but tool might not have been — add tool to cache
                synchronized(routingCache) {
                    routingCache[msgKey]?.let { entry ->
                        if (toolName !in entry.tools && entry.tools.isNotEmpty()) {
                            metrics.toolMisses++
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

    /** Get current routing metrics. */
    fun getMetrics(): RoutingMetrics = metrics.copy()

    /** Reset all routing metrics to zero. */
    fun resetMetrics() {
        metrics.cacheHits = 0
        metrics.cacheMisses = 0
        metrics.fuzzyCacheHits = 0
        metrics.llmRoutingCalls = 0
        metrics.llmRoutingFailures = 0
        metrics.keywordFallbacks = 0
        metrics.embeddingFallbacks = 0
        metrics.frequencyFallbacks = 0
        metrics.fullFallbacks = 0
        metrics.conversationalShortCircuits = 0
        metrics.routingMisses = 0
        metrics.toolMisses = 0
        metrics.overRoutes = 0
        metrics.totalRoutingTimeMs = 0
        persistMetrics()
    }

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
     * Includes tool names and descriptions under each skill so the LLM can route at tool level.
     * Excludes only CORE and dGEN1_CORE (those are always included regardless).
     * External skills (clawhub:, ai:, ext:) are included so the LLM can route them.
     * Heavy skills are annotated with [heavy] so the LLM is conservative about them.
     *
     * Tier-aware: on PRIVILEGED tier, uses the privileged manifest description and
     * tools when available.
     *
     * The catalog is cached and rebuilt when the enabled skill set or tier changes.
     */
    /**
     * Semantic category groupings for the skill catalog. Skills are grouped by
     * domain so the routing LLM can narrow by category before selecting skills,
     * improving accuracy on large label spaces (40+ skills).
     */
    private val SKILL_CATEGORIES: List<Pair<String, List<String>>> = listOf(
        "DEVICE" to listOf("connectivity", "audio", "screen", "camera", "device_power", "device_info", "settings"),
        "COMMS" to listOf("sms", "phone", "gmail", "telegram", "messenger", "contacts", "notifications"),
        "CRYPTO" to listOf("wallet", "swap", "token_lookup", "ens", "bankr_trading"),
        "PRODUCTIVITY" to listOf("calendar", "google_calendar", "reminders", "cronjobs", "sheets", "drive"),
        "APPS" to listOf("apps", "aurora_store", "package_manager", "agent_display"),
        "FILES" to listOf("storage", "filesystem", "clipboard"),
        "META" to listOf("skill-creator", "skill-refinement", "clawhub", "cli-tool-manager"),
        "OTHER" to listOf("web_search", "location", "shell", "termux", "screen_time", "proactive_agent"),
    )

    /** Tool names that are self-explanatory and don't need descriptions in the catalog. */
    private val SELF_EXPLANATORY_TOOLS = setOf(
        "send_sms", "read_sms", "get_contacts", "search_contacts", "get_contact_details",
        "take_photo", "get_device_info", "get_current_location", "list_events", "create_event",
        "delete_event", "web_search", "fetch_webpage", "list_installed_apps", "launch_app",
        "get_audio_state", "set_volume", "read_screen", "read_clipboard", "write_clipboard",
        "read_file", "write_file", "list_directory", "create_reminder", "list_reminders",
        "cancel_reminder", "make_call", "get_call_log", "uninstall_app", "list_notifications",
        "get_connectivity_status", "lock_screen", "reboot_device",
        "list_telegram_chats", "send_telegram_message",
        "run_shell_command", "take_screenshot",
    )

    private fun buildSkillCatalog(allEnabledSkillIds: Set<String>, tier: Tier): String {
        // Return cached catalog if the skill set and tier haven't changed
        if (cachedCatalogKey == allEnabledSkillIds && cachedCatalogTier == tier) {
            cachedCatalog?.let { return it }
        }

        val registry = skillRegistry ?: return ""
        val excludeIds = CORE_SKILL_IDS + DGEN1_CORE_SKILL_IDS
        val skillMap = registry.getAll()
            .filter { it.id in allEnabledSkillIds && it.id !in excludeIds }
            .associateBy { it.id }

        val sb = StringBuilder()
        // Emit categorized skills
        val categorized = mutableSetOf<String>()
        for ((category, skillIds) in SKILL_CATEGORIES) {
            val present = skillIds.filter { it in skillMap }
            if (present.isEmpty()) continue
            sb.append("[$category]\n")
            for (skillId in present) {
                val skill = skillMap[skillId] ?: continue
                categorized.add(skillId)
                val desc = if (tier == Tier.PRIVILEGED && skill.privilegedManifest != null) {
                    skill.privilegedManifest!!.description.take(120).trim()
                } else {
                    skill.baseManifest.description.take(120).trim()
                }
                val heavyTag = if (skill.id in HEAVY_SKILL_IDS) " [heavy]" else ""
                val tools = skill.baseManifest.tools +
                    if (tier == Tier.PRIVILEGED) {
                        skill.privilegedManifest?.tools ?: emptyList()
                    } else {
                        emptyList()
                    }
                val toolDescs = tools.joinToString(", ") { tool ->
                    if (tool.name in SELF_EXPLANATORY_TOOLS) {
                        tool.name
                    } else {
                        val toolDesc = tool.description.take(40).trim()
                        if (toolDesc.isNotEmpty()) "${tool.name} ($toolDesc)" else tool.name
                    }
                }
                sb.append("- $skillId: $desc$heavyTag\n  tools: $toolDescs\n")
            }
        }
        // Emit uncategorized skills under [INSTALLED] header so the LLM
        // treats them as first-class routable skills, not afterthoughts.
        val uncategorized = skillMap.keys.filter { it !in categorized }
        if (uncategorized.isNotEmpty()) {
            sb.append("[INSTALLED]\n")
            for (skillId in uncategorized) {
                val skill = skillMap[skillId] ?: continue
                val desc = if (tier == Tier.PRIVILEGED && skill.privilegedManifest != null) {
                    skill.privilegedManifest!!.description.take(120).trim()
                } else {
                    skill.baseManifest.description.take(120).trim()
                }
                val heavyTag = if (skill.id in HEAVY_SKILL_IDS) " [heavy]" else ""
                val tools = skill.baseManifest.tools +
                    if (tier == Tier.PRIVILEGED) {
                        skill.privilegedManifest?.tools ?: emptyList()
                    } else {
                        emptyList()
                    }
                val toolDescs = tools.joinToString(", ") { tool ->
                    if (tool.name in SELF_EXPLANATORY_TOOLS) {
                        tool.name
                    } else {
                        val toolDesc = tool.description.take(40).trim()
                        if (toolDesc.isNotEmpty()) "${tool.name} ($toolDesc)" else tool.name
                    }
                }
                sb.append("- $skillId: $desc$heavyTag\n  tools: $toolDescs\n")
            }
        }

        val result = sb.toString().trimEnd()
        cachedCatalog = result
        cachedCatalogKey = allEnabledSkillIds.toSet()
        cachedCatalogTier = tier
        return result
    }

    /**
     * Constructs a [MessagesRequest] for the routing LLM call.
     * Prompt is structured for small/fast models (3B-8B):
     * - Format constraint first (anchors output for small models)
     * - Categorized skill catalog (reduces cognitive load vs flat lists)
     * - Positive + negative examples (contrastive learning for accuracy)
     * - No exclude field (handled server-side, saves output tokens)
     * - No dependency hints (expanded server-side, saves input tokens)
     */
    private fun buildRoutingRequest(
        userMessage: String,
        catalog: String,
        modelId: String,
    ): MessagesRequest {
        val systemPrompt = buildString {
            // Format constraint FIRST — anchors output for small models
            append("Respond with ONLY a JSON object. No other text, no markdown.\n")
            append("Format: {\"skills\":[...],\"tools\":[...],\"budget\":\"low|medium|high\",\"difficulty\":\"easy|medium|hard\"}\n\n")
            // Role and field definitions
            append("You route user messages to phone assistant skills.\n")
            append("- \"skills\": skill IDs needed (empty array if none needed)\n")
            append("- \"tools\": specific tool names from those skills\n")
            append("- \"budget\": \"low\" (brief/tool-only), \"medium\" (summaries, multi-step), \"high\" (long/creative)\n")
            append("- \"difficulty\": how capable the LLM needs to be:\n")
            append("  \"easy\" = simple commands, factual Q&A, device operations, greetings\n")
            append("  \"medium\" = multi-step tasks, summaries, moderate reasoning, tool chaining\n")
            append("  \"hard\" = complex reasoning, code generation, creative writing, deep analysis, debugging\n\n")
            // Rules — concise, no hedging
            append("Rules:\n")
            append("- Empty skills for greetings, chat, and general knowledge.\n")
            append("- Only include skills when clearly needed — keyword overlap is not enough.\n")
            append("- For similar tools (e.g. get_user_wallet_address vs get_agent_wallet_address), include ALL variants.\n")
            append("- [heavy] skills are expensive — only when clearly relevant.\n")
            append("- difficulty is about REASONING capability, not output length. A long but simple list is \"easy\"; a short but tricky logic puzzle is \"hard\".\n\n")
            // Examples — positive, negative/trap, and multi-intent
            append("Examples:\n")
            // Single skill, simple command
            append("\"send a text to mom\" -> {\"skills\":[\"sms\",\"contacts\"],\"tools\":[\"send_sms\",\"get_contacts\"],\"budget\":\"low\",\"difficulty\":\"easy\"}\n")
            // No skills needed (general knowledge)
            append("\"tell me a joke about wifi\" -> {\"skills\":[],\"tools\":[],\"budget\":\"low\",\"difficulty\":\"easy\"}\n")
            // Negative/trap: "powerful" ≠ device_power
            append("\"how powerful is my phone\" -> {\"skills\":[\"device_info\"],\"tools\":[\"get_device_info\"],\"budget\":\"low\",\"difficulty\":\"easy\"}\n")
            // Negative/trap: "connection" in abstract context ≠ connectivity
            append("\"what's the connection between AI and art\" -> {\"skills\":[],\"tools\":[],\"budget\":\"medium\",\"difficulty\":\"medium\"}\n")
            // Multi-intent: 3 skills
            append("\"text mom I'm late and set a timer for 30 min\" -> {\"skills\":[\"sms\",\"contacts\",\"reminders\"],\"tools\":[\"send_sms\",\"get_contacts\",\"create_reminder\"],\"budget\":\"low\",\"difficulty\":\"easy\"}\n")
            // Web search with summarization
            append("\"search for the latest AI news and summarize\" -> {\"skills\":[\"web_search\"],\"tools\":[\"web_search\"],\"budget\":\"medium\",\"difficulty\":\"medium\"}\n")
            // Heavy skill trigger
            append("\"open youtube and play a video\" -> {\"skills\":[\"agent_display\"],\"tools\":[\"agent_display_create\",\"agent_display_interact\"],\"budget\":\"medium\",\"difficulty\":\"easy\"}\n")
            // Trap: "search contacts" = contacts, NOT web_search
            append("\"search my contacts for John\" -> {\"skills\":[\"contacts\"],\"tools\":[\"search_contacts\"],\"budget\":\"low\",\"difficulty\":\"easy\"}\n")
            // Hard task: code generation
            append("\"write a Python script to scrape stock prices\" -> {\"skills\":[\"web_search\"],\"tools\":[\"web_search\",\"fetch_webpage\"],\"budget\":\"high\",\"difficulty\":\"hard\"}\n")
            // Hard task: analysis
            append("\"analyze the pros and cons of switching to Kotlin Multiplatform\" -> {\"skills\":[],\"tools\":[],\"budget\":\"high\",\"difficulty\":\"hard\"}\n\n")
            // Catalog
            append("Available skills:\n$catalog")
        }
        return MessagesRequest(
            model = modelId,
            maxTokens = 150,
            system = systemPrompt,
            messages = listOf(Message.user(userMessage)),
            temperature = 0f,
            reasoning = ReasoningConfig(effort = "none"),
        )
    }

    /** Parsed result from the routing LLM response. */
    private data class ParsedRouting(
        val skills: Set<String>,
        val tools: Set<String>,
        val budget: RoutingBudget?,
        val excluded: Set<String> = emptySet(),
        val modelTier: org.ethereumphone.andyclaw.llm.ModelTier? = null,
    )

    /**
     * Parses the routing LLM response into skill IDs, tool names, and budget hint.
     * Also parses optional exclusions for backward compatibility (no longer prompted).
     * Supports both the new object format and the legacy array format.
     * Returns null on any parse failure.
     */
    private fun parseRoutingResponse(
        responseText: String,
        allEnabledSkillIds: Set<String>,
    ): ParsedRouting? {
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
                val budgetMatch = Regex("\"budget\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
                val excludeMatch = Regex("\"exclude\"\\s*:\\s*\\[([^\\]]*)\\]").find(cleaned)
                val difficultyMatch = Regex("\"difficulty\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)

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

                val budget = RoutingBudget.fromString(budgetMatch?.groupValues?.get(1))

                val excluded = excludeMatch?.groupValues?.get(1)?.let { arr ->
                    Regex("\"([^\"]+)\"").findAll(arr)
                        .map { it.groupValues[1] }
                        .toSet()
                } ?: emptySet()

                val modelTier = org.ethereumphone.andyclaw.llm.ModelTier.fromString(
                    difficultyMatch?.groupValues?.get(1)
                )

                return ParsedRouting(skillIds, toolNames, budget, excluded, modelTier)
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

            ParsedRouting(ids, emptySet(), null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse routing response: ${e.message}")
            null
        }
    }

    /**
     * Orchestrates the LLM routing pass. Returns parsed routing on success,
     * or null on any failure. Logs token usage for the routing call.
     */
    private suspend fun tryLlmRouting(
        userMessage: String,
        allEnabledSkillIds: Set<String>,
        tier: Tier = Tier.OPEN,
    ): ParsedRouting? {
        metrics.llmRoutingCalls++
        val config = try {
            routingClientProvider?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "LLM routing config unavailable: ${e.message}")
            metrics.llmRoutingFailures++
            null
        }
        if (config == null) {
            metrics.llmRoutingFailures++
            return null
        }

        return try {
            val catalog = buildSkillCatalog(allEnabledSkillIds, tier)
            if (catalog.isBlank()) {
                metrics.llmRoutingFailures++
                return null
            }

            val request = buildRoutingRequest(userMessage, catalog, config.modelId)
            val response = config.client.sendMessage(request)

            // Log routing call token usage
            response.usage?.let { u ->
                Log.d(TAG, "TokenStats | routing_call input=${u.inputTokens} output=${u.outputTokens} total=${u.inputTokens + u.outputTokens}")
            }

            val text = response.content
                .filterIsInstance<ContentBlock.TextBlock>()
                .joinToString("") { it.text }

            Log.d(TAG, "LLM raw response: ${text.take(300)}")
            val parsed = parseRoutingResponse(text, allEnabledSkillIds)
            if (parsed != null) {
                Log.d(TAG, "LLM routed '${userMessage.take(60)}...' -> ${parsed.skills.size} skills, ${parsed.tools.size} tools, budget=${parsed.budget}, tier=${parsed.modelTier}: ${parsed.skills.joinToString()}")
            } else {
                Log.w(TAG, "LLM routing parse failed for '${userMessage.take(60)}...', raw: ${text.take(200)}")
                metrics.llmRoutingFailures++
            }
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "LLM routing failed: ${e.message}")
            metrics.llmRoutingFailures++
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
                        // Cap embedding cache size
                        while (cacheKeyEmbeddings.size > ROUTING_CACHE_MAX_SIZE) {
                            cacheKeyEmbeddings.keys.firstOrNull()?.let { cacheKeyEmbeddings.remove(it) }
                        }
                    }
                    persistEmbeddingCache()
                }
            } catch (_: Exception) {}
        }
    }

    /** Persists the routing cache to SharedPreferences as JSON. */
    private fun persistRoutingCache() {
        val snapshot = synchronized(routingCache) {
            routingCache.mapValues { (_, v) -> v.toEntry() }
        }
        try {
            val json = jsonCodec.encodeToString(snapshot)
            prefs?.edit()?.putString("routing_cache", json)?.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist routing cache: ${e.message}")
        }
    }

    /** Loads the routing cache from SharedPreferences. Pre-warms if empty. */
    private fun loadRoutingCache() {
        val json = prefs?.getString("routing_cache", null)
        if (json != null) {
            try {
                val map: Map<String, CachedRoutingEntry> = jsonCodec.decodeFromString(json)
                synchronized(routingCache) {
                    for ((key, entry) in map) {
                        routingCache[key] = entry.toMutable()
                    }
                }
                Log.d(TAG, "Loaded ${map.size} routing cache entries from disk")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load routing cache: ${e.message}")
            }
        }

        // Pre-warm cache with defaults if persisted cache is empty
        if (routingCache.isEmpty()) {
            preWarmCache()
        }
    }

    /** Populates the routing cache with common phone queries as defaults. */
    private fun preWarmCache() {
        val defaults = mapOf(
            "turn on wifi" to CachedRoutingEntry(setOf("connectivity"), emptySet(), "low"),
            "turn off wifi" to CachedRoutingEntry(setOf("connectivity"), emptySet(), "low"),
            "enable bluetooth" to CachedRoutingEntry(setOf("connectivity"), emptySet(), "low"),
            "turn on airplane mode" to CachedRoutingEntry(setOf("connectivity"), emptySet(), "low"),
            "set timer" to CachedRoutingEntry(setOf("reminders"), emptySet(), "low"),
            "set alarm" to CachedRoutingEntry(setOf("reminders"), emptySet(), "low"),
            "take photo" to CachedRoutingEntry(setOf("camera"), emptySet(), "low"),
            "what's weather like" to CachedRoutingEntry(setOf("web_search"), emptySet(), "low"),
            "check battery level" to CachedRoutingEntry(setOf("device_power"), emptySet(), "low"),
            "show notifications" to CachedRoutingEntry(setOf("notifications"), emptySet(), "low"),
            "send text message" to CachedRoutingEntry(setOf("sms", "contacts"), emptySet(), "low"),
            "call mom" to CachedRoutingEntry(setOf("phone", "contacts"), emptySet(), "low"),
            "set brightness" to CachedRoutingEntry(setOf("screen"), emptySet(), "low"),
            "turn up volume" to CachedRoutingEntry(setOf("audio"), emptySet(), "low"),
            "check calendar" to CachedRoutingEntry(setOf("calendar", "google_calendar"), emptySet(), "low"),
            "take screenshot" to CachedRoutingEntry(setOf("agent_display", "screen"), emptySet(), "low"),
            "send email" to CachedRoutingEntry(setOf("gmail", "contacts"), emptySet(), "low"),
            "check wallet balance" to CachedRoutingEntry(setOf("wallet", "token_lookup"), emptySet(), "low"),
            "open settings" to CachedRoutingEntry(setOf("agent_display", "apps"), emptySet(), "low"),
            "search restaurants nearby" to CachedRoutingEntry(setOf("web_search"), emptySet(), "medium"),
        )
        synchronized(routingCache) {
            for ((msg, entry) in defaults) {
                val key = normalizeForCache(msg)
                routingCache[key] = entry.toMutable()
            }
        }
        Log.d(TAG, "Pre-warmed cache with ${defaults.size} default entries")
    }

    // ── Fuzzy cache matching ─────────────────────────────────────────

    /**
     * Finds a similar cached routing entry using embedding cosine similarity.
     * Returns null if no similar entry is found above [CACHE_SIMILARITY_THRESHOLD],
     * if the embedding provider is unavailable, or if content-word overlap fails.
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

        // Content word overlap guard against false positives
        if (bestKey != null && !contentWordsOverlap(normalizedMessage, bestKey)) {
            Log.d(TAG, "Fuzzy match rejected (no content word overlap): '$normalizedMessage' vs '$bestKey'")
            return null
        }

        return bestKey?.let { key ->
            synchronized(routingCache) {
                routingCache[key]?.let { entry ->
                    // TTL check on fuzzy matches too
                    val age = System.currentTimeMillis() - entry.createdAt
                    if (age > CACHE_TTL_MS || entry.correctionCount > MAX_CORRECTIONS) {
                        routingCache.remove(key)
                        null
                    } else {
                        CachedRouting(
                            skills = entry.skills.toMutableSet(),
                            tools = entry.tools.toMutableSet(),
                            budget = entry.budget,
                            modelTier = entry.modelTier,
                            createdAt = entry.createdAt,
                            correctionCount = entry.correctionCount,
                        )
                    }
                }
            }
        }?.also {
            Log.d(TAG, "Fuzzy cache match: similarity=${String.format("%.3f", bestSimilarity)} for '${normalizedMessage.take(60)}...'")
        }
    }

    // ── Embedding-based fallback ──────────────────────────────────────

    /**
     * Uses semantic embeddings to find the most relevant skills for the message.
     * Applies gap threshold: stops adding skills when similarity drops by more
     * than [EMBEDDING_GAP_THRESHOLD] from the previous entry.
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
            var previousSimilarity = 1.0f
            for ((skillId, similarity) in ranked) {
                if (added >= EMBEDDING_TOP_K) break
                // Gap threshold: stop if similarity drops significantly (unless below min results)
                if (added >= EMBEDDING_MIN_RESULTS && previousSimilarity - similarity > EMBEDDING_GAP_THRESHOLD) break
                if (similarity >= EMBEDDING_MIN_SIMILARITY || added < EMBEDDING_MIN_RESULTS) {
                    result.add(skillId)
                    added++
                }
                previousSimilarity = similarity
            }

            // External skills are already ranked by embedding similarity above,
            // so only add any remaining externals via keyword/description matching
            addExternalSkills(result, allEnabledSkillIds, userMessage.lowercase())
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
     * Returns null if no usage history exists (falls through to reduced fallback).
     */
    private fun frequencyBiasedFallback(
        allEnabledSkillIds: Set<String>,
        coreMatched: Set<String>,
        messageLower: String? = null,
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
        // External skills not in top-frequency get keyword/description filtering
        addExternalSkills(result, allEnabledSkillIds, messageLower)
        return result
    }

    // ── Cache maintenance ─────────────────────────────────────────────

    /**
     * Evicts cache entries whose skill sets have zero intersection with the
     * current enabled skill set. Called when the enabled set changes.
     */
    private fun evictStaleEntries(currentEnabledSkillIds: Set<String>) {
        var evicted = 0
        synchronized(routingCache) {
            val iterator = routingCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.skills.none { it in currentEnabledSkillIds }) {
                    iterator.remove()
                    evicted++
                }
            }
        }
        if (evicted > 0) {
            Log.d(TAG, "Evicted $evicted stale cache entries after skill set change")
            persistRoutingCache()
        }
    }

    // ── Auto keyword generation ────────────────────────────────────────

    /**
     * Auto-generates keywords from skill descriptions and tool names.
     * Supplements the manual KEYWORD_MAP to ensure new/external skills get
     * keyword coverage without manual updates.
     */
    private fun autoGenerateKeywords() {
        val registry = skillRegistry ?: return
        val manualKeywords = KEYWORD_MAP.keys
        val stopWords = setOf("the", "and", "for", "with", "that", "this", "from", "are",
            "was", "were", "been", "being", "have", "has", "had", "does", "did", "will",
            "would", "could", "should", "may", "might", "can", "shall", "not", "but",
            "its", "your", "our", "their", "which", "when", "where", "who", "what", "how")

        val generated = mutableMapOf<String, MutableSet<String>>()
        for (skill in registry.getAll()) {
            if (skill.id in CORE_SKILL_IDS || skill.id in DGEN1_CORE_SKILL_IDS) continue
            val text = "${skill.baseManifest.description} ${skill.baseManifest.tools.joinToString(" ") { it.name.replace("_", " ") }}"
            val words = text.lowercase().split(Regex("[\\s_]+"))
                .filter { it.length > 3 && it !in stopWords && it !in manualKeywords }
                .distinct()
            for (word in words) {
                generated.getOrPut(word) { mutableSetOf() }.add(skill.id)
            }
        }
        generatedKeywordMap = generated
        Log.d(TAG, "Auto-generated ${generated.size} keywords from skill descriptions")
    }

    // ── Metrics persistence ────────────────────────────────────────────

    private fun persistMetrics() {
        val editor = prefs?.edit() ?: return
        editor.putInt("routing_metrics_cache_hits", metrics.cacheHits)
        editor.putInt("routing_metrics_cache_misses", metrics.cacheMisses)
        editor.putInt("routing_metrics_fuzzy_cache_hits", metrics.fuzzyCacheHits)
        editor.putInt("routing_metrics_llm_calls", metrics.llmRoutingCalls)
        editor.putInt("routing_metrics_llm_failures", metrics.llmRoutingFailures)
        editor.putInt("routing_metrics_keyword_fallbacks", metrics.keywordFallbacks)
        editor.putInt("routing_metrics_embedding_fallbacks", metrics.embeddingFallbacks)
        editor.putInt("routing_metrics_frequency_fallbacks", metrics.frequencyFallbacks)
        editor.putInt("routing_metrics_full_fallbacks", metrics.fullFallbacks)
        editor.putInt("routing_metrics_conversational", metrics.conversationalShortCircuits)
        editor.putInt("routing_metrics_routing_misses", metrics.routingMisses)
        editor.putInt("routing_metrics_tool_misses", metrics.toolMisses)
        editor.putInt("routing_metrics_over_routes", metrics.overRoutes)
        editor.putLong("routing_metrics_total_time", metrics.totalRoutingTimeMs)
        editor.apply()
    }

    private fun loadMetrics() {
        val p = prefs ?: return
        metrics.cacheHits = p.getInt("routing_metrics_cache_hits", 0)
        metrics.cacheMisses = p.getInt("routing_metrics_cache_misses", 0)
        metrics.fuzzyCacheHits = p.getInt("routing_metrics_fuzzy_cache_hits", 0)
        metrics.llmRoutingCalls = p.getInt("routing_metrics_llm_calls", 0)
        metrics.llmRoutingFailures = p.getInt("routing_metrics_llm_failures", 0)
        metrics.keywordFallbacks = p.getInt("routing_metrics_keyword_fallbacks", 0)
        metrics.embeddingFallbacks = p.getInt("routing_metrics_embedding_fallbacks", 0)
        metrics.frequencyFallbacks = p.getInt("routing_metrics_frequency_fallbacks", 0)
        metrics.fullFallbacks = p.getInt("routing_metrics_full_fallbacks", 0)
        metrics.conversationalShortCircuits = p.getInt("routing_metrics_conversational", 0)
        metrics.routingMisses = p.getInt("routing_metrics_routing_misses", 0)
        metrics.toolMisses = p.getInt("routing_metrics_tool_misses", 0)
        metrics.overRoutes = p.getInt("routing_metrics_over_routes", 0)
        metrics.totalRoutingTimeMs = p.getLong("routing_metrics_total_time", 0)
    }

    // ── Embedding cache persistence ──────────────────────────────────

    @Serializable
    private data class EmbeddingCacheEntry(
        val key: String,
        val embedding: List<Float>,
    )

    private fun persistEmbeddingCache() {
        val dir = filesDir ?: return
        try {
            val entries = synchronized(cacheKeyEmbeddings) {
                cacheKeyEmbeddings.map { (k, v) -> EmbeddingCacheEntry(k, v.toList()) }
            }
            val json = jsonCodec.encodeToString(entries)
            File(dir, "routing-embeddings.json").writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist embedding cache: ${e.message}")
        }
    }

    private fun loadEmbeddingCache() {
        val dir = filesDir ?: return
        try {
            val file = File(dir, "routing-embeddings.json")
            if (!file.exists()) return
            val json = file.readText()
            val entries: List<EmbeddingCacheEntry> = jsonCodec.decodeFromString(json)
            synchronized(cacheKeyEmbeddings) {
                for (entry in entries.take(ROUTING_CACHE_MAX_SIZE)) {
                    cacheKeyEmbeddings[entry.key] = entry.embedding.toFloatArray()
                }
            }
            Log.d(TAG, "Loaded ${entries.size} embedding cache entries from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load embedding cache: ${e.message}")
        }
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

    /**
     * Checks whether a skill ID is an external/dynamic skill (clawhub, AI-created, extension).
     */
    private fun isExternalSkill(skillId: String): Boolean =
        skillId.startsWith("clawhub:") || skillId.startsWith("ai:") || skillId.startsWith("ext:")

    /**
     * Add external/dynamic skills (clawhub:, ai:, ext:) to the result set.
     * Uses a two-pass filter:
     * 1. Tool/skill name word-boundary match against the message (cheap, accurate)
     * 2. Description content-word overlap (broader, catches synonyms)
     * Falls back to including all externals only when no registry is available.
     */
    private fun addExternalSkills(
        result: MutableSet<String>,
        allEnabledSkillIds: Set<String>,
        messageLower: String? = null,
    ) {
        val registry = skillRegistry
        for (skillId in allEnabledSkillIds) {
            if (!isExternalSkill(skillId)) continue
            if (skillId in result) continue // already routed (e.g. by LLM or cache)

            if (messageLower == null || registry == null) {
                // No message context or no registry — include all (safe fallback)
                result.add(skillId)
                continue
            }

            val skill = registry.getAll().find { it.id == skillId }
            if (skill == null) {
                // Unknown external skill — include to be safe
                result.add(skillId)
                continue
            }

            // Pass 1: Check tool names with word-boundary matching (most precise).
            // E.g. a skill with tool "get_weather" matches "weather" in the message.
            val toolWords = skill.baseManifest.tools.flatMap { tool ->
                tool.name.split("_").filter { it.length > 3 }
            }
            if (toolWords.any { word -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(messageLower) }) {
                result.add(skillId)
                continue
            }

            // Pass 2: Check description content words (broader coverage).
            // Filter out common stop words and short words to reduce false positives.
            val desc = skill.baseManifest.description.lowercase()
            val stopWords = setOf("the", "and", "for", "with", "that", "this", "from",
                "are", "was", "will", "can", "use", "used", "using", "also", "your",
                "into", "have", "has", "been", "does", "each", "some", "about")
            val descWords = desc.split(Regex("[\\s,./]+"))
                .filter { it.length > 3 && it !in stopWords }
                .distinct()
            if (descWords.any { word -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(messageLower) }) {
                result.add(skillId)
            }
        }
    }

    /**
     * Expands the routed skill set by resolving transitive dependencies from
     * [SKILL_DEPENDENCIES]. Only adds dependencies present in [allEnabledSkillIds].
     */
    private fun expandDependencies(
        matched: MutableSet<String>,
        allEnabledSkillIds: Set<String>,
    ) {
        var frontier = matched.toSet()
        while (true) {
            val newDeps = mutableSetOf<String>()
            for (skillId in frontier) {
                SKILL_DEPENDENCIES[skillId]?.forEach { dep ->
                    if (dep in allEnabledSkillIds && dep !in matched) {
                        newDeps.add(dep)
                    }
                }
            }
            if (newDeps.isEmpty()) break
            matched.addAll(newDeps)
            frontier = newDeps
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
