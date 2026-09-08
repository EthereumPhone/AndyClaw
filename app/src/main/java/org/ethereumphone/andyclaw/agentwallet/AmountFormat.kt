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
