package org.ethereumphone.andyclaw.ui.agentwallet

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.dgenWhite
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.agentwallet.AgentWalletChains
import org.ethereumphone.andyclaw.agentwallet.AmountFormat
import org.ethereumphone.andyclaw.agentwallet.EthAddress
import org.ethereumphone.andyclaw.agentwallet.SendRequest
import org.ethereumphone.andyclaw.agentwallet.UserOpLookup
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.DgenBackNavigationBackground
import org.ethereumphone.andyclaw.ui.components.DgenCursorTextfield
import org.ethereumphone.andyclaw.ui.components.DgenPrimaryButton
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.SmallDetailItem
import org.ethereumphone.andyclaw.ui.components.SpendAuthGate
import org.ethereumphone.andyclaw.ui.components.ChadAlertDialog

private val ErrorRed = Color(0xFFFF6B6B)
private val WarnAmber = Color(0xFFFFC24B)

/**
 * Send funds out of the agent's sub-account, without going through the LLM.
 *
 * Confirmation is a [SpendAuthGate] prompt — deliberately a UI gate, never bound to the
 * sub-account's signing key. See that class for why that distinction is load-bearing.
 */
@Composable
fun AgentWalletSendScreen(
    onNavigateBack: () -> Unit,
    viewModel: AgentWalletSendViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { SystemColorManager.refresh(context) }
    val primaryColor = SystemColorManager.primaryColor

    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)

    /** The send awaiting the no-lock-screen confirmation dialog, frozen when it opened. */
    var pendingRequest by remember { mutableStateOf<SendRequest?>(null) }
    val scope = rememberCoroutineScope()
    var explorerNote by remember { mutableStateOf<String?>(null) }

    fun beginConfirmation() {
        // Freeze the send now: the prompt shows exactly this, and exactly this is sent.
        val request = state.sendRequest() ?: return
        val activity = context as? Activity
        if (activity == null) {
            pendingRequest = request
            return
        }
        SpendAuthGate.authenticate(
            activity = activity,
            title = "Send from agent wallet",
            subtitle = "${request.amountDisplay} ${request.tokenSymbol} on ${request.chainName}",
            // The whole address: the last chance to catch a wrong recipient is not the place
            // to shorten it.
            description = buildString {
                append("To ${request.recipient}")
                request.ensName?.let { append(" ($it)") }
                request.warning?.let { append("\n\n$it") }
            },
            onSuccess = { viewModel.confirmSend(request) },
            onCancelled = { },
            // No lock screen on this device — fall back to an explicit typed confirmation
            // rather than sending on a single unauthenticated tap.
            onUnavailable = { pendingRequest = request },
        )
    }

    fun openExplorer(chainId: Int, userOpHash: String) {
        scope.launch {
            explorerNote = "Looking up the transaction…"
            explorerNote = when (val lookup = viewModel.lookUpTransaction(chainId, userOpHash)) {
                is UserOpLookup.Included -> {
                    uriHandler.openUri(AgentWalletChains.explorerTxUrl(lookup.transactionHash))
                    null
                }
                UserOpLookup.Pending -> "Not on-chain yet — try again in a moment."
                UserOpLookup.Unavailable -> "Couldn't reach the bundler to look it up. Try again."
            }
        }
    }

    DgenBackNavigationBackground(
        title = "Agent Wallet",
        primaryColor = primaryColor,
        onNavigateBack = onNavigateBack,
    ) {
        if (state.loading && state.address == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("LOADING AGENT WALLET…", style = sectionTitleStyle, color = primaryColor)
            }
            return@DgenBackNavigationBackground
        }

        val block = state.integrityBlock
        if (block != null) {
            IntegrityBlockedPanel(block, state.address, sectionTitleStyle, contentBodyStyle, primaryColor)
            return@DgenBackNavigationBackground
        }

        val loadError = state.loadError
        if (loadError != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(loadError, style = contentBodyStyle, color = ErrorRed)
            }
            return@DgenBackNavigationBackground
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── Address ────────────────────────────────────────────────
            Text("AGENT WALLET ADDRESS", style = sectionTitleStyle, color = primaryColor)
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = state.address.orEmpty(),
                    style = contentBodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    color = dgenWhite,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Chain ──────────────────────────────────────────────────
            Text("CHAIN", style = sectionTitleStyle, color = primaryColor)
            Spacer(Modifier.height(6.dp))
            state.balances.forEach { chain ->
                PickerRow(
                    label = chain.chainName,
                    detail = when {
                        !chain.nativeKnown -> "couldn't load"
                        chain.hasFunds -> chain.all.filter { it.raw.signum() > 0 }
                            .joinToString("  ") { "${it.display()} ${it.symbol}" }
                        else -> "empty"
                    },
                    selected = chain.chainId == state.selectedChainId,
                    primaryColor = primaryColor,
                    contentTitleStyle = contentTitleStyle,
                    contentBodyStyle = contentBodyStyle,
                    onClick = { viewModel.selectChain(chain.chainId) },
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Token ──────────────────────────────────────────────────
            Text("TOKEN", style = sectionTitleStyle, color = primaryColor)
            Spacer(Modifier.height(6.dp))
            val chain = state.selectedChain
            if (state.balanceUnknown) {
                // A failed read is not an empty wallet: say so, and never offer a paid top-up
                // on the strength of it.
                Text(
                    "Couldn't load the agent wallet's balance on ${state.chainName}.",
                    style = contentBodyStyle,
                    color = WarnAmber,
                )
                Spacer(Modifier.height(6.dp))
                DgenSmallPrimaryButton(
                    text = if (state.loading) "Loading…" else "Retry",
                    primaryColor = primaryColor,
                    onClick = { viewModel.refresh(force = true) },
                    enabled = !state.loading,
                )
            } else if (chain == null || chain.all.none { it.raw.signum() > 0 }) {
                Text(
                    "The agent wallet holds nothing on ${state.chainName}.",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            } else {
                chain.all.filter { it.raw.signum() > 0 }.forEach { token ->
                    PickerRow(
                        label = token.symbol,
                        detail = "${token.display()} available",
                        selected = token.sameAsset(state.selectedToken),
                        primaryColor = primaryColor,
                        contentTitleStyle = contentTitleStyle,
                        contentBodyStyle = contentBodyStyle,
                        onClick = { viewModel.selectToken(token) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Amount ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AMOUNT", style = sectionTitleStyle, color = primaryColor)
                DgenSmallPrimaryButton(
                    text = "Max",
                    primaryColor = primaryColor,
                    onClick = { viewModel.setMaxAmount() },
                    // For the native token Max has to leave gas behind, so it waits for the
                    // estimate rather than guess.
                    enabled = state.maxSendable != null && !state.sending,
                )
            }
            Spacer(Modifier.height(6.dp))
            DgenCursorTextfield(
                value = state.amount,
                onValueChange = viewModel::setAmount,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0.0", style = contentBodyStyle, color = dgenWhite.copy(alpha = 0.4f)) },
                primaryColor = primaryColor,
                keyboardType = KeyboardType.Decimal,
            )
            state.amountError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = contentBodyStyle, color = ErrorRed)
            }

            Spacer(Modifier.height(20.dp))

            // ── Recipient ──────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TO", style = sectionTitleStyle, color = primaryColor)
                if (state.osWalletAddress != null) {
                    DgenSmallPrimaryButton(
                        text = "My wallet",
                        primaryColor = primaryColor,
                        onClick = { viewModel.useOwnWalletAsRecipient() },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            DgenCursorTextfield(
                value = state.recipient,
                onValueChange = viewModel::setRecipient,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0x… or name.eth", style = contentBodyStyle, color = dgenWhite.copy(alpha = 0.4f)) },
                primaryColor = primaryColor,
            )
            if (EthAddress.looksLikeEns(state.recipient)) {
                Spacer(Modifier.height(6.dp))
                DgenSmallPrimaryButton(
                    text = if (state.resolvingEns) "Resolving…" else "Resolve",
                    primaryColor = primaryColor,
                    onClick = { viewModel.resolveEns() },
                    enabled = !state.resolvingEns && state.ensName != null && !state.sending,
                )
                // Only an answer for the name as it is typed now.
                state.effectiveRecipient?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("→ $it", style = contentBodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp), color = dgenWhite)
                }
            }
            state.recipientError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = contentBodyStyle, color = ErrorRed)
            }
            state.recipientWarning?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = contentBodyStyle, color = WarnAmber)
            }

            // ── Gas ────────────────────────────────────────────────────
            state.gasWarning?.let { warning ->
                Spacer(Modifier.height(20.dp))
                Text(warning, style = contentBodyStyle, color = WarnAmber)
                Spacer(Modifier.height(8.dp))
                val topUp = state.gasTopUpWei
                val estimateFailed = state.selectedChainId in state.gasReserveFailed
                DgenSmallPrimaryButton(
                    // Sized to this chain's gas price: a fixed amount that covers a few sends on
                    // Base does not cover one on Polygon.
                    text = when {
                        state.fundingGas -> "Sending…"
                        topUp != null -> "Send ${AmountFormat.formatForDisplay(topUp, 18)} ${state.nativeSymbol} for gas"
                        estimateFailed -> "Couldn't estimate gas — retry"
                        else -> "Estimating gas…"
                    },
                    primaryColor = primaryColor,
                    onClick = { if (topUp != null) viewModel.fundGas() else viewModel.retryGasEstimate() },
                    enabled = !state.fundingGas && (topUp != null || estimateFailed),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Confirm this on the terminal screen — it spends your dGEN1 wallet.",
                    style = contentBodyStyle,
                    color = dgenWhite.copy(alpha = 0.7f),
                )
            }
            state.gasLowWarning?.let { warning ->
                Spacer(Modifier.height(20.dp))
                Text(warning, style = contentBodyStyle, color = WarnAmber)
            }

            // ── Review + send ──────────────────────────────────────────
            if (state.canSend) {
                Spacer(Modifier.height(24.dp))
                Text("REVIEW", style = sectionTitleStyle, color = primaryColor)
                Spacer(Modifier.height(6.dp))
                SmallDetailItem("AMOUNT", "${state.canonicalAmount} ${state.selectedToken?.symbol}", primaryColor)
                SmallDetailItem(
                    "TO",
                    state.effectiveRecipient.orEmpty() + (state.ensName?.let { "\n($it)" } ?: ""),
                    primaryColor,
                )
                SmallDetailItem("CHAIN", state.chainName, primaryColor)
                if (state.selectedChain?.native?.raw?.signum() == 1) {
                    SmallDetailItem(
                        "GAS",
                        "Paid from the agent wallet's ${state.nativeSymbol}",
                        primaryColor,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            DgenPrimaryButton(
                text = if (state.sending) "SENDING…" else "SEND",
                onClick = { beginConfirmation() },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canSend,
            )

            // ── Outcome ────────────────────────────────────────────────
            state.outcome?.let { outcome ->
                Spacer(Modifier.height(16.dp))
                when (outcome) {
                    is SendOutcome.Sent -> Column {
                        Text("SUBMITTED", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Submitted to the bundler. It is not confirmed on-chain yet.",
                            style = contentBodyStyle,
                            color = dgenWhite,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "View on explorer",
                            style = contentBodyStyle,
                            color = primaryColor,
                            // A userOpHash is not a transaction hash; look the transaction up first.
                            modifier = Modifier.clickable { openExplorer(outcome.chainId, outcome.userOpHash) },
                        )
                        explorerNote?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = contentBodyStyle, color = dgenWhite.copy(alpha = 0.7f))
                        }
                    }

                    is SendOutcome.GasFunded -> Text(
                        "Gas top-up submitted. Balances refresh in a moment.",
                        style = contentBodyStyle,
                        color = primaryColor,
                    )

                    is SendOutcome.Failed -> Text(
                        outcome.message,
                        style = contentBodyStyle,
                        color = ErrorRed,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingRequest?.let { request ->
        ChadAlertDialog(
            onDismissRequest = { pendingRequest = null },
            title = "Confirm send",
            message = "Send ${request.amountDisplay} ${request.tokenSymbol} on ${request.chainName} " +
                "to ${request.recipient}${request.ensName?.let { " ($it)" } ?: ""}." +
                (request.warning?.let { "\n\n$it" } ?: "") +
                "\n\nThis device has no screen lock, so this cannot be confirmed biometrically.",
            confirmButtonText = "SEND",
            dismissButtonText = "CANCEL",
            onConfirm = {
                pendingRequest = null
                viewModel.confirmSend(request)
            },
            onDismiss = { pendingRequest = null },
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    detail: String,
    selected: Boolean,
    primaryColor: Color,
    contentTitleStyle: androidx.compose.ui.text.TextStyle,
    contentBodyStyle: androidx.compose.ui.text.TextStyle,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) primaryColor else Color.Transparent,
            )
            .background(if (selected) primaryColor.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = contentTitleStyle,
            color = if (selected) primaryColor else dgenWhite,
        )
        Text(
            text = detail,
            style = contentBodyStyle,
            color = dgenWhite.copy(alpha = 0.7f),
        )
    }
}

/**
 * Shown when the sub-account key has been replaced or wiped. This is the unrecoverable
 * case, so it says so plainly rather than presenting an empty new wallet as normal.
 */
@Composable
private fun IntegrityBlockedPanel(
    message: String,
    currentAddress: String?,
    sectionTitleStyle: androidx.compose.ui.text.TextStyle,
    contentBodyStyle: androidx.compose.ui.text.TextStyle,
    primaryColor: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("AGENT WALLET UNAVAILABLE", style = sectionTitleStyle, color = ErrorRed)
        Spacer(Modifier.height(8.dp))
        Text(message, style = contentBodyStyle, color = dgenWhite)
        if (currentAddress != null) {
            Spacer(Modifier.height(12.dp))
            Text("CURRENT ADDRESS", style = sectionTitleStyle, color = primaryColor)
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = currentAddress,
                    style = contentBodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    color = dgenWhite,
                )
            }
        }
    }
}
