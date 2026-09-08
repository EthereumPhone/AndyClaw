package org.ethereumphone.andyclaw.ui.agentwallet

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agentwallet.AgentWalletChains
import org.ethereumphone.andyclaw.agentwallet.AmountFormat
import org.ethereumphone.andyclaw.agentwallet.ChainBalances
import org.ethereumphone.andyclaw.agentwallet.EthAddress
import org.ethereumphone.andyclaw.agentwallet.GasTopUpResult
import org.ethereumphone.andyclaw.agentwallet.SubWalletResult
import org.ethereumphone.andyclaw.agentwallet.TokenBalance
import org.ethereumphone.andyclaw.agentwallet.WalletIntegrity
import org.kethereum.eip137.model.ENSName
import org.kethereum.ens.ENS
import org.kethereum.ens.isPotentialENSDomain
import org.kethereum.rpc.HttpEthereumRPC
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Drives the user-initiated send from the agent sub-account.
 *
 * The agent can already spend this wallet autonomously through its tools; this exists so a
 * user can spend it *without* the LLM — which is the only way to get funds out when the
 * agent is unavailable, uncooperative, or the user simply does not know the tools exist.
 */
class AgentWalletSendViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AgentWalletSendVM"
        private const val ENS_RPC = "https://ethereum.publicnode.com"

        /** Default gas top-up: enough for a handful of L2 sends, trivial on mainnet. */
        val DEFAULT_GAS_TOP_UP_ETH: BigDecimal = BigDecimal("0.0005")
    }

    private val app = application as NodeApp
    private val repo = app.agentWalletRepository
    private val txRepo = app.agentTxRepository

    private val _state = MutableStateFlow(AgentWalletSendUiState())
    val state: StateFlow<AgentWalletSendUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }

            val integrity = repo.checkIntegrity()
            val address = (integrity as? WalletIntegrity.Ok)?.address ?: repo.getAddress()

            if (address == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        integrity = integrity,
                        loadError = "The agent wallet is not available on this device.",
                    )
                }
                return@launch
            }

            val osAddress = repo.getOsWalletAddress()
            val balances = repo.scanAll(forceRefresh = force)

            _state.update { current ->
                // Default to the chain that actually holds something, so the common case
                // needs no picking at all.
                val chainId = current.selectedChainId
                    ?: balances.firstOrNull { it.hasFunds }?.chainId
                    ?: AgentWalletChains.SUPPORTED_CHAIN_IDS.first()
                val chain = balances.firstOrNull { it.chainId == chainId }
                current.copy(
                    loading = false,
                    address = address,
                    osWalletAddress = osAddress,
                    integrity = integrity,
                    balances = balances,
                    selectedChainId = chainId,
                    selectedToken = current.selectedToken
                        ?: chain?.all?.firstOrNull { it.raw.signum() > 0 }
                        ?: chain?.native,
                )
            }
        }
    }

    // ── Field edits ─────────────────────────────────────────────────────

    fun selectChain(chainId: Int) {
        _state.update { current ->
            val chain = current.balances.firstOrNull { it.chainId == chainId }
            current.copy(
                selectedChainId = chainId,
                // Token identity is per-chain; carrying one across would send the wrong asset.
                selectedToken = chain?.all?.firstOrNull { it.raw.signum() > 0 } ?: chain?.native,
                amount = "",
                outcome = null,
            )
        }
    }

    fun selectToken(token: TokenBalance) {
        _state.update { it.copy(selectedToken = token, amount = "", outcome = null) }
    }

    fun setAmount(value: String) {
        _state.update { it.copy(amount = value, outcome = null) }
    }

    /** Fill the amount with the full balance. Gas still comes out of the native balance. */
    fun setMaxAmount() {
        val token = _state.value.selectedToken ?: return
        _state.update {
            it.copy(amount = AmountFormat.fromBaseUnits(token.raw, token.decimals), outcome = null)
        }
    }

    fun setRecipient(value: String) {
        _state.update {
            it.copy(recipient = value, resolvedRecipient = null, ensError = null, outcome = null)
        }
    }

    fun useOwnWalletAsRecipient() {
        val own = _state.value.osWalletAddress ?: return
        setRecipient(own)
    }

    /** Resolve a `.eth` name. No-op for anything else. */
    fun resolveEns() {
        val input = _state.value.recipient.trim()
        if (!EthAddress.looksLikeEns(input)) return

        viewModelScope.launch {
            _state.update { it.copy(resolvingEns = true, ensError = null) }
            val resolved = withContext(Dispatchers.IO) {
                try {
                    val name = ENSName(input.lowercase())
                    if (!name.isPotentialENSDomain()) return@withContext null
                    ENS(HttpEthereumRPC(ENS_RPC)).getAddress(name)?.hex
                } catch (e: Exception) {
                    Log.w(TAG, "ENS resolution failed for $input: ${e.message}")
                    null
                }
            }
            _state.update {
                it.copy(
                    resolvingEns = false,
                    resolvedRecipient = resolved,
                    ensError = if (resolved == null) "Could not resolve $input" else null,
                )
            }
        }
    }

    // ── Sending ─────────────────────────────────────────────────────────

    /**
     * Perform the send. Call only after the confirmation gate has passed — this method does
     * not itself authenticate the user.
     */
    fun confirmSend() {
        val snapshot = _state.value
        val chainId = snapshot.selectedChainId ?: return
        val token = snapshot.selectedToken ?: return
        val recipient = snapshot.effectiveRecipient ?: return
        val amountBase = snapshot.amountBaseUnits ?: return

        viewModelScope.launch {
            _state.update { it.copy(sending = true, outcome = null) }

            when (val result = repo.send(chainId, token, recipient, amountBase)) {
                is SubWalletResult.Success -> {
                    // Only a real userOpHash is recorded — an error string is not history.
                    runCatching {
                        txRepo.save(
                            userOpHash = result.userOpHash,
                            chainId = chainId,
                            to = recipient,
                            amount = snapshot.amount.trim(),
                            token = token.symbol,
                            toolName = "ui_send",
                        )
                    }.onFailure { Log.w(TAG, "Failed to record send: ${it.message}") }

                    repo.invalidateBalances()
                    _state.update {
                        it.copy(
                            sending = false,
                            amount = "",
                            outcome = SendOutcome.Sent(result.userOpHash, chainId),
                        )
                    }
                    refresh(force = true)
                }

                is SubWalletResult.Failure -> _state.update {
                    it.copy(sending = false, outcome = SendOutcome.Failed(result.message))
                }
            }
        }
    }

    /**
     * Top the sub-account up with native token from the user's own wallet so it can pay gas.
     *
     * There is no paymaster behind the sub-account, so an account holding only ERC-20s
     * cannot move them without this.
     */
    fun fundGas() {
        val chainId = _state.value.selectedChainId ?: return
        val amountWei = DEFAULT_GAS_TOP_UP_ETH.movePointRight(18).toBigIntegerExact()

        viewModelScope.launch {
            _state.update { it.copy(fundingGas = true, outcome = null) }
            val outcome = when (val result = repo.fundGasFromOsWallet(chainId, amountWei)) {
                is GasTopUpResult.Success -> SendOutcome.GasFunded(result.userOpHash)
                is GasTopUpResult.Declined -> SendOutcome.Failed("You declined the gas top-up.")
                is GasTopUpResult.Failure -> SendOutcome.Failed(result.message)
            }
            _state.update { it.copy(fundingGas = false, outcome = outcome) }
            if (outcome is SendOutcome.GasFunded) {
                repo.invalidateBalances()
                refresh(force = true)
            }
        }
    }

    fun dismissOutcome() {
        _state.update { it.copy(outcome = null) }
    }
}

// ── UI state ────────────────────────────────────────────────────────────

data class AgentWalletSendUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val address: String? = null,
    val osWalletAddress: String? = null,
    val integrity: WalletIntegrity? = null,
    val balances: List<ChainBalances> = emptyList(),
    val selectedChainId: Int? = null,
    val selectedToken: TokenBalance? = null,
    val amount: String = "",
    val recipient: String = "",
    val resolvedRecipient: String? = null,
    val resolvingEns: Boolean = false,
    val ensError: String? = null,
    val sending: Boolean = false,
    val fundingGas: Boolean = false,
    val outcome: SendOutcome? = null,
) {
    val selectedChain: ChainBalances?
        get() = balances.firstOrNull { it.chainId == selectedChainId }

    val nativeSymbol: String
        get() = AgentWalletChains.nativeSymbol(selectedChainId ?: 1)

    val chainName: String
        get() = AgentWalletChains.chainDisplayName(selectedChainId ?: 1)

    /** Blocked entirely — the key is gone or the address moved. */
    val integrityBlock: String?
        get() = when (val i = integrity) {
            null, is WalletIntegrity.Ok -> null
            else -> i.blockedReason()
        }

    /** The address a send would actually go to: the ENS resolution if there was one. */
    val effectiveRecipient: String?
        get() = resolvedRecipient ?: recipient.trim().takeIf { EthAddress.isValid(it) }

    val amountBaseUnits: BigInteger?
        get() {
            val token = selectedToken ?: return null
            return AmountFormat.toBaseUnits(amount, token.decimals)
        }

    val amountError: String?
        get() {
            val token = selectedToken ?: return null
            if (amount.isBlank()) return null
            val base = AmountFormat.toBaseUnits(amount, token.decimals)
                ?: return "Enter a valid amount with at most ${token.decimals} decimals"
            if (base.signum() == 0) return "Amount must be greater than zero"
            if (base > token.raw) return "More than the agent wallet holds"
            return null
        }

    val recipientError: String?
        get() {
            if (recipient.isBlank()) return null
            if (EthAddress.looksLikeEns(recipient)) {
                return ensError ?: if (resolvedRecipient == null) "Tap resolve to look up this name" else null
            }
            return EthAddress.validationError(recipient)
        }

    /**
     * With no paymaster, the sub-account pays its own gas. Warn before the user reaches a
     * bundler rejection they cannot interpret.
     */
    val gasWarning: String?
        get() {
            val chain = selectedChain ?: return null
            if (chain.canPayGas) return null
            return "The agent wallet holds no $nativeSymbol on $chainName, so it cannot pay " +
                "gas. Top it up below before sending."
        }

    val canSend: Boolean
        get() = integrityBlock == null &&
            !sending &&
            !loading &&
            selectedToken != null &&
            amountBaseUnits != null &&
            amountError == null &&
            effectiveRecipient != null &&
            recipientError == null &&
            (selectedChain?.canPayGas == true)
}

sealed interface SendOutcome {
    data class Sent(val userOpHash: String, val chainId: Int) : SendOutcome
    data class GasFunded(val userOpHash: String) : SendOutcome
    data class Failed(val message: String) : SendOutcome
}
