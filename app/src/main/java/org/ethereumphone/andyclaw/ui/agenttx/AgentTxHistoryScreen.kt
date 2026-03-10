package org.ethereumphone.andyclaw.ui.agenttx

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.dgenWhite
import org.ethereumphone.andyclaw.agenttx.db.entity.AgentTxEntity
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.DgenBackNavigationBackground
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CHAIN_NAMES = mapOf(
    1 to "Ethereum",
    10 to "Optimism",
    137 to "Polygon",
    42161 to "Arbitrum",
    8453 to "Base",
    7777777 to "Zora",
    56 to "BNB",
    43114 to "Avalanche",
)

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
                        onClick = { viewModel.clearAll() },
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
) {
    val uriHandler = LocalUriHandler.current
    val timeText = remember(tx.timestamp) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(tx.timestamp))
    }
    val chainName = CHAIN_NAMES[tx.chainId] ?: "Chain ${tx.chainId}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (tx.userOpHash.isNotBlank()) {
                    uriHandler.openUri("https://blockscan.com/tx/${tx.userOpHash}")
                }
            }
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
