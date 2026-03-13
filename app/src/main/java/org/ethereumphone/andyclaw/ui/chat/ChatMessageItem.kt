package org.ethereumphone.andyclaw.ui.chat

import android.annotation.SuppressLint
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.res.painterResource
import org.ethereumphone.andyclaw.R
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.GlowStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import org.ethereumphone.andyclaw.ui.components.ChadBackground
import org.ethereumphone.andyclaw.ui.theme.AndyClawTheme

@Composable
fun ChatMessageItem(
    message: ChatUiMessage,
    isExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isSecurity = message.isSecurityBlock
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        if (isSecurity) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Security",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Safety Blocked" + if (message.toolName != null) " — ${message.toolName}" else "",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                shadow = GlowStyle.status(MaterialTheme.colorScheme.error),
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.padding(top = 6.dp))
                    Text(
                        text = message.content
                            .removePrefix("[Safety] ")
                            .replace(". Disable safety mode in Settings to bypass this check.", ".")
                            .replace(". Disable safety mode in Settings to allow this command.", "."),
                        style = MaterialTheme.typography.bodySmall.copy(
                            shadow = GlowStyle.body(MaterialTheme.colorScheme.onErrorContainer),
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "You can disable safety mode in Settings to bypass this.",
                        style = MaterialTheme.typography.labelSmall.copy(
                            shadow = GlowStyle.body(MaterialTheme.colorScheme.onErrorContainer),
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else if (isTool) {
            CollapsibleToolResult(
                message = message,
                isExpanded = isExpanded,
                onToggle = onToggleExpand,
                primaryColor = primaryColor,
            )
        } else if (isUser) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        shadow = GlowStyle.body(textColor),
                    ),
                    color = textColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        } else {
            SelectionContainer {
                MarkdownText(
                    text = message.content,
                    color = primaryColor,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            val clipboardManager = LocalClipboardManager.current
            IconButton(
                onClick = { clipboardManager.setText(AnnotatedString(message.content)) },
                modifier = Modifier.padding(top=4.dp) .size(24.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.content_copy),
                    contentDescription = "Copy",
                    tint = primaryColor,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (message.explorerUrl != null) {
                val uriHandler = LocalUriHandler.current
                Spacer(Modifier.height(16.dp))
                DgenSmallPrimaryButton(
                    text = "View on Explorer",
                    primaryColor = primaryColor,
                    onClick = { uriHandler.openUri(message.explorerUrl) },
                )
            }
        }
    }
}

@Composable
private fun CollapsibleToolResult(
    message: ChatUiMessage,
    isExpanded: Boolean,
    onToggle: (() -> Unit)?,
    primaryColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val chevron = if (isExpanded) "▾" else "▸"
    val toolLabel = message.toolName ?: "Tool"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = onToggle != null) { onToggle?.invoke() }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
            .padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = chevron,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    shadow = GlowStyle.subtitle(primaryColor),
                ),
                color = primaryColor.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "[$toolLabel]",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                    shadow = GlowStyle.subtitle(primaryColor),
                ),
                color = primaryColor.copy(alpha = 0.7f),
            )
            if (message.toolSummary != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = message.toolSummary,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        shadow = GlowStyle.body(primaryColor),
                    ),
                    color = primaryColor.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (isExpanded && message.content.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        shadow = GlowStyle.body(primaryColor),
                    ),
                    color = primaryColor.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }

    }
}

@SuppressLint("SuspiciousIndentation")
@Preview(showBackground = true, backgroundColor = 0xFF050505, widthDp = 480, heightDp = 480)
@Composable
private fun PreviewChatMessageItemTransaction() {
    val messages = listOf(
        ChatUiMessage(
            id = "1",
            role = "user",
            content = "Send 0.05 ETH to vitalik.eth on Base",
        ),
        ChatUiMessage(
            id = "2",
            role = "tool",
            toolName = "ens_resolve",
            toolSummary = "vitalik.eth → 0xd8dA…6045",
            content = "Resolved vitalik.eth to 0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
        ),
        ChatUiMessage(
            id = "3",
            role = "tool",
            toolName = "send_transaction",
            toolSummary = "0.05 ETH → 0xd8dA…6045",
            content = "Transaction hash: 0xabc1…f9e2\nStatus: confirmed\nBlock: 18294021\nGas used: 21,000",
        ),
        ChatUiMessage(
            id = "4",
            role = "assistant",
            content = "**Transaction submitted**\n\n" +
                "Sent **0.05 ETH** to `vitalik.eth` (`0xd8dA…6045`) on Base.\n\n" +
                "Gas used: 21,000 · Tx hash: `0xabc1…f9e2`",
            explorerUrl = "https://basescan.org/tx/0xabc1f9e2",
        ),
    )


        ChadBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                messages.forEach { message ->
                    ChatMessageItem(message = message)
                }
            }
        }

}
