package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.walletsdk.WalletSDK
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.math.BigDecimal

class SwapSkill(private val context: Context) : AndyClawSkill {
    override val id = "swap"
    override val name = "Token Swap"

    companion object {
        private const val TAG = "SwapSkill"
        private const val SWAP_DATA_AUTHORITY = "com.walletmanager.swapdata.provider"
        private const val SWAP_DATA_URI = "content://$SWAP_DATA_AUTHORITY/swapdata"

        private val SUPPORTED_CHAINS = setOf(1, 10, 137, 42161, 8453)

        private val CHAIN_NAMES = mapOf(
            1 to "eth-mainnet", 10 to "opt-mainnet", 137 to "polygon-mainnet",
            42161 to "arb-mainnet", 8453 to "base-mainnet",
        )

        private fun chainIdToRpc(chainId: Int): String? {
            val name = CHAIN_NAMES[chainId] ?: return null
            return "https://${name}.g.alchemy.com/v2/${BuildConfig.ALCHEMY_API}"
        }

        private fun chainIdToBundler(chainId: Int): String =
            "https://api.pimlico.io/v2/$chainId/rpc?apikey=${BuildConfig.BUNDLER_API}"
    }

    private val walletsByChain = mutableMapOf<Int, WalletSDK>()

    private fun getOrCreateWallet(chainId: Int): WalletSDK? {
        walletsByChain[chainId]?.let { return it }
        val rpc = chainIdToRpc(chainId) ?: return null
        return try {
            val sdk = WalletSDK(
                context = context,
                web3jInstance = Web3j.build(HttpService(rpc)),
                bundlerRPCUrl = chainIdToBundler(chainId),
            )
            walletsByChain[chainId] = sdk
            sdk
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init WalletSDK for chain $chainId: ${e.message}")
            null
        }
    }

    override val baseManifest = SkillManifest(
        description = "Swap tokens via the WalletManager on ethOS devices.",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Swap tokens via the WalletManager ContentProvider on ethOS. " +
                "Supports Ethereum, Optimism, Polygon, Arbitrum, and Base. " +
                "Handles approval + swap transactions as a batch. " +
                "For native ETH, use address 0x0000000000000000000000000000000000000000.",
        tools = listOf(
            ToolDefinition(
                name = "swap_tokens",
                description = "Swap tokens via the WalletManager app. Queries swap data from the " +
                        "WalletManager ContentProvider and executes approval + swap as a batch transaction. " +
                        "The user will be prompted to approve. " +
                        "For native ETH use 0x0000000000000000000000000000000000000000. " +
                        "Supported chains: Ethereum (1), Optimism (10), Polygon (137), Arbitrum (42161), Base (8453). " +
                        "NOTE: This uses the WalletManager app which must be installed on the device. " +
                        "If unavailable, use the existing agent_swap or get_swap_quote tools from the wallet skill instead.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "sell_token" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Contract address of token to sell. Use 0x0000000000000000000000000000000000000000 for native ETH."
                            ),
                        )),
                        "buy_token" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Contract address of token to buy."
                            ),
                        )),
                        "sell_amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Human-readable amount to sell (e.g. '100' for 100 USDC, '0.1' for 0.1 ETH)"
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Chain ID: 1 (Ethereum), 10 (Optimism), 137 (Polygon), 42161 (Arbitrum), 8453 (Base)"
                            ),
                        )),
                        "sell_decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Decimals of sell token (e.g. 18 for ETH, 6 for USDC). Default: 18"),
                        )),
                        "buy_decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Decimals of buy token. Default: 18"),
                        )),
                        "sell_symbol" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Symbol of sell token (e.g. ETH, USDC). Helps with native ETH detection."),
                        )),
                        "buy_symbol" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Symbol of buy token. Helps with native ETH detection."),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("sell_token"),
                        JsonPrimitive("buy_token"),
                        JsonPrimitive("sell_amount"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
                requiresApproval = true,
            ),
        ),
    )

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "swap_tokens" -> swapTokens(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    private suspend fun swapTokens(params: JsonObject): SkillResult {
        val sellToken = params["sell_token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("'sell_token' is required")
        val buyToken = params["buy_token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("'buy_token' is required")
        val sellAmountStr = params["sell_amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("'sell_amount' is required")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("'chain_id' is required")
        val sellDecimals = params["sell_decimals"]?.jsonPrimitive?.intOrNull ?: 18
        val buyDecimals = params["buy_decimals"]?.jsonPrimitive?.intOrNull ?: 18
        val sellSymbol = params["sell_symbol"]?.jsonPrimitive?.contentOrNull ?: ""
        val buySymbol = params["buy_symbol"]?.jsonPrimitive?.contentOrNull ?: ""

        if (chainId !in SUPPORTED_CHAINS) {
            return SkillResult.Error(
                "Unsupported chain: $chainId. Supported: ${SUPPORTED_CHAINS.joinToString()}"
            )
        }

        val sellAmount = try {
            BigDecimal(sellAmountStr)
        } catch (e: Exception) {
            return SkillResult.Error("Invalid sell_amount: $sellAmountStr")
        }

        return withContext(Dispatchers.IO) {
            // Query ContentProvider for swap data
            val swapResult = querySwapProvider(
                sellToken, buyToken, sellAmount, chainId,
                sellDecimals, buyDecimals, sellSymbol, buySymbol,
            )

            if (swapResult.error.isNotEmpty()) {
                return@withContext SkillResult.Error(swapResult.error)
            }
            if (swapResult.transactions.isEmpty()) {
                return@withContext SkillResult.Error("No swap transactions returned from provider")
            }

            // Execute transactions via WalletSDK
            val wallet = getOrCreateWallet(chainId)
                ?: return@withContext SkillResult.Error("Wallet not available for chain $chainId")

            val rpc = chainIdToRpc(chainId)
                ?: return@withContext SkillResult.Error("Unsupported chain")
            if (chainId != wallet.getChainId()) {
                wallet.changeChain(chainId, rpc, chainIdToBundler(chainId))
            }

            try {
                val batchTxParams = swapResult.transactions.map { tx ->
                    WalletSDK.TxParams(to = tx.to, value = tx.value, data = tx.data)
                }

                val txHash = if (batchTxParams.size == 1) {
                    val tx = batchTxParams.first()
                    wallet.sendTransaction(
                        to = tx.to,
                        value = tx.value,
                        data = tx.data,
                        callGas = null,
                        chainId = chainId,
                        rpcEndpoint = rpc,
                    )
                } else {
                    wallet.sendTransaction(
                        txParamsList = batchTxParams,
                        callGas = null,
                        chainId = chainId,
                    )
                }

                if (txHash == null || txHash == "error") {
                    return@withContext SkillResult.Error("Swap transaction failed")
                }
                if (txHash == "decline") {
                    return@withContext SkillResult.Error("Swap declined by user")
                }

                val sb = StringBuilder("Swap executed successfully!\n")
                sb.appendLine("Sold: $sellAmountStr ${sellSymbol.ifBlank { shortenAddress(sellToken) }}")
                if (swapResult.buyAmount.isNotBlank()) {
                    try {
                        val rawBuy = BigDecimal(swapResult.buyAmount)
                        val humanBuy = rawBuy.divide(
                            BigDecimal.TEN.pow(buyDecimals), 8, java.math.RoundingMode.HALF_UP
                        ).stripTrailingZeros().toPlainString()
                        sb.appendLine("Received: ~$humanBuy ${buySymbol.ifBlank { shortenAddress(buyToken) }}")
                    } catch (_: Exception) { }
                }
                sb.appendLine("Transaction: $txHash")
                SkillResult.Success(sb.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "Swap execution failed", e)
                SkillResult.Error("Swap execution failed: ${e.message}")
            }
        }
    }

    // ── ContentProvider query ─────────────────────────────────────────────

    private data class TxParams(val to: String, val value: String, val data: String)
    private data class SwapResult(
        val transactions: List<TxParams>,
        val buyAmount: String,
        val sellAmount: String,
        val error: String,
    )

    private fun querySwapProvider(
        sellToken: String, buyToken: String, sellAmount: BigDecimal, chainId: Int,
        sellDecimals: Int, buyDecimals: Int, sellSymbol: String, buySymbol: String,
    ): SwapResult {
        try {
            val uri = Uri.parse(SWAP_DATA_URI).buildUpon()
                .appendQueryParameter("sellToken", sellToken)
                .appendQueryParameter("buyToken", buyToken)
                .appendQueryParameter("sellAmount", sellAmount.toPlainString())
                .appendQueryParameter("chainId", chainId.toString())
                .appendQueryParameter("sellDecimals", sellDecimals.toString())
                .appendQueryParameter("buyDecimals", buyDecimals.toString())
                .appendQueryParameter("sellSymbol", sellSymbol)
                .appendQueryParameter("buySymbol", buySymbol)
                .build()

            Log.d(TAG, "Querying swap provider: $uri")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: return SwapResult(emptyList(), "", "", "WalletManager may not be installed")

            cursor.use {
                val txs = mutableListOf<TxParams>()
                var buyAmt = ""
                var sellAmt = ""
                var error = ""

                while (it.moveToNext()) {
                    val errIdx = it.getColumnIndex("error")
                    if (errIdx != -1) {
                        val err = it.getString(errIdx) ?: ""
                        if (err.isNotEmpty()) { error = err; break }
                    }

                    val toIdx = it.getColumnIndex("to")
                    val valIdx = it.getColumnIndex("value")
                    val dataIdx = it.getColumnIndex("data")
                    if (toIdx == -1 || valIdx == -1 || dataIdx == -1) {
                        error = "Invalid response from swap provider"; break
                    }

                    txs.add(TxParams(
                        to = it.getString(toIdx) ?: "",
                        value = it.getString(valIdx) ?: "0",
                        data = it.getString(dataIdx) ?: "",
                    ))

                    val txType = it.getColumnIndex("tx_type").let { idx ->
                        if (idx != -1) it.getString(idx) else "swap"
                    }
                    if (txType == "swap") {
                        it.getColumnIndex("buy_amount").let { idx ->
                            if (idx != -1) buyAmt = it.getString(idx) ?: ""
                        }
                        it.getColumnIndex("sell_amount").let { idx ->
                            if (idx != -1) sellAmt = it.getString(idx) ?: ""
                        }
                    }
                }

                if (error.isEmpty() && txs.isEmpty()) error = "No transactions returned"
                return SwapResult(txs, buyAmt, sellAmt, error)
            }
        } catch (e: SecurityException) {
            return SwapResult(emptyList(), "", "", "Permission denied — WalletManager may not grant access")
        } catch (e: IllegalArgumentException) {
            return SwapResult(emptyList(), "", "", "WalletManager not installed")
        } catch (e: Exception) {
            Log.e(TAG, "Swap provider query failed", e)
            return SwapResult(emptyList(), "", "", "Swap provider error: ${e.message}")
        }
    }

    private fun shortenAddress(addr: String): String =
        if (addr.length > 10) "${addr.take(6)}...${addr.takeLast(4)}" else addr
}
