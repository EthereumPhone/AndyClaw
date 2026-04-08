package org.ethereumphone.andyclaw.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.example.dgenlibrary.DgenLoadingMatrix
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.PitagonsSans
import com.example.dgenlibrary.ui.theme.SpaceMono
import com.example.dgenlibrary.ui.theme.dgenOcean
import com.example.dgenlibrary.ui.theme.dgenWhite
import androidx.compose.material3.MaterialTheme
import com.example.dgenlibrary.showDgenToast
import org.ethereumphone.andyclaw.ui.components.DgenSquareSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dgenlibrary.ui.theme.label_fontSize
import com.example.dgenlibrary.ui.theme.smalllabel_fontSize
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities
import org.ethereumphone.andyclaw.ui.components.ChadBackground
import org.ethereumphone.andyclaw.ui.components.DgenCursorTextfield
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.SkillRow
import org.ethereumphone.andyclaw.ui.components.GlowStyle
import org.ethereumphone.andyclaw.ui.theme.AndyClawTheme

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()
    val error by viewModel.error.collectAsState()

    val isPrivileged = viewModel.isPrivileged
    val walletAddress by viewModel.walletAddress.collectAsState()
    val isSigning by viewModel.isSigning.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val tinfoilApiKey by viewModel.tinfoilApiKey.collectAsState()
    val claudeOauthRefreshToken by viewModel.claudeOauthRefreshToken.collectAsState()
    val openaiApiKey by viewModel.openaiApiKey.collectAsState()
    val veniceApiKey by viewModel.veniceApiKey.collectAsState()
    val goals by viewModel.goals.collectAsState()
    val customName by viewModel.customName.collectAsState()
    val values by viewModel.values.collectAsState()
    val yoloMode by viewModel.yoloMode.collectAsState()
    val selectedSkills by viewModel.selectedSkills.collectAsState()

    val totalSteps = viewModel.totalSteps

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        SystemColorManager.refresh(context)
    }
    val primaryColor = SystemColorManager.primaryColor
    val secondaryColor = SystemColorManager.secondaryColor

    LaunchedEffect(error) {
        error?.let {
            showDgenToast(context, it)
            viewModel.clearError()
        }
    }

    ChadBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            // Bobbing personality emoji (matches ethOSLauncher dGENT tab)
            val bobTransition = rememberInfiniteTransition(label = "onboardingBob")
            val bobOffset by bobTransition.animateFloat(
                initialValue = -4f,
                targetValue = 4f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1900
                        -4f at 0
                        -4f at 949
                        4f at 950
                        4f at 1900
                    },
                ),
                label = "bobOffset",
            )
            Text(
                text = "( \u02D8\u03C9\u02D8 )",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = primaryColor,
                style = MaterialTheme.typography.bodyMedium.copy(
                    shadow = GlowStyle.subtitle(primaryColor),
                ),
                modifier = Modifier.offset(y = bobOffset.dp),
            )

            Spacer(Modifier.height(24.dp))

            // Step content
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn())
                            .togetherWith(slideOutHorizontally { -it } + fadeOut())
                    } else {
                        (slideInHorizontally { -it } + fadeIn())
                            .togetherWith(slideOutHorizontally { it } + fadeOut())
                    }
                },
                modifier = Modifier.weight(1f),
                label = "onboarding_step",
            ) { step ->
                val canAdvance = when (currentStep) {
                    0 -> if (isPrivileged) {
                        walletAddress.isNotBlank()
                    } else {
                        when (selectedProvider) {
                            LlmProvider.OPEN_ROUTER -> apiKey.isNotBlank()
                            LlmProvider.CLAUDE_OAUTH -> claudeOauthRefreshToken.isNotBlank()
                            LlmProvider.TINFOIL -> tinfoilApiKey.isNotBlank()
                            LlmProvider.OPENAI -> openaiApiKey.isNotBlank()
                            LlmProvider.VENICE -> veniceApiKey.isNotBlank()
                            LlmProvider.LOCAL,
                            LlmProvider.ETHOS_PREMIUM -> true
                        }
                    }
                    1 -> goals.isNotBlank()
                    else -> true
                }
                val onNext = { if (canAdvance) viewModel.nextStep() }

                when (step) {
                    0 -> if (isPrivileged) {
                        StepWalletSign(
                            walletAddress = walletAddress,
                            isSigning = isSigning,
                            onSign = { viewModel.signWithWallet() },
                        )
                    } else {
                        StepProviderSelection(
                            selectedProvider = selectedProvider,
                            apiKey = apiKey,
                            tinfoilApiKey = tinfoilApiKey,
                            claudeOauthRefreshToken = viewModel.claudeOauthRefreshToken.collectAsState().value,
                            openaiApiKey = openaiApiKey,
                            veniceApiKey = veniceApiKey,
                            onProviderSelected = { viewModel.selectedProvider.value = it },
                            onApiKeyChange = { viewModel.apiKey.value = it },
                            onTinfoilApiKeyChange = { viewModel.tinfoilApiKey.value = it },
                            onClaudeOauthTokenChange = { viewModel.claudeOauthRefreshToken.value = it },
                            onOpenaiApiKeyChange = { viewModel.openaiApiKey.value = it },
                            onVeniceApiKeyChange = { viewModel.veniceApiKey.value = it },
                            onNext = onNext,
                        )
                    }
                    1 -> StepGoals(goals, onNext = onNext) { viewModel.goals.value = it }
                    2 -> StepName(customName, onNext = onNext) { viewModel.customName.value = it }
                    3 -> StepValues(values, onNext = onNext) { viewModel.values.value = it }
                    4 -> {
                        StepPermissions(
                            skills = viewModel.registeredSkills,
                            yoloMode = yoloMode,
                            selectedSkills = selectedSkills,
                            onYoloModeChange = { viewModel.setYoloMode(it) },
                            onToggleSkill = { id, enabled -> viewModel.toggleSkill(id, enabled) },
                        )
                    }
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (currentStep > 0) {
                    DgenSmallPrimaryButton(
                        text = "BACK",
                        primaryColor = primaryColor,
                        onClick = { viewModel.previousStep() },
                        enabled = !isSubmitting,
                    )
                } else {
                    Spacer(Modifier)
                }

                if (currentStep < totalSteps - 1) {
                    DgenSmallPrimaryButton(
                        text = "NEXT",
                        primaryColor = primaryColor,
                        onClick = { viewModel.nextStep() },
                        enabled = when (currentStep) {
                            0 -> if (isPrivileged) {
                                walletAddress.isNotBlank()
                            } else {
                                when (selectedProvider) {
                                    LlmProvider.OPEN_ROUTER -> apiKey.isNotBlank()
                                    LlmProvider.CLAUDE_OAUTH -> claudeOauthRefreshToken.isNotBlank()
                                    LlmProvider.TINFOIL -> tinfoilApiKey.isNotBlank()
                                    LlmProvider.OPENAI -> openaiApiKey.isNotBlank()
                                    LlmProvider.VENICE -> veniceApiKey.isNotBlank()
                                    LlmProvider.LOCAL,
                                    LlmProvider.ETHOS_PREMIUM -> true
                                }
                            }
                            1 -> goals.isNotBlank()
                            else -> true
                        },
                    )
                } else {
                    Box(
                        modifier = Modifier.width(100.dp).height(50.dp),
                        contentAlignment = Alignment.Center
                    ){
                        if (isSubmitting) {
                            DgenLoadingMatrix(
                                size = 20.dp,
                                LEDSize = 5.dp,
                                activeLEDColor = primaryColor,
                                unactiveLEDColor = secondaryColor,
                            )
                        } else {
                            DgenSmallPrimaryButton(
                                text = "LAUNCH",
                                primaryColor = primaryColor,
                                onClick = { viewModel.submit(onComplete) },
                            )
                        }
                    }
                }
            }
        }

    }
}

// --- Step composables ---

@Composable
private fun SectionTitle(text: String) {
    val primaryColor = SystemColorManager.primaryColor
    Text(
        text = text,
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Bold,
        fontSize = label_fontSize,
        color = primaryColor,
        style = MaterialTheme.typography.headlineLarge.copy(
            shadow = GlowStyle.title(primaryColor),
        ),
    )
}

@Composable
private fun SectionDescription(text: String) {
    val primaryColor = SystemColorManager.primaryColor
    Text(
        text = text,
        fontFamily = SpaceMono,
        fontSize = 16.sp,
        color = dgenWhite,
        lineHeight = 20.sp,
        style = MaterialTheme.typography.bodySmall.copy(
            shadow = GlowStyle.body(dgenWhite),
        ),
    )
}

@Composable
private fun StepWalletSign(
    walletAddress: String,
    isSigning: Boolean,
    onSign: () -> Unit,
) {
    val primaryColor = SystemColorManager.primaryColor
    val secondaryColor = SystemColorManager.secondaryColor

    Column {
        SectionTitle("WALLET SIGN-IN")
        Spacer(Modifier.height(8.dp))
        SectionDescription("Sign a message to verify your identity. This lets us track your usage for billing.")
        Spacer(Modifier.height(24.dp))

        if (walletAddress.isNotBlank()) {
            val truncated = walletAddress.take(6) + "..." + walletAddress.takeLast(4)
            Text(
                text = "SIGNED AS $truncated",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = primaryColor,
                style = MaterialTheme.typography.titleMedium.copy(
                    shadow = GlowStyle.title(primaryColor),
                ),
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSigning) {
                    DgenLoadingMatrix(
                        size = 20.dp,
                        LEDSize = 5.dp,
                        activeLEDColor = primaryColor,
                        unactiveLEDColor = secondaryColor,
                    )
                } else {
                    DgenSmallPrimaryButton(
                        text = "SIGN",
                        primaryColor = primaryColor,
                        onClick = onSign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

        }
    }
}

@Composable
private fun StepProviderSelection(
    selectedProvider: LlmProvider,
    apiKey: String,
    tinfoilApiKey: String,
    claudeOauthRefreshToken: String,
    openaiApiKey: String,
    veniceApiKey: String,
    onProviderSelected: (LlmProvider) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onTinfoilApiKeyChange: (String) -> Unit,
    onClaudeOauthTokenChange: (String) -> Unit,
    onOpenaiApiKeyChange: (String) -> Unit,
    onVeniceApiKeyChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    val primaryColor = SystemColorManager.primaryColor

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle("CHOOSE AI PROVIDER")
        Spacer(Modifier.height(8.dp))
        SectionDescription("Select how your AI assistant processes requests. You can change this later in Settings.")
        Spacer(Modifier.height(16.dp))

        ProviderCard(
            label = "ON-DEVICE (QWEN2.5-1.5B)",
            description = "Best privacy — runs entirely on your phone. No data leaves the device.",
            isSelected = selectedProvider == LlmProvider.LOCAL,
            onClick = { onProviderSelected(LlmProvider.LOCAL) },
        )
        Spacer(Modifier.height(8.dp))
        ProviderCard(
            label = "TINFOIL TEE",
            description = "Best balance — cloud inference inside a verified Trusted Execution Environment.",
            isSelected = selectedProvider == LlmProvider.TINFOIL,
            onClick = { onProviderSelected(LlmProvider.TINFOIL) },
        )
        Spacer(Modifier.height(8.dp))
        ProviderCard(
            label = "CLAUDE (OAUTH)",
            description = "Use your Claude Pro/Max subscription directly. Requires a setup-token from Claude Code CLI.",
            isSelected = selectedProvider == LlmProvider.CLAUDE_OAUTH,
            onClick = { onProviderSelected(LlmProvider.CLAUDE_OAUTH) },
        )
        Spacer(Modifier.height(8.dp))
        ProviderCard(
            label = "OPENROUTER",
            description = "Best performance — cloud inference via OpenRouter. Fast and capable.",
            isSelected = selectedProvider == LlmProvider.OPEN_ROUTER,
            onClick = { onProviderSelected(LlmProvider.OPEN_ROUTER) },
        )
        Spacer(Modifier.height(8.dp))
        ProviderCard(
            label = "OPENAI",
            description = "Cloud inference via OpenAI's API. GPT-4o, o3, and more.",
            isSelected = selectedProvider == LlmProvider.OPENAI,
            onClick = { onProviderSelected(LlmProvider.OPENAI) },
        )
        Spacer(Modifier.height(8.dp))
        ProviderCard(
            label = "VENICE AI",
            description = "Privacy-focused inference. Uncensored models available.",
            isSelected = selectedProvider == LlmProvider.VENICE,
            onClick = { onProviderSelected(LlmProvider.VENICE) },
        )

        Spacer(Modifier.height(16.dp))

        when (selectedProvider) {
            LlmProvider.OPEN_ROUTER -> {
                DgenCursorTextfield(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "API KEY",
                    placeholder = { Text("sk-or-v1-...", color = dgenWhite.copy(alpha = 0.3f), fontSize = label_fontSize) },
                    primaryColor = primaryColor,
                )
            }
            LlmProvider.CLAUDE_OAUTH -> {
                DgenCursorTextfield(
                    value = claudeOauthRefreshToken,
                    onValueChange = onClaudeOauthTokenChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "SETUP TOKEN",
                    placeholder = { Text("sk-ant-ort01-...", color = dgenWhite.copy(alpha = 0.3f), fontSize = label_fontSize) },
                    primaryColor = primaryColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Run `claude setup-token` in Claude Code CLI to generate this token.",
                    fontFamily = SpaceMono,
                    fontSize = 13.sp,
                    color = primaryColor.copy(alpha = 0.6f),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        shadow = GlowStyle.body(primaryColor),
                    ),
                )
            }
            LlmProvider.TINFOIL -> {
                DgenCursorTextfield(
                    value = tinfoilApiKey,
                    onValueChange = onTinfoilApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "API KEY",
                    placeholder = { Text("tf-...", color = dgenWhite.copy(alpha = 0.3f), fontSize = label_fontSize) },
                    primaryColor = primaryColor,
                )
            }
            LlmProvider.OPENAI -> {
                DgenCursorTextfield(
                    value = openaiApiKey,
                    onValueChange = onOpenaiApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "API KEY",
                    placeholder = { Text("sk-...", color = dgenWhite.copy(alpha = 0.3f), fontSize = label_fontSize) },
                    primaryColor = primaryColor,
                )
            }
            LlmProvider.VENICE -> {
                DgenCursorTextfield(
                    value = veniceApiKey,
                    onValueChange = onVeniceApiKeyChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = "API KEY",
                    placeholder = { Text("vce-...", color = dgenWhite.copy(alpha = 0.3f), fontSize = label_fontSize) },
                    primaryColor = primaryColor,
                )
            }
            LlmProvider.LOCAL,
            LlmProvider.ETHOS_PREMIUM -> {
                Text(
                    text = "No API key needed. The model (~2.5 GB) will be downloaded after setup.",
                    fontFamily = SpaceMono,
                    fontSize = 13.sp,
                    color = primaryColor.copy(alpha = 0.6f),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        shadow = GlowStyle.body(primaryColor),
                    ),
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val primaryColor = SystemColorManager.primaryColor
    val view = LocalView.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) primaryColor.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.3f),
                shape = RoundedCornerShape(4.dp),
            )
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = label,
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium.copy(
                    shadow = GlowStyle.subtitle(primaryColor),
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                fontFamily = SpaceMono,
                fontSize = 12.sp,
                color = if (isSelected) primaryColor.copy(alpha = 0.8f) else primaryColor.copy(alpha = 0.5f),
                lineHeight = 18.sp,
                style = MaterialTheme.typography.bodySmall.copy(
                    shadow = GlowStyle.body(primaryColor),
                ),
            )
        }
    }
}

@Composable
private fun StepGoals(value: String, onNext: () -> Unit, onValueChange: (String) -> Unit) {
    Column {
        SectionTitle("SET YOUR GOALS")
        Spacer(Modifier.height(8.dp))
        SectionDescription("Tell your AI what you'd like help with on your dGEN1.")
        Spacer(Modifier.height(16.dp))
        DgenCursorTextfield(
            value = value,
            onValueChange = { onValueChange(it.replace("\n", " ")) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Help me manage my crypto portfolio...", color = dgenWhite.copy(alpha = 0.3f), fontSize = label_fontSize) },
            singleLine = false,
            primaryColor = SystemColorManager.primaryColor,
        )
    }
}

@Composable
private fun StepName(value: String, onNext: () -> Unit, onValueChange: (String) -> Unit) {
    Column {
        SectionTitle("NAME YOUR AI")
        Spacer(Modifier.height(8.dp))
        SectionDescription("Give your AI a custom name, or keep the generated one.")
        Spacer(Modifier.height(16.dp))
        DgenCursorTextfield(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            primaryColor = SystemColorManager.primaryColor,
        )
    }
}

@Composable
private fun StepValues(value: String, onNext: () -> Unit, onValueChange: (String) -> Unit) {
    Column {
        SectionTitle("YOUR VALUES")
        Spacer(Modifier.height(8.dp))
        SectionDescription("Share your values and priorities so your AI can align with what's important to you.")
        Spacer(Modifier.height(16.dp))
        DgenCursorTextfield(
            value = value,
            onValueChange = { onValueChange(it.replace("\n", " ")) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Privacy, decentralization, security...", color = dgenWhite.copy(alpha = 0.3f), fontSize = label_fontSize) },
            singleLine = false,
            primaryColor = SystemColorManager.primaryColor,
        )
    }
}

@Composable
private fun StepPermissions(
    skills: List<AndyClawSkill>,
    yoloMode: Boolean,
    selectedSkills: Set<String>,
    onYoloModeChange: (Boolean) -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
) {
    val tier = OsCapabilities.currentTier()
    val primaryColor = SystemColorManager.primaryColor
    val sectionTitleStyle = TextStyle(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Left,
        shadow = GlowStyle.subtitle(primaryColor),
    )
    val bodyStyle = TextStyle(
        fontFamily = PitagonsSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        textAlign = TextAlign.Left,
        shadow = GlowStyle.body(primaryColor),
    )

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SectionTitle("PERMISSIONS")
        Spacer(Modifier.height(8.dp))
        SectionDescription("Choose which capabilities your AI can use. You can change these later in Settings.")

        Spacer(Modifier.height(16.dp))

        // YOLO mode card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (yoloMode) primaryColor.copy(alpha = 0.15f) else Color.Transparent)
                .border(
                    width = if (yoloMode) 2.dp else 1.dp,
                    color = if (yoloMode) primaryColor else primaryColor.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YOLO MODE",
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = label_fontSize,
                        color = primaryColor,
                        style = MaterialTheme.typography.labelMedium.copy(
                            shadow = GlowStyle.subtitle(primaryColor),
                        ),
                    )
                    Text(
                        text = "Give your AI full access to all device capabilities. Tool usage will be auto-approved.",
                        fontFamily = SpaceMono,
                        fontSize = 14.sp,
                        color = dgenWhite,
                        lineHeight = 18.sp,
                        style = MaterialTheme.typography.bodySmall.copy(
                            shadow = GlowStyle.body(dgenWhite),
                        ),
                    )
                }
                DgenSquareSwitch(
                    checked = yoloMode,
                    onCheckedChange = onYoloModeChange,
                    activeColor = primaryColor,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "INDIVIDUAL SKILLS",
            fontFamily = SpaceMono,
            fontWeight = FontWeight.Bold,
            fontSize = label_fontSize,
            color = primaryColor.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.labelMedium.copy(
                shadow = GlowStyle.subtitle(primaryColor),
            ),
        )

        for (skill in skills) {
            SkillRow(
                skill = skill,
                tier = tier,
                enabled = if (yoloMode) true else skill.id in selectedSkills,
                onToggle = { onToggleSkill(skill.id, it) },
                primaryColor = primaryColor,
                titleColor = primaryColor,
                sectionTitleStyle = sectionTitleStyle,
                bodyStyle = bodyStyle,
                disabledByYolo = yoloMode,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewStepWalletSign() {
    AndyClawTheme {
        ChadBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                StepWalletSign(
                    walletAddress = "",
                    isSigning = false,
                    onSign = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewStepWalletSignSigned() {
    AndyClawTheme {
        ChadBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                StepWalletSign(
                    walletAddress = "0x1234567890abcdef1234567890abcdef12345678",
                    isSigning = false,
                    onSign = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewStepProviderSelection() {
    AndyClawTheme {
        ChadBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                StepProviderSelection(
                    selectedProvider = LlmProvider.LOCAL,
                    apiKey = "",
                    tinfoilApiKey = "",
                    claudeOauthRefreshToken = "",
                    openaiApiKey = "",
                    veniceApiKey = "",
                    onProviderSelected = {},
                    onApiKeyChange = {},
                    onTinfoilApiKeyChange = {},
                    onClaudeOauthTokenChange = {},
                    onOpenaiApiKeyChange = {},
                    onVeniceApiKeyChange = {},
                    onNext = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewStepGoals() {
    AndyClawTheme {
        ChadBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                StepGoals(
                    value = "",
                    onNext = {},
                    onValueChange = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewStepName() {
    AndyClawTheme {
        ChadBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                StepName(
                    value = "ChadBot-9000",
                    onNext = {},
                    onValueChange = {},
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun PreviewStepValues() {
    AndyClawTheme {
        ChadBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(24.dp)) {
                StepValues(
                    value = "",
                    onNext = {},
                    onValueChange = {},
                )
            }
        }
    }
}
