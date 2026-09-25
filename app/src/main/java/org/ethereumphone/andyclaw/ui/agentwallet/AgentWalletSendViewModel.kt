package org.ethereumphone.andyclaw.ui.agentwallet

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.ExecutionEngine.rethrowIfCancelled
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agentwallet.AgentWalletChains
import org.ethereumphone.andyclaw.agentwallet.AmountFormat
import org.ethereumphone.andyclaw.agentwallet.ChainBalances
import org.ethereumphone.andyclaw.agentwallet.EthAddress
import org.ethereumphone.andyclaw.agentwallet.GasBudget
import org.ethereumphone.andyclaw.agentwallet.GasTopUpResult
import org.ethereumphone.andyclaw.agentwallet.SendGate
import org.ethereumphone.andyclaw.agentwallet.SendRequest
import org.ethereumphone.andyclaw.agentwallet.SubWalletResult
import org.ethereumphone.andyclaw.agentwallet.TokenBalance
import org.ethereumphone.andyclaw.agentwallet.UserOpLookup
import org.ethereumphone.andyclaw.agentwallet.WalletIntegrity
import org.kethereum.eip137.model.ENSName
import org.kethereum.ens.ENS
import org.kethereum.ens.isPotentialENSDomain
import org.kethereum.rpc.HttpEthereumRPC
import java.math.BigInteger
import java.text.DecimalFormatSymbols

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
    }

    private val app = application as NodeApp
    private val repo = app.agentWalletRepository
    private val txRepo = app.agentTxRepository

    private val _state = MutableStateFlow(
        AgentWalletSendUiState(decimalSeparator = DecimalFormatSymbols.getInstance().decimalSeparator)
    )
    val state: StateFlow<AgentWalletSendUiState> = _state.asStateFlow()

    /** The ENS lookup in flight; an edit to the field cancels it. */
    private var ensJob: Job? = null

    private val sendGate = SendGate()
    private val gasGate = SendGate()

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
                    // The same asset, with the balance just read — not the object from before
                    // the refresh, whose stale balance Max and the checks would otherwise use.
                    selectedToken = AgentWalletSendUiState.rebindSelection(chain, current.selectedToken),
                )
            }
            _state.value.selectedChainId?.let(::loadGasReserve)
        }
    }

    private fun loadGasReserve(chainId: Int) {
        viewModelScope.launch {
            val reserve = repo.gasReserveWei(chainId)
            _state.update {
                if (reserve != null) {
                    it.copy(gasReserves = it.gasReserves + (chainId to reserve), gasReserveFailed = it.gasReserveFailed - chainId)
                } else {
                    it.copy(gasReserveFailed = it.gasReserveFailed + chainId)
                }
            }
        }
    }

    fun retryGasEstimate() {
        _state.value.selectedChainId?.let(::loadGasReserve)
    }

    // ── Field edits ─────────────────────────────────────────────────────

    /** Nothing changes under a send in flight: what is being sent is what was confirmed. */
    private inline fun edit(crossinline change: (AgentWalletSendUiState) -> AgentWalletSendUiState) {
        _state.update { if (it.sending) it else change(it) }
    }

    fun selectChain(chainId: Int) {
        if (_state.value.sending) return
        edit { current ->
            val chain = current.balances.firstOrNull { it.chainId == chainId }
            current.copy(
                selectedChainId = chainId,
                // Token identity is per-chain; carrying one across would send the wrong asset.
                selectedToken = chain?.all?.firstOrNull { it.raw.signum() > 0 } ?: chain?.native,
                amount = "",
                outcome = null,
            )
        }
        if (chainId !in _state.value.gasReserves) loadGasReserve(chainId)
    }

    fun selectToken(token: TokenBalance) {
        edit { it.copy(selectedToken = token, amount = "", outcome = null) }
    }

    fun setAmount(value: String) {
        edit { it.copy(amount = value, outcome = null) }
    }

    /**
     * Fill in the most that can be sent: the whole balance of a token, or for the native token
     * the balance less what the send itself will need for gas. Written with this device's
     * decimal separator, so the field reads it back the same way.
     */
    fun setMaxAmount() {
        edit { current ->
            val token = current.selectedToken ?: return@edit current
            val max = current.maxSendable ?: return@edit current
            val text = AmountFormat.fromBaseUnits(max, token.decimals).replace('.', current.decimalSeparator)
            current.copy(amount = text, outcome = null)
        }
    }

    fun setRecipient(value: String) {
        if (_state.value.sending) return
        // An answer for the old text must never land on the new one.
        ensJob?.cancel()
        ensJob = null
        edit {
            it.copy(
                recipient = value,
                resolvedRecipient = null,
                resolvedFor = null,
                resolvingEns = false,
                ensError = null,
                outcome = null,
            )
        }
    }

    fun useOwnWalletAsRecipient() {
        val own = _state.value.osWalletAddress ?: return
        setRecipient(own)
    }

    /** Resolve a `.eth` name. No-op for anything else. */
    fun resolveEns() {
        val current = _state.value
        if (current.sending || !EthAddress.looksLikeEns(current.recipient)) return
        val name = EthAddress.normalizeEnsName(current.recipient) ?: return

        ensJob?.cancel()
        ensJob = viewModelScope.launch {
            _state.update { it.copy(resolvingEns = true, ensError = null) }
            val resolved = withContext(Dispatchers.IO) {
                try {
                    val ensName = ENSName(name)
                    if (!ensName.isPotentialENSDomain()) return@withContext null
                    ENS(HttpEthereumRPC(ENS_RPC)).getAddress(ensName)?.hex
                } catch (e: Exception) {
                    rethrowIfCancelled(e)
                    Log.w(TAG, "ENS resolution failed for $name: ${e.message}")
                    null
                }
            }
            // A name with no address record resolves to the zero address; sending there burns
            // the funds. Only a real, valid address is an answer.
            val usable = resolved
                ?.takeIf { EthAddress.isValid(it) && !EthAddress.isZero(it) }
                ?.let(EthAddress::checksummed)
            _state.update { latest ->
                // The field may have changed while the lookup ran: this answer is for `name` only.
                if (EthAddress.normalizeEnsName(latest.recipient) != name) return@update latest
                latest.copy(
                    resolvingEns = false,
                    resolvedRecipient = usable,
                    resolvedFor = if (usable != null) name else null,
                    ensError = when {
                        resolved == null -> "Could not resolve $name"
                        usable == null -> "$name has no address set"
                        else -> null
                    },
                )
            }
        }
    }

    // ── Sending ─────────────────────────────────────────────────────────

    /**
     * Send [request] — the one built before the confirmation prompt, which the user just
     * authenticated. Call only after that gate has passed; this does not authenticate.
     *
     * If the screen no longer describes the same send (an edit, a late ENS answer, a refresh
     * that changed what is allowed), nothing is sent and the user is asked to review again.
     */
    fun confirmSend(request: SendRequest) {
        if (_state.value.sendRequest() != request) {
            _state.update {
                it.copy(outcome = SendOutcome.Failed("The details changed after you confirmed. Check them and send again."))
            }
            return
        }
        if (!sendGate.tryEnter()) return
        _state.update { it.copy(sending = true, outcome = null) }

        viewModelScope.launch {
            try {
                // Authenticated, so it runs to the end — the history row included — even if the
                // screen is closed halfway. A send that went out without its row invites a second.
                val result = withContext(NonCancellable) {
                    repo.send(request.chainId, request.asToken(), request.recipient, request.amountBaseUnits)
                        .also { outcome ->
                            if (outcome is SubWalletResult.Success) recordSend(request, outcome.userOpHash)
                        }
                }
                when (result) {
                    is SubWalletResult.Success -> {
                        repo.invalidateBalances()
                        _state.update {
                            it.copy(
                                sending = false,
                                amount = "",
                                outcome = SendOutcome.Sent(result.userOpHash, request.chainId),
                            )
                        }
                        refresh(force = true)
                    }

                    is SubWalletResult.Failure -> _state.update {
                        it.copy(sending = false, outcome = SendOutcome.Failed(result.message))
                    }
                }
            } finally {
                sendGate.exit()
                _state.update { if (it.sending) it.copy(sending = false) else it }
            }
        }
    }

    /** Only a real userOpHash is recorded, with the amount as it was confirmed — never raw input. */
    private suspend fun recordSend(request: SendRequest, userOpHash: String) {
        runCatching {
            txRepo.save(
                userOpHash = userOpHash,
                chainId = request.chainId,
                to = request.recipient,
                amount = request.amountDisplay,
                token = request.tokenSymbol,
                toolName = "ui_send",
            )
        }.onFailure { Log.w(TAG, "Failed to record send: ${it.message}") }
    }

    /**
     * Top the sub-account up with native token from the user's own wallet so it can pay gas —
     * enough for a few sends at this chain's gas price ([GasBudget.topUpWei]).
     *
     * There is no paymaster behind the sub-account, so an account holding only ERC-20s
     * cannot move them without this.
     */
    fun fundGas() {
        val snapshot = _state.value
        val chainId = snapshot.selectedChainId ?: return
        val amountWei = snapshot.gasTopUpWei ?: return
        if (!gasGate.tryEnter()) return
        _state.update { it.copy(fundingGas = true, outcome = null) }

        viewModelScope.launch {
            try {
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
            } finally {
                gasGate.exit()
                _state.update { if (it.fundingGas) it.copy(fundingGas = false) else it }
            }
        }
    }

    /** Where "view on explorer" can go for a submitted send: the transaction that carried it. */
    suspend fun lookUpTransaction(chainId: Int, userOpHash: String): UserOpLookup =
        repo.transactionFor(chainId, userOpHash)

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
    /** The ENS name [resolvedRecipient] answers; an answer for any other name is ignored. */
    val resolvedFor: String? = null,
    val resolvingEns: Boolean = false,
    val ensError: String? = null,
    val sending: Boolean = false,
    val fundingGas: Boolean = false,
    val outcome: SendOutcome? = null,
    /** How this device writes decimals. The amount field takes it as well as ".". */
    val decimalSeparator: Char = '.',
    /** Native token each chain has to keep for gas, where it could be estimated. */
    val gasReserves: Map<Int, BigInteger> = emptyMap(),
    /** Chains whose gas reserve could not be estimated. */
    val gasReserveFailed: Set<Int> = emptySet(),
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

    /** The typed `.eth` name in the form it is resolved, or null (not a name, or not a plain one). */
    val ensName: String?
        get() = if (EthAddress.looksLikeEns(recipient)) EthAddress.normalizeEnsName(recipient) else null

    /**
     * The address a send would actually go to, checksummed. For a typed name, only an answer
     * for *that* name counts: a lookup still in flight when the field changed must not steer
     * the send to an address the user is no longer looking at.
     */
    val effectiveRecipient: String?
        get() {
            val input = recipient.trim()
            if (EthAddress.looksLikeEns(input)) {
                val name = ensName ?: return null
                return resolvedRecipient?.takeIf { resolvedFor == name }
            }
            if (!EthAddress.isValid(input) || EthAddress.isZero(input)) return null
            return EthAddress.checksummed(input)
        }

    val parsedAmount: AmountFormat.UserAmount
        get() {
            val token = selectedToken ?: return AmountFormat.UserAmount.Empty
            return AmountFormat.parseUserAmount(amount, token.decimals, decimalSeparator)
        }

    val amountBaseUnits: BigInteger?
        get() = (parsedAmount as? AmountFormat.UserAmount.Ok)?.baseUnits

    /** The amount as review, prompt and history show it. */
    val canonicalAmount: String?
        get() = (parsedAmount as? AmountFormat.UserAmount.Ok)?.canonical

    /** Native token the selected chain has to keep for gas, when known. */
    val gasReserve: BigInteger?
        get() = selectedChainId?.let { gasReserves[it] }

    /** What Max fills in; null while that cannot be known safely (see [AmountFormat.maxSendable]). */
    val maxSendable: BigInteger?
        get() {
            val token = selectedToken ?: return null
            if (selectedChain?.nativeKnown == false) return null
            return AmountFormat.maxSendable(token.raw, token.isNative, gasReserve)
        }

    val amountError: String?
        get() {
            val token = selectedToken ?: return null
            return when (val parsed = parsedAmount) {
                AmountFormat.UserAmount.Empty -> null
                is AmountFormat.UserAmount.Invalid -> parsed.message
                is AmountFormat.UserAmount.Ok -> {
                    val reserve = gasReserve
                    when {
                        parsed.baseUnits.signum() == 0 -> "Amount must be greater than zero"
                        parsed.baseUnits > token.raw -> "More than the agent wallet holds"
                        token.isNative && reserve != null && parsed.baseUnits > token.raw - reserve ->
                            "Leave ~${AmountFormat.formatForDisplay(reserve, token.decimals)} ${token.symbol} for gas"
                        else -> null
                    }
                }
            }
        }

    val recipientError: String?
        get() {
            if (recipient.isBlank()) return null
            if (EthAddress.looksLikeEns(recipient)) {
                if (ensName == null) return "Only plain names can be looked up: a–z, 0–9 and -"
                if (resolvingEns) return null
                return ensError ?: if (effectiveRecipient == null) "Tap resolve to look up this name" else null
            }
            return EthAddress.validationError(recipient)
        }

    /** Allowed, but worth a second look before confirming. */
    val recipientWarning: String?
        get() {
            val to = effectiveRecipient ?: return null
            val token = selectedToken
            if (token?.contractAddress != null && to.equals(token.contractAddress, ignoreCase = true)) {
                return "This is the ${token.symbol} contract itself — tokens sent to it are almost always lost"
            }
            if (address != null && to.equals(address, ignoreCase = true)) {
                return "This is the agent wallet itself — nothing would move except the gas"
            }
            return null
        }

    /** The selected chain's balance could not be read, so nothing about it is known. */
    val balanceUnknown: Boolean
        get() = selectedChain?.nativeKnown == false

    /**
     * With no paymaster, the sub-account pays its own gas. Warn before the user reaches a
     * bundler rejection they cannot interpret.
     */
    val gasWarning: String?
        get() {
            val chain = selectedChain ?: return null
            if (!chain.nativeKnown || chain.native.raw.signum() > 0) return null
            return "The agent wallet holds no $nativeSymbol on $chainName, so it cannot pay " +
                "gas. Top it up below before sending."
        }

    /** Some native token, but likely too little for a token send's gas. */
    val gasLowWarning: String?
        get() {
            val chain = selectedChain ?: return null
            val reserve = gasReserve ?: return null
            if (selectedToken?.isNative != false || !chain.nativeKnown) return null
            val native = chain.native.raw
            if (native.signum() == 0 || native >= reserve) return null
            return "The agent wallet has ${chain.native.display()} $nativeSymbol on $chainName; a send can need " +
                "about ${AmountFormat.formatForDisplay(reserve, chain.native.decimals)} for gas."
        }

    /** How much native token a top-up moves in, sized to this chain's gas; null until estimated. */
    val gasTopUpWei: BigInteger?
        get() = gasReserve?.let(GasBudget::topUpWei)

    // Not gated on `loading`: a background refresh keeps the last balances on screen, and a
    // confirmation arriving mid-refresh must not read as "the details changed".
    val canSend: Boolean
        get() = integrityBlock == null &&
            !sending &&
            selectedToken != null &&
            amountBaseUnits != null &&
            amountError == null &&
            effectiveRecipient != null &&
            recipientError == null &&
            (selectedChain?.canPayGas == true)

    /** The send the screen describes right now, frozen for the confirmation prompt; null if it can't be sent. */
    fun sendRequest(): SendRequest? {
        if (!canSend) return null
        val chainId = selectedChainId ?: return null
        val token = selectedToken ?: return null
        return SendRequest(
            chainId = chainId,
            chainName = chainName,
            tokenSymbol = token.symbol,
            tokenContract = token.contractAddress,
            tokenDecimals = token.decimals,
            recipient = effectiveRecipient ?: return null,
            ensName = ensName,
            amountBaseUnits = amountBaseUnits ?: return null,
            amountDisplay = canonicalAmount ?: return null,
            warning = recipientWarning,
        )
    }

    companion object {
        /**
         * The token to keep selected once [chain]'s balances have been read again: the same
         * asset with its new balance, or — if it is gone (spent to zero) — the first thing the
         * chain still holds.
         */
        fun rebindSelection(chain: ChainBalances?, previous: TokenBalance?): TokenBalance? {
            if (chain == null) return previous
            previous?.let { p -> chain.all.firstOrNull { it.sameAsset(p) }?.let { return it } }
            return chain.all.firstOrNull { it.raw.signum() > 0 } ?: chain.native
        }
    }
}

sealed interface SendOutcome {
    data class Sent(val userOpHash: String, val chainId: Int) : SendOutcome
    data class GasFunded(val userOpHash: String) : SendOutcome
    data class Failed(val message: String) : SendOutcome
}
