package org.ethereumphone.andyclaw.ui.agenttx

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.dgenWhite
import org.ethereumphone.andyclaw.agenttx.db.entity.AgentTxEntity
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.agentwallet.AgentWalletChains
import org.ethereumphone.andyclaw.agentwallet.UserOpLookup
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.ChadAlertDialog
import org.ethereumphone.andyclaw.ui.components.DgenBackNavigationBackground
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun AgentTxHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: AgentTxHistoryViewModel = viewModel(),
) {
    val transactions by viewModel.transactions.collectAsState()

    val context = LocalContext.current
    LaunchedEffect(Unit) { SystemColorManager.refresh(context) }
    val primaryColor = SystemColorManager.primaryColor

    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)

    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    fun openExplorer(tx: AgentTxEntity) {
        if (tx.userOpHash.isBlank()) return
        scope.launch {
            when (val lookup = viewModel.lookUpTransaction(tx)) {
                is UserOpLookup.Included -> uriHandler.openUri(AgentWalletChains.explorerTxUrl(lookup.transactionHash))
                UserOpLookup.Pending ->
                    Toast.makeText(context, "Not on-chain yet — try again in a moment.", Toast.LENGTH_SHORT).show()
                UserOpLookup.Unavailable ->
                    Toast.makeText(context, "Couldn't look the transaction up. Try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (confirmClear) {
        ChadAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = "Clear history?",
            message = "This removes the list of agent wallet transactions from this phone. " +
                "It cannot be undone. Nothing on-chain changes.",
            confirmButtonText = "CLEAR",
            dismissButtonText = "CANCEL",
            onConfirm = {
                confirmClear = false
                viewModel.clearAll()
            },
            onDismiss = { confirmClear = false },
        )
    }

    DgenBackNavigationBackground(
        title = "Agent TX History",
        primaryColor = primaryColor,
        onNavigateBack = onNavigateBack,
    ) {
        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "NO TRANSACTIONS YET",
                        style = sectionTitleStyle,
                        color = primaryColor,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Agent wallet transactions will appear here",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TRANSACTIONS",
                        style = sectionTitleStyle,
                        color = primaryColor,
                    )
                    DgenSmallPrimaryButton(
                        text = "Clear",
                        primaryColor = primaryColor,
                        onClick = { confirmClear = true },
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = transactions,
                        key = { it.id },
                    ) { tx ->
                        AgentTxRow(
                            tx = tx,
                            primaryColor = primaryColor,
                            contentTitleStyle = contentTitleStyle,
                            contentBodyStyle = contentBodyStyle,
                            onOpen = { openExplorer(tx) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentTxRow(
    tx: AgentTxEntity,
    primaryColor: androidx.compose.ui.graphics.Color,
    contentTitleStyle: androidx.compose.ui.text.TextStyle,
    contentBodyStyle: androidx.compose.ui.text.TextStyle,
    onOpen: () -> Unit,
) {
    val timeText = remember(tx.timestamp) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
    }
    val chainName = AgentWalletChains.chainDisplayName(tx.chainId)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp, horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = timeText,
                style = contentTitleStyle,
                color = primaryColor,
            )
            Text(
                text = chainName.uppercase(),
                style = contentBodyStyle,
                color = primaryColor.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (tx.amount.isNotBlank() && tx.token.isNotBlank()) {
                "${tx.amount} ${tx.token}"
            } else {
                tx.toolName
            },
            style = contentTitleStyle,
            color = dgenWhite,
        )
        if (tx.to.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "To: ${tx.to.take(6)}...${tx.to.takeLast(4)}",
                style = contentBodyStyle,
                color = dgenWhite.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${tx.userOpHash.take(10)}...${tx.userOpHash.takeLast(6)}",
            style = contentBodyStyle,
            color = primaryColor.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = primaryColor.copy(alpha = 0.15f))
    }
}
