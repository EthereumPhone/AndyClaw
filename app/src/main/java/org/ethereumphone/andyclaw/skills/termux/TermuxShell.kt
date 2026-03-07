package org.ethereumphone.andyclaw.skills.termux

/**
 * Shared validation and escaping helpers for constructing shell commands
 * executed via Termux.
 */
internal object TermuxShell {

    private const val MAX_SLUG_CHARS = 128
    private const val MAX_PATH_SEGMENT_CHARS = 255
    private val BIN_REGEX = Regex("^[a-z0-9][a-z0-9+._-]{0,63}$")

    /**
     * Single-quote shell escaping for POSIX-compatible shells.
     */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * Validate a skill slug used in Termux paths.
     *
     * The slug rules are intentionally permissive for backward compatibility:
     * reject traversal/control characters and path separators, but allow
     * mixed-case and spaces.
     */
    fun validateSlug(rawSlug: String): String {
        val slug = rawSlug.trim()
        require(slug.isNotEmpty()) { "Slug cannot be blank" }
        require(slug.length <= MAX_SLUG_CHARS) {
            "Slug is too long (max $MAX_SLUG_CHARS chars)"
        }
        require(slug != "." && slug != "..") {
            "Slug cannot be '.' or '..'"
        }
        require('/' !in slug && '\\' !in slug) {
            "Slug cannot contain path separators"
        }
        require(!containsControlChars(slug)) {
            "Slug contains control characters"
        }
        return slug
    }

    /**
     * Validate a relative path inside a synced skill directory.
     *
     * Path is normalised to forward slashes and traversal is rejected.
     * Segment characters are permissive to avoid breaking existing skills,
     * while still rejecting control chars and separators.
     */
    fun validateRelativePath(rawPath: String, label: String = "path"): String {
        val normalised = rawPath.trim().replace('\\', '/')
        require(normalised.isNotEmpty()) { "$label cannot be blank" }
        require(!normalised.startsWith('/')) { "$label must be relative" }

        val segments = normalised.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "$label contains invalid traversal segments"
        }
        require(segments.all { it.length <= MAX_PATH_SEGMENT_CHARS }) {
            "$label contains an overly long segment (max $MAX_PATH_SEGMENT_CHARS chars)"
        }
        require(segments.all { '/' !in it && '\\' !in it }) {
            "$label contains invalid separators"
        }
        require(segments.all { !containsControlChars(it) }) {
            "$label contains control characters"
        }

        return segments.joinToString("/")
    }

    /**
     * Validate package/binary names declared in SKILL.md metadata.
     */
    fun validateBinName(rawBin: String): String {
        val bin = rawBin.trim()
        require(bin.isNotEmpty()) { "Binary name cannot be blank" }
        require(BIN_REGEX.matches(bin)) {
            "Invalid binary name '$rawBin'. Allowed pattern: ${BIN_REGEX.pattern}"
        }
        return bin
    }

    private fun containsControlChars(value: String): Boolean {
        return value.any { ch -> ch.code < 32 || ch.code == 127 }
    }
}
