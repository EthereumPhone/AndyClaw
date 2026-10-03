package org.ethereumphone.andyclaw.agentwallet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled
import org.ethereumphone.subwalletsdk.SubWalletSDK
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import java.security.KeyStore

/**
 * The one place this app builds a [SubWalletSDK]. The agent's tools, the agent-wallet screens and
 * the launcher all get theirs from [get]: one instance per chain, built under one lock.
 *
 * The SDK is pinned and unmodified, and two things in its constructor are guarded here:
 *  - **It never mints a second key.** The constructor reads
 *    [AgentWalletRepository.SUB_WALLET_KEY_ALIAS] with `getEntry` inside a catch-all and generates a
 *    new key under the same alias whenever that read fails. The key is owner[0] of the
 *    sub-account, so a keystore hiccup moved the agent wallet to a new, empty address and left
 *    what sat at the old one unreachable for good. With the alias present, its entry must read
 *    back before anything is built; a key that does not is reported, never replaced. With the
 *    alias gone but an address already anchored, the key is lost, and building would quietly
 *    open a new wallet under the old one's name, so nothing is built then either. The lock means
 *    two first constructions on a fresh install cannot both find the alias absent and both
 *    generate.
 *  - **It does not crash the process.** The constructor also starts an RPC (the counterfactual
 *    address) on a coroutine of its own that nothing catches, so a failed call killed AndyClaw —
 *    offline, or when the RPC answered 403/429. It is built only once that chain's RPC has
 *    answered. A failure between that answer and the SDK's own call is still possible; only the
 *    SDK can close it.
 */
object SubWalletFactory {

    private const val TAG = "SubWalletFactory"
    private const val KEY_READ_ATTEMPTS = 3
    private const val KEY_READ_RETRY_MS = 250L

    sealed interface Outcome {
        data class Ready(val sdk: SubWalletSDK) : Outcome
        data class Unavailable(val reason: String) : Outcome
    }

    private val lock = Mutex()
    private val byChain = HashMap<Int, SubWalletSDK>()

    /**
     * The sub-account SDK for [chainId]. [anchoredAddress] is the address first seen on this
     * device, or null before there was one (see [AgentWalletRepository.checkIntegrity]).
     */
    suspend fun get(context: Context, chainId: Int, anchoredAddress: () -> String?): Outcome = lock.withLock {
        byChain[chainId]?.let { return@withLock Outcome.Ready(it) }
        val rpc = AgentWalletChains.chainIdToRpc(chainId)
            ?: return@withLock Outcome.Unavailable("Chain $chainId is not supported by the agent wallet.")

        keyRefusal(anchoredAddress)?.let { return@withLock Outcome.Unavailable(it) }

        val web3 = Web3j.build(HttpService(rpc))
        if (!rpcAnswers(web3, chainId)) {
            web3.shutdown()
            return@withLock Outcome.Unavailable("The network for chain $chainId cannot be reached right now.")
        }

        try {
            val sdk = withContext(Dispatchers.IO) {
                SubWalletSDK(
                    context = context.applicationContext,
                    web3jInstance = web3,
                    bundlerRPCUrl = AgentWalletChains.chainIdToBundler(chainId),
                )
            }
            byChain[chainId] = sdk
            Outcome.Ready(sdk)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            Log.w(TAG, "SubWalletSDK unavailable for chain $chainId: ${e.message}")
            Outcome.Unavailable("The agent wallet is not available on this device: ${e.message}")
        }
    }

    /** Why the SDK must not be built now, or null when it may. Never touches the key itself. */
    private suspend fun keyRefusal(anchoredAddress: () -> String?): String? {
        val alias = AgentWalletRepository.SUB_WALLET_KEY_ALIAS
        repeat(KEY_READ_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(KEY_READ_RETRY_MS)
            val state = try {
                withContext(Dispatchers.IO) {
                    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    when {
                        !ks.containsAlias(alias) -> KeyState.ABSENT
                        ks.getEntry(alias, null) is KeyStore.PrivateKeyEntry -> KeyState.READABLE
                        else -> KeyState.UNREADABLE
                    }
                }
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                Log.w(TAG, "Keystore read failed (attempt ${attempt + 1}): ${e.message}")
                KeyState.UNREADABLE
            }
            when (state) {
                KeyState.READABLE -> return null
                KeyState.ABSENT -> {
                    if (anchoredAddress().isNullOrBlank()) return null // first use: the SDK creates it
                    Log.e(TAG, "Agent wallet key is gone although an address was anchored; not creating a new one")
                    return "The agent wallet's signing key is missing from this device. Not creating a " +
                        "new one: funds at the agent wallet's address could no longer be moved."
                }
                KeyState.UNREADABLE -> Unit
            }
        }
        Log.e(TAG, "Agent wallet key exists but could not be read; not creating a new one")
        return "The agent wallet's signing key could not be read right now. Not creating a new one — " +
            "that would move the agent wallet to a new address. Try again in a moment."
    }

    private enum class KeyState { READABLE, ABSENT, UNREADABLE }

    private suspend fun rpcAnswers(web3: Web3j, chainId: Int): Boolean = try {
        withContext(Dispatchers.IO) { !web3.ethChainId().send().hasError() }
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        Log.w(TAG, "RPC for chain $chainId did not answer: ${e.message}")
        false
    }
}
