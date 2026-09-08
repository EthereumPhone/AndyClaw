package org.ethereumphone.andyclaw.ui.agentwallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.dgenlibrary.ui.theme.dgenWhite
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.agentwallet.ChainBalances
import org.ethereumphone.andyclaw.ui.components.AppTextStyles

/**
 * A one-line cue on the main screen when the agent's sub-account holds anything.
 *
 * Users have funded this wallet and then had no idea how to get the money back, because the
 * only way out was buried in Settings or required knowing the agent's tool names. Tapping
 * this goes straight to the send screen.
 *
 * Renders nothing at all when the wallet is empty or unavailable, so it costs a quiet user
 * nothing. Balances come from the repository's short-lived cache rather than a fresh scan.
 */
@Composable
fun AgentWalletBalanceBanner(
    primaryColor: Color,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var funded by remember { mutableStateOf<List<ChainBalances>>(emptyList()) }

    LaunchedEffect(Unit) {
        val app = context.applicationContext as? NodeApp ?: return@LaunchedEffect
        val repo = app.agentWalletRepository
        if (repo.getAddress() == null) return@LaunchedEffect
        funded = repo.scanAll().filter { it.hasFunds }
    }

    if (funded.isEmpty()) return

    val summary = funded
        .flatMap { chain -> chain.all.filter { it.raw.signum() > 0 }.map { it to chain } }
        .take(3)
        .joinToString("  ") { (token, _) -> "${token.display(4)} ${token.symbol}" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(primaryColor.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "AGENT WALLET  $summary",
            style = AppTextStyles.contentBody(primaryColor),
            color = dgenWhite,
        )
        Text(
            text = "SEND ›",
            style = AppTextStyles.contentBody(primaryColor),
            color = primaryColor,
        )
    }
}
