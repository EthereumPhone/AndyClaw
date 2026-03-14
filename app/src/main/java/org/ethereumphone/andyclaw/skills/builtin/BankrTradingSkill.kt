package org.ethereumphone.andyclaw.skills.builtin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.ethereumphone.walletsdk.WalletSDK
import org.json.JSONArray
import org.json.JSONObject
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BankrTradingSkill(private val context: Context) : AndyClawSkill {
    override val id = "bankr_trading"
    override val name = "Bankr Trading"

    companion object {
        private const val TAG = "BankrTradingSkill"
        private const val BASE_URL = "https://api.bankr.bot/trading/order"
        private const val APP_FEE_BPS = 15
        private const val APP_FEE_RECIPIENT = "0xFE5cDA3C48d52b4EdF53361bF28C4213fDa7eA09"

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

        private fun chainDisplayName(chainId: Int) = when (chainId) {
            1 -> "Ethereum"; 8453 -> "Base"; 10 -> "Optimism"
            137 -> "Polygon"; 42161 -> "Arbitrum"; else -> "Chain $chainId"
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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

    // ── Manifests ─────────────────────────────────────────────────────────

    private val informationalTools = listOf(
        ToolDefinition(
            name = "get_bankr_wallet",
            description = "Look up a wallet address by Twitter or Farcaster username via Bankr. " +
                    "Returns EVM and Solana addresses if available.",
            inputSchema = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "username" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Twitter or Farcaster username to look up"),
                    )),
                    "platform" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Platform: 'twitter' or 'farcaster'. Default: 'twitter'"),
                    )),
                )),
                "required" to JsonArray(listOf(JsonPrimitive("username"))),
            )),
        ),
        ToolDefinition(
            name = "get_bankr_orders",
            description = "List Bankr orders for the user's wallet. " +
                    "Optionally filter by order type (limit-buy, limit-sell, stop-buy, stop-sell, dca, twap) " +
                    "or status (open, ready, pending, completed, cancelled, paused, expired, error).",
            inputSchema = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "order_type" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive(
                            "Filter by type: limit-buy, limit-sell, stop-buy, stop-sell, dca, twap"
                        ),
                    )),
                    "status" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive(
                            "Filter by status: open, ready, pending, completed, cancelled, paused, expired, error"
                        ),
                    )),
                )),
                "required" to JsonArray(emptyList()),
            )),
        ),
        ToolDefinition(
            name = "get_bankr_order_details",
            description = "Get detailed information about a specific Bankr order by its ID.",
            inputSchema = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "order_id" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The Bankr order ID"),
                    )),
                )),
                "required" to JsonArray(listOf(JsonPrimitive("order_id"))),
            )),
        ),
    )

    private val tradingTools = listOf(
        ToolDefinition(
            name = "create_bankr_order",
            description = "Create a Bankr order (limit, stop, DCA, or TWAP). " +
                    "Handles the full flow: creates a quote, executes approval if needed, " +
                    "signs the order via the user's wallet, and submits it. " +
                    "IMPORTANT: For orders involving ETH, always use the WETH address instead " +
                    "(Base: 0x4200000000000000000000000000000000000006, " +
                    "Ethereum: 0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2). " +
                    "Orders require ERC-20 tokens — native ETH cannot be used directly. " +
                    "Use lookup_token to verify token addresses before creating orders.",
            inputSchema = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "order_type" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive(
                            "Order type: limit-buy, limit-sell, stop-buy, stop-sell, dca, twap"
                        ),
                    )),
                    "sell_token" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive(
                            "Contract address (0x...) of the token to sell. Use WETH address for ETH."
                        ),
                    )),
                    "buy_token" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive(
                            "Contract address (0x...) of the token to buy. Use WETH address for ETH."
                        ),
                    )),
                    "sell_amount" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive(
                            "Amount of sell_token in human-readable format (e.g. '100' for 100 USDC). " +
                                    "For DCA/TWAP this is the PER-EXECUTION amount, not total."
                        ),
                    )),
                    "sell_decimals" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive(
                            "Decimals of the sell token (e.g. 18 for WETH, 6 for USDC). Default: 18"
                        ),
                    )),
                    "chain_id" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Chain ID. Default: 8453 (Base)"),
                    )),
                    "slippage_bps" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive(
                            "Slippage tolerance in basis points (100 = 1%). Default: 100"
                        ),
                    )),
                    "expiration_hours" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive("Hours until order expires. Default: 24"),
                    )),
                    "trigger_price" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive(
                            "For limit/stop orders: trigger price as a token ratio. " +
                                    "Buy orders: sell_token per 1 buy_token. " +
                                    "Sell orders: buy_token per 1 sell_token."
                        ),
                    )),
                    "interval_seconds" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive(
                            "For DCA/TWAP: seconds between executions. DCA min: 3600 (1hr). TWAP min: 180 (3min)."
                        ),
                    )),
                    "max_executions" to JsonObject(mapOf(
                        "type" to JsonPrimitive("integer"),
                        "description" to JsonPrimitive(
                            "For DCA/TWAP: number of executions (chunks)."
                        ),
                    )),
                )),
                "required" to JsonArray(listOf(
                    JsonPrimitive("order_type"),
                    JsonPrimitive("sell_token"),
                    JsonPrimitive("buy_token"),
                    JsonPrimitive("sell_amount"),
                )),
            )),
            requiresApproval = true,
        ),
        ToolDefinition(
            name = "cancel_bankr_order",
            description = "Cancel an active Bankr order. Only open/ready/pending orders can be cancelled. " +
                    "The user's wallet will sign the cancellation.",
            inputSchema = JsonObject(mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf(
                    "order_id" to JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The Bankr order ID to cancel"),
                    )),
                )),
                "required" to JsonArray(listOf(JsonPrimitive("order_id"))),
            )),
            requiresApproval = true,
        ),
    )

    override val baseManifest = SkillManifest(
        description = "Bankr trading: limit orders, stop orders, DCA, TWAP strategies, and wallet lookup. " +
                "Supports EVM chains (Ethereum, Base, Optimism, Polygon, Arbitrum).",
        tools = informationalTools,
    )

    override val privilegedManifest = SkillManifest(
        description = "Bankr trading: create and cancel orders on EVM chains via the Bankr protocol. " +
                "Requires ethOS wallet for signing.",
        tools = tradingTools,
    )

    // ── Execute ───────────────────────────────────────────────────────────

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "get_bankr_wallet" -> getBankrWallet(params)
            "get_bankr_orders" -> getBankrOrders(params)
            "get_bankr_order_details" -> getBankrOrderDetails(params)
            "create_bankr_order" -> createBankrOrder(params)
            "cancel_bankr_order" -> cancelBankrOrder(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    // ── get_bankr_wallet ──────────────────────────────────────────────────

    private suspend fun getBankrWallet(params: JsonObject): SkillResult {
        val username = params["username"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return SkillResult.Error("'username' is required")
        val platform = params["platform"]?.jsonPrimitive?.contentOrNull?.trim() ?: "twitter"

        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api-staging.bankr.bot/public/wallet" +
                        "?username=${java.net.URLEncoder.encode(username, "UTF-8")}" +
                        "&platform=${java.net.URLEncoder.encode(platform, "UTF-8")}"

                val body = httpGet(url)
                    ?: return@withContext SkillResult.Error("Empty response from Bankr wallet API")

                val json = JSONObject(body)
                val sb = StringBuilder("Bankr wallet for @$username ($platform):\n")
                json.optString("evmAddress").takeIf { it.isNotBlank() }
                    ?.let { sb.appendLine("EVM address: $it") }
                json.optString("solanaAddress").takeIf { it.isNotBlank() }
                    ?.let { sb.appendLine("Solana address: $it") }

                if (sb.lines().size <= 1) {
                    sb.appendLine("No wallet found for this username.")
                }
                SkillResult.Success(sb.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "Bankr wallet lookup failed", e)
                SkillResult.Error("Bankr wallet lookup failed: ${e.message}")
            }
        }
    }

    // ── get_bankr_orders ──────────────────────────────────────────────────

    private suspend fun getBankrOrders(params: JsonObject): SkillResult {
        val apiKey = BuildConfig.BANKR_API
        if (apiKey.isBlank()) return SkillResult.Error("Bankr API key not configured. Add BANKR_API to local.properties.")

        val wallet = getOrCreateWallet(1) ?: return SkillResult.Error("Wallet not available")
        val walletAddress = withContext(Dispatchers.IO) { wallet.getAddress() }
            ?: return SkillResult.Error("Could not get wallet address")

        val orderType = params["order_type"]?.jsonPrimitive?.contentOrNull
        val status = params["status"]?.jsonPrimitive?.contentOrNull

        return withContext(Dispatchers.IO) {
            try {
                val reqBody = JSONObject().apply {
                    put("maker", walletAddress)
                    orderType?.let { put("type", it) }
                    status?.let { put("status", it) }
                }

                val response = httpPost("$BASE_URL/list", reqBody, apiKey)
                val json = JSONObject(response)
                val ordersArr = json.getJSONArray("orders")
                val total = json.getInt("totalOrdersCount")

                if (ordersArr.length() == 0) {
                    return@withContext SkillResult.Success("No orders found.")
                }

                val sb = StringBuilder("Found $total order(s):\n")
                for (i in 0 until ordersArr.length()) {
                    val o = ordersArr.getJSONObject(i)
                    val id = o.getString("orderId")
                    val type = formatOrderType(o.optString("orderType", "unknown"))
                    val st = o.optString("status", "unknown").uppercase()
                    val sell = extractTokenSymbol(o, "sellToken")
                    val buy = extractTokenSymbol(o, "buyToken")
                    val sellAmt = o.optJSONObject("sellToken")?.optJSONObject("amount")
                        ?.optString("formatted", "?") ?: "?"
                    sb.appendLine("${i + 1}. $type: $sellAmt $sell -> $buy [$st]")
                    sb.appendLine("   orderId: $id")
                }
                json.optString("next").takeIf { it.isNotBlank() }?.let {
                    sb.appendLine("\nMore orders available (use cursor to paginate)")
                }
                SkillResult.Success(sb.toString().trim())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list orders", e)
                SkillResult.Error("Failed to list orders: ${e.message}")
            }
        }
    }

    // ── get_bankr_order_details ───────────────────────────────────────────

    private suspend fun getBankrOrderDetails(params: JsonObject): SkillResult {
        val orderId = params["order_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return SkillResult.Error("'order_id' is required")
        val apiKey = BuildConfig.BANKR_API
        if (apiKey.isBlank()) return SkillResult.Error("Bankr API key not configured.")

        return withContext(Dispatchers.IO) {
            try {
                val response = httpGetWithKey("$BASE_URL/$orderId", apiKey)
                val json = JSONObject(response)
                val order = json.getJSONObject("order")
                SkillResult.Success(formatOrderDetails(order))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get order details", e)
                SkillResult.Error("Failed to get order details: ${e.message}")
            }
        }
    }

    // ── create_bankr_order ────────────────────────────────────────────────

    private suspend fun createBankrOrder(params: JsonObject): SkillResult {
        val apiKey = BuildConfig.BANKR_API
        if (apiKey.isBlank()) return SkillResult.Error("Bankr API key not configured. Add BANKR_API to local.properties.")

        val orderType = params["order_type"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("'order_type' is required")
        val sellToken = params["sell_token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("'sell_token' is required")
        val buyToken = params["buy_token"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("'buy_token' is required")
        val sellAmountHuman = params["sell_amount"]?.jsonPrimitive?.contentOrNull
            ?: return SkillResult.Error("'sell_amount' is required")
        val sellDecimals = params["sell_decimals"]?.jsonPrimitive?.intOrNull ?: 18
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull ?: 8453
        val slippageBps = (params["slippage_bps"]?.jsonPrimitive?.intOrNull ?: 100).coerceIn(0, 2000)
        val expirationHours = params["expiration_hours"]?.jsonPrimitive?.intOrNull ?: 24

        // Build config
        val config = JSONObject()
        val triggerPrice = params["trigger_price"]?.jsonPrimitive?.contentOrNull
        val intervalSeconds = params["interval_seconds"]?.jsonPrimitive?.longOrNull
        val maxExecutions = params["max_executions"]?.jsonPrimitive?.intOrNull

        if (orderType in listOf("limit-buy", "limit-sell", "stop-buy", "stop-sell")) {
            if (triggerPrice.isNullOrBlank()) {
                return SkillResult.Error("'trigger_price' is required for $orderType orders")
            }
            config.put("triggerPrice", triggerPrice)
            config.put("allowPartial", true)
        }

        if (orderType in listOf("dca", "twap")) {
            val interval = intervalSeconds ?: return SkillResult.Error("'interval_seconds' is required for $orderType orders")
            val execs = maxExecutions ?: return SkillResult.Error("'max_executions' is required for $orderType orders")
            val minInterval = if (orderType == "dca") 3600L else 180L
            if (interval < minInterval) {
                return SkillResult.Error("interval_seconds must be >= $minInterval for $orderType orders")
            }
            config.put("interval", interval)
            config.put("maxExecutions", execs)
        }

        // Resolve wallet
        val wallet = getOrCreateWallet(chainId)
            ?: return SkillResult.Error("Wallet not available for chain $chainId")
        val walletAddress = withContext(Dispatchers.IO) { wallet.getAddress() }
            ?: return SkillResult.Error("Could not get wallet address")

        // Convert human amount to raw
        val rawSellAmount = try {
            val bd = java.math.BigDecimal(sellAmountHuman)
            bd.multiply(java.math.BigDecimal.TEN.pow(sellDecimals)).toBigInteger().toString()
        } catch (e: Exception) {
            return SkillResult.Error("Invalid sell_amount: $sellAmountHuman")
        }

        val expirationDate = (System.currentTimeMillis() / 1000) + (expirationHours * 3600L)

        return withContext(Dispatchers.IO) {
            try {
                // Step 1: Create quote
                val quoteBody = JSONObject().apply {
                    put("maker", walletAddress)
                    put("orderType", orderType)
                    put("config", config)
                    put("chainId", chainId)
                    put("sellToken", sellToken)
                    put("buyToken", buyToken)
                    put("sellAmount", rawSellAmount)
                    put("slippageBps", slippageBps)
                    put("expirationDate", expirationDate)
                    put("appFeeBps", APP_FEE_BPS)
                    put("appFeeRecipient", APP_FEE_RECIPIENT)
                }

                Log.d(TAG, "Creating order quote: $quoteBody")
                val quoteResponse = httpPost("$BASE_URL/quote", quoteBody, apiKey)
                val quoteJson = JSONObject(quoteResponse)
                val quoteId = quoteJson.getString("quoteId")
                val actions = quoteJson.getJSONArray("actions")

                // Step 2: Execute approval transaction if present
                for (i in 0 until actions.length()) {
                    val action = actions.getJSONObject(i)
                    if (action.getString("type") == "approval") {
                        val approvalTo = action.getString("to")
                        val approvalData = action.getString("data")
                        val approvalValue = action.optString("value", "0")

                        Log.d(TAG, "Executing approval transaction")
                        val rpc = chainIdToRpc(chainId) ?: return@withContext SkillResult.Error("Unsupported chain")
                        if (chainId != wallet.getChainId()) {
                            wallet.changeChain(chainId, rpc, chainIdToBundler(chainId))
                        }
                        val txResult = wallet.sendTransaction(
                            to = approvalTo,
                            value = approvalValue,
                            data = approvalData,
                            callGas = null,
                            chainId = chainId,
                            rpcEndpoint = rpc,
                        )
                        if (txResult == "decline") {
                            return@withContext SkillResult.Error("Order cancelled: approval declined by user")
                        }
                        Log.d(TAG, "Approval tx: $txResult")
                    }
                }

                // Step 3: Sign EIP-712 typed data
                var signature: String? = null
                for (i in 0 until actions.length()) {
                    val action = actions.getJSONObject(i)
                    if (action.getString("type") == "orderSignature") {
                        val typedData = action.getJSONObject("typedData")
                        val typedDataJson = buildSignableTypedData(typedData)

                        val rpc = chainIdToRpc(chainId) ?: return@withContext SkillResult.Error("Unsupported chain")
                        if (chainId != wallet.getChainId()) {
                            wallet.changeChain(chainId, rpc, chainIdToBundler(chainId))
                        }

                        val typeStrings = listOf(
                            "eth_signTypedData_v4", "eth_signTypedData",
                            "typed_data", "signTypedData", "typed",
                        )
                        for (ts in typeStrings) {
                            val sig = wallet.signMessage(typedDataJson, chainId, ts)
                            if (sig != null && sig != "decline" && !sig.startsWith("error", true) &&
                                sig.startsWith("0x") && sig.length >= 130
                            ) {
                                signature = sig
                                break
                            }
                            if (sig == "decline") {
                                return@withContext SkillResult.Error("Order cancelled: signing declined by user")
                            }
                        }
                        if (signature == null) {
                            return@withContext SkillResult.Error("Failed to sign order — wallet may not support EIP-712 typed data signing")
                        }
                    }
                }

                if (signature == null) {
                    return@withContext SkillResult.Error("No signature action found in quote response")
                }

                // Step 4: Submit
                val submitBody = JSONObject().apply {
                    put("quoteId", quoteId)
                    put("orderSignature", signature)
                }
                Log.d(TAG, "Submitting order with quoteId=$quoteId")
                val submitResponse = httpPost("$BASE_URL/submit", submitBody, apiKey)
                val submitJson = JSONObject(submitResponse)

                val orderId = submitJson.optString("orderId", "")
                    .ifBlank { submitJson.optString("_id", "unknown") }
                val submitStatus = submitJson.optString("status", "submitted")

                val meta = quoteJson.optJSONObject("metadata")
                val sellSym = meta?.optJSONObject("sellToken")?.optString("symbol") ?: "?"
                val buySym = meta?.optJSONObject("buyToken")?.optString("symbol") ?: "?"

                SkillResult.Success(buildString {
                    appendLine("ORDER CREATED SUCCESSFULLY")
                    appendLine("Order ID: $orderId")
                    appendLine("Type: ${formatOrderType(orderType)}")
                    appendLine("Status: $submitStatus")
                    appendLine("Sell: $sellAmountHuman $sellSym")
                    appendLine("Buy: $buySym")
                    appendLine("Chain: ${chainDisplayName(chainId)}")
                    appendLine("Expires: ${formatTimestamp(expirationDate)}")
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create Bankr order", e)
                SkillResult.Error("Failed to create order: ${e.message}")
            }
        }
    }

    // ── cancel_bankr_order ────────────────────────────────────────────────

    private suspend fun cancelBankrOrder(params: JsonObject): SkillResult {
        val orderId = params["order_id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return SkillResult.Error("'order_id' is required")
        val apiKey = BuildConfig.BANKR_API
        if (apiKey.isBlank()) return SkillResult.Error("Bankr API key not configured.")

        return withContext(Dispatchers.IO) {
            try {
                // Fetch order to get chain and protocol info
                val orderResponse = httpGetWithKey("$BASE_URL/$orderId", apiKey)
                val orderJson = JSONObject(orderResponse).getJSONObject("order")
                val chainId = orderJson.optInt("chainId", 8453)
                val status = orderJson.optString("status", "")

                if (status.lowercase() !in listOf("open", "ready", "pending")) {
                    return@withContext SkillResult.Error(
                        "Cannot cancel order — status is '$status'. Only open/ready/pending orders can be cancelled."
                    )
                }

                val protocolData = orderJson.optJSONObject("protocolData")
                val protocolAddress = protocolData?.optString("protocolAddress")
                    ?: return@withContext SkillResult.Error("Order missing protocol address for cancellation")

                // Build cancel typed data
                val cancelTypedData = JSONObject().apply {
                    put("domain", JSONObject().apply {
                        put("name", "BankrOrders")
                        put("version", "1")
                        put("chainId", chainId)
                        put("verifyingContract", protocolAddress)
                    })
                    put("types", JSONObject().apply {
                        put("EIP712Domain", JSONArray().apply {
                            put(JSONObject().apply { put("name", "name"); put("type", "string") })
                            put(JSONObject().apply { put("name", "version"); put("type", "string") })
                            put(JSONObject().apply { put("name", "chainId"); put("type", "uint256") })
                            put(JSONObject().apply { put("name", "verifyingContract"); put("type", "address") })
                        })
                        put("CancelOrder", JSONArray().apply {
                            put(JSONObject().apply { put("name", "orderId"); put("type", "string") })
                        })
                    })
                    put("primaryType", "CancelOrder")
                    put("message", JSONObject().apply { put("orderId", orderId) })
                }

                // Sign cancellation
                val wallet = getOrCreateWallet(chainId)
                    ?: return@withContext SkillResult.Error("Wallet not available for chain $chainId")
                val rpc = chainIdToRpc(chainId) ?: return@withContext SkillResult.Error("Unsupported chain")
                if (chainId != wallet.getChainId()) {
                    wallet.changeChain(chainId, rpc, chainIdToBundler(chainId))
                }

                var signature: String? = null
                val typedDataStr = cancelTypedData.toString()
                val typeStrings = listOf(
                    "eth_signTypedData_v4", "eth_signTypedData",
                    "typed_data", "signTypedData", "typed",
                )
                for (ts in typeStrings) {
                    val sig = wallet.signMessage(typedDataStr, chainId, ts)
                    if (sig != null && sig != "decline" && !sig.startsWith("error", true) &&
                        sig.startsWith("0x") && sig.length >= 130
                    ) {
                        signature = sig
                        break
                    }
                    if (sig == "decline") {
                        return@withContext SkillResult.Error("Cancellation declined by user")
                    }
                }
                if (signature == null) {
                    return@withContext SkillResult.Error("Failed to sign cancellation")
                }

                // Submit cancellation
                val cancelBody = JSONObject().apply { put("signature", signature) }
                val cancelResponse = httpPost("$BASE_URL/cancel/$orderId", cancelBody, apiKey)
                val cancelJson = JSONObject(cancelResponse)

                if (cancelJson.optBoolean("success", false)) {
                    SkillResult.Success("ORDER CANCELLED SUCCESSFULLY\nOrder ID: $orderId\nStatus: ${cancelJson.optString("status")}")
                } else {
                    SkillResult.Error("Cancellation failed. Status: ${cancelJson.optString("status")}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel order", e)
                SkillResult.Error("Failed to cancel order: ${e.message}")
            }
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────

    private fun httpGet(url: String): String? {
        val request = Request.Builder().url(url)
            .addHeader("Accept", "application/json")
            .get().build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.string()
        }
    }

    private fun httpGetWithKey(url: String, apiKey: String): String {
        val request = Request.Builder().url(url)
            .addHeader("Accept", "application/json")
            .addHeader("X-API-Key", apiKey)
            .get().build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body == null) {
                val errMsg = body?.let { parseApiError(it) } ?: "HTTP ${resp.code}"
                throw Exception(errMsg)
            }
            return body
        }
    }

    private fun httpPost(url: String, jsonBody: JSONObject, apiKey: String): String {
        val request = Request.Builder().url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-Key", apiKey)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body == null) {
                val errMsg = body?.let { parseApiError(it) } ?: "HTTP ${resp.code}"
                throw Exception(errMsg)
            }
            return body
        }
    }

    private fun parseApiError(body: String): String {
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message") ?: body.take(200)
        } catch (_: Exception) {
            body.take(200)
        }
    }

    // ── Signing helper ───────────────────────────────────────────────────

    private fun buildSignableTypedData(typedData: JSONObject): String {
        val result = JSONObject()
        result.put("domain", typedData.getJSONObject("domain"))

        val types = typedData.getJSONObject("types")
        if (!types.has("EIP712Domain")) {
            types.put("EIP712Domain", JSONArray().apply {
                put(JSONObject().apply { put("name", "name"); put("type", "string") })
                put(JSONObject().apply { put("name", "version"); put("type", "string") })
                put(JSONObject().apply { put("name", "chainId"); put("type", "uint256") })
                put(JSONObject().apply { put("name", "verifyingContract"); put("type", "address") })
            })
        }
        result.put("types", types)
        result.put("primaryType", typedData.getString("primaryType"))
        result.put("message", typedData.getJSONObject("message"))

        return result.toString()
    }

    // ── Formatting helpers ────────────────────────────────────────────────

    private fun extractTokenSymbol(order: JSONObject, key: String): String {
        val token = order.optJSONObject(key)
        return token?.optString("symbol")?.takeIf { it.isNotBlank() }
            ?: token?.optString("address")?.let { "${it.take(6)}...${it.takeLast(4)}" }
            ?: "?"
    }

    private fun formatOrderType(type: String) = when (type) {
        "limit-buy" -> "Limit Buy"; "limit-sell" -> "Limit Sell"
        "stop-buy" -> "Stop Buy"; "stop-sell" -> "Stop Sell"
        "twap" -> "TWAP"; "dca" -> "DCA"; else -> type
    }

    private fun formatTimestamp(epoch: Long): String {
        val fmt = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.US)
        return fmt.format(Date(epoch * 1000))
    }

    private fun formatInterval(seconds: Int) = when {
        seconds >= 86400 -> "${seconds / 86400}d"
        seconds >= 3600 -> "${seconds / 3600}h"
        seconds >= 60 -> "${seconds / 60}m"
        else -> "${seconds}s"
    }

    private fun formatOrderDetails(order: JSONObject): String {
        val orderId = order.optString("orderId", "?")
        val type = formatOrderType(order.optString("orderType", "?"))
        val status = order.optString("status", "?").uppercase()
        val chainId = order.optInt("chainId", 0)
        val sellSym = extractTokenSymbol(order, "sellToken")
        val buySym = extractTokenSymbol(order, "buyToken")
        val sellAmt = order.optJSONObject("sellToken")?.optJSONObject("amount")
            ?.optString("formatted", "?") ?: "?"
        val buyAmt = order.optJSONObject("buyToken")?.optJSONObject("amount")
            ?.optString("formatted", "?") ?: "?"
        val expiresAt = order.optLong("expiresAt", 0)

        return buildString {
            appendLine("Order: $orderId")
            appendLine("Type: $type")
            appendLine("Status: $status")
            appendLine("Sell: $sellAmt $sellSym")
            appendLine("Buy: $buyAmt $buySym")

            val cfg = order.optJSONObject("config")
            if (cfg != null) {
                cfg.optString("triggerPrice").takeIf { it.isNotBlank() }
                    ?.let { appendLine("Trigger price: $it") }
                val interval = cfg.optInt("interval", 0)
                if (interval > 0) appendLine("Interval: ${formatInterval(interval)}")
                val maxExec = cfg.optInt("maxExecutions", 0)
                if (maxExec > 0) appendLine("Max executions: $maxExec")
            }

            appendLine("Chain: ${chainDisplayName(chainId)}")
            if (expiresAt > 0) appendLine("Expires: ${formatTimestamp(expiresAt)}")

            val history = order.optJSONArray("executionHistory")
            if (history != null && history.length() > 0) {
                appendLine("Executions: ${history.length()}")
                val last = history.getJSONObject(history.length() - 1)
                val execStatus = last.optString("status", "?")
                val execAt = last.optLong("executedAt", 0)
                appendLine("Last execution: $execStatus at ${formatTimestamp(execAt)}")
                last.optJSONObject("output")?.optString("txHash")?.takeIf { it.isNotBlank() }
                    ?.let { appendLine("Transaction: https://blockscan.com/tx/$it") }
            }

            order.optJSONObject("totalSoldAmount")?.optString("formatted")
                ?.let { appendLine("Total sold: $it $sellSym") }
            order.optJSONObject("totalReceivedAmount")?.optString("formatted")
                ?.let { appendLine("Total received: $it $buySym") }
        }.trim()
    }
}
