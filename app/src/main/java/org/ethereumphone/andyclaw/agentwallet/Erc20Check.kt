package org.ethereumphone.andyclaw.agentwallet

import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import java.math.BigInteger

/**
 * What the chain says about a token contract that is not in [AgentWalletChains.WELL_KNOWN_TOKENS].
 *
 * A contract address is only a token on the chain it was deployed to. Mainnet USDC's address
 * on Base has no code, and `transfer` to an address with no code "succeeds" — the UserOperation
 * lands, nothing moves, and the tool reported a send. Decimals were the model's word, so a
 * 6-decimal token given as 18 was scaled by 10^12. Both are read here before anything is signed.
 */
object Erc20Check {

    /** A failed read is null — never mistaken for "no code" or for a decimals value. */
    data class OnChain(val code: String?, val decimals: Int?)

    sealed interface Verdict {
        data class Ok(val decimals: Int) : Verdict
        data class Refused(val reason: String) : Verdict
    }

    /** Blocking: `eth_getCode` and `decimals()` for [contract]. Call off the main thread. */
    fun read(rpcUrl: String, contract: String): OnChain {
        val web3 = Web3j.build(HttpService(rpcUrl))
        try {
            val code = runCatching {
                web3.ethGetCode(contract, DefaultBlockParameterName.LATEST).send()
            }.getOrNull()?.takeUnless { it.hasError() }?.code
            val decimals = if (code != null && hasCode(code)) readDecimals(web3, contract) else null
            return OnChain(code, decimals)
        } finally {
            web3.shutdown()
        }
    }

    /**
     * Whether a transfer of [contract] may go ahead, and with which decimals.
     *
     * [claimedDecimals] is what the caller was told. When the contract answers `decimals()` the
     * two must agree; a contract without `decimals()` is taken at the caller's word only if the
     * caller gave one. An unreadable chain refuses — this runs only for tokens nobody has vetted.
     */
    fun verdict(contract: String, chainName: String, onChain: OnChain, claimedDecimals: Int?): Verdict {
        val code = onChain.code
            ?: return Verdict.Refused("Could not check token $contract on $chainName. Nothing was sent.")
        if (!hasCode(code)) {
            return Verdict.Refused(
                "There is no contract at $contract on $chainName, so it is not a token there " +
                    "(is it the address from another chain?). Nothing was sent."
            )
        }
        val actual = onChain.decimals
        return when {
            actual != null && claimedDecimals != null && actual != claimedDecimals -> Verdict.Refused(
                "Token $contract on $chainName has $actual decimals, not $claimedDecimals. Nothing was sent."
            )
            actual != null -> Verdict.Ok(actual)
            claimedDecimals != null -> Verdict.Ok(claimedDecimals)
            else -> Verdict.Refused(
                "Could not read the decimals of token $contract on $chainName; provide them explicitly."
            )
        }
    }

    private fun hasCode(code: String): Boolean = code.trim().removePrefix("0x").isNotEmpty()

    private fun readDecimals(web3: Web3j, contract: String): Int? = runCatching {
        val function = Function("decimals", emptyList(), listOf(object : TypeReference<Uint8>() {}))
        val response = web3.ethCall(
            Transaction.createEthCallTransaction(ZERO_ADDRESS, contract, FunctionEncoder.encode(function)),
            DefaultBlockParameterName.LATEST,
        ).send()
        if (response.hasError()) return@runCatching null
        val value = FunctionReturnDecoder.decode(response.value, function.outputParameters)
            .firstOrNull()?.value as? BigInteger
        value?.takeIf { it <= BigInteger.valueOf(255) }?.toInt()
    }.getOrNull()

    private const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"
}
