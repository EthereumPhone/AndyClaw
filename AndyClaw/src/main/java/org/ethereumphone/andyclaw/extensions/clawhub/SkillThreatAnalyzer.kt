package org.ethereumphone.andyclaw.extensions.clawhub

import org.ethereumphone.andyclaw.skills.SkillFrontmatter
import java.io.File

/**
 * Threat level assigned to a ClawHub skill based on static analysis.
 *
 * Levels are ordered by severity — ordinal comparison is meaningful.
 */
enum class ThreatLevel {
    LOW, MEDIUM, HIGH, CRITICAL;

    val displayName: String
        get() = when (this) {
            LOW -> "Low Risk"
            MEDIUM -> "Medium Risk"
            HIGH -> "High Risk"
            CRITICAL -> "Critical Risk"
        }
}

/**
 * A single risk signal discovered during analysis.
 */
data class ThreatIndicator(
    val severity: ThreatLevel,
    val category: String,
    val description: String,
)

/**
 * Result of a threat assessment — overall level plus the individual indicators.
 */
data class ThreatAssessment(
    val level: ThreatLevel,
    val indicators: List<ThreatIndicator>,
    val summary: String,
)

/**
 * Result of the download-and-assess phase.
 */
sealed class DownloadAssessResult {
    data class Ready(
        val slug: String,
        val version: String?,
        val assessment: ThreatAssessment,
    ) : DownloadAssessResult()

    data class AlreadyInstalled(
        val slug: String,
        val version: String?,
    ) : DownloadAssessResult()

    data class Failed(
        val slug: String,
        val reason: String,
    ) : DownloadAssessResult()
}

/**
 * Static analyser that evaluates ClawHub skill bundles for potential threats.
 *
 * Two modes:
 * - [quickAssess]: fast heuristic from name/description + optional server
 *   moderation flags (for browse cards).
 * - [deepAssess]:  full file scan after extraction (before installation
 *   confirmation). Also integrates server moderation data when available.
 *
 * Patterns are informed by the Feb 2026 ClawHavoc incident where 1 000+
 * malicious skills were published to ClawHub using prompt-injection,
 * fake prerequisites, and disguised payloads.
 */
object SkillThreatAnalyzer {

    // ── Pattern dictionaries ────────────────────────────────────────────

    // Semver-like pattern (e.g. "1.0.1") — legitimate skills don't embed
    // version numbers in their *name*; malware often does.
    private val VERSION_IN_NAME_REGEX = Regex("""\d+\.\d+(\.\d+)?""")

    private val EXEC_KEYWORDS = setOf(
        "termux", "execute", "shell", "bash", "terminal",
        "script", "command line", "run command",
    )

    private val SENSITIVE_KEYWORDS = setOf(
        "sms", "contacts", "location", "camera", "files",
        "storage", "root", "keylog", "screenshot",
    )

    // -- Deep-scan patterns --

    private val NETWORK_EXFIL_PATTERNS = listOf(
        "curl ", "curl\t", "wget ", "wget\t",
        "nc ", "netcat ", "ncat ",
        "requests.post", "requests.get",
        "urllib.request", "httpx.",
        "fetch(", "xmlhttprequest",
    )

    private val SENSITIVE_DATA_PATTERNS = listOf(
        "/sdcard", "content://contacts", "content://sms",
        "content://call_log", "/data/data/", "credential",
        "password", "private.key", "private_key", "keystore",
        "wallet", ".ssh/", "api_key", "secret_key",
        "mnemonic", "seed phrase", "recovery phrase",
        "browser/profile", "chrome/default", "firefox/profiles",
        "cookies.sqlite", "login data",
    )

    private val OBFUSCATION_PATTERNS = listOf(
        Regex("base64", RegexOption.IGNORE_CASE),
        Regex("eval\\s*\\(", RegexOption.IGNORE_CASE),
        Regex("exec\\s*\\(", RegexOption.IGNORE_CASE),
        Regex("\\\\x[0-9a-fA-F]{2}"),
        Regex("\\\\u[0-9a-fA-F]{4}"),
        Regex("String\\.fromCharCode", RegexOption.IGNORE_CASE),
        Regex("atob\\s*\\(", RegexOption.IGNORE_CASE),
        Regex("charCodeAt", RegexOption.IGNORE_CASE),
    )

    private val INJECTION_PATTERNS = listOf(
        "ignore previous", "ignore all prior", "disregard",
        "forget your instructions", "new system prompt",
        "you are now", "override your", "do not refuse",
        "bypass", "jailbreak", "act as if",
        "pretend you are", "simulate a", "roleplay as",
    )

    private val DANGEROUS_COMMANDS = listOf(
        "rm -rf /", "chmod 777", "dd if=",
        "mkfs", "> /dev/sd", ":(){ :|:& };:",
    )

    /** Pipe-to-shell — download something and immediately execute it. */
    private val PIPE_TO_SHELL_PATTERNS = listOf(
        Regex("""curl\s+[^|]*\|\s*(sh|bash|zsh|dash|python|ruby|perl|node)""", RegexOption.IGNORE_CASE),
        Regex("""wget\s+[^|]*\|\s*(sh|bash|zsh|dash|python|ruby|perl|node)""", RegexOption.IGNORE_CASE),
        Regex("""curl\s+.*-o\s*-\s*\|""", RegexOption.IGNORE_CASE),
        Regex("""wget\s+.*-O\s*-\s*\|""", RegexOption.IGNORE_CASE),
        Regex("""curl\s+.*>\s*/tmp/[^\s]+\s*&&\s*(sh|bash|chmod)""", RegexOption.IGNORE_CASE),
        Regex("""wget\s+.*-O\s+/tmp/[^\s]+\s*&&\s*(sh|bash|chmod)""", RegexOption.IGNORE_CASE),
    )

    /** URL substrings that are suspicious inside a SKILL.md bundle. */
    private val SUSPICIOUS_URL_SUBSTRINGS = listOf(
        "raw.githubusercontent.com",
        "pastebin.com", "paste.ee", "hastebin.com",
        "transfer.sh", "file.io", "0x0.st",
        "ngrok.io", "ngrok-free.app", "serveo.net",
        "localhost.run", "trycloudflare.com",
    )

    /** File extensions in URLs that hint at direct payload downloads. */
    private val SUSPICIOUS_URL_EXTENSIONS = listOf(
        ".sh", ".bash", ".py", ".rb", ".pl",
        ".exe", ".msi", ".dmg", ".pkg", ".deb", ".apk",
        ".bin", ".run", ".elf",
    )

    /**
     * Patterns in the SKILL.md *body* that try to trick the AI agent into
     * fetching and running external code (the main ClawHavoc attack vector).
     */
    private val AI_MANIPULATION_PATTERNS = listOf(
        "download and run", "download and execute",
        "download this", "fetch and execute",
        "execute the script", "run this script",
        "install this first", "install before using",
        "required dependency", "required prerequisite",
        "must install", "must download",
        "run the following command", "paste this into your terminal",
        "open terminal and run",
    )

    /**
     * Markdown/HTML hidden content that may contain concealed instructions.
     * These won't be visible to a human reviewing the skill page but will
     * be parsed by the AI agent.
     */
    private val HTML_COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")

    private val HIDDEN_CONTENT_KEYWORDS = listOf(
        "curl", "wget", "download", "install", "execute",
        "run", "sh ", "bash ", "python ", "token",
        "password", "credential", "exfiltrate", "send",
        "http://", "https://",
    )

    // ── Quick assess (browse / search cards) ────────────────────────────

    /**
     * Quick heuristic based on the skill name, description, and optional
     * server-side moderation flags.
     *
     * Used in the browse/search UI where we don't have the actual files.
     * Accepts [moderation] from the API when available (may be null for
     * list/search endpoints that don't return it).
     */
    fun quickAssess(
        description: String?,
        name: String?,
        moderation: ClawHubModeration? = null,
    ): ThreatLevel {
        // Server says it's blocked malware — no analysis needed.
        if (moderation?.isMalwareBlocked == true) return ThreatLevel.CRITICAL
        if (moderation?.isSuspicious == true) return ThreatLevel.CRITICAL

        val nameStr = name.orEmpty()
        val descStr = description.orEmpty()
        val text = "$nameStr $descStr".lowercase()

        val signals = mutableListOf<ThreatLevel>()

        // Version number baked into the display name (e.g. "Wed 1.0.1").
        // Legitimate skills use the version field; malware packs fake
        // version info into the name for social engineering.
        if (VERSION_IN_NAME_REGEX.containsMatchIn(nameStr)) {
            signals += ThreatLevel.HIGH
        }

        // Keyword-based signals
        val hasExec = EXEC_KEYWORDS.any { it in text }
        val hasSensitive = SENSITIVE_KEYWORDS.any { it in text }
        if (hasExec && hasSensitive) signals += ThreatLevel.HIGH
        else if (hasExec || hasSensitive) signals += ThreatLevel.MEDIUM

        // Description mentions downloading / piping to shell
        val hasSuspiciousInstall = listOf(
            "download and run", "download and install",
            "curl |", "wget |", "pipe to",
            "open terminal and run",
        ).any { it in text }
        if (hasSuspiciousInstall) signals += ThreatLevel.HIGH

        return signals.maxOrNull() ?: ThreatLevel.LOW
    }

    // ── Deep assess (post-download, pre-registration) ───────────────────

    /**
     * Deep static analysis of an extracted skill directory.
     *
     * Scans the SKILL.md front-matter, instructions, and every text file
     * in the bundle for dangerous patterns. Also integrates server-side
     * [moderation] flags when available.
     */
    fun deepAssess(
        skillDir: File,
        moderation: ClawHubModeration? = null,
    ): ThreatAssessment {
        val indicators = mutableListOf<ThreatIndicator>()

        // ── Server-side moderation ──────────────────────────────────────
        if (moderation?.isMalwareBlocked == true) {
            indicators += ThreatIndicator(
                ThreatLevel.CRITICAL,
                "Blocked by ClawHub",
                "This skill has been flagged and blocked as malware by ClawHub moderators",
            )
        }
        if (moderation?.isSuspicious == true) {
            indicators += ThreatIndicator(
                ThreatLevel.CRITICAL,
                "Flagged as Suspicious",
                "ClawHub's moderation system has flagged this skill as suspicious",
            )
        }

        // ── SKILL.md presence ───────────────────────────────────────────
        val skillMd = File(skillDir, "SKILL.md")
        if (!skillMd.isFile) {
            return ThreatAssessment(
                level = ThreatLevel.CRITICAL,
                indicators = indicators + ThreatIndicator(
                    ThreatLevel.CRITICAL, "Structure", "Missing SKILL.md — invalid skill bundle",
                ),
                summary = "Invalid skill bundle: missing SKILL.md.",
            )
        }

        val content = skillMd.readText()
        val frontmatter = SkillFrontmatter.parse(content)
        val metadata = SkillFrontmatter.resolveMetadata(frontmatter)
        val body = SkillFrontmatter.extractBody(content)
        val bodyLower = body.lowercase()

        // ── Execution spec (Termux / code execution) ────────────────────
        metadata?.execution?.let { exec ->
            indicators += ThreatIndicator(
                ThreatLevel.HIGH,
                "Code Execution",
                "Runs code via ${exec.type} — can execute arbitrary commands on your device",
            )
        }
        if (metadata?.requires?.bins?.isNotEmpty() == true) {
            indicators += ThreatIndicator(
                ThreatLevel.MEDIUM,
                "Package Installation",
                "Requires system binaries: ${metadata.requires.bins.joinToString(", ")}",
            )
        }

        // ── Collect all text content ────────────────────────────────────
        val allFiles = skillDir.walkTopDown().filter { it.isFile }.toList()

        val binaryFiles = allFiles.filter { isBinaryFile(it) }
        if (binaryFiles.isNotEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.HIGH,
                "Binary Files",
                "Contains ${binaryFiles.size} binary file(s) that cannot be inspected: " +
                    binaryFiles.take(3).joinToString(", ") { it.name },
            )
        }

        val textContent = buildString {
            for (file in allFiles) {
                if (!isBinaryFile(file) && file.length() < 500_000) {
                    runCatching { append(file.readText().lowercase()).append('\n') }
                }
            }
        }

        // ── Pipe-to-shell ───────────────────────────────────────────────
        val pipeToShellHits = PIPE_TO_SHELL_PATTERNS.any { it.containsMatchIn(textContent) }
        if (pipeToShellHits) {
            indicators += ThreatIndicator(
                ThreatLevel.CRITICAL,
                "Remote Code Execution",
                "Contains pipe-to-shell patterns that download and immediately execute remote code",
            )
        }

        // ── Suspicious URLs ─────────────────────────────────────────────
        val urlRegex = Regex("""https?://[^\s"')\]>]+""", RegexOption.IGNORE_CASE)
        val foundUrls = urlRegex.findAll(textContent).map { it.value }.toList()

        val suspiciousByHost = foundUrls.filter { url ->
            SUSPICIOUS_URL_SUBSTRINGS.any { it in url }
        }
        val suspiciousByExt = foundUrls.filter { url ->
            SUSPICIOUS_URL_EXTENSIONS.any { url.substringBefore('?').endsWith(it) }
        }
        val suspiciousUrls = (suspiciousByHost + suspiciousByExt).distinct()
        if (suspiciousUrls.isNotEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.HIGH,
                "Suspicious URLs",
                "References URLs that may serve malicious payloads: " +
                    suspiciousUrls.take(3).joinToString(", "),
            )
        }

        // Any URL in the skill body is worth flagging at medium if nothing
        // else already caught it — legitimate instruction-only skills
        // rarely embed raw URLs.
        if (foundUrls.isNotEmpty() && suspiciousUrls.isEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.MEDIUM,
                "External URLs",
                "References ${foundUrls.size} external URL(s) — verify they are trusted",
            )
        }

        // ── AI manipulation / fake prerequisites ────────────────────────
        val aiManipHits = AI_MANIPULATION_PATTERNS.filter { it in bodyLower }
        if (aiManipHits.isNotEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.CRITICAL,
                "AI Manipulation",
                "Skill instructions attempt to make the AI agent download or execute external code " +
                    "(matched: ${aiManipHits.take(3).joinToString(", ") { "\"$it\"" }})",
            )
        }

        // ── Hidden instructions in HTML comments ────────────────────────
        val hiddenComments = HTML_COMMENT_REGEX.findAll(body).toList()
        val suspiciousComments = hiddenComments.filter { comment ->
            val c = comment.value.lowercase()
            HIDDEN_CONTENT_KEYWORDS.any { it in c }
        }
        if (suspiciousComments.isNotEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.CRITICAL,
                "Hidden Instructions",
                "Contains hidden HTML comments with suspicious directives " +
                    "(${suspiciousComments.size} comment(s) with keywords like download, execute, etc.)",
            )
        }

        // ── Network exfiltration ────────────────────────────────────────
        val networkHits = NETWORK_EXFIL_PATTERNS.filter { it in textContent }
        if (networkHits.isNotEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.HIGH,
                "Network Access",
                "Contains network commands that could exfiltrate data: " +
                    networkHits.joinToString(", ") { it.trim() },
            )
        }

        // ── Sensitive data access ───────────────────────────────────────
        val sensitiveHits = SENSITIVE_DATA_PATTERNS.filter { it in textContent }
        if (sensitiveHits.isNotEmpty()) {
            indicators += ThreatIndicator(
                if (sensitiveHits.size >= 3) ThreatLevel.CRITICAL else ThreatLevel.HIGH,
                "Sensitive Data Access",
                "References sensitive data paths or credentials: " +
                    sensitiveHits.joinToString(", "),
            )
        }

        // ── Obfuscation ────────────────────────────────────────────────
        val obfuscationHits = OBFUSCATION_PATTERNS.filter { it.containsMatchIn(textContent) }
        if (obfuscationHits.isNotEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.HIGH,
                "Obfuscation",
                "Contains potentially obfuscated code patterns",
            )
        }

        // ── Prompt injection ────────────────────────────────────────────
        val injectionHits = INJECTION_PATTERNS.filter { it in bodyLower }
        if (injectionHits.isNotEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.CRITICAL,
                "Prompt Injection",
                "Skill instructions contain suspicious override patterns that may " +
                    "manipulate the AI agent (matched: ${injectionHits.take(3).joinToString(", ") { "\"$it\"" }})",
            )
        }

        // ── Dangerous shell commands ────────────────────────────────────
        val dangerousHits = DANGEROUS_COMMANDS.filter { it in textContent }
        if (dangerousHits.isNotEmpty()) {
            indicators += ThreatIndicator(
                ThreatLevel.CRITICAL,
                "Dangerous Commands",
                "Contains destructive system commands: ${dangerousHits.joinToString(", ")}",
            )
        }

        // ── Version baked into directory / skill name ───────────────────
        val dirName = skillDir.name
        if (VERSION_IN_NAME_REGEX.containsMatchIn(dirName)) {
            indicators += ThreatIndicator(
                ThreatLevel.HIGH,
                "Suspicious Naming",
                "Skill slug \"$dirName\" embeds a version number — " +
                    "a pattern commonly used by malicious packages to impersonate legitimate ones",
            )
        }

        // ── Aggregate ───────────────────────────────────────────────────
        val overallLevel = indicators
            .maxOfOrNull { it.severity }
            ?: ThreatLevel.LOW

        val summary = when (overallLevel) {
            ThreatLevel.LOW ->
                "No dangerous patterns detected. This skill appears safe."
            ThreatLevel.MEDIUM ->
                "This skill has some capabilities that could be misused. Review the details below."
            ThreatLevel.HIGH ->
                "This skill has significant security concerns. Only install if you trust the author."
            ThreatLevel.CRITICAL ->
                "This skill shows strong signs of being compromised or malicious. Install at your own risk."
        }

        return ThreatAssessment(
            level = overallLevel,
            indicators = indicators.sortedByDescending { it.severity.ordinal },
            summary = summary,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun isBinaryFile(file: File): Boolean {
        if (file.length() == 0L) return false
        return try {
            val buffer = ByteArray(512)
            val bytesRead = file.inputStream().use { it.read(buffer) }
            if (bytesRead <= 0) return false
            buffer.take(bytesRead).count { it == 0.toByte() } > bytesRead / 10
        } catch (_: Exception) {
            false
        }
    }
}
