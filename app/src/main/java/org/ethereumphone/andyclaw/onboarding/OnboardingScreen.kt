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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val currentStep by viewModel.currentStep.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()
    val error by viewModel.error.collectAsState()

    val goals by viewModel.goals.collectAsState()
    val customName by viewModel.customName.collectAsState()
    val values by viewModel.values.collectAsState()

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
                progress = { (currentStep + 1) / 3f },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Step ${currentStep + 1} of 3",
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
                when (step) {
                    0 -> StepGoals(goals) { viewModel.goals.value = it }
                    1 -> StepName(customName) { viewModel.customName.value = it }
                    2 -> StepValues(values) { viewModel.values.value = it }
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

                if (currentStep < 2) {
                    Button(
                        onClick = { viewModel.nextStep() },
                        enabled = when (currentStep) {
                            0 -> goals.isNotBlank()
                            else -> true
                        },
                    ) {
                        Text("Next")
                    }
                } else {
                    Button(
                        onClick = { viewModel.submit(onComplete) },
                        enabled = !isSubmitting && values.isNotBlank(),
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
private fun StepGoals(value: String, onValueChange: (String) -> Unit) {
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
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Help me manage my crypto portfolio, stay on top of DeFi...") },
            minLines = 4,
            maxLines = 8,
        )
    }
}

@Composable
private fun StepName(value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(
            text = "What should I call myself?",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Give your AI a custom name, or leave blank for \"AndyClaw\".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("AndyClaw") },
            singleLine = true,
        )
    }
}

@Composable
private fun StepValues(value: String, onValueChange: (String) -> Unit) {
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
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Privacy, decentralization, security, simplicity...") },
            minLines = 4,
            maxLines = 8,
        )
    }
}
