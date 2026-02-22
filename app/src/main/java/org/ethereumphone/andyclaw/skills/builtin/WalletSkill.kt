package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.subwalletsdk.SubWalletSDK
import org.ethereumphone.walletsdk.WalletSDK
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.math.BigDecimal
import java.math.BigInteger

class WalletSkill(private val context: Context) : AndyClawSkill {
    override val id = "wallet"
    override val name = "Wallet"

    companion object {
        private const val TAG = "WalletSkill"

        /** Alchemy network names keyed by chain ID. */
        private val CHAIN_NAMES = mapOf(
            1 to "eth-mainnet",
            10 to "opt-mainnet",
            137 to "polygon-mainnet",
            42161 to "arb-mainnet",
            8453 to "base-mainnet",
            7777777 to "zora-mainnet",
            56 to "bnb-mainnet",
            43114 to "avax-mainnet",
        )

        private fun chainIdToRpc(chainId: Int): String? {
            val name = CHAIN_NAMES[chainId] ?: return null
            return "https://${name}.g.alchemy.com/v2/${BuildConfig.ALCHEMY_API}"
        }

        private fun chainIdToBundler(chainId: Int): String {
            return "https://api.pimlico.io/v2/$chainId/rpc?apikey=${BuildConfig.BUNDLER_API}"
        }
    }

    // ── SDK caches ──────────────────────────────────────────────────────

    /** Cache of per-chain WalletSDK instances (user's OS wallet). */
    private val walletsByChain = mutableMapOf<Int, WalletSDK>()

    /** Cache of per-chain SubWalletSDK instances (agent's own wallet). */
    private val subWalletsByChain = mutableMapOf<Int, SubWalletSDK>()

    /**
     * Default WalletSDK instance (Ethereum mainnet) used for non-chain-specific
     * calls like getAddress() and token queries via WalletManager content providers.
     */
    private val defaultWallet: WalletSDK? by lazy {
        getOrCreateWallet(1)
    }

    /**
     * Default SubWalletSDK instance (Ethereum mainnet) used for
     * non-chain-specific calls like getAddress().
     */
    private val defaultSubWallet: SubWalletSDK? by lazy {
        getOrCreateSubWallet(1)
    }

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
            Log.w(TAG, "Failed to initialize WalletSDK for chain $chainId: ${e.message}")
            null
        }
    }

    private fun getOrCreateSubWallet(chainId: Int): SubWalletSDK? {
        subWalletsByChain[chainId]?.let { return it }
        val rpc = chainIdToRpc(chainId) ?: return null
        return try {
            val sdk = SubWalletSDK(
                context = context,
                web3jInstance = Web3j.build(HttpService(rpc)),
                bundlerRPCUrl = chainIdToBundler(chainId),
            )
            subWalletsByChain[chainId] = sdk
            sdk
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize SubWalletSDK for chain $chainId: ${e.message}")
            null
        }
    }

    // ── Tool definitions ────────────────────────────────────────────────

    override val baseManifest = SkillManifest(
        description = "",
        tools = emptyList(),
    )

    override val privilegedManifest = SkillManifest(
        description = "Interact with the ethOS wallet system. You have TWO wallets: " +
                "(1) the user's OS system wallet — transactions require on-device approval, and " +
                "(2) your own agent sub-account wallet — you can sign and send autonomously. " +
                "Supported chains: Ethereum (1), Optimism (10), Polygon (137), Arbitrum (42161), " +
                "Base (8453), Zora (7777777), BNB (56), Avalanche (43114).",
        tools = listOf(
            // ── User wallet (WalletSDK) ─────────────────────────────
            ToolDefinition(
                name = "get_user_wallet_address",
                description = "Get the user's ethOS system wallet address.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
            ),
            ToolDefinition(
                name = "get_owned_tokens",
                description = "Get all tokens owned by the user's wallet with balances, USD prices, " +
                        "and total values. Returns a portfolio view of all tokens with positive " +
                        "balances. Optionally filter by chain ID.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Optional chain ID to filter tokens " +
                                        "(e.g., 1 for Ethereum, 8453 for Base). " +
                                        "Omit to get tokens across all chains."
                            ),
                        )),
                    )),
                )),
            ),
            ToolDefinition(
                name = "get_swap_quote",
                description = "Get a DEX swap quote for exchanging tokens. Returns the expected " +
                        "output amount, price, minimum amount after slippage, and gas costs. " +
                        "Use get_owned_tokens first to find token contract addresses and decimals. " +
                        "Supported swap chains: Ethereum (1), Optimism (10), Polygon (137), " +
                        "Base (8453), Arbitrum (42161).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "sell_token" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Contract address of the token to sell (0x-prefixed)"),
                        )),
                        "buy_token" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Contract address of the token to buy (0x-prefixed)"),
                        )),
                        "sell_amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount of sell token in human-readable form " +
                                        "(e.g., '100' for 100 USDC, not in smallest unit)"
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Chain ID where the swap will occur"),
                        )),
                        "sell_decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Decimals of the sell token (e.g., 6 for USDC, 18 for ETH/WETH)"),
                        )),
                        "buy_decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Decimals of the buy token (e.g., 6 for USDC, 18 for ETH/WETH)"),
                        )),
                        "sell_symbol" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Optional ticker symbol of sell token (e.g., 'USDC'). Helps with ETH detection."),
                        )),
                        "buy_symbol" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Optional ticker symbol of buy token (e.g., 'WETH'). Helps with ETH detection."),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("sell_token"),
                        JsonPrimitive("buy_token"),
                        JsonPrimitive("sell_amount"),
                        JsonPrimitive("chain_id"),
                        JsonPrimitive("sell_decimals"),
                        JsonPrimitive("buy_decimals"),
                    )),
                )),
            ),
            ToolDefinition(
                name = "propose_transaction",
                description = "Propose a blockchain transaction from the user's system wallet. " +
                        "The user will be prompted to approve the transaction on-device before it is sent. " +
                        "Returns a user operation hash on success. " +
                        "For simple native token (ETH/MATIC/etc.) transfers, set data to '0'. " +
                        "For contract interactions, provide the ABI-encoded calldata as a 0x-prefixed hex string. " +
                        "To fund the agent's own wallet, use this with the agent's address as the recipient.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient address (0x-prefixed, 42 characters)"),
                        )),
                        "value" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount of native token to send in wei " +
                                        "(e.g., '1000000000000000000' for 1 ETH, '0' for pure contract calls). " +
                                        "1 ETH = 10^18 wei."
                            ),
                        )),
                        "data" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Transaction calldata. Use '0' for simple native token transfers. " +
                                        "For contract calls, provide 0x-prefixed hex-encoded calldata."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Chain ID for the transaction (e.g., 1 for Ethereum, 8453 for Base)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("to"),
                        JsonPrimitive("value"),
                        JsonPrimitive("data"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "propose_token_transfer",
                description = "Propose an ERC-20 token transfer from the user's system wallet. " +
                        "The user will be prompted to approve the transaction on-device. " +
                        "Use get_owned_tokens first to find the contract address and decimals of the token. " +
                        "To fund the agent's own wallet with tokens, use the agent's address as the recipient.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "contract_address" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("ERC-20 token contract address (0x-prefixed)"),
                        )),
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient address (0x-prefixed, 42 characters)"),
                        )),
                        "amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount to send in human-readable form " +
                                        "(e.g., '100' to send 100 USDC, '0.5' to send 0.5 WETH)"
                            ),
                        )),
                        "decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Token decimals (e.g., 6 for USDC, 18 for WETH). " +
                                        "Available from get_owned_tokens results."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Chain ID where the token exists (e.g., 1 for Ethereum, 8453 for Base)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("contract_address"),
                        JsonPrimitive("to"),
                        JsonPrimitive("amount"),
                        JsonPrimitive("decimals"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
                requiresApproval = true,
            ),
            // ── Agent wallet (SubWalletSDK) ─────────────────────────
            ToolDefinition(
                name = "get_agent_wallet_address",
                description = "Get the agent's own sub-account wallet address. " +
                        "This is the wallet the agent can send from autonomously without user approval.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(emptyMap()),
                )),
            ),
            ToolDefinition(
                name = "agent_send_transaction",
                description = "Send a blockchain transaction from the agent's own sub-account wallet. " +
                        "Does NOT require user approval — signs locally and submits to the bundler. " +
                        "The agent wallet must be funded first (use propose_transaction to send funds from the user's wallet). " +
                        "For simple native token (ETH/MATIC/etc.) transfers, set data to '0x'. " +
                        "For contract interactions, provide the ABI-encoded calldata as a 0x-prefixed hex string.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient address (0x-prefixed, 42 characters)"),
                        )),
                        "value" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount of native token to send in wei " +
                                        "(e.g., '1000000000000000000' for 1 ETH, '0' for pure contract calls). " +
                                        "1 ETH = 10^18 wei."
                            ),
                        )),
                        "data" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Transaction calldata. Use '0x' for simple native token transfers. " +
                                        "For contract calls, provide 0x-prefixed hex-encoded calldata."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Chain ID for the transaction (e.g., 1 for Ethereum, 8453 for Base)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("to"),
                        JsonPrimitive("value"),
                        JsonPrimitive("data"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
            ),
            ToolDefinition(
                name = "agent_transfer_token",
                description = "Transfer an ERC-20 token from the agent's own sub-account wallet. " +
                        "Does NOT require user approval — signs locally and submits to the bundler. " +
                        "The agent wallet must hold the token first. " +
                        "Use get_owned_tokens to find the contract address and decimals of the token.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "contract_address" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("ERC-20 token contract address (0x-prefixed)"),
                        )),
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient address (0x-prefixed, 42 characters)"),
                        )),
                        "amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount to send in human-readable form " +
                                        "(e.g., '100' to send 100 USDC, '0.5' to send 0.5 WETH)"
                            ),
                        )),
                        "decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Token decimals (e.g., 6 for USDC, 18 for WETH). " +
                                        "Available from get_owned_tokens results."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive("Chain ID where the token exists (e.g., 1 for Ethereum, 8453 for Base)"),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("contract_address"),
                        JsonPrimitive("to"),
                        JsonPrimitive("amount"),
                        JsonPrimitive("decimals"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
            ),
        ),
    )

    // ── Tool dispatch ───────────────────────────────────────────────────

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        if (tier != Tier.PRIVILEGED) {
            return SkillResult.Error("Wallet tools require ethOS (privileged access).")
        }

        return when (tool) {
            // User wallet (WalletSDK)
            "get_user_wallet_address" -> {
                val w = defaultWallet ?: return walletUnavailableError()
                getUserWalletAddress(w)
            }
            "get_owned_tokens" -> {
                val w = defaultWallet ?: return walletUnavailableError()
                getOwnedTokens(w, params)
            }
            "get_swap_quote" -> {
                val w = defaultWallet ?: return walletUnavailableError()
                getSwapQuote(w, params)
            }
            "propose_transaction" -> proposeTransaction(params)
            "propose_token_transfer" -> proposeTokenTransfer(params)

            // Agent wallet (SubWalletSDK)
            "get_agent_wallet_address" -> {
                val sw = defaultSubWallet ?: return subWalletUnavailableError()
                getAgentWalletAddress(sw)
            }
            "agent_send_transaction" -> agentSendTransaction(params)
            "agent_transfer_token" -> agentTransferToken(params)

            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    // ── Error helpers ───────────────────────────────────────────────────

    private fun walletUnavailableError() = SkillResult.Error(
        "System wallet not available. " +
                "Ensure this device runs ethOS with the wallet service enabled."
    )

    private fun subWalletUnavailableError() = SkillResult.Error(
        "Agent sub-account wallet not available. " +
                "Ensure this device runs ethOS with the wallet service enabled."
    )

    // ── User wallet operations ──────────────────────────────────────────

    private suspend fun getUserWalletAddress(wallet: WalletSDK): SkillResult {
        return try {
            val address = withContext(Dispatchers.IO) { wallet.getAddress() }
            SkillResult.Success(
                buildJsonObject { put("address", address) }.toString()
            )
        } catch (e: Exception) {
            SkillResult.Error("Failed to get wallet address: ${e.message}")
        }
    }

    private suspend fun getOwnedTokens(wallet: WalletSDK, params: JsonObject): SkillResult {
        return try {
            val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            val tokens = withContext(Dispatchers.IO) {
                if (chainId != null) {
                    wallet.getOwnedTokensByChain(chainId)
                } else {
                    wallet.getAllOwnedTokens()
                }
            }
            val result = buildJsonArray {
                tokens.forEach { t ->
                    add(buildJsonObject {
                        put("contract_address", t.contractAddress)
                        put("chain_id", t.chainId)
                        put("name", t.name)
                        put("symbol", t.symbol)
                        put("decimals", t.decimals)
                        put("balance", t.balance.toPlainString())
                        put("price_usd", t.price)
                        put("total_value_usd", t.totalValue)
                        put("swappable", t.swappable)
                    })
                }
            }
            SkillResult.Success(result.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get owned tokens: ${e.message}")
        }
    }

    private suspend fun getSwapQuote(wallet: WalletSDK, params: JsonObject): SkillResult {
        val sellToken = params["sell_token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: sell_token")
        val buyToken = params["buy_token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: buy_token")
        val sellAmount = params["sell_amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: sell_amount")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")
        val sellDecimals = params["sell_decimals"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: sell_decimals")
        val buyDecimals = params["buy_decimals"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: buy_decimals")
        val sellSymbol = params["sell_symbol"]?.jsonPrimitive?.contentOrNull
        val buySymbol = params["buy_symbol"]?.jsonPrimitive?.contentOrNull

        return try {
            val quote = withContext(Dispatchers.IO) {
                wallet.getSwapQuote(
                    sellToken = sellToken,
                    buyToken = buyToken,
                    sellAmount = sellAmount,
                    chainId = chainId,
                    sellDecimals = sellDecimals,
                    buyDecimals = buyDecimals,
                    sellSymbol = sellSymbol ?: "",
                    buySymbol = buySymbol ?: "",
                )
            }
            if (quote == null) {
                return SkillResult.Error("Failed to get swap quote: no response")
            }
            if (!quote.isSuccess) {
                return SkillResult.Error("Swap quote failed: ${quote.error}")
            }
            SkillResult.Success(buildJsonObject {
                put("sell_token", quote.sellToken)
                put("buy_token", quote.buyToken)
                put("sell_amount", quote.sellAmount)
                put("buy_amount", quote.buyAmount)
                put("min_buy_amount", quote.minBuyAmount)
                put("price", quote.price)
                put("guaranteed_price", quote.guaranteedPrice)
                put("estimated_price_impact", quote.estimatedPriceImpact)
                put("gas", quote.gas)
                put("gas_price", quote.gasPrice)
                put("total_network_fee", quote.totalNetworkFee)
                put("allowance_target", quote.allowanceTarget)
                put("chain_id", quote.chainId)
                put("liquidity_available", quote.liquidityAvailable)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to get swap quote: ${e.message}")
        }
    }

    private suspend fun proposeTransaction(params: JsonObject): SkillResult {
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val value = params["value"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: value")
        val data = params["data"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: data")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")

        val rpcEndpoint = chainIdToRpc(chainId)
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

        val w = getOrCreateWallet(chainId)
            ?: return walletUnavailableError()

        return try {
            val result = withContext(Dispatchers.IO) {
                if (chainId != w.getChainId()) {
                    w.changeChain(
                        chainId = chainId,
                        rpcEndpoint = rpcEndpoint,
                        mBundlerRPCUrl = chainIdToBundler(chainId),
                    )
                }
                w.sendTransaction(
                    to = to,
                    value = value,
                    data = data,
                    callGas = null,
                    chainId = chainId,
                    rpcEndpoint = rpcEndpoint,
                )
            }
            if (result == "decline") {
                SkillResult.Error("Transaction was declined by the user.")
            } else {
                SkillResult.Success(buildJsonObject {
                    put("user_op_hash", result)
                    put("status", "submitted")
                    put("chain_id", chainId)
                }.toString())
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to send transaction: ${e.message}")
        }
    }

    private suspend fun proposeTokenTransfer(params: JsonObject): SkillResult {
        val contractAddress = params["contract_address"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: contract_address")
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val amount = params["amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: amount")
        val decimals = params["decimals"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: decimals")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")

        val rpcEndpoint = chainIdToRpc(chainId)
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

        val rawAmount = try {
            BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(decimals))
                .toBigIntegerExact()
        } catch (e: Exception) {
            return SkillResult.Error("Invalid amount '$amount': ${e.message}")
        }

        val function = Function(
            "transfer",
            listOf(Address(to), Uint256(rawAmount)),
            emptyList(),
        )
        val encodedData = FunctionEncoder.encode(function)

        val w = getOrCreateWallet(chainId)
            ?: return walletUnavailableError()

        return try {
            val result = withContext(Dispatchers.IO) {
                if (chainId != w.getChainId()) {
                    w.changeChain(
                        chainId = chainId,
                        rpcEndpoint = rpcEndpoint,
                        mBundlerRPCUrl = chainIdToBundler(chainId),
                    )
                }
                w.sendTransaction(
                    to = contractAddress,
                    value = "0",
                    data = encodedData,
                    callGas = null,
                    chainId = chainId,
                    rpcEndpoint = rpcEndpoint,
                )
            }
            if (result == "decline") {
                SkillResult.Error("Transaction was declined by the user.")
            } else {
                SkillResult.Success(buildJsonObject {
                    put("user_op_hash", result)
                    put("status", "submitted")
                    put("chain_id", chainId)
                    put("token", contractAddress)
                    put("to", to)
                    put("amount", amount)
                }.toString())
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to transfer token: ${e.message}")
        }
    }

    // ── Agent wallet operations (SubWalletSDK) ──────────────────────────

    private suspend fun getAgentWalletAddress(subWallet: SubWalletSDK): SkillResult {
        return try {
            val address = withContext(Dispatchers.IO) { subWallet.getAddress() }
            SkillResult.Success(
                buildJsonObject { put("address", address) }.toString()
            )
        } catch (e: Exception) {
            SkillResult.Error("Failed to get agent wallet address: ${e.message}")
        }
    }

    private suspend fun agentSendTransaction(params: JsonObject): SkillResult {
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val value = params["value"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: value")
        val data = params["data"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: data")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")

        val rpcEndpoint = chainIdToRpc(chainId)
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

        val sw = getOrCreateSubWallet(chainId)
            ?: return subWalletUnavailableError()

        return try {
            val result = withContext(Dispatchers.IO) {
                if (chainId != sw.getChainId()) {
                    sw.changeChain(
                        chainId = chainId,
                        rpcEndpoint = rpcEndpoint,
                        mBundlerRPCUrl = chainIdToBundler(chainId),
                    )
                }
                sw.sendTransaction(
                    to = to,
                    value = value,
                    data = data,
                    callGas = null,
                    chainId = chainId,
                )
            }
            SkillResult.Success(buildJsonObject {
                put("user_op_hash", result)
                put("status", "submitted")
                put("chain_id", chainId)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to send agent transaction: ${e.message}")
        }
    }

    private suspend fun agentTransferToken(params: JsonObject): SkillResult {
        val contractAddress = params["contract_address"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: contract_address")
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val amount = params["amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: amount")
        val decimals = params["decimals"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: decimals")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")

        val rpcEndpoint = chainIdToRpc(chainId)
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

        val rawAmount = try {
            BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(decimals))
                .toBigIntegerExact()
        } catch (e: Exception) {
            return SkillResult.Error("Invalid amount '$amount': ${e.message}")
        }

        val function = Function(
            "transfer",
            listOf(Address(to), Uint256(rawAmount)),
            emptyList(),
        )
        val encodedData = FunctionEncoder.encode(function)

        val sw = getOrCreateSubWallet(chainId)
            ?: return subWalletUnavailableError()

        return try {
            val result = withContext(Dispatchers.IO) {
                if (chainId != sw.getChainId()) {
                    sw.changeChain(
                        chainId = chainId,
                        rpcEndpoint = rpcEndpoint,
                        mBundlerRPCUrl = chainIdToBundler(chainId),
                    )
                }
                sw.sendTransaction(
                    to = contractAddress,
                    value = "0",
                    data = encodedData,
                    callGas = null,
                    chainId = chainId,
                )
            }
            SkillResult.Success(buildJsonObject {
                put("user_op_hash", result)
                put("status", "submitted")
                put("chain_id", chainId)
                put("token", contractAddress)
                put("to", to)
                put("amount", amount)
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to transfer token from agent wallet: ${e.message}")
        }
    }
}
