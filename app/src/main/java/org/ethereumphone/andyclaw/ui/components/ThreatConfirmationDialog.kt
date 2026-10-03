package org.ethereumphone.andyclaw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.dgenlibrary.button.DgenPrimaryButton
import com.example.dgenlibrary.button.DgenSecondaryButton
import com.example.dgenlibrary.ui.theme.PitagonsSans
import com.example.dgenlibrary.ui.theme.dgenBlack
import com.example.dgenlibrary.ui.theme.lazerBurn
import com.example.dgenlibrary.ui.theme.neonOpacity
import com.example.dgenlibrary.ui.theme.orcheAsh
import com.example.dgenlibrary.ui.theme.orcheCore
import com.example.dgenlibrary.ui.theme.terminalHack
import org.ethereumphone.andyclaw.extensions.clawhub.ThreatAssessment
import org.ethereumphone.andyclaw.extensions.clawhub.ThreatIndicator
import org.ethereumphone.andyclaw.extensions.clawhub.ThreatLevel

@Composable
fun ThreatConfirmationDialog(
    slug: String,
    assessment: ThreatAssessment,
    primaryColor: Color,
    secondaryColor: Color,
    confirmButtonText: String = "ACCEPT & INSTALL",
    cancelButtonText: String = "CANCEL",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // A window of its own. Drawn inline it sat under whatever the screen emitted after it — the
    // ClawHub page took every touch, and an install waited on a warning nobody could see.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ThreatConfirmationContent(
            slug, assessment, primaryColor, secondaryColor, confirmButtonText, cancelButtonText, onConfirm, onDismiss,
        )
    }
}

@Composable
private fun ThreatConfirmationContent(
    slug: String,
    assessment: ThreatAssessment,
    primaryColor: Color,
    secondaryColor: Color,
    confirmButtonText: String,
    cancelButtonText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current

    val threatColor = when (assessment.level) {
        ThreatLevel.LOW -> Color(0xFF4CAF50)
        ThreatLevel.MEDIUM -> Color(0xFFFFA000)
        ThreatLevel.HIGH -> Color(0xFFFF6D00)
        ThreatLevel.CRITICAL -> Color(0xFFD50000)
    }
    val threatSecondaryColor = when (assessment.level) {
        ThreatLevel.LOW -> terminalHack
        ThreatLevel.MEDIUM -> orcheAsh
        ThreatLevel.HIGH -> orcheAsh
        ThreatLevel.CRITICAL -> lazerBurn
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(dgenBlack)
            .pointerInput(Unit) {
                detectTapGestures {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                // A tap on the warning itself is not a tap outside it.
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            // The text scrolls and the buttons stay: four indicators outgrew a 480 dp screen and
            // pushed APPROVE and DENY out of reach.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Security Warning",
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontFamily = PitagonsSans,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = primaryColor,
                        lineHeight = 32.sp,
                        letterSpacing = 0.sp,
                        textDecoration = TextDecoration.None
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "\"$slug\"",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontFamily = PitagonsSans,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                        ),
                    )
                    ThreatLevelBadge(level = assessment.level, primaryColor = primaryColor)
                    Spacer(Modifier.weight(1f))
                }

                Text(
                    text = assessment.summary,
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontFamily = PitagonsSans,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        color = primaryColor.copy(neonOpacity),
                        lineHeight = 24.sp,
                        letterSpacing = 0.sp,
                        textDecoration = TextDecoration.None
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (assessment.indicators.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (indicator in assessment.indicators) {
                            ThreatIndicatorRow(indicator, primaryColor)
                        }
                    }
                }

                Text(
                    text = "By installing this skill you accept the associated risks. " +
                        "Only proceed if you trust the skill author.",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontFamily = PitagonsSans,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        color = primaryColor.copy(alpha = 0.5f),
                        lineHeight = 20.sp,
                        textDecoration = TextDecoration.None
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                DgenPrimaryButton(
                    text = confirmButtonText,
                    backgroundColor = if (assessment.level >= ThreatLevel.HIGH) threatColor else primaryColor,
                    containerColor = if (assessment.level >= ThreatLevel.HIGH) threatSecondaryColor else secondaryColor,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onConfirm()
                    }
                )

                Spacer(modifier = Modifier.width(24.dp))

                DgenSecondaryButton(
                    text = cancelButtonText,
                    containerColor = primaryColor,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreatIndicatorRow(indicator: ThreatIndicator, primaryColor: Color) {
    val dotColor = when (indicator.severity) {
        ThreatLevel.LOW -> Color(0xFF4CAF50)
        ThreatLevel.MEDIUM -> Color(0xFFFFA000)
        ThreatLevel.HIGH -> Color(0xFFFF6D00)
        ThreatLevel.CRITICAL -> Color(0xFFD50000)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(dotColor),
            )
            Text(
                text = indicator.category,
                style = TextStyle(
                    fontFamily = PitagonsSans,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryColor,
                ),
            )
        }
        Text(
            text = indicator.description,
            style = TextStyle(
                fontFamily = PitagonsSans,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = primaryColor.copy(alpha = neonOpacity),
            ),
        )
    }
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFF050505,
    widthDp = 720,
    heightDp = 720,
)
@Composable
private fun ThreatConfirmationDialogPreview() {
    ThreatConfirmationContent(
        slug = "ethos-swap-skill",
        assessment = ThreatAssessment(
            level = ThreatLevel.MEDIUM,
            summary = "This skill requests broad permissions including network access, " +
                "wallet signing, and access to contacts. Proceed with caution.",
            indicators = listOf(
                ThreatIndicator(
                    severity = ThreatLevel.CRITICAL,
                    category = "Wallet Access",
                    description = "Requests permission to sign transactions on your behalf.",
                ),
                ThreatIndicator(
                    severity = ThreatLevel.HIGH,
                    category = "Network Access",
                    description = "Can make outbound HTTP requests to arbitrary endpoints.",
                ),
                ThreatIndicator(
                    severity = ThreatLevel.MEDIUM,
                    category = "Contact Access",
                    description = "Reads your contact list to suggest recipients.",
                ),
            ),
        ),
        primaryColor = orcheCore,
        secondaryColor = orcheAsh,
        confirmButtonText = "ACCEPT & INSTALL",
        cancelButtonText = "CANCEL",
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview(
    showBackground = true,
    backgroundColor = 0xFF050505,
    widthDp = 720,
    heightDp = 720,
)
@Composable
private fun ThreatApprovalDialogPreview() {
    ThreatConfirmationContent(
        slug = "crypto-wallet-drainer",
        assessment = ThreatAssessment(
            level = ThreatLevel.CRITICAL,
            summary = "This skill requests access to your wallet's private keys and attempts " +
                    "to initiate transactions to an unknown external address.",
            indicators = listOf(),
        ),
        primaryColor = orcheCore,
        secondaryColor = orcheAsh,
        confirmButtonText = "APPROVE",
        cancelButtonText = "DENY",
        onConfirm = {},
        onDismiss = {},
    )
}
