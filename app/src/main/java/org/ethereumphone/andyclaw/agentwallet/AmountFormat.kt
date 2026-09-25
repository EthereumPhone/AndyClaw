package org.ethereumphone.andyclaw.agentwallet

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Human-readable <-> base-unit conversion for token amounts.
 *
 * The same two expressions were inlined at seven-plus call sites in `WalletSkill`. The
 * important difference here is that [toBaseUnits] validates *before* converting: the old
 * inline form called `toBigIntegerExact()`, which throws an unhelpful ArithmeticException
 * when the user types more decimal places than the token has (e.g. "1.1234567" USDC).
 */
object AmountFormat {

    /**
     * Parse a human-readable amount ("100", "0.5") into base units for a token with
     * [decimals] decimal places.
     *
     * Returns null when the input is not a usable amount: unparseable, negative, or more
     * precise than the token can represent. Callers surface that as a field error rather
     * than letting an exception escape into a send path.
     */
    fun toBaseUnits(human: String, decimals: Int): BigInteger? {
        val trimmed = human.trim()
        if (trimmed.isEmpty()) return null

        val decimal = try {
            BigDecimal(trimmed)
        } catch (_: NumberFormatException) {
            return null
        }

        if (decimal.signum() < 0) return null
        // scale > decimals means the user asked for precision the token does not have.
        if (decimal.scale() > decimals) return null

        return decimal.movePointRight(decimals).toBigIntegerExact()
    }

    /** What the amount field holds, read the way the person typing it meant it. */
    sealed interface UserAmount {
        data object Empty : UserAmount

        /** [canonical] is the amount as the review, the prompt and the history show it: "0.5". */
        data class Ok(val baseUnits: BigInteger, val canonical: String) : UserAmount

        data class Invalid(val message: String) : UserAmount
    }

    /**
     * Read an amount a person typed, in their locale.
     *
     * [toBaseUnits] is strict on purpose — tool input is machine-written — but a German keypad
     * types "0,5", and the strict parser rejected it outright. This accepts either `,` or `.` as
     * the decimal separator, with one exception that matters more than convenience: the
     * *other* locale's separator followed by exactly three digits ("1,000" on an English phone,
     * "1.000" on a German one) is a thousands separator as often as a decimal one, and reading
     * it the wrong way sends a thousand times too much or too little. That, both separators at
     * once, grouping, exponents and a leading sign are refused with a message saying what to
     * type instead — never guessed at.
     */
    fun parseUserAmount(raw: String, decimals: Int, decimalSeparator: Char = '.'): UserAmount {
        val s = raw.trim()
        if (s.isEmpty()) return UserAmount.Empty
        if (s.startsWith('+')) return UserAmount.Invalid("Leave out the + sign")
        if (s.startsWith('-')) return UserAmount.Invalid("The amount can't be negative")
        if (s.any { it == 'e' || it == 'E' }) return UserAmount.Invalid("Write the amount out in full, without an e")
        if (s.any { it !in '0'..'9' && it != '.' && it != ',' }) {
            return UserAmount.Invalid("Use only digits and one decimal separator")
        }
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }
        if (dots > 0 && commas > 0) return UserAmount.Invalid("Use one decimal separator and no thousands separators")
        if (dots > 1 || commas > 1) return UserAmount.Invalid("Leave out thousands separators")

        val separator = when {
            dots == 1 -> '.'
            commas == 1 -> ','
            else -> null
        }
        if (separator != null && separator != decimalSeparator) {
            val before = s.substringBefore(separator)
            val after = s.substringAfter(separator)
            if (before.isNotEmpty() && after.length == 3) {
                return UserAmount.Invalid(
                    "\"$s\" could mean a thousand times more. Leave out thousands separators, " +
                        "and use $decimalSeparator for decimals"
                )
            }
        }

        val normalized = if (separator == ',') s.replace(',', '.') else s
        if (normalized.none { it in '0'..'9' }) return UserAmount.Invalid("Enter an amount")
        val decimal = try {
            BigDecimal(normalized).stripTrailingZeros()
        } catch (_: NumberFormatException) {
            return UserAmount.Invalid("Enter an amount")
        }
        if (decimal.scale() > decimals) {
            return UserAmount.Invalid(
                if (decimals == 0) "This token has no decimals" else "At most $decimals decimals for this token"
            )
        }
        val base = decimal.movePointRight(decimals).toBigIntegerExact()
        return UserAmount.Ok(base, if (base.signum() == 0) "0" else decimal.toPlainString())
    }

    /**
     * The most that can be sent of a token the agent wallet holds [balance] of.
     *
     * Gas is paid in the chain's native token, so a native send has to leave [gasReserve]
     * behind — "Max" used to send the whole balance and the bundler then rejected it (AA21),
     * after the user had already authenticated. With the reserve unknown there is no safe
     * maximum: null, and the Max button stays off rather than guessing.
     */
    fun maxSendable(balance: BigInteger, isNative: Boolean, gasReserve: BigInteger?): BigInteger? {
        if (!isNative) return balance
        val reserve = gasReserve ?: return null
        return (balance - reserve).max(BigInteger.ZERO)
    }

    /** Format base units as a human-readable amount, trimming trailing zeros. */
    fun fromBaseUnits(raw: BigInteger, decimals: Int): String {
        if (raw.signum() == 0) return "0"
        return BigDecimal(raw)
            .divide(BigDecimal.TEN.pow(decimals), decimals, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    /**
     * Shorter form for balance lists: at most [maxDecimals] places, so a dust balance
     * does not push the row off the screen. Never rounds up to a non-zero display for a
     * balance that is actually zero.
     */
    fun formatForDisplay(raw: BigInteger, decimals: Int, maxDecimals: Int = 6): String {
        if (raw.signum() == 0) return "0"
        val places = maxDecimals.coerceIn(0, decimals)
        val exact = BigDecimal(raw).divide(BigDecimal.TEN.pow(decimals), decimals, RoundingMode.HALF_UP)
        val rounded = exact.setScale(places, RoundingMode.DOWN)
        if (rounded.signum() == 0) {
            // Non-zero but below what we can show: say so rather than displaying "0".
            return if (places == 0) "<1" else "<0.${"0".repeat(places - 1)}1"
        }
        return rounded.stripTrailingZeros().toPlainString()
    }
}
