package org.ethereumphone.andyclaw.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.Tier
import org.ethereumphone.andyclaw.skills.tier.OsCapabilities

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()
    val error by viewModel.error.collectAsState()

    val apiKey by viewModel.apiKey.collectAsState()
    val goals by viewModel.goals.collectAsState()
    val customName by viewModel.customName.collectAsState()
    val values by viewModel.values.collectAsState()
    val yoloMode by viewModel.yoloMode.collectAsState()
    val selectedSkills by viewModel.selectedSkills.collectAsState()

    val needsApiKey = viewModel.needsApiKey
    val totalSteps = viewModel.totalSteps
    val stepOffset = if (needsApiKey) 1 else 0

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = { (currentStep + 1) / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Step ${currentStep + 1} of $totalSteps",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(32.dp))

            // Step content with animated transitions
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
                    0 -> if (needsApiKey) apiKey.isNotBlank() else goals.isNotBlank()
                    0 + stepOffset -> goals.isNotBlank()
                    else -> true
                }
                val onNext = { if (canAdvance) viewModel.nextStep() }

                when (step) {
                    0 -> if (needsApiKey) {
                        StepApiKey(apiKey, onNext = onNext) { viewModel.apiKey.value = it }
                    } else {
                        StepGoals(goals, onNext = onNext) { viewModel.goals.value = it }
                    }
                    0 + stepOffset -> StepGoals(goals, onNext = onNext) { viewModel.goals.value = it }
                    1 + stepOffset -> StepName(customName, onNext = onNext) { viewModel.customName.value = it }
                    2 + stepOffset -> StepValues(values, onNext = onNext) { viewModel.values.value = it }
                    3 + stepOffset -> StepPermissions(
                        skills = viewModel.registeredSkills,
                        yoloMode = yoloMode,
                        selectedSkills = selectedSkills,
                        onYoloModeChange = { viewModel.setYoloMode(it) },
                        onToggleSkill = { id, enabled -> viewModel.toggleSkill(id, enabled) },
                    )
                }
            }

            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = { viewModel.previousStep() },
                        enabled = !isSubmitting,
                    ) {
                        Text("Back")
                    }
                } else {
                    Spacer(Modifier)
                }

                if (currentStep < totalSteps - 1) {
                    Button(
                        onClick = { viewModel.nextStep() },
                        enabled = when (currentStep) {
                            0 -> if (needsApiKey) apiKey.isNotBlank() else goals.isNotBlank()
                            0 + stepOffset -> goals.isNotBlank()
                            else -> true
                        },
                    ) {
                        Text("Next")
                    }
                } else {
                    Button(
                        onClick = { viewModel.submit(onComplete) },
                        enabled = !isSubmitting,
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Get Started")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepApiKey(value: String, onNext: () -> Unit, onValueChange: (String) -> Unit) {
    Column {
        Text(
            text = "Enter your API key",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "An OpenRouter API key is required to power your AI assistant. You can get one at openrouter.ai.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("sk-or-v1-...") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
        )
    }
}

@Composable
private fun StepGoals(value: String, onNext: () -> Unit, onValueChange: (String) -> Unit) {
    Column {
        Text(
            text = "What do you want to achieve?",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tell your AI what you'd like help with on your dGEN1.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.replace("\n", " ")) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Help me manage my crypto portfolio, stay on top of DeFi...") },
            minLines = 4,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
        )
    }
}

@Composable
private fun StepName(value: String, onNext: () -> Unit, onValueChange: (String) -> Unit) {
    Column {
        Text(
            text = "What should I call myself?",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Give your AI a custom name, or keep the generated one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
        )
    }
}

@Composable
private fun StepValues(value: String, onNext: () -> Unit, onValueChange: (String) -> Unit) {
    Column {
        Text(
            text = "What matters to you?",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Share your values and priorities so your AI can align with what's important to you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.replace("\n", " ")) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Privacy, decentralization, security, simplicity...") },
            minLines = 4,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
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

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = "Permissions",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Choose which capabilities your AI can use. You can change these later in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // YOLO mode card
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (yoloMode) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YOLO Mode",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Give your AI full access to all device capabilities. Tool usage will be auto-approved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = yoloMode,
                    onCheckedChange = onYoloModeChange,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Individual Skills",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        for (skill in skills) {
            SkillRow(
                skill = skill,
                tier = tier,
                enabled = if (yoloMode) true else skill.id in selectedSkills,
                disabledByYolo = yoloMode,
                onToggle = { onToggleSkill(skill.id, it) },
            )
        }
    }
}

@Composable
private fun SkillRow(
    skill: AndyClawSkill,
    tier: Tier,
    enabled: Boolean,
    disabledByYolo: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            val baseToolCount = skill.baseManifest.tools.size
            val privToolCount = skill.privilegedManifest?.tools?.size ?: 0
            val toolText = buildString {
                append("$baseToolCount base tool${if (baseToolCount != 1) "s" else ""}")
                if (privToolCount > 0) {
                    append(", $privToolCount privileged")
                    if (tier != Tier.PRIVILEGED) append(" (locked)")
                }
            }
            Text(
                text = toolText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            enabled = !disabledByYolo,
        )
    }
}
