package org.ethereumphone.andyclaw.agentwallet

import org.web3j.crypto.Keys
import java.util.Locale

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
     * The zero address. Nothing can ever spend from it, so anything sent there is gone — and it
     * is exactly what an ENS name with no address record resolves to.
     */
    fun isZero(address: String): Boolean = isWellFormed(address) && address.trim().drop(2).all { it == '0' }

    /** [address] in its EIP-55 form, for showing the whole address the user is sending to. */
    fun checksummed(address: String): String = Keys.toChecksumAddress(address.trim())

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
            isZero(trimmed) -> "That is the zero address — anything sent there is lost"
            else -> null
        }
    }

    fun looksLikeEns(input: String): Boolean =
        input.trim().endsWith(".eth", ignoreCase = true)

    /**
     * A `.eth` name in the one form this app resolves: lower-case ASCII letters, digits and
     * hyphens. Anything else — look-alike Unicode above all, which renders as a familiar name
     * and resolves to someone else's address — is refused rather than normalised. Null when
     * [input] is not such a name.
     */
    fun normalizeEnsName(input: String): String? {
        val name = input.trim().lowercase(Locale.ROOT)
        if (!name.endsWith(".eth")) return null
        val labels = name.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        val plain = labels.all { label -> label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } }
        return name.takeIf { plain }
    }

    /** `0x1234…abcd`, for review rows and history. */
    fun shorten(address: String): String {
        val trimmed = address.trim()
        if (trimmed.length < 12) return trimmed
        return "${trimmed.take(6)}…${trimmed.takeLast(4)}"
    }
}
