package org.ethereumphone.andyclaw.agentwallet

import java.math.BigInteger

/**
 * How much native token the agent wallet has to keep for gas.
 *
 * There is no paymaster behind the sub-account: every UserOperation is paid from its own native
 * balance. These are deliberately generous upper bounds — a send the bundler rejects for want of
 * gas (AA21) is rejected after the user has already authenticated it.
 */
object GasBudget {

    /** A send from an account that is already deployed. */
    const val DEPLOYED_GAS = 600_000L

    /** The first send also deploys the account. */
    const val UNDEPLOYED_GAS = 1_000_000L

    /** Head-room over the current gas price, which can rise before the send lands. */
    private val MARGIN_NUMERATOR: BigInteger = BigInteger.valueOf(3)
    private val MARGIN_DENOMINATOR: BigInteger = BigInteger.valueOf(2)

    /** Gas top-ups cover this many sends. */
    const val TOP_UP_SENDS = 3L

    /** Native token to leave behind for one send at [gasPriceWei]. */
    fun reserveWei(gasPriceWei: BigInteger, deployed: Boolean): BigInteger =
        gasPriceWei
            .multiply(BigInteger.valueOf(if (deployed) DEPLOYED_GAS else UNDEPLOYED_GAS))
            .multiply(MARGIN_NUMERATOR)
            .divide(MARGIN_DENOMINATOR)

    /**
     * How much to move in from the user's own wallet so the agent wallet can pay for a few
     * sends. Sized from the chain's gas price: the fixed 0.0005 it replaced covered a handful of
     * L2 sends but not a single one on Polygon or Avalanche.
     */
    fun topUpWei(reserveWei: BigInteger): BigInteger = reserveWei.multiply(BigInteger.valueOf(TOP_UP_SENDS))
}
