package org.ethereumphone.andyclaw.skills.builtin

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.SkillManifest
import org.ethereumphone.andyclaw.skills.SkillResult
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

class TokenLookupSkill : AndyClawSkill {
    override val id = "token_lookup"
    override val name = "Token Lookup"

    companion object {
        private const val TAG = "TokenLookupSkill"

        private val WETH_ADDRESSES = mapOf(
            1 to "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2",
            10 to "0x4200000000000000000000000000000000000006",
            137 to "0x7ceb23fd6bc0add59e62ac25578270cff1b9f619",
            42161 to "0x82af49447d8a07e3bd95bd0d56f35241523fbab1",
            8453 to "0x4200000000000000000000000000000000000006",
        )

        private val CHAIN_NAMES = mapOf(
            1 to "ethereum", 10 to "optimism", 137 to "polygon",
            42161 to "arbitrum", 8453 to "base",
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override val baseManifest = SkillManifest(
        description = "Look up token information, prices, and launched tokens. " +
                "Search tokens by name, symbol, or contract address via DexScreener and Clanker. " +
                "Get real-time USD prices for any ERC-20 or native token.",
        tools = listOf(
            ToolDefinition(
                name = "lookup_token",
                description = "Search for a token by name, symbol, or contract address. " +
                        "Returns trading pairs with price, liquidity, 24h volume/change, FDV, and DexScreener URL. " +
                        "Uses DexScreener as primary source with Clanker as fallback. Only returns EVM chains (no Solana).",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "query" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Token name, ticker symbol, or contract address (0x...)"
                                        ),
                                    )
                                ),
                                "limit" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive(
                                            "Maximum number of results (default: 5, max: 10)"
                                        ),
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("query"))),
                    )
                ),
            ),
            ToolDefinition(
                name = "get_token_price",
                description = "Get the current USD price of a token. " +
                        "For ERC-20 tokens, provide the contract address. " +
                        "For native tokens (ETH, MATIC, etc.), provide the symbol or any ETH/WETH address. " +
                        "Handles ETH/WETH addresses interchangeably.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "token" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Contract address (0x...) or native token symbol (ETH, MATIC, BNB, AVAX)"
                                        ),
                                    )
                                ),
                                "chain_id" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive(
                                            "Chain ID (1=Ethereum, 10=Optimism, 137=Polygon, 42161=Arbitrum, 8453=Base). Default: 8453"
                                        ),
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("token"))),
                    )
                ),
            ),
            ToolDefinition(
                name = "get_launched_tokens",
                description = "Query tokens launched via Clanker / ethOS. " +
                        "Search by name, symbol, or filter by social interface (e.g. ethOS). " +
                        "Returns token info with market data when available.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "query" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Token name or symbol to search for"
                                        ),
                                    )
                                ),
                                "limit" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "description" to JsonPrimitive(
                                            "Maximum number of results (default: 5, max: 20)"
                                        ),
                                    )
                                ),
                                "social_interface" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "description" to JsonPrimitive(
                                            "Filter by social interface (e.g. 'ethOS'). Optional."
                                        ),
                                    )
                                ),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("query"))),
                    )
                ),
            ),
        ),
    )

    override val privilegedManifest: SkillManifest? = null

    override suspend fun execute(tool: String, params: JsonObject, tier: Tier): SkillResult {
        return when (tool) {
            "lookup_token" -> lookupToken(params)
            "get_token_price" -> getTokenPrice(params)
            "get_launched_tokens" -> getLaunchedTokens(params)
            else -> SkillResult.Error("Unknown tool: $tool")
        }
    }

    // ── lookup_token ──────────────────────────────────────────────────────

    private suspend fun lookupToken(params: JsonObject): SkillResult {
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return SkillResult.Error("'query' parameter is required")
        if (query.isEmpty()) return SkillResult.Error("Query must not be empty")

        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 10)
        val isAddress = isContractAddress(query)

        return withContext(Dispatchers.IO) {
            // Try DexScreener first
            try {
                val url = if (isAddress) {
                    "https://api.dexscreener.com/latest/dex/tokens/${query.lowercase(Locale.US)}"
                } else {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    "https://api.dexscreener.com/latest/dex/search?q=$encoded"
                }

                val body = httpGet(url) ?: throw Exception("Empty response from DexScreener")
                val json = JSONObject(body)
                val allPairs = json.optJSONArray("pairs") ?: JSONArray()

                val evmPairs = JSONArray()
                for (i in 0 until allPairs.length()) {
                    val pair = allPairs.getJSONObject(i)
                    if (pair.optString("chainId", "").lowercase(Locale.US) != "solana") {
                        evmPairs.put(pair)
                    }
                }

                if (evmPairs.length() > 0) {
                    return@withContext SkillResult.Success(formatPairs(query, evmPairs, limit, isAddress))
                }
            } catch (e: Exception) {
                Log.d(TAG, "DexScreener lookup failed, trying Clanker", e)
            }

            // Fallback to Clanker (only for name/symbol, not addresses)
            if (isAddress) {
                return@withContext SkillResult.Error(
                    "Token lookup failed: no results found for contract $query"
                )
            }

            try {
                val result = lookupClankerTokens(query, limit)
                SkillResult.Success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Both DexScreener and Clanker lookups failed", e)
                SkillResult.Error("Token lookup failed: ${e.message ?: "no results found"}")
            }
        }
    }

    // ── get_token_price ───────────────────────────────────────────────────

    private suspend fun getTokenPrice(params: JsonObject): SkillResult {
        val token = params["token"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return SkillResult.Error("'token' parameter is required")
        val chainId = params["chain_id"]?.jsonPrimitive?.intOrNull ?: 8453

        return withContext(Dispatchers.IO) {
            try {
                if (isEthOrWeth(token, chainId)) {
                    val price = fetchNativeTokenPrice("ETH")
                        ?: return@withContext SkillResult.Error("Unable to fetch ETH price")
                    return@withContext SkillResult.Success(
                        "Current ETH price: \$${String.format(Locale.US, "%.2f", price)}"
                    )
                }

                val nativeSymbol = nativeSymbolOrNull(token)
                if (nativeSymbol != null) {
                    val price = fetchNativeTokenPrice(nativeSymbol)
                        ?: return@withContext SkillResult.Error("Unable to fetch $nativeSymbol price")
                    return@withContext SkillResult.Success(
                        "Current $nativeSymbol price: \$${String.format(Locale.US, "%.2f", price)}"
                    )
                }

                val price = fetchDexScreenerPrice(token, chainId)
                    ?: return@withContext SkillResult.Error(
                        "Unable to fetch price for token $token on chain $chainId"
                    )

                val formatted = formatPriceValue(price)
                SkillResult.Success("Current token price: $formatted")
            } catch (e: Exception) {
                Log.e(TAG, "Price lookup failed", e)
                SkillResult.Error("Price lookup failed: ${e.message}")
            }
        }
    }

    // ── get_launched_tokens ───────────────────────────────────────────────

    private suspend fun getLaunchedTokens(params: JsonObject): SkillResult {
        val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return SkillResult.Error("'query' parameter is required")
        val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 20)
        val socialInterface = params["social_interface"]?.jsonPrimitive?.contentOrNull?.trim()

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val urlBuilder = StringBuilder(
                    "https://www.clanker.world/api/tokens?q=$encoded&limit=$limit&includeMarket=true"
                )
                if (!socialInterface.isNullOrBlank()) {
                    urlBuilder.append("&socialInterface=")
                    urlBuilder.append(URLEncoder.encode(socialInterface, "UTF-8"))
                }

                val body = httpGet(urlBuilder.toString())
                    ?: return@withContext SkillResult.Error("Empty response from Clanker")
                val json = JSONObject(body)
                val dataArray = json.optJSONArray("data") ?: JSONArray()
                val total = json.optInt("total", 0)

                if (dataArray.length() == 0) {
                    return@withContext SkillResult.Success(
                        "No launched tokens found matching \"$query\"."
                    )
                }

                val result = formatClankerTokens(query, dataArray, limit, total)
                SkillResult.Success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Launched tokens lookup failed", e)
                SkillResult.Error("Launched tokens lookup failed: ${e.message}")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            return response.body?.string()
        }
    }

    private fun isContractAddress(value: String): Boolean =
        value.startsWith("0x") && value.length == 42 &&
                value.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    private fun isEthOrWeth(token: String, chainId: Int): Boolean {
        val norm = token.lowercase()
        if (norm == "eth" ||
            norm == "0x0000000000000000000000000000000000000000" ||
            norm == "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        ) return true
        WETH_ADDRESSES[chainId]?.let { if (norm == it) return true }
        return WETH_ADDRESSES.values.any { it == norm }
    }

    private fun nativeSymbolOrNull(token: String): String? {
        return when (token.uppercase()) {
            "ETH", "MATIC", "BNB", "AVAX" -> token.uppercase()
            else -> null
        }
    }

    private fun fetchNativeTokenPrice(symbol: String): Double? {
        val coinId = when (symbol.uppercase()) {
            "ETH" -> "ethereum"
            "MATIC" -> "matic-network"
            "BNB" -> "binancecoin"
            "AVAX" -> "avalanche-2"
            else -> symbol.lowercase()
        }
        return try {
            val url = "https://api.coingecko.com/api/v3/simple/price?ids=$coinId&vs_currencies=usd"
            val body = httpGet(url) ?: return null
            val json = JSONObject(body)
            json.optJSONObject(coinId)?.optDouble("usd", 0.0)?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "CoinGecko price fetch failed for $symbol", e)
            null
        }
    }

    private fun fetchDexScreenerPrice(contractAddress: String, chainId: Int): Double? {
        val chainName = CHAIN_NAMES[chainId] ?: "base"
        return try {
            val url = "https://api.dexscreener.com/latest/dex/tokens/$contractAddress"
            val body = httpGet(url) ?: return null
            val json = JSONObject(body)
            val pairs = json.optJSONArray("pairs") ?: return null

            var bestPrice: Double? = null
            var bestLiquidity = 0.0
            for (i in 0 until pairs.length()) {
                val pair = pairs.getJSONObject(i)
                if (pair.optString("chainId", "").lowercase() == chainName) {
                    val price = pair.optDouble("priceUsd", Double.NaN)
                    val liq = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0
                    if (!price.isNaN() && price > 0 && liq > bestLiquidity) {
                        bestPrice = price
                        bestLiquidity = liq
                    }
                }
            }
            if (bestPrice != null) return bestPrice

            for (i in 0 until pairs.length()) {
                val price = pairs.getJSONObject(i).optDouble("priceUsd", Double.NaN)
                if (!price.isNaN() && price > 0) return price
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "DexScreener price fetch failed for $contractAddress", e)
            null
        }
    }

    // ── Formatting ───────────────────────────────────────────────────────

    private fun formatPairs(query: String, pairs: JSONArray, limit: Int, queriedByAddress: Boolean): String {
        if (pairs.length() == 0) {
            val subject = if (queriedByAddress) "contract $query" else "\"$query\""
            return "DexScreener lookup for $subject: no active trading pairs found."
        }
        val count = min(limit, pairs.length())
        val sb = StringBuilder()
        sb.appendLine("DexScreener lookup for \"$query\" (showing $count of ${pairs.length()} pairs):")

        for (i in 0 until count) {
            val pair = pairs.getJSONObject(i)
            val base = pair.optJSONObject("baseToken")
            val quote = pair.optJSONObject("quoteToken")
            val sym = base?.optString("symbol").orEmpty()
            val bName = base?.optString("name").orEmpty()
            val bAddr = base?.optString("address").orEmpty()
            val qSym = quote?.optString("symbol").orEmpty()
            val qAddr = quote?.optString("address").orEmpty()
            val dex = pair.optString("dexId").ifBlank { "Unknown DEX" }
            val chain = pair.optString("chainId").ifBlank { "unknown chain" }

            val display = buildString {
                if (sym.isNotBlank()) {
                    append(sym)
                    if (bName.isNotBlank() && !bName.equals(sym, ignoreCase = true)) append(" ($bName)")
                } else append(bName.ifBlank { "Token" })
            }

            sb.appendLine("${i + 1}. $display vs $qSym on $dex (${chain.uppercase(Locale.US)})")
            if (bAddr.isNotBlank()) sb.appendLine("   Token address: $bAddr")
            if (qAddr.isNotBlank()) sb.appendLine("   Quote address: $qAddr")

            val metrics = mutableListOf<String>()
            val priceUsd = pair.optDouble("priceUsd", Double.NaN)
            val priceNative = pair.optString("priceNative")
            val isEthQuote = qSym.uppercase(Locale.US).let { it == "ETH" || it == "WETH" }

            if (isEthQuote && priceNative.isNotBlank()) {
                metrics.add("Price: $priceNative $qSym")
                formatPriceValue(priceUsd)?.let { metrics.add("Price: $it") }
            } else {
                formatPriceValue(priceUsd)?.let { metrics.add("Price: $it") }
                priceNative.takeIf { it.isNotBlank() }?.let {
                    metrics.add("Price: $it ${qSym.ifBlank { "native" }}")
                }
            }

            pair.optJSONObject("liquidity")?.optDouble("usd")?.let { formatUsd(it) }
                ?.let { metrics.add("Liquidity: $it") }
            pair.optJSONObject("volume")?.optDouble("h24")?.let { formatUsd(it) }
                ?.let { metrics.add("24h volume: $it") }
            pair.optJSONObject("priceChange")?.optDouble("h24", Double.NaN)?.let { formatPercent(it) }
                ?.let { metrics.add("24h change: $it") }
            pair.optDouble("fdv", Double.NaN).let { formatUsd(it) }
                ?.let { metrics.add("FDV: $it") }

            if (metrics.isNotEmpty()) sb.appendLine("   ${metrics.joinToString(" | ")}")
            pair.optString("url").takeIf { it.isNotBlank() }?.let { sb.appendLine("   $it") }
        }
        return sb.toString().trim()
    }

    private fun lookupClankerTokens(query: String, limit: Int): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.clanker.world/api/tokens?q=$encoded&limit=${limit.coerceIn(1, 20)}&includeMarket=true"
        val body = httpGet(url) ?: throw Exception("Empty response from Clanker")
        val json = JSONObject(body)
        val dataArray = json.optJSONArray("data") ?: JSONArray()
        val total = json.optInt("total", 0)

        if (dataArray.length() == 0) {
            return "Clanker lookup for \"$query\": no tokens found."
        }
        return formatClankerTokens(query, dataArray, limit, total)
    }

    private fun formatClankerTokens(query: String, data: JSONArray, limit: Int, total: Int): String {
        val count = min(limit, data.length())
        val sb = StringBuilder()
        sb.appendLine("Clanker lookup for \"$query\" (showing $count of $total tokens):")

        for (i in 0 until count) {
            val token = data.getJSONObject(i)
            val tName = token.optString("name", "").ifBlank { "Unknown Token" }
            val sym = token.optString("symbol", "").ifBlank { "UNKNOWN" }
            val addr = token.optString("contract_address", "")
            val desc = token.optString("description", "")
            val pair = token.optString("pair", "").ifBlank { "Unknown" }
            val cid = token.optInt("chain_id", 0)
            val pool = token.optString("pool_address", "")
            val deployed = token.optString("deployed_at", "")

            val display = if (sym.isNotBlank()) {
                if (tName.isNotBlank() && !tName.equals(sym, ignoreCase = true)) "$sym ($tName)" else sym
            } else tName

            val chainLabel = when (cid) {
                8453 -> "BASE"; 1 -> "ETHEREUM"; 137 -> "POLYGON"
                42161 -> "ARBITRUM"; 10 -> "OPTIMISM"; else -> "CHAIN_$cid"
            }

            sb.appendLine("${i + 1}. $display")
            if (addr.isNotBlank()) sb.appendLine("   Token address: $addr")
            sb.appendLine("   Pair: $pair on $chainLabel")
            if (pool.isNotBlank()) sb.appendLine("   Pool address: $pool")
            if (deployed.isNotBlank()) sb.appendLine("   Deployed: $deployed")
            if (desc.isNotBlank()) {
                sb.appendLine("   Description: ${if (desc.length > 100) desc.take(100) + "..." else desc}")
            }

            val market = token.optJSONObject("related")?.optJSONObject("market")
            val metrics = mutableListOf<String>()
            market?.optDouble("priceUsd")?.let { formatPriceValue(it) }?.let { metrics.add("Price: $it") }
            market?.optDouble("marketCap")?.let { formatUsd(it) }?.let { metrics.add("Market Cap: $it") }
            market?.optDouble("txH24")?.let { formatUsd(it) }?.let { metrics.add("24h Volume: $it") }
            market?.optDouble("pricePercentH24")?.let { formatPercent(it) }?.let { metrics.add("24h Change: $it") }
            if (metrics.isNotEmpty()) sb.appendLine("   ${metrics.joinToString(" | ")}")
        }
        return sb.toString().trim()
    }

    private fun formatPriceValue(value: Double?): String? {
        val v = value ?: return null
        if (v.isNaN() || v <= 0) return null
        val dec = when {
            v >= 1 -> 2; v >= 0.01 -> 4; v >= 0.0001 -> 6; else -> 8
        }
        return "\$${String.format(Locale.US, "%,.${dec}f", v)}"
    }

    private fun formatUsd(value: Double?): String? {
        val v = value ?: return null
        if (v.isNaN() || v <= 0) return null
        return "\$${String.format(Locale.US, "%,.2f", v)}"
    }

    private fun formatPercent(value: Double?): String? {
        val v = value ?: return null
        if (v.isNaN()) return null
        return String.format(Locale.US, "%+.2f%%", v)
    }
}
