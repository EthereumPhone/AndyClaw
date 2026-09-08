package org.ethereumphone.andyclaw.agentwallet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ethereumphone.andyclaw.gateway.KeyValueStore
import org.ethereumphone.subwalletsdk.SubWalletSDK
import org.ethereumphone.walletsdk.WalletSDK
import org.json.JSONObject
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import java.math.BigInteger
import java.security.KeyStore
import java.util.concurrent.TimeUnit

/**
 * Everything the user-facing agent-wallet UI needs: the sub-account address, what it holds,
 * and how to send from it.
 *
 * ## Key safety
 *
 * The sub-account address is `CREATE2` over `owners = [P-256 pubkey (x,y), OS wallet
 * address]`, so the P-256 key **is** owner[0]. Destroying or regenerating
 * [SUB_WALLET_KEY_ALIAS] changes the address and strands whatever sits at the old one, and
 * there is no rescue path — the OS wallet cannot produce an ERC-1271 signature over a raw
 * hash, so owner[1] cannot authorise a sub-account UserOperation.
 *
 * Consequently this class:
 *  - never touches the key (no `deleteEntry`, no `KeyGenParameterSpec`, no `CryptoObject`),
 *  - leaves `DgenSubAccountSDK` pinned and unmodified, and
 *  - runs [checkIntegrity] before every send, so a key that has been replaced or wiped
 *    surfaces as a blocked send naming the old address rather than a silently-new, empty
 *    wallet.
 */
class AgentWalletRepository(
    context: Context,
    private val store: KeyValueStore,
) {

    companion object {
        private const val TAG = "AgentWalletRepo"

        /** Default `keyAlias` of [SubWalletSDK]. Read only — never created or deleted here. */
        const val SUB_WALLET_KEY_ALIAS = "p256_walletsdk"

        /** Where the first-seen sub-account address is anchored. */
        private const val ANCHOR_KEY = "agentwallet.anchorAddress"

        private const val BALANCE_TTL_MS = 60_000L

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val appContext = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val subWalletsByChain = mutableMapOf<Int, SubWalletSDK>()
    private val osWalletsByChain = mutableMapOf<Int, WalletSDK>()
    private val web3ByChain = mutableMapOf<Int, Web3j>()
    private val lock = Mutex()

    private var cachedAddress: String? = null
    private var cachedBalances: List<ChainBalances> = emptyList()
    private var cachedBalancesAt: Long = 0L

    // ── SDK access ──────────────────────────────────────────────────────

    private suspend fun subWallet(chainId: Int): SubWalletSDK? = lock.withLock {
        val existing = subWalletsByChain[chainId]
        if (existing != null) return@withLock existing

        val rpc = AgentWalletChains.chainIdToRpc(chainId) ?: return@withLock null
        try {
            SubWalletSDK(
                context = appContext,
                web3jInstance = Web3j.build(HttpService(rpc)),
                bundlerRPCUrl = AgentWalletChains.chainIdToBundler(chainId),
            ).also { subWalletsByChain[chainId] = it }
        } catch (e: Exception) {
            Log.w(TAG, "SubWalletSDK unavailable for chain $chainId: ${e.message}")
            null
        }
    }

    private suspend fun osWallet(chainId: Int): WalletSDK? = lock.withLock {
        val existing = osWalletsByChain[chainId]
        if (existing != null) return@withLock existing

        val rpc = AgentWalletChains.chainIdToRpc(chainId) ?: return@withLock null
        try {
            WalletSDK(
                context = appContext,
                web3jInstance = Web3j.build(HttpService(rpc)),
                bundlerRPCUrl = AgentWalletChains.chainIdToBundler(chainId),
            ).also { osWalletsByChain[chainId] = it }
        } catch (e: Exception) {
            Log.w(TAG, "WalletSDK unavailable for chain $chainId: ${e.message}")
            null
        }
    }

    private fun web3(chainId: Int): Web3j? {
        web3ByChain[chainId]?.let { return it }
        val rpc = AgentWalletChains.chainIdToRpc(chainId) ?: return null
        return Web3j.build(HttpService(rpc)).also { web3ByChain[chainId] = it }
    }

    // ── Address and integrity ───────────────────────────────────────────

    /** The agent sub-account address, or null when the sub-account is unavailable. */
    suspend fun getAddress(): String? {
        cachedAddress?.let { return it }
        val sdk = subWallet(1) ?: return null
        return try {
            withContext(Dispatchers.IO) { sdk.getAddress() }.also { cachedAddress = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to derive agent wallet address: ${e.message}")
            null
        }
    }

    /** The user's own dGEN1 wallet address, used to pre-fill the recipient field. */
    suspend fun getOsWalletAddress(): String? {
        val sdk = osWallet(1) ?: return null
        return try {
            withContext(Dispatchers.IO) { sdk.getAddress() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read OS wallet address: ${e.message}")
            null
        }
    }

    /**
     * Verify the sub-account is still the one we have seen before.
     *
     * On the first successful read the address is anchored. Afterwards a differing address,
     * or a missing keystore alias while an anchor exists, means owner[0] has changed — the
     * unrecoverable case. Sending is blocked so the user is told about the old address
     * instead of quietly funding a new wallet.
     */
    suspend fun checkIntegrity(): WalletIntegrity = withContext(Dispatchers.IO) {
        val anchor = store.getString(ANCHOR_KEY)?.trim()?.takeIf { it.isNotEmpty() }
        val aliasPresent = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias(SUB_WALLET_KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore lookup failed: ${e.message}")
            // Fail loudly rather than assuming the key is fine.
            return@withContext WalletIntegrity.Unknown(e.message ?: "keystore unavailable")
        }

        // Best-effort: constructing SubWalletSDK anywhere mints a key if the alias is
        // absent, so by the time we look another component may already have recreated it.
        // The address comparison below is the detector that holds regardless of ordering.
        if (anchor != null && !aliasPresent) {
            return@withContext WalletIntegrity.KeyMissing(anchor)
        }

        val current = getAddress()
            ?: return@withContext WalletIntegrity.Unknown("agent wallet unavailable")

        if (anchor == null) {
            store.putString(ANCHOR_KEY, current)
            return@withContext WalletIntegrity.Ok(current)
        }

        if (!anchor.equals(current, ignoreCase = true)) {
            WalletIntegrity.AddressChanged(expected = anchor, actual = current)
        } else {
            WalletIntegrity.Ok(current)
        }
    }

    // ── Balances ────────────────────────────────────────────────────────

    /** Balances for one chain: the native token plus every ERC-20 we can see. */
    suspend fun scanChain(chainId: Int): ChainBalances = withContext(Dispatchers.IO) {
        val address = getAddress() ?: return@withContext ChainBalances.empty(chainId)
        val web3 = web3(chainId) ?: return@withContext ChainBalances.empty(chainId)
        val nativeInfo = AgentWalletChains.NATIVE_TOKENS[chainId]

        val native = try {
            web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().balance
        } catch (e: Exception) {
            Log.w(TAG, "Native balance failed on chain $chainId: ${e.message}")
            BigInteger.ZERO
        }

        val tokens = try {
            if (chainId in AgentWalletChains.ALCHEMY_TOKEN_BALANCE_CHAIN_IDS) {
                scanTokensViaAlchemy(chainId, address)
            } else {
                scanTokensViaBalanceOf(chainId, address, web3)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Token scan failed on chain $chainId, falling back: ${e.message}")
            runCatching { scanTokensViaBalanceOf(chainId, address, web3) }.getOrDefault(emptyList())
        }

        ChainBalances(
            chainId = chainId,
            native = TokenBalance(
                symbol = nativeInfo?.symbol ?: "ETH",
                name = nativeInfo?.name ?: "Ether",
                decimals = nativeInfo?.decimals ?: 18,
                contractAddress = null,
                raw = native,
            ),
            tokens = tokens.filter { it.raw.signum() > 0 },
        )
    }

    /**
     * Scan every supported chain.
     *
     * Chains are scanned concurrently — done sequentially this is ~16 round trips, which is
     * far too slow for the main-screen banner. Results are also cached briefly so repeated
     * navigation does not re-scan.
     */
    suspend fun scanAll(forceRefresh: Boolean = false): List<ChainBalances> = coroutineScope {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedBalances.isNotEmpty() && now - cachedBalancesAt < BALANCE_TTL_MS) {
            return@coroutineScope cachedBalances
        }
        val results = AgentWalletChains.SUPPORTED_CHAIN_IDS
            .map { chainId -> async { scanChain(chainId) } }
            .awaitAll()
        cachedBalances = results
        cachedBalancesAt = System.currentTimeMillis()
        results
    }

    /** One `alchemy_getTokenBalances` call covers every ERC-20 the account holds. */
    private fun scanTokensViaAlchemy(chainId: Int, address: String): List<TokenBalance> {
        val rpc = AgentWalletChains.chainIdToRpc(chainId) ?: return emptyList()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", "alchemy_getTokenBalances")
            .put("params", org.json.JSONArray().put(address).put("erc20"))
            .toString()

        val response = http.newCall(
            Request.Builder().url(rpc).post(body.toRequestBody(JSON)).build()
        ).execute()

        val text = response.use { it.body?.string().orEmpty() }
        val json = JSONObject(text)
        if (json.has("error")) return emptyList()

        val balances = json.optJSONObject("result")?.optJSONArray("tokenBalances")
            ?: return emptyList()

        val known = AgentWalletChains.tokensOnChain(chainId)
            .associateBy { it.addresses.getValue(chainId).lowercase() }

        val out = mutableListOf<TokenBalance>()
        for (i in 0 until balances.length()) {
            val entry = balances.optJSONObject(i) ?: continue
            val contract = entry.optString("contractAddress").lowercase()
            val rawHex = entry.optString("tokenBalance").removePrefix("0x")
            if (rawHex.isEmpty()) continue
            val raw = runCatching { BigInteger(rawHex, 16) }.getOrNull() ?: continue
            if (raw.signum() == 0) continue

            // Only surface tokens we can name and price in decimals. An unknown contract
            // could be a spoofed airdrop, and we would have to guess its decimals.
            val token = known[contract] ?: continue
            out += TokenBalance(
                symbol = token.symbol,
                name = token.name,
                decimals = token.decimals,
                contractAddress = token.addresses.getValue(chainId),
                raw = raw,
            )
        }
        return out
    }

    /** Fallback for chains Alchemy does not index: ask each well-known token directly. */
    private fun scanTokensViaBalanceOf(
        chainId: Int,
        address: String,
        web3: Web3j,
    ): List<TokenBalance> = AgentWalletChains.tokensOnChain(chainId).mapNotNull { token ->
        val contract = token.addresses[chainId] ?: return@mapNotNull null
        val raw = runCatching { erc20BalanceOf(web3, contract, address) }.getOrNull()
            ?: return@mapNotNull null
        if (raw.signum() == 0) return@mapNotNull null
        TokenBalance(token.symbol, token.name, token.decimals, contract, raw)
    }

    private fun erc20BalanceOf(web3: Web3j, contract: String, owner: String): BigInteger {
        val function = Function(
            "balanceOf",
            listOf(Address(owner)),
            listOf(object : TypeReference<Uint256>() {}),
        )
        val encoded = FunctionEncoder.encode(function)
        val response = web3.ethCall(
            org.web3j.protocol.core.methods.request.Transaction
                .createEthCallTransaction(owner, contract, encoded),
            DefaultBlockParameterName.LATEST,
        ).send()
        if (response.hasError()) return BigInteger.ZERO
        val decoded = FunctionReturnDecoder.decode(response.value, function.outputParameters)
        return (decoded.firstOrNull()?.value as? BigInteger) ?: BigInteger.ZERO
    }

    // ── Sending ─────────────────────────────────────────────────────────

    /**
     * Send [amountBaseUnits] of [token] from the sub-account to [to] on [chainId].
     *
     * Blocked if [checkIntegrity] is not [WalletIntegrity.Ok]. The SDK returns the bundler's
     * raw string and does not throw on rejection, so the result goes through
     * [SubWalletResult.parse] rather than being treated as a hash.
     */
    suspend fun send(
        chainId: Int,
        token: TokenBalance,
        to: String,
        amountBaseUnits: BigInteger,
    ): SubWalletResult {
        val integrity = checkIntegrity()
        if (integrity !is WalletIntegrity.Ok) {
            return SubWalletResult.Failure(integrity.blockedReason(), "integrity:$integrity")
        }

        val sdk = subWallet(chainId)
            ?: return SubWalletResult.Failure("Agent wallet is not available on this chain.", "")
        val rpc = AgentWalletChains.chainIdToRpc(chainId)
        val nativeSymbol = AgentWalletChains.nativeSymbol(chainId)
        val chainName = AgentWalletChains.chainDisplayName(chainId)

        return try {
            val raw = withContext(Dispatchers.IO) {
                if (token.isNative) {
                    sdk.sendTransaction(
                        to = to,
                        value = amountBaseUnits.toString(),
                        data = "0x",
                        callGas = null,
                        chainId = chainId,
                        rpcEndpoint = rpc,
                    )
                } else {
                    val transfer = Function(
                        "transfer",
                        listOf(Address(to), Uint256(amountBaseUnits)),
                        emptyList(),
                    )
                    sdk.sendTransaction(
                        to = token.contractAddress!!,
                        value = "0",
                        data = FunctionEncoder.encode(transfer),
                        callGas = null,
                        chainId = chainId,
                        rpcEndpoint = rpc,
                    )
                }
            }
            SubWalletResult.parse(raw, nativeSymbol, chainName)
        } catch (e: Exception) {
            SubWalletResult.Failure(
                e.message ?: "The transaction could not be submitted.",
                e.toString(),
            )
        }
    }

    /**
     * Move a little native token from the user's own dGEN1 wallet into the sub-account so it
     * can pay its own gas.
     *
     * There is no paymaster behind `SubWalletSDK` — `paymasterAndData` is always empty — so
     * an account holding only ERC-20s cannot move them. This is the way out of that corner.
     * It spends the *user's* wallet, so it raises the normal terminal-screen confirmation.
     */
    suspend fun fundGasFromOsWallet(chainId: Int, amountWei: BigInteger): GasTopUpResult {
        val agentAddress = getAddress()
            ?: return GasTopUpResult.Failure("Agent wallet address unavailable.")
        val sdk = osWallet(chainId)
            ?: return GasTopUpResult.Failure("Your dGEN1 wallet is not available on this chain.")
        val rpc = AgentWalletChains.chainIdToRpc(chainId)

        return try {
            val result = withContext(Dispatchers.IO) {
                sdk.sendTransaction(
                    to = agentAddress,
                    value = amountWei.toString(),
                    data = "0x",
                    callGas = null,
                    chainId = chainId,
                    rpcEndpoint = rpc,
                )
            }
            when {
                result == "decline" -> GasTopUpResult.Declined
                result.startsWith("0x") -> GasTopUpResult.Success(result)
                else -> GasTopUpResult.Failure(result.removePrefix("Error:").trim())
            }
        } catch (e: Exception) {
            GasTopUpResult.Failure(e.message ?: "Could not send from your dGEN1 wallet.")
        }
    }

    /** Drop cached balances, e.g. after a send. */
    fun invalidateBalances() {
        cachedBalancesAt = 0L
    }
}

// ── Types ───────────────────────────────────────────────────────────────

data class TokenBalance(
    val symbol: String,
    val name: String,
    val decimals: Int,
    /** null for the chain's native token. */
    val contractAddress: String?,
    val raw: BigInteger,
) {
    val isNative: Boolean get() = contractAddress == null

    fun display(maxDecimals: Int = 6): String =
        AmountFormat.formatForDisplay(raw, decimals, maxDecimals)
}

data class ChainBalances(
    val chainId: Int,
    val native: TokenBalance,
    val tokens: List<TokenBalance>,
) {
    val chainName: String get() = AgentWalletChains.chainDisplayName(chainId)

    /** Native first, then ERC-20s — the order the token picker shows. */
    val all: List<TokenBalance> get() = listOf(native) + tokens

    val hasFunds: Boolean get() = native.raw.signum() > 0 || tokens.any { it.raw.signum() > 0 }

    val canPayGas: Boolean get() = native.raw.signum() > 0

    companion object {
        fun empty(chainId: Int): ChainBalances {
            val info = AgentWalletChains.NATIVE_TOKENS[chainId]
            return ChainBalances(
                chainId = chainId,
                native = TokenBalance(
                    symbol = info?.symbol ?: "ETH",
                    name = info?.name ?: "Ether",
                    decimals = info?.decimals ?: 18,
                    contractAddress = null,
                    raw = BigInteger.ZERO,
                ),
                tokens = emptyList(),
            )
        }
    }
}

/** Result of comparing the live sub-account address against the anchored one. */
sealed interface WalletIntegrity {

    data class Ok(val address: String) : WalletIntegrity

    /** The keystore alias is gone but we have seen an address before. Funds are stranded. */
    data class KeyMissing(val anchoredAddress: String) : WalletIntegrity

    /** The derived address no longer matches the anchor — owner[0] changed. */
    data class AddressChanged(val expected: String, val actual: String) : WalletIntegrity

    data class Unknown(val reason: String) : WalletIntegrity

    fun blockedReason(): String = when (this) {
        is Ok -> ""
        is KeyMissing ->
            "The agent wallet's signing key is missing. Funds at $anchoredAddress cannot be " +
                "moved from this device. Do not send anything further to the agent wallet."
        is AddressChanged ->
            "The agent wallet address changed from $expected to $actual. Sending is blocked — " +
                "the funds at $expected are no longer reachable from this device."
        is Unknown -> "Could not verify the agent wallet ($reason). Sending is blocked."
    }
}

sealed interface GasTopUpResult {
    data class Success(val userOpHash: String) : GasTopUpResult
    data object Declined : GasTopUpResult
    data class Failure(val message: String) : GasTopUpResult
}
