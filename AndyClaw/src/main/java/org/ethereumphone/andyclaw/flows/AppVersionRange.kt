package org.ethereumphone.andyclaw.flows

/**
 * The version pin. `agent-os-design.md` §3: "Version-pin to the app. On app upgrade,
 * mark all its flows `stale` and revalidate lazily on next use — don't blindly replay
 * into a changed UI."
 *
 * Ranges are npm-shaped and comma-separated: `">=7.2,<8.0"`, `"*"`, `"=1.4.2"`.
 * Comparison is component-wise numeric, with any non-numeric tail ignored — Android
 * `versionName`s are `7.2.1`, `7.2.1-beta`, `7.2.1 (4471)` and everything in between,
 * and a flow must not be refused because a build appended a suffix.
 */
object AppVersionRange {

    private val CLAUSE = Regex("^(>=|<=|>|<|=|\\^|~)?\\s*([0-9][0-9._-]*)$")

    fun isParseable(range: String): Boolean {
        val trimmed = range.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "*") return true
        return trimmed.split(',').all { CLAUSE.matches(it.trim()) }
    }

    /** Whether [version] satisfies [range]. An unparseable range satisfies nothing. */
    fun contains(range: String, version: String?): Boolean {
        val trimmed = range.trim()
        if (!isParseable(trimmed)) return false
        // An app that is not installed satisfies nothing, `*` included: the range says
        // which versions a flow may run against, not whether it may run against none.
        if (version.isNullOrBlank()) return false
        if (trimmed == "*") return true

        return trimmed.split(',').all { clause ->
            val match = CLAUSE.find(clause.trim()) ?: return false
            val op = match.groupValues[1].ifEmpty { "=" }
            val bound = match.groupValues[2]
            val cmp = compare(version, bound)
            when (op) {
                ">=" -> cmp >= 0
                "<=" -> cmp <= 0
                ">" -> cmp > 0
                "<" -> cmp < 0
                "=" -> cmp == 0
                // `^7.2` / `~7.2` — same major, at least this version.
                "^", "~" -> cmp >= 0 && majorOf(version) == majorOf(bound)
                else -> false
            }
        }
    }

    /** Component-wise numeric comparison; missing components count as 0. */
    fun compare(a: String, b: String): Int {
        val left = components(a)
        val right = components(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0L }
            val r = right.getOrElse(i) { 0L }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    /**
     * The range a flow compiled now against [versionName] is pinned to: at least this version,
     * below the next major. Pinning to the exact build would retire every flow on a patch
     * update; leaving it open would replay into a redesigned UI. Within the range, the per-step
     * checksums are what actually catch drift.
     *
     * Only the leading numeric part is used. Gmail, Messages, X and Discord ship version names
     * like `2024.07.14.123456789.Release`, and embedding that verbatim produced a range this
     * class cannot parse — so no flow could ever compile for them. A name with no leading number
     * pins to `*`. Null when there is no version (the app is not installed).
     */
    fun suggestedFor(versionName: String?): String? {
        val version = versionName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val numeric = LEADING_NUMERIC.find(version)?.value ?: return "*"
        val major = numeric.substringBefore('.').toLongOrNull() ?: return "*"
        return ">=$numeric,<${major + 1}"
    }

    private val LEADING_NUMERIC = Regex("^\\d+(\\.\\d+)*")

    private fun majorOf(v: String): Long = components(v).firstOrNull() ?: 0L

    private fun components(version: String): List<Long> =
        version.trim()
            .split(Regex("[^0-9]+"))
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toLongOrNull() }
}
