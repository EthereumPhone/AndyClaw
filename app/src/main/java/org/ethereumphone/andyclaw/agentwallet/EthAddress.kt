package org.ethereumphone.andyclaw.agentwallet

import org.web3j.crypto.Keys

/**
 * Address validation for the send screen.
 *
 * The rest of the app checks addresses with `startsWith("0x") && length == 42`, which
 * accepts a mistyped address as readily as a correct one. Sending funds is the one place
 * that warrants the EIP-55 checksum, since a wrong recipient is unrecoverable.
 */
object EthAddress {

    private val HEX_40 = Regex("^0x[0-9a-fA-F]{40}$")

    fun isWellFormed(address: String): Boolean = HEX_40.matches(address.trim())

    /**
     * True when [address] is well-formed and, if it carries mixed-case characters (i.e.
     * claims to be checksummed), the EIP-55 checksum matches.
     *
     * An all-lowercase or all-uppercase address carries no checksum information, so it is
     * accepted — rejecting it would break pasting from block explorers that lower-case.
     */
    fun isValid(address: String): Boolean {
        val trimmed = address.trim()
        if (!isWellFormed(trimmed)) return false

        val body = trimmed.substring(2)
        val hasLower = body.any { it in 'a'..'z' }
        val hasUpper = body.any { it in 'A'..'Z' }
        if (!hasLower || !hasUpper) return true

        return try {
            Keys.toChecksumAddress(trimmed) == trimmed
        } catch (_: Exception) {
            false
        }
    }

    /** Reason [address] is unusable, or null when it is fine. */
    fun validationError(address: String): String? {
        val trimmed = address.trim()
        return when {
            trimmed.isEmpty() -> "Enter a recipient address"
            !isWellFormed(trimmed) -> "Not a valid address (expected 0x + 40 hex characters)"
            !isValid(trimmed) -> "Address checksum does not match — check for a typo"
            else -> null
        }
    }

    fun looksLikeEns(input: String): Boolean =
        input.trim().endsWith(".eth", ignoreCase = true)

    /** `0x1234…abcd`, for review rows and history. */
    fun shorten(address: String): String {
        val trimmed = address.trim()
        if (trimmed.length < 12) return trimmed
        return "${trimmed.take(6)}…${trimmed.takeLast(4)}"
    }
}
