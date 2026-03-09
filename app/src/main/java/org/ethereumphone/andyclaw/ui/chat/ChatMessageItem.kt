package org.ethereumphone.andyclaw.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import org.ethereumphone.andyclaw.ui.components.GlowStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                    text = "> ${message.content}",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                        shadow = GlowStyle.body(textColor),
                    ),
                    color = textColor,
                    modifier = Modifier.padding(vertical = 2.dp),
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
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
