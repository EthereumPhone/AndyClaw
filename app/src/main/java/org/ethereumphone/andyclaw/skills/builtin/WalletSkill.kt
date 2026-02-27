package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ethereumphone.subwalletsdk.SubWalletSDK
import org.ethereumphone.walletsdk.WalletSDK
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

class WalletSkill(private val context: Context) : AndyClawSkill {
    override val id = "wallet"
    override val name = "Wallet"

    companion object {
        private const val TAG = "WalletSkill"

        // ── 0x Swap API constants ─────────────────────────────────────
        private const val ZEROX_API_BASE_URL = "https://api.0x.org"
        private const val ZEROX_API_VERSION = "v2"
        private const val ETH_TOKEN_ADDRESS = "0xEeeeeEeeeEeEeeEeEeEeeEEEeeeeEeeeeeeeEEeE"
        private const val SWAP_FEE_BPS = 15 // 0.15%
        private const val SWAP_FEE_RECIPIENT = "0xF1F39090D2bE5010Cc1Dd633b6dCe476A38b5675"
        private val ZEROX_SUPPORTED_CHAIN_IDS = setOf(1, 10, 137, 42161, 8453)

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

        // ── Native token info per chain ──────────────────────────────────

        data class NativeTokenInfo(
            val symbol: String,
            val name: String,
            val decimals: Int = 18,
        )

        val NATIVE_TOKENS = mapOf(
            1 to NativeTokenInfo("ETH", "Ether"),
            10 to NativeTokenInfo("ETH", "Ether"),
            137 to NativeTokenInfo("POL", "POL (ex-MATIC)"),
            42161 to NativeTokenInfo("ETH", "Ether"),
            8453 to NativeTokenInfo("ETH", "Ether"),
            7777777 to NativeTokenInfo("ETH", "Ether"),
            56 to NativeTokenInfo("BNB", "BNB"),
            43114 to NativeTokenInfo("AVAX", "Avalanche"),
        )

        // ── Well-known ERC-20 tokens ────────────────────────────────────

        data class WellKnownToken(
            val symbol: String,
            val name: String,
            val decimals: Int,
            /** chainId -> contract address (checksummed). */
            val addresses: Map<Int, String>,
        )

        /**
         * Registry of well-known tokens with verified contract addresses.
         * The LLM can look these up by symbol instead of needing to call
         * get_owned_tokens first.
         */
        val WELL_KNOWN_TOKENS = listOf(
            WellKnownToken(
                symbol = "USDC", name = "USD Coin", decimals = 6,
                addresses = mapOf(
                    1 to "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                    10 to "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85",
                    137 to "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359",
                    42161 to "0xaf88d065e77c8cC2239327C5EDb3A432268e5831",
                    8453 to "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                    43114 to "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E",
                ),
            ),
            WellKnownToken(
                symbol = "USDT", name = "Tether USD", decimals = 6,
                addresses = mapOf(
                    1 to "0xdAC17F958D2ee523a2206206994597C13D831ec7",
                    10 to "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58",
                    137 to "0xc2132D05D31c914a87C6611C10748AEb04B58e8F",
                    42161 to "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9",
                    43114 to "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7",
                ),
            ),
            // Note: BSC USDT uses 18 decimals, listed separately
            WellKnownToken(
                symbol = "USDT", name = "Tether USD (BSC)", decimals = 18,
                addresses = mapOf(
                    56 to "0x55d398326f99059fF775485246999027B3197955",
                ),
            ),
            WellKnownToken(
                symbol = "WETH", name = "Wrapped Ether", decimals = 18,
                addresses = mapOf(
                    1 to "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2",
                    10 to "0x4200000000000000000000000000000000000006",
                    137 to "0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619",
                    42161 to "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1",
                    8453 to "0x4200000000000000000000000000000000000006",
                ),
            ),
            WellKnownToken(
                symbol = "DAI", name = "Dai Stablecoin", decimals = 18,
                addresses = mapOf(
                    1 to "0x6B175474E89094C44Da98b954EedeAC495271d0F",
                    10 to "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1",
                    137 to "0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063",
                    42161 to "0xDA10009cBd5D07dd0CeCc66161FC93D7c9000da1",
                    8453 to "0x50c5725949A6F0c72E6C4a641F24049A917DB0Cb",
                ),
            ),
            WellKnownToken(
                symbol = "WBTC", name = "Wrapped Bitcoin", decimals = 8,
                addresses = mapOf(
                    1 to "0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599",
                    10 to "0x68f180fcCe6836688e9084f035309E29Bf0A2095",
                    137 to "0x1BFD67037B42Cf73acF2047067bd4F2C47D9BfD6",
                    42161 to "0x2f2a2543B76A4166549F7aaB2e75Bef0aefC5B0f",
                ),
            ),
            WellKnownToken(
                symbol = "LINK", name = "Chainlink", decimals = 18,
                addresses = mapOf(
                    1 to "0x514910771AF9Ca656af840dff83E8264EcF986CA",
                    10 to "0x350a791Bfc2C21F9Ed5d10980Dad2e2638ffa7f6",
                    137 to "0x53E0bca35eC356BD5ddDFebbD1Fc0fD03FaBad39",
                    42161 to "0xf97f4df75117a78c1A5a0DBb814Af92458539FB4",
                    8453 to "0x88Fb150BDc53A65fe94Dea0c9BA0a6dAf8C6e196",
                ),
            ),
            WellKnownToken(
                symbol = "UNI", name = "Uniswap", decimals = 18,
                addresses = mapOf(
                    1 to "0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984",
                    10 to "0x6fd9d7AD17242c41f7131d257212c54A0e816691",
                    137 to "0xb33EaAd8d922B1083446DC23f610c2567fB5180f",
                    42161 to "0xFa7F8980b0f1E64A2062791cc3b0871572f1F7f0",
                ),
            ),
            WellKnownToken(
                symbol = "DEGEN", name = "Degen", decimals = 18,
                addresses = mapOf(
                    8453 to "0x4ed4E862860beD51a9570b96d89aF5E1B0Efefed",
                ),
            ),
            WellKnownToken(
                symbol = "AAVE", name = "Aave", decimals = 18,
                addresses = mapOf(
                    1 to "0x7Fc66500c84A76Ad7e9c93437bFc5Ac33E2DDaE9",
                    10 to "0x76FB31fb4af56892A25e32cFC43De717950c9278",
                    137 to "0xD6DF932A45C0f255f85145f286eA0b292B21C90B",
                    42161 to "0xba5DdD1f9d7F570dc94a51479a000E3BCE967196",
                ),
            ),
        )

        /**
         * Find a well-known token by symbol and chain.
         * Returns (contractAddress, decimals) or null.
         */
        fun resolveWellKnownToken(symbol: String, chainId: Int): Pair<String, Int>? {
            val upper = symbol.uppercase()
            for (token in WELL_KNOWN_TOKENS) {
                if (token.symbol.uppercase() == upper) {
                    val address = token.addresses[chainId] ?: continue
                    return address to token.decimals
                }
            }
            return null
        }

        /**
         * Find all chain deployments for a symbol.
         */
        fun findTokenBySymbol(symbol: String): List<WellKnownToken> {
            val upper = symbol.uppercase()
            return WELL_KNOWN_TOKENS.filter { it.symbol.uppercase() == upper }
        }
    }

    // ── SDK caches ──────────────────────────────────────────────────────

    /** Cache of per-chain WalletSDK instances (user's OS wallet). */
    private val walletsByChain = mutableMapOf<Int, WalletSDK>()

    /** Cache of per-chain SubWalletSDK instances (agent's own wallet). */
    private val subWalletsByChain = mutableMapOf<Int, SubWalletSDK>()

    /** HTTP client for direct 0x API calls (agent swap). */
    private val swapHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

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
                "Base (8453), Zora (7777777), BNB (56), Avalanche (43114). " +
                "IMPORTANT: For sending crypto, prefer the high-level tools: " +
                "send_token/agent_send_token for ERC-20s (auto-resolves USDC, USDT, WETH, DAI, etc.), " +
                "send_native_token/agent_send_native_token for ETH/MATIC/BNB/AVAX. " +
                "For swapping tokens from the agent wallet, use agent_swap (accepts symbols like 'USDC', 'WETH', 'ETH'). " +
                "These take human-readable amounts (e.g., '100' for 100 USDC) and handle all conversions. " +
                "Use read_wallet_holdings to check the user's portfolio, " +
                "read_agent_balance to check the agent wallet's balance.",
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
                description = "LOW-LEVEL: Propose a raw blockchain transaction from the user's system wallet. " +
                        "PREFER send_native_token for ETH/MATIC/BNB/AVAX sends, or send_token for ERC-20 transfers. " +
                        "Only use this for advanced contract interactions where higher-level tools don't apply. " +
                        "The user will be prompted to approve. " +
                        "IMPORTANT: 'value' must be in wei (1 ETH = 1000000000000000000 wei). " +
                        "For simple native token transfers set data to '0'. " +
                        "For contract interactions provide ABI-encoded calldata as 0x-prefixed hex.",
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
                description = "LOW-LEVEL: Propose an ERC-20 token transfer from the user's system wallet. " +
                        "PREFER send_token instead — it auto-resolves contract addresses and decimals for common tokens (USDC, USDT, WETH, DAI, etc.). " +
                        "Only use this if send_token can't resolve the token or you already have the contract address and decimals. " +
                        "The user will be prompted to approve.",
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
                description = "LOW-LEVEL: Send a raw blockchain transaction from the agent's sub-account wallet. " +
                        "PREFER agent_send_native_token for ETH/MATIC/BNB/AVAX sends, or agent_send_token for ERC-20 transfers. " +
                        "Only use this for advanced contract interactions. Does NOT require user approval. " +
                        "IMPORTANT: 'value' must be in wei (1 ETH = 1000000000000000000 wei). " +
                        "For simple native token transfers set data to '0x'. " +
                        "For contract interactions provide ABI-encoded calldata as 0x-prefixed hex.",
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
                description = "LOW-LEVEL: Transfer an ERC-20 token from the agent's sub-account wallet. " +
                        "PREFER agent_send_token instead — it auto-resolves contract addresses and decimals for common tokens. " +
                        "Only use this if agent_send_token can't resolve the token. Does NOT require user approval.",
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
            // ── High-level helper tools ───────────────────────────────
            ToolDefinition(
                name = "resolve_token",
                description = "Look up a token by symbol to get its contract address and decimals. " +
                        "Supports common tokens: USDC, USDT, WETH, DAI, WBTC, LINK, UNI, AAVE, DEGEN. " +
                        "Returns the verified contract address and decimals for the given chain. " +
                        "If chain_id is omitted, returns all chains where the token is deployed. " +
                        "Use this before sending tokens if you don't already know the contract address.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "symbol" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Token ticker symbol (e.g., 'USDC', 'WETH', 'DAI'). Case-insensitive."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Optional chain ID to look up the token on a specific chain. " +
                                        "Omit to see all chains where this token is available."
                            ),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("symbol"),
                    )),
                )),
            ),
            ToolDefinition(
                name = "send_native_token",
                description = "Send native token (ETH, MATIC, BNB, AVAX) from the user's system wallet. " +
                        "Takes a HUMAN-READABLE amount — e.g., '0.1' to send 0.1 ETH, '50' to send 50 MATIC. " +
                        "Handles wei conversion automatically. User will be prompted to approve. " +
                        "USE THIS instead of propose_transaction for native token sends.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient address (0x-prefixed, 42 characters)"),
                        )),
                        "amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount in human-readable form (e.g., '0.1' for 0.1 ETH, '50' for 50 MATIC). " +
                                        "NOT in wei — the conversion is handled automatically."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Chain ID (e.g., 1 for Ethereum/ETH, 137 for Polygon/MATIC, " +
                                        "8453 for Base/ETH, 56 for BNB Chain, 43114 for Avalanche/AVAX)"
                            ),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("to"),
                        JsonPrimitive("amount"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "send_token",
                description = "Send an ERC-20 token from the user's system wallet using just the token symbol. " +
                        "Automatically resolves the contract address and decimals for common tokens " +
                        "(USDC, USDT, WETH, DAI, WBTC, LINK, UNI, AAVE, DEGEN). " +
                        "Takes a HUMAN-READABLE amount — e.g., '100' to send 100 USDC, '0.5' to send 0.5 WETH. " +
                        "User will be prompted to approve. " +
                        "USE THIS instead of propose_token_transfer for common token sends. " +
                        "For unknown tokens, provide contract_address and decimals as fallback.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "symbol" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Token ticker symbol (e.g., 'USDC', 'WETH', 'DAI'). Case-insensitive. " +
                                        "If provided, contract_address and decimals are resolved automatically."
                            ),
                        )),
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient address (0x-prefixed, 42 characters)"),
                        )),
                        "amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount in human-readable form (e.g., '100' for 100 USDC, '0.5' for 0.5 WETH). " +
                                        "NOT in smallest unit — decimal conversion is handled automatically."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Chain ID where the token exists (e.g., 1 for Ethereum, 8453 for Base, " +
                                        "10 for Optimism, 137 for Polygon, 42161 for Arbitrum)"
                            ),
                        )),
                        "contract_address" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Optional: ERC-20 contract address. Only needed if the token is not in " +
                                        "the well-known registry and can't be resolved by symbol."
                            ),
                        )),
                        "decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Optional: Token decimals. Only needed if providing contract_address " +
                                        "for a token not in the well-known registry."
                            ),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("to"),
                        JsonPrimitive("amount"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
                requiresApproval = true,
            ),
            ToolDefinition(
                name = "agent_send_native_token",
                description = "Send native token (ETH, MATIC, BNB, AVAX) from the agent's sub-account wallet. " +
                        "Takes a HUMAN-READABLE amount — e.g., '0.01' for 0.01 ETH. " +
                        "Handles wei conversion automatically. Does NOT require user approval. " +
                        "USE THIS instead of agent_send_transaction for native token sends.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient address (0x-prefixed, 42 characters)"),
                        )),
                        "amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount in human-readable form (e.g., '0.01' for 0.01 ETH). " +
                                        "NOT in wei — the conversion is handled automatically."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Chain ID (e.g., 1 for Ethereum/ETH, 137 for Polygon/MATIC, " +
                                        "8453 for Base/ETH, 56 for BNB Chain, 43114 for Avalanche/AVAX)"
                            ),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("to"),
                        JsonPrimitive("amount"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
            ),
            ToolDefinition(
                name = "agent_send_token",
                description = "Send an ERC-20 token from the agent's sub-account wallet using just the token symbol. " +
                        "Automatically resolves contract address and decimals for common tokens " +
                        "(USDC, USDT, WETH, DAI, WBTC, LINK, UNI, AAVE, DEGEN). " +
                        "Takes a HUMAN-READABLE amount — e.g., '5' to send 5 USDC. " +
                        "Does NOT require user approval. " +
                        "USE THIS instead of agent_transfer_token for common token sends.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "symbol" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Token ticker symbol (e.g., 'USDC', 'WETH'). Case-insensitive. " +
                                        "If provided, contract_address and decimals are resolved automatically."
                            ),
                        )),
                        "to" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Recipient address (0x-prefixed, 42 characters)"),
                        )),
                        "amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount in human-readable form (e.g., '5' for 5 USDC, '0.1' for 0.1 WETH). " +
                                        "NOT in smallest unit — decimal conversion is handled automatically."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Chain ID where the token exists (e.g., 1 for Ethereum, 8453 for Base)"
                            ),
                        )),
                        "contract_address" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Optional: ERC-20 contract address. Only needed for tokens not in " +
                                        "the well-known registry."
                            ),
                        )),
                        "decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Optional: Token decimals. Only needed with contract_address " +
                                        "for tokens not in the well-known registry."
                            ),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("to"),
                        JsonPrimitive("amount"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
            ),
            // ── Agent swap ────────────────────────────────────────────
            ToolDefinition(
                name = "agent_swap",
                description = "Swap tokens from the agent's sub-account wallet via DEX (0x aggregator). " +
                        "Accepts token symbols (USDC, WETH, DAI, etc.) or 'ETH' for native currency — " +
                        "contract addresses and decimals are resolved automatically for well-known tokens. " +
                        "Takes a HUMAN-READABLE sell amount (e.g., '100' to swap 100 USDC). " +
                        "Handles token approval and swap execution atomically in a single transaction. " +
                        "Does NOT require user approval. " +
                        "Use read_agent_balance first to check the agent has sufficient funds. " +
                        "Supported swap chains: Ethereum (1), Optimism (10), Polygon (137), " +
                        "Arbitrum (42161), Base (8453).",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "sell_token" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Token to sell: a symbol like 'USDC', 'WETH', 'DAI', " +
                                        "'ETH' for native currency, or a 0x-prefixed contract address. " +
                                        "If using a contract address not in the well-known registry, " +
                                        "also provide sell_decimals."
                            ),
                        )),
                        "buy_token" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Token to buy: a symbol like 'USDC', 'WETH', 'DAI', " +
                                        "'ETH' for native currency, or a 0x-prefixed contract address. " +
                                        "If using a contract address not in the well-known registry, " +
                                        "also provide buy_decimals."
                            ),
                        )),
                        "sell_amount" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Amount to sell in human-readable form " +
                                        "(e.g., '100' for 100 USDC, '0.05' for 0.05 ETH). " +
                                        "NOT in smallest unit — decimal conversion is handled automatically."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Chain ID where the swap will occur. " +
                                        "Supported: Ethereum (1), Optimism (10), Polygon (137), " +
                                        "Arbitrum (42161), Base (8453)."
                            ),
                        )),
                        "sell_decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Optional: decimals of the sell token. Only needed when sell_token " +
                                        "is a contract address not in the well-known registry."
                            ),
                        )),
                        "buy_decimals" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Optional: decimals of the buy token. Only needed when buy_token " +
                                        "is a contract address not in the well-known registry."
                            ),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("sell_token"),
                        JsonPrimitive("buy_token"),
                        JsonPrimitive("sell_amount"),
                        JsonPrimitive("chain_id"),
                    )),
                )),
            ),
            // ── Balance queries ───────────────────────────────────────
            ToolDefinition(
                name = "read_wallet_holdings",
                description = "Get all tokens and balances in the user's wallet. " +
                        "Returns a portfolio view: token name, symbol, contract address, decimals, " +
                        "human-readable balance, USD price, and total value. " +
                        "Optionally filter by chain_id. " +
                        "Use this to check what the user owns before sending tokens.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Optional chain ID to filter (e.g., 1 for Ethereum, 8453 for Base). " +
                                        "Omit to get tokens across all chains."
                            ),
                        )),
                    )),
                )),
            ),
            ToolDefinition(
                name = "read_agent_balance",
                description = "Check the agent's sub-account wallet balance for a specific token or native currency. " +
                        "Performs a live RPC lookup. " +
                        "For native currency (ETH/MATIC/BNB/AVAX), pass token='native'. " +
                        "For ERC-20 tokens, pass a token symbol (e.g., 'USDC') or contract address (0x-prefixed). " +
                        "Returns the balance in both human-readable and raw form.",
                inputSchema = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "token" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive(
                                "Token to check: 'native' for chain native currency, " +
                                        "a symbol like 'USDC' or 'WETH', " +
                                        "or a 0x-prefixed ERC-20 contract address."
                            ),
                        )),
                        "chain_id" to JsonObject(mapOf(
                            "type" to JsonPrimitive("integer"),
                            "description" to JsonPrimitive(
                                "Chain ID to query (e.g., 1 for Ethereum, 8453 for Base, " +
                                        "137 for Polygon)"
                            ),
                        )),
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("token"),
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

            // High-level helpers
            "resolve_token" -> resolveToken(params)
            "send_native_token" -> sendNativeToken(params)
            "send_token" -> sendToken(params)
            "agent_send_native_token" -> agentSendNativeToken(params)
            "agent_send_token" -> agentSendToken(params)
            "agent_swap" -> agentSwap(params)
            "read_wallet_holdings" -> readWalletHoldings(params)
            "read_agent_balance" -> readAgentBalance(params)

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

    // ── High-level helper operations ─────────────────────────────────────

    private fun resolveToken(params: JsonObject): SkillResult {
        val symbol = params["symbol"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: symbol")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull

        val matches = findTokenBySymbol(symbol)
        if (matches.isEmpty()) {
            return SkillResult.Error(
                "Token '$symbol' not found in the well-known registry. " +
                        "Available tokens: USDC, USDT, WETH, DAI, WBTC, LINK, UNI, AAVE, DEGEN. " +
                        "Use get_owned_tokens to look up tokens by checking wallet holdings."
            )
        }

        if (chainId != null) {
            val resolved = resolveWellKnownToken(symbol, chainId)
            if (resolved != null) {
                val (address, decimals) = resolved
                val tokenName = matches.first().name
                return SkillResult.Success(buildJsonObject {
                    put("symbol", symbol.uppercase())
                    put("name", tokenName)
                    put("contract_address", address)
                    put("decimals", decimals)
                    put("chain_id", chainId)
                }.toString())
            } else {
                val availableChains = matches.flatMap { it.addresses.keys }.distinct().sorted()
                return SkillResult.Error(
                    "Token '$symbol' is not available on chain $chainId. " +
                            "Available on chains: ${availableChains.joinToString { "$it (${CHAIN_NAMES[it] ?: "unknown"})" }}"
                )
            }
        }

        // No chain specified — return all deployments
        val result = buildJsonArray {
            for (token in matches) {
                for ((cId, address) in token.addresses) {
                    add(buildJsonObject {
                        put("symbol", token.symbol)
                        put("name", token.name)
                        put("contract_address", address)
                        put("decimals", token.decimals)
                        put("chain_id", cId)
                        put("chain_name", CHAIN_NAMES[cId] ?: "unknown")
                    })
                }
            }
        }
        return SkillResult.Success(result.toString())
    }

    private suspend fun sendNativeToken(params: JsonObject): SkillResult {
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val amount = params["amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: amount")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")

        val nativeToken = NATIVE_TOKENS[chainId]
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

        // Convert human-readable amount to wei (18 decimals for all native tokens)
        val weiAmount = try {
            BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(nativeToken.decimals))
                .toBigIntegerExact()
        } catch (e: Exception) {
            return SkillResult.Error("Invalid amount '$amount': ${e.message}")
        }

        if (weiAmount <= BigInteger.ZERO) {
            return SkillResult.Error("Amount must be greater than zero.")
        }

        // Delegate to the existing propose_transaction with wei value
        val txParams = JsonObject(mapOf(
            "to" to JsonPrimitive(to),
            "value" to JsonPrimitive(weiAmount.toString()),
            "data" to JsonPrimitive("0"),
            "chain_id" to JsonPrimitive(chainId),
        ))
        val result = proposeTransaction(txParams)

        // Enhance the success response with human-readable info
        if (result is SkillResult.Success) {
            return SkillResult.Success(buildJsonObject {
                put("user_op_hash", Json.parseToJsonElement(result.data)
                    .jsonObject["user_op_hash"]?.jsonPrimitive?.content ?: "")
                put("status", "submitted")
                put("chain_id", chainId)
                put("to", to)
                put("amount", amount)
                put("token", nativeToken.symbol)
                put("amount_wei", weiAmount.toString())
            }.toString())
        }
        return result
    }

    /**
     * Resolve token info from symbol or explicit params.
     * Returns (contractAddress, decimals) or an error.
     */
    private fun resolveTokenForTransfer(
        params: JsonObject,
        chainId: Int,
    ): Pair<String, Int>? {
        val symbol = params["symbol"]?.jsonPrimitive?.contentOrNull
        val contractAddress = params["contract_address"]?.jsonPrimitive?.contentOrNull
        val decimals = params["decimals"]?.jsonPrimitive?.intOrNull

        // Try symbol resolution first
        if (symbol != null) {
            val resolved = resolveWellKnownToken(symbol, chainId)
            if (resolved != null) return resolved
        }

        // Fall back to explicit params, but cross-check decimals against
        // the well-known registry to catch LLM-provided wrong decimals
        if (contractAddress != null) {
            val registryDecimals = resolveDecimalsByAddress(contractAddress, chainId)
            if (registryDecimals != null) {
                return contractAddress to registryDecimals
            }
            if (decimals != null) {
                return contractAddress to decimals
            }
        }

        return null
    }

    /**
     * Reverse-lookup: find the correct decimals for a contract address
     * from the well-known registry.
     */
    private fun resolveDecimalsByAddress(contractAddress: String, chainId: Int): Int? {
        val lower = contractAddress.lowercase()
        for (token in WELL_KNOWN_TOKENS) {
            val addr = token.addresses[chainId] ?: continue
            if (addr.lowercase() == lower) return token.decimals
        }
        return null
    }

    private suspend fun sendToken(params: JsonObject): SkillResult {
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val amount = params["amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: amount")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")
        val symbol = params["symbol"]?.jsonPrimitive?.contentOrNull

        val resolved = resolveTokenForTransfer(params, chainId)
            ?: return SkillResult.Error(
                if (symbol != null) {
                    val matches = findTokenBySymbol(symbol)
                    if (matches.isEmpty()) {
                        "Token '$symbol' not found in the well-known registry. " +
                                "Provide contract_address and decimals, or use get_owned_tokens to look it up."
                    } else {
                        val availableChains = matches.flatMap { it.addresses.keys }.distinct().sorted()
                        "Token '$symbol' is not available on chain $chainId. " +
                                "Available on chains: ${availableChains.joinToString { "$it (${CHAIN_NAMES[it] ?: "unknown"})" }}. " +
                                "Or provide contract_address and decimals manually."
                    }
                } else {
                    "Must provide either 'symbol' (for well-known tokens) or both 'contract_address' and 'decimals'."
                }
            )

        val (contractAddress, decimals) = resolved

        // Convert human-readable amount to smallest-unit amount using token decimals
        // e.g. "1" USDC (6 decimals) → 1_000_000 smallest units
        val rawAmount = try {
            BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(decimals))
                .toBigIntegerExact()
        } catch (e: Exception) {
            return SkillResult.Error("Invalid amount '$amount': ${e.message}")
        }

        if (rawAmount <= BigInteger.ZERO) {
            return SkillResult.Error("Amount must be greater than zero.")
        }

        val rpcEndpoint = chainIdToRpc(chainId)
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

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
                    put("amount_raw", rawAmount.toString())
                    if (symbol != null) put("symbol", symbol.uppercase())
                }.toString())
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to transfer token: ${e.message}")
        }
    }

    private suspend fun agentSendNativeToken(params: JsonObject): SkillResult {
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val amount = params["amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: amount")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")

        val nativeToken = NATIVE_TOKENS[chainId]
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

        val weiAmount = try {
            BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(nativeToken.decimals))
                .toBigIntegerExact()
        } catch (e: Exception) {
            return SkillResult.Error("Invalid amount '$amount': ${e.message}")
        }

        if (weiAmount <= BigInteger.ZERO) {
            return SkillResult.Error("Amount must be greater than zero.")
        }

        val txParams = JsonObject(mapOf(
            "to" to JsonPrimitive(to),
            "value" to JsonPrimitive(weiAmount.toString()),
            "data" to JsonPrimitive("0x"),
            "chain_id" to JsonPrimitive(chainId),
        ))
        val result = agentSendTransaction(txParams)

        if (result is SkillResult.Success) {
            return SkillResult.Success(buildJsonObject {
                put("user_op_hash", Json.parseToJsonElement(result.data)
                    .jsonObject["user_op_hash"]?.jsonPrimitive?.content ?: "")
                put("status", "submitted")
                put("chain_id", chainId)
                put("to", to)
                put("amount", amount)
                put("token", nativeToken.symbol)
                put("amount_wei", weiAmount.toString())
            }.toString())
        }
        return result
    }

    private suspend fun agentSendToken(params: JsonObject): SkillResult {
        val to = params["to"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: to")
        val amount = params["amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: amount")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")
        val symbol = params["symbol"]?.jsonPrimitive?.contentOrNull

        val resolved = resolveTokenForTransfer(params, chainId)
            ?: return SkillResult.Error(
                if (symbol != null) {
                    val matches = findTokenBySymbol(symbol)
                    if (matches.isEmpty()) {
                        "Token '$symbol' not found in the well-known registry. " +
                                "Provide contract_address and decimals, or use get_owned_tokens to look it up."
                    } else {
                        val availableChains = matches.flatMap { it.addresses.keys }.distinct().sorted()
                        "Token '$symbol' is not available on chain $chainId. " +
                                "Available on chains: ${availableChains.joinToString { "$it (${CHAIN_NAMES[it] ?: "unknown"})" }}. " +
                                "Or provide contract_address and decimals manually."
                    }
                } else {
                    "Must provide either 'symbol' (for well-known tokens) or both 'contract_address' and 'decimals'."
                }
            )

        val (contractAddress, decimals) = resolved

        // Convert human-readable amount to smallest-unit amount using token decimals
        // e.g. "5" USDC (6 decimals) → 5_000_000 smallest units
        val rawAmount = try {
            BigDecimal(amount)
                .multiply(BigDecimal.TEN.pow(decimals))
                .toBigIntegerExact()
        } catch (e: Exception) {
            return SkillResult.Error("Invalid amount '$amount': ${e.message}")
        }

        if (rawAmount <= BigInteger.ZERO) {
            return SkillResult.Error("Amount must be greater than zero.")
        }

        val rpcEndpoint = chainIdToRpc(chainId)
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

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
                put("amount_raw", rawAmount.toString())
                if (symbol != null) put("symbol", symbol.uppercase())
            }.toString())
        } catch (e: Exception) {
            SkillResult.Error("Failed to transfer token from agent wallet: ${e.message}")
        }
    }

    // ── Agent swap (0x API → SubWalletSDK batch) ──────────────────────────

    /**
     * Resolve a token identifier (symbol, "ETH", or 0x-address) into
     * (contractAddress, decimals, symbol?) for swap purposes.
     */
    private fun resolveSwapToken(
        token: String,
        chainId: Int,
        explicitDecimals: Int?,
    ): Triple<String, Int, String?>? {
        // Native currency keywords
        val nativeKeywords = setOf("ETH", "MATIC", "POL", "BNB", "AVAX")
        if (token.uppercase() in nativeKeywords) {
            return Triple(ETH_TOKEN_ADDRESS, 18, token.uppercase())
        }

        // 0x-prefixed contract address
        if (token.startsWith("0x")) {
            val decimals = resolveDecimalsByAddress(token, chainId)
                ?: explicitDecimals
                ?: return null
            val symbol = WELL_KNOWN_TOKENS.find {
                it.addresses[chainId]?.equals(token, ignoreCase = true) == true
            }?.symbol
            return Triple(token, decimals, symbol)
        }

        // Try well-known symbol resolution
        val resolved = resolveWellKnownToken(token, chainId)
        if (resolved != null) {
            return Triple(resolved.first, resolved.second, token.uppercase())
        }

        return null
    }

    private fun isEthLike(address: String, symbol: String?, chainId: Int): Boolean {
        return address.equals(ETH_TOKEN_ADDRESS, ignoreCase = true) ||
                address == "0x0000000000000000000000000000000000000000" ||
                (symbol != null && symbol.equals("ETH", ignoreCase = true) &&
                        NATIVE_TOKENS[chainId]?.symbol == "ETH")
    }

    private suspend fun agentSwap(params: JsonObject): SkillResult {
        val sellTokenParam = params["sell_token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: sell_token")
        val buyTokenParam = params["buy_token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: buy_token")
        val sellAmount = params["sell_amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: sell_amount")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")
        val explicitSellDecimals = params["sell_decimals"]?.jsonPrimitive?.intOrNull
        val explicitBuyDecimals = params["buy_decimals"]?.jsonPrimitive?.intOrNull

        if (chainId !in ZEROX_SUPPORTED_CHAIN_IDS) {
            return SkillResult.Error(
                "Chain $chainId is not supported for swaps. " +
                        "Supported: ${ZEROX_SUPPORTED_CHAIN_IDS.sorted().joinToString()}"
            )
        }

        if (BuildConfig.ZEROX_API_KEY.isBlank()) {
            return SkillResult.Error("0x API key is not configured. Add ZEROX_API_KEY to local.properties.")
        }

        // Resolve sell token
        val sellResolved = resolveSwapToken(sellTokenParam, chainId, explicitSellDecimals)
            ?: return SkillResult.Error(
                "Cannot resolve sell token '$sellTokenParam' on chain $chainId. " +
                        "Use a well-known symbol (USDC, WETH, DAI, …), 'ETH' for native, " +
                        "or provide a 0x-prefixed address with sell_decimals."
            )
        val (sellAddress, sellDecimals, sellSymbol) = sellResolved

        // Resolve buy token
        val buyResolved = resolveSwapToken(buyTokenParam, chainId, explicitBuyDecimals)
            ?: return SkillResult.Error(
                "Cannot resolve buy token '$buyTokenParam' on chain $chainId. " +
                        "Use a well-known symbol (USDC, WETH, DAI, …), 'ETH' for native, " +
                        "or provide a 0x-prefixed address with buy_decimals."
            )
        val (buyAddress, buyDecimals, _) = buyResolved

        val isSellingEth = isEthLike(sellAddress, sellSymbol, chainId)

        // Convert human-readable amount to smallest unit
        val rawSellAmount = try {
            BigDecimal(sellAmount)
                .multiply(BigDecimal.TEN.pow(sellDecimals))
                .toBigInteger()
        } catch (e: Exception) {
            return SkillResult.Error("Invalid sell_amount '$sellAmount': ${e.message}")
        }
        if (rawSellAmount <= BigInteger.ZERO) {
            return SkillResult.Error("sell_amount must be greater than zero.")
        }

        val rpcEndpoint = chainIdToRpc(chainId)
            ?: return SkillResult.Error("No RPC endpoint for chain $chainId")

        val sw = getOrCreateSubWallet(chainId)
            ?: return subWalletUnavailableError()

        return try {
            val agentAddress = withContext(Dispatchers.IO) { sw.getAddress() }

            // Normalise addresses for 0x API
            val apiSellToken = if (isSellingEth) ETH_TOKEN_ADDRESS else sellAddress
            val apiBuyToken = if (isEthLike(buyAddress, null, chainId)) ETH_TOKEN_ADDRESS else buyAddress

            // Call 0x API
            val url = "$ZEROX_API_BASE_URL/swap/allowance-holder/quote" +
                    "?chainId=$chainId" +
                    "&sellToken=$apiSellToken" +
                    "&buyToken=$apiBuyToken" +
                    "&sellAmount=$rawSellAmount" +
                    "&taker=$agentAddress" +
                    "&swapFeeRecipient=$SWAP_FEE_RECIPIENT" +
                    "&swapFeeBps=$SWAP_FEE_BPS" +
                    "&swapFeeToken=$apiSellToken"

            val request = Request.Builder()
                .url(url)
                .addHeader("0x-api-key", BuildConfig.ZEROX_API_KEY)
                .addHeader("0x-version", ZEROX_API_VERSION)
                .get()
                .build()

            val body = withContext(Dispatchers.IO) {
                val response = swapHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "0x API error: ${response.code} – $responseBody")
                    return@withContext null to "0x API error (${response.code}): ${responseBody ?: "no body"}"
                }
                responseBody to null
            }

            val (responseBody, apiError) = body
            if (responseBody == null) {
                return SkillResult.Error(apiError ?: "Unknown 0x API error")
            }

            // Parse 0x response
            val json = Json.parseToJsonElement(responseBody).jsonObject

            val liquidityAvailable = json["liquidityAvailable"]?.jsonPrimitive?.contentOrNull?.toBoolean()
            if (liquidityAvailable == false) {
                return SkillResult.Error("No liquidity available for this swap pair on chain $chainId.")
            }

            // Check balance issues
            val issues = json["issues"]?.jsonObject
            issues?.get("balance")?.let { balanceNode ->
                if (balanceNode !is kotlinx.serialization.json.JsonNull) {
                    val expected = balanceNode.jsonObject["expected"]?.jsonPrimitive?.contentOrNull
                    val actual = balanceNode.jsonObject["actual"]?.jsonPrimitive?.contentOrNull
                    return SkillResult.Error(
                        "Insufficient balance for swap. Expected: $expected, actual: $actual"
                    )
                }
            }

            // Extract transaction data
            val transaction = json["transaction"]?.jsonObject
                ?: return SkillResult.Error("0x API response missing transaction data.")
            val txTo = transaction["to"]?.jsonPrimitive?.contentOrNull
                ?: return SkillResult.Error("0x API response missing transaction.to")
            val txData = transaction["data"]?.jsonPrimitive?.contentOrNull
                ?: return SkillResult.Error("0x API response missing transaction.data")
            val txValue = transaction["value"]?.jsonPrimitive?.contentOrNull ?: "0"

            // Build transaction list (approval + swap)
            val txList = mutableListOf<SubWalletSDK.TxParams>()

            // Add approval if needed (only for ERC-20 sells)
            if (!isSellingEth) {
                issues?.get("allowance")?.let { allowanceNode ->
                    if (allowanceNode !is kotlinx.serialization.json.JsonNull) {
                        val spender = allowanceNode.jsonObject["spender"]?.jsonPrimitive?.contentOrNull
                        if (spender != null) {
                            val maxApproval = BigInteger("2").pow(256).subtract(BigInteger.ONE)
                            val approveFn = Function(
                                "approve",
                                listOf(Address(spender), Uint256(maxApproval)),
                                emptyList(),
                            )
                            txList.add(SubWalletSDK.TxParams(
                                to = apiSellToken,
                                value = "0",
                                data = FunctionEncoder.encode(approveFn),
                            ))
                        }
                    }
                }
            }

            // Add swap transaction
            txList.add(SubWalletSDK.TxParams(to = txTo, value = txValue, data = txData))

            // Execute batch via SubWalletSDK
            val result = withContext(Dispatchers.IO) {
                if (chainId != sw.getChainId()) {
                    sw.changeChain(chainId, rpcEndpoint, chainIdToBundler(chainId))
                }
                sw.sendTransaction(
                    txParamsList = txList,
                    callGas = null,
                    chainId = chainId,
                )
            }

            // Format buy amount for response
            val rawBuyAmount = json["buyAmount"]?.jsonPrimitive?.contentOrNull ?: "0"
            val humanBuyAmount = try {
                BigDecimal(rawBuyAmount)
                    .divide(BigDecimal.TEN.pow(buyDecimals), buyDecimals, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
            } catch (_: Exception) { rawBuyAmount }

            when {
                result.startsWith("0x") -> SkillResult.Success(buildJsonObject {
                    put("user_op_hash", result)
                    put("status", "submitted")
                    put("chain_id", chainId)
                    put("sell_token", sellTokenParam)
                    put("buy_token", buyTokenParam)
                    put("sell_amount", sellAmount)
                    put("expected_buy_amount", humanBuyAmount)
                }.toString())
                result.contains("AA21") -> SkillResult.Error(
                    "Insufficient gas in agent wallet to execute swap on chain $chainId."
                )
                else -> SkillResult.Error("Swap failed: $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "agent_swap failed", e)
            SkillResult.Error("Failed to execute agent swap: ${e.message}")
        }
    }

    // ── Balance query helpers ────────────────────────────────────────────

    private suspend fun readWalletHoldings(params: JsonObject): SkillResult {
        val w = defaultWallet ?: return walletUnavailableError()
        return getOwnedTokens(w, params)
    }

    private suspend fun readAgentBalance(params: JsonObject): SkillResult {
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull
            ?: return SkillResult.Error("Missing required parameter: chain_id")
        val token = params["token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("Missing required parameter: token (use 'native' or a contract address)")

        val rpcEndpoint = chainIdToRpc(chainId)
            ?: return SkillResult.Error(
                "Unsupported chain ID: $chainId. " +
                        "Supported chains: ${CHAIN_NAMES.keys.sorted().joinToString()}"
            )

        val sw = defaultSubWallet ?: return subWalletUnavailableError()
        val agentAddress = try {
            withContext(Dispatchers.IO) { sw.getAddress() }
        } catch (e: Exception) {
            return SkillResult.Error("Failed to get agent wallet address: ${e.message}")
        }

        val web3j = Web3j.build(HttpService(rpcEndpoint))

        return try {
            if (token.equals("native", ignoreCase = true) || token.equals("eth", ignoreCase = true)
                || token.equals("matic", ignoreCase = true) || token.equals("pol", ignoreCase = true)
                || token.equals("bnb", ignoreCase = true) || token.equals("avax", ignoreCase = true)
            ) {
                // Native balance
                val nativeInfo = NATIVE_TOKENS[chainId]
                val balanceWei = withContext(Dispatchers.IO) {
                    web3j.ethGetBalance(agentAddress, DefaultBlockParameterName.LATEST)
                        .send().balance
                }
                val humanBalance = BigDecimal(balanceWei)
                    .divide(BigDecimal.TEN.pow(nativeInfo?.decimals ?: 18), 18, RoundingMode.HALF_UP)
                    .stripTrailingZeros()

                SkillResult.Success(buildJsonObject {
                    put("address", agentAddress)
                    put("token", nativeInfo?.symbol ?: "NATIVE")
                    put("balance", humanBalance.toPlainString())
                    put("balance_wei", balanceWei.toString())
                    put("chain_id", chainId)
                }.toString())
            } else {
                // ERC-20 balance via balanceOf(address)
                // Try to resolve symbol to address first
                var contractAddress = token
                var tokenSymbol: String? = null
                var tokenDecimals: Int? = null

                if (!token.startsWith("0x")) {
                    // Treat as symbol
                    val resolved = resolveWellKnownToken(token, chainId)
                    if (resolved != null) {
                        contractAddress = resolved.first
                        tokenDecimals = resolved.second
                        tokenSymbol = token.uppercase()
                    } else {
                        return SkillResult.Error(
                            "Token '$token' not found in registry for chain $chainId. " +
                                    "Provide the 0x-prefixed contract address instead."
                        )
                    }
                } else {
                    // Try to find it in registry for decimals
                    for (wkt in WELL_KNOWN_TOKENS) {
                        val addr = wkt.addresses[chainId]
                        if (addr != null && addr.equals(token, ignoreCase = true)) {
                            tokenSymbol = wkt.symbol
                            tokenDecimals = wkt.decimals
                            break
                        }
                    }
                }

                val balanceOfFn = Function(
                    "balanceOf",
                    listOf(Address(agentAddress)),
                    listOf(object : TypeReference<Uint256>() {}),
                )
                val encodedFn = FunctionEncoder.encode(balanceOfFn)

                val callResult = withContext(Dispatchers.IO) {
                    web3j.ethCall(
                        org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(
                            agentAddress, contractAddress, encodedFn
                        ),
                        DefaultBlockParameterName.LATEST,
                    ).send()
                }

                if (callResult.isReverted) {
                    return SkillResult.Error("balanceOf call reverted: ${callResult.revertReason}")
                }

                val decoded = FunctionReturnDecoder.decode(
                    callResult.value, balanceOfFn.outputParameters
                )
                val rawBalance = if (decoded.isNotEmpty()) {
                    decoded[0].value as BigInteger
                } else {
                    BigInteger.ZERO
                }

                val result = buildJsonObject {
                    put("address", agentAddress)
                    put("contract_address", contractAddress)
                    if (tokenSymbol != null) put("symbol", tokenSymbol)
                    put("balance_raw", rawBalance.toString())
                    if (tokenDecimals != null) {
                        val humanBal = BigDecimal(rawBalance)
                            .divide(BigDecimal.TEN.pow(tokenDecimals), tokenDecimals, RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                        put("balance", humanBal.toPlainString())
                        put("decimals", tokenDecimals)
                    }
                    put("chain_id", chainId)
                }
                SkillResult.Success(result.toString())
            }
        } catch (e: Exception) {
            SkillResult.Error("Failed to query balance: ${e.message}")
        } finally {
            web3j.shutdown()
        }
    }
}
