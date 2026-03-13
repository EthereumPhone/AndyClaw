package org.ethereumphone.andyclaw.skills

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.ethereumphone.andyclaw.memory.embedding.EmbeddingProvider
import kotlin.math.sqrt

/**
 * Routes user messages to the minimal set of skills needed, reducing token overhead
 * by 80–90% on typical queries. Skills are tiered:
 *
 * - CORE: always included (device_info, apps, code_execution, shell, memory, clipboard, settings)
 * - DGEN1_CORE: also always included on dGEN1 devices (led_matrix, terminal_text, agent_display)
 * - HEAVY: only included when keywords match (skill-creator, clawhub, bankr_trading, etc.)
 * - STANDARD: everything else, included when keywords match
 *
 * Enhanced fallback (when no keywords match beyond CORE):
 * 1. Embedding-based semantic matching (if EmbeddingProvider available)
 * 2. Usage-frequency biased selection (if usage history exists)
 * 3. All enabled skills (original fallback)
 *
 * Additional features:
 * - Conversation-aware: skills from previous turn's tools are kept
 * - Session tracking: active sessions (e.g. virtual display) keep their skills included
 */
class SkillRouter(
    context: Context? = null,
    private val skillRegistry: NativeSkillRegistry? = null,
    private val embeddingProvider: EmbeddingProvider? = null,
) {
    companion object {
        private const val TAG = "SkillRouter"
        private const val PREFS_NAME = "andyclaw_skill_router"
        private const val EMBEDDING_TOP_K = 10
        private const val EMBEDDING_MIN_SIMILARITY = 0.2f
        private const val EMBEDDING_MIN_RESULTS = 3
        private const val FREQUENCY_TOP_K = 15
    }

    /** Skills always sent regardless of user message. */
    private val CORE_SKILL_IDS = setOf(
        "device_info",
        "apps",
        "code_execution",
        "shell",
        "memory",
        "clipboard",
        "settings",
    )

    /** Skills always sent on dGEN1 (PRIVILEGED tier): hardware + app automation. */
    private val DGEN1_CORE_SKILL_IDS = setOf(
        "led_matrix",
        "terminal_text",
        "agent_display",
    )

    /** Large/expensive skills only sent when explicitly keyword-matched. */
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
     * Map of keyword/phrase → set of skill IDs that keyword should activate.
     * Keywords are matched case-insensitively against the user message.
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

        // Agent display (HEAVY — but also dGEN1 CORE)
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

    // ── Main routing ──────────────────────────────────────────────────

    /**
     * Given a user message, device tier, the full set of enabled skill IDs,
     * and optionally the tool names from the previous conversation turn,
     * returns the subset of skill IDs that should be sent to the LLM.
     *
     * Routing pipeline:
     * 1. CORE skills always included
     * 2. dGEN1 CORE on PRIVILEGED tier
     * 3. Conversation-aware: skills from previous turn's tools
     * 4. Session tracking: skills with active sessions
     * 5. Keyword matching
     * 6. External skills (clawhub:*, ai:*, ext:*) always included
     * 7. Smart fallback if no keywords/context matched
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

        // 5. Keyword matching
        var anyKeywordMatched = false
        for ((keyword, skillIds) in KEYWORD_MAP) {
            if (messageLower.contains(keyword)) {
                for (skillId in skillIds) {
                    if (skillId in allEnabledSkillIds) {
                        matched.add(skillId)
                        anyKeywordMatched = true
                    }
                }
            }
        }

        // 6. External/dynamic skills always included (can't keyword-match unknown content)
        for (skillId in allEnabledSkillIds) {
            if (skillId.startsWith("clawhub:") || skillId.startsWith("ai:") || skillId.startsWith("ext:")) {
                matched.add(skillId)
            }
        }

        // 7. Smart fallback when no keywords match and no conversation/session context
        if (!anyKeywordMatched && !hasConversationContext && !hasActiveSessions) {
            // Try embedding-based semantic matching first
            val embeddingResult = tryEmbeddingFallback(userMessage, allEnabledSkillIds, matched)
            if (embeddingResult != null) {
                Log.d(TAG, "Embedding fallback: '${userMessage.take(60)}…' → ${embeddingResult.size} skills")
                return embeddingResult
            }

            // Try usage-frequency biased fallback
            val frequencyResult = frequencyBiasedFallback(allEnabledSkillIds, matched)
            if (frequencyResult != null) {
                Log.d(TAG, "Frequency fallback: '${userMessage.take(60)}…' → ${frequencyResult.size} skills")
                return frequencyResult
            }

            // Ultimate fallback: send everything
            Log.d(TAG, "Full fallback: '${userMessage.take(60)}…' → all ${allEnabledSkillIds.size} skills")
            return allEnabledSkillIds
        }

        Log.d(TAG, "Routed '${userMessage.take(60)}…' → ${matched.size} skills: ${matched.joinToString()}")
        return matched
    }

    // ── Tool execution notifications ──────────────────────────────────

    /**
     * Called after each tool execution to track sessions and usage frequency.
     */
    fun notifyToolExecuted(toolName: String, tier: Tier) {
        // Session tracking
        SESSION_START_TOOLS[toolName]?.let { activeSessions.add(it) }
        SESSION_END_TOOLS[toolName]?.let { activeSessions.remove(it) }

        // Usage frequency (skip CORE skills — they're always included anyway)
        val skill = skillRegistry?.findSkillForTool(toolName, tier)
        if (skill != null && skill.id !in CORE_SKILL_IDS) {
            usageCounts[skill.id] = (usageCounts[skill.id] ?: 0) + 1
            persistUsageCounts()
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

            // Include external skills
            for (skillId in allEnabledSkillIds) {
                if (skillId.startsWith("clawhub:") || skillId.startsWith("ai:") || skillId.startsWith("ext:")) {
                    result.add(skillId)
                }
            }

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

        // Include external skills
        for (skillId in allEnabledSkillIds) {
            if (skillId.startsWith("clawhub:") || skillId.startsWith("ai:") || skillId.startsWith("ext:")) {
                result.add(skillId)
            }
        }

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
