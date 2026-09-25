package org.ethereumphone.andyclaw.safety

import android.util.Log

enum class LeakAction { BLOCK, REDACT, WARN }
enum class LeakSeverity { LOW, MEDIUM, HIGH, CRITICAL }

data class LeakPattern(
    val name: String,
    val regex: Regex,
    val severity: LeakSeverity,
    val action: LeakAction,
    val prefix: String?,
)

data class LeakMatch(
    val patternName: String,
    val severity: LeakSeverity,
    val action: LeakAction,
    val location: IntRange,
    val maskedPreview: String,
)

data class LeakScanResult(
    val matches: List<LeakMatch>,
    val shouldBlock: Boolean,
    val redactedContent: String?,
) {
    val blockReasons: List<String>
        get() = matches.filter { it.action == LeakAction.BLOCK }.map { it.patternName }
}

class LeakDetector(
    private val patterns: List<LeakPattern> = defaultPatterns(),
) {

    fun scan(content: String): LeakScanResult {
        val matches = mutableListOf<LeakMatch>()

        val candidatePatterns = if (prefixes.isNotEmpty()) {
            // Both sides lower-cased. The content was, the prefixes were not — so every pattern
            // whose prefix has a capital in it (AWS "AKIA", Google "AIza", PEM "-----BEGIN",
            // Twilio, SendGrid, Bearer) was filtered out before it could ever match.
            val lower = content.lowercase()
            val matchedPrefixes = prefixes.filter { lower.contains(it) }.toSet()
            patterns.filter { p ->
                p.prefix == null || p.prefix.lowercase() in matchedPrefixes
            }
        } else {
            patterns
        }

        for (pattern in candidatePatterns) {
            for (match in pattern.regex.findAll(content)) {
                matches.add(
                    LeakMatch(
                        patternName = pattern.name,
                        severity = pattern.severity,
                        action = pattern.action,
                        location = match.range,
                        maskedPreview = maskSecret(match.value),
                    )
                )
            }
        }

        if (matches.isNotEmpty()) {
            Log.w(TAG, "LeakDetector found ${matches.size} potential secret(s): " +
                    matches.joinToString { "${it.patternName}(${it.action})" })
        }

        val shouldBlock = matches.any { it.action == LeakAction.BLOCK }
        val needsRedaction = matches.any { it.action == LeakAction.REDACT }

        val redacted = if (needsRedaction && !shouldBlock) {
            applyRedactions(content, matches.filter { it.action == LeakAction.REDACT })
        } else {
            null
        }

        return LeakScanResult(
            matches = matches,
            shouldBlock = shouldBlock,
            redactedContent = redacted,
        )
    }

    fun scanAndClean(content: String): Result<String> {
        val result = scan(content)
        if (result.shouldBlock) {
            return Result.failure(
                SecurityException(
                    "[Safety] Output blocked: potential secret leakage detected " +
                            "(${result.blockReasons.joinToString()})"
                )
            )
        }
        return Result.success(result.redactedContent ?: content)
    }

    private val prefixes: List<String> = patterns.mapNotNull { it.prefix?.lowercase() }

    companion object {
        private const val TAG = "LeakDetector"
        private const val MAX_MASK_STARS = 8

        fun maskSecret(secret: String): String {
            if (secret.length <= 8) return "*".repeat(secret.length)
            val head = secret.take(4)
            val tail = secret.takeLast(4)
            val starCount = (secret.length - 8).coerceAtMost(MAX_MASK_STARS)
            return "$head${"*".repeat(starCount)}$tail"
        }

        fun applyRedactions(content: String, matches: List<LeakMatch>): String {
            if (matches.isEmpty()) return content
            val sorted = matches.sortedByDescending { it.location.first }
            val sb = StringBuilder(content)
            for (m in sorted) {
                val start = m.location.first.coerceIn(0, sb.length)
                val end = (m.location.last + 1).coerceIn(start, sb.length)
                sb.replace(start, end, "[REDACTED]")
            }
            return sb.toString()
        }

        fun defaultPatterns(): List<LeakPattern> = listOf(
            LeakPattern(
                name = "openai_api_key",
                regex = Regex("""sk-(?:proj-)?[a-zA-Z0-9]{20,}(?:T3BlbkFJ[a-zA-Z0-9_-]*)?"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "sk-",
            ),
            LeakPattern(
                name = "anthropic_api_key",
                regex = Regex("""sk-ant-api[a-zA-Z0-9_-]{90,}"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "sk-ant",
            ),
            LeakPattern(
                name = "aws_access_key",
                regex = Regex("""AKIA[0-9A-Z]{16}"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "AKIA",
            ),
            LeakPattern(
                name = "github_token",
                regex = Regex("""gh[pousr]_[A-Za-z0-9_]{36,}"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "gh",
            ),
            LeakPattern(
                name = "github_fine_grained_pat",
                regex = Regex("""github_pat_[a-zA-Z0-9]{22}_[a-zA-Z0-9]{59}"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "github_pat_",
            ),
            LeakPattern(
                name = "stripe_api_key",
                regex = Regex("""sk_(?:live|test)_[a-zA-Z0-9]{24,}"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "sk_",
            ),
            LeakPattern(
                name = "nearai_session",
                regex = Regex("""sess_[a-zA-Z0-9]{32,}"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "sess_",
            ),
            LeakPattern(
                name = "pem_private_key",
                regex = Regex("""-----BEGIN\s+(?:RSA\s+)?PRIVATE\s+KEY-----"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "-----BEGIN",
            ),
            LeakPattern(
                name = "ssh_private_key",
                regex = Regex("""-----BEGIN\s+(?:OPENSSH|EC|DSA)\s+PRIVATE\s+KEY-----"""),
                severity = LeakSeverity.CRITICAL,
                action = LeakAction.BLOCK,
                prefix = "-----BEGIN",
            ),
            LeakPattern(
                name = "google_api_key",
                regex = Regex("""AIza[0-9A-Za-z_-]{35}"""),
                severity = LeakSeverity.HIGH,
                action = LeakAction.BLOCK,
                prefix = "AIza",
            ),
            LeakPattern(
                name = "slack_token",
                regex = Regex("""xox[baprs]-[0-9a-zA-Z-]{10,}"""),
                severity = LeakSeverity.HIGH,
                action = LeakAction.BLOCK,
                prefix = "xox",
            ),
            LeakPattern(
                name = "twilio_api_key",
                regex = Regex("""SK[a-fA-F0-9]{32}"""),
                severity = LeakSeverity.HIGH,
                action = LeakAction.BLOCK,
                prefix = "SK",
            ),
            LeakPattern(
                name = "sendgrid_api_key",
                regex = Regex("""SG\.[a-zA-Z0-9_-]{22}\.[a-zA-Z0-9_-]{43}"""),
                severity = LeakSeverity.HIGH,
                action = LeakAction.BLOCK,
                prefix = "SG.",
            ),
            LeakPattern(
                name = "bearer_token",
                regex = Regex("""Bearer\s+[a-zA-Z0-9_-]{20,}"""),
                severity = LeakSeverity.HIGH,
                action = LeakAction.REDACT,
                prefix = "Bearer",
            ),
            LeakPattern(
                name = "auth_header",
                regex = Regex("""(?i)authorization:\s*[a-zA-Z]+\s+[a-zA-Z0-9_-]{20,}"""),
                severity = LeakSeverity.HIGH,
                action = LeakAction.REDACT,
                prefix = "authorization",
            ),
            LeakPattern(
                name = "high_entropy_hex",
                regex = Regex("""\b[a-fA-F0-9]{64}\b"""),
                severity = LeakSeverity.MEDIUM,
                action = LeakAction.WARN,
                prefix = null,
            ),
        )
    }
}
