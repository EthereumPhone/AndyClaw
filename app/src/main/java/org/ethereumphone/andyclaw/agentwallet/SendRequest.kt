package org.ethereumphone.andyclaw.agentwallet

import java.math.BigInteger

/**
 * One send, exactly as the user was shown it.
 *
 * Built before the confirmation prompt and handed back after it, so what goes out is what was
 * authenticated. The screen used to read its live state again once the prompt returned — by
 * then a late ENS answer, an edited field or a refresh could have changed the recipient or the
 * amount behind the prompt the user had just approved.
 */
data class SendRequest(
    val chainId: Int,
    val chainName: String,
    val tokenSymbol: String,
    /** Null for the chain's native token. */
    val tokenContract: String?,
    val tokenDecimals: Int,
    /** The whole address, EIP-55 checksummed — never shortened where the user confirms it. */
    val recipient: String,
    /** The `.eth` name [recipient] was resolved from, when the user typed one. */
    val ensName: String?,
    val amountBaseUnits: BigInteger,
    /** The amount as the review, the prompt and the history show it: "0.5". */
    val amountDisplay: String,
    /** Something to know before confirming — sending to the token's own contract, say. */
    val warning: String? = null,
) {
    val isNative: Boolean get() = tokenContract == null

    /** The token as [AgentWalletRepository.send] takes it; only its identity is read there. */
    fun asToken(): TokenBalance = TokenBalance(
        symbol = tokenSymbol,
        name = tokenSymbol,
        decimals = tokenDecimals,
        contractAddress = tokenContract,
        raw = amountBaseUnits,
    )
}
