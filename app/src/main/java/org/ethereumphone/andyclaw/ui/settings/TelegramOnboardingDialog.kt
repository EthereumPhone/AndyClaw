package org.ethereumphone.andyclaw.ui.settings

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.telegram.TelegramBotClient

private const val TAG = "TelegramOnboarding"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramOnboardingDialog(
    onComplete: (token: String, ownerChatId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(1) }
    var token by remember { mutableStateOf("") }

    // Step 2 state
    var pendingChatId by remember { mutableStateOf<Long?>(null) }
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var enteredCode by remember { mutableStateOf("") }
    var isPolling by remember { mutableStateOf(false) }
    var waitingForMessage by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    var pollingJob by remember { mutableStateOf<Job?>(null) }

    // Clean up polling when the dialog is dismissed
    DisposableEffect(Unit) {
        onDispose {
            pollingJob?.cancel()
        }
    }

    Dialog(
        onDismissRequest = {
            pollingJob?.cancel()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Set up Telegram Bot") },
                    navigationIcon = {
                        IconButton(onClick = {
                            pollingJob?.cancel()
                            onDismiss()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when (step) {
                    1 -> StepEnterToken(
                        token = token,
                        onTokenChange = { token = it },
                        onNext = {
                            step = 2
                            // Start polling for messages with this token
                            val client = TelegramBotClient(token = { token.trim() })
                            isPolling = true
                            waitingForMessage = true
                            errorMessage = null
                            pollingJob = scope.launch {
                                pollForVerification(
                                    client = client,
                                    onMessageReceived = { chatId, code ->
                                        pendingChatId = chatId
                                        pendingCode = code
                                        waitingForMessage = false
                                    },
                                    onError = { msg ->
                                        errorMessage = msg
                                        isPolling = false
                                    },
                                )
                            }
                        },
                    )

                    2 -> StepVerifyCode(
                        waitingForMessage = waitingForMessage,
                        enteredCode = enteredCode,
                        onCodeChange = { enteredCode = it },
                        errorMessage = errorMessage,
                        onVerify = {
                            if (enteredCode.equals(pendingCode, ignoreCase = true)) {
                                pollingJob?.cancel()
                                onComplete(token.trim(), pendingChatId!!)
                            } else {
                                errorMessage = "Code does not match. Please try again."
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepEnterToken(
    token: String,
    onTokenChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    Text(
        text = "Step 1: Enter Bot Token",
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Create a Telegram bot and get its token:",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "1. Open Telegram and search for @BotFather\n" +
            "2. Send /newbot and follow the prompts\n" +
            "3. Copy the bot token BotFather gives you\n" +
            "4. Paste it below",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Bot Token") },
        placeholder = { Text("123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth(),
        enabled = token.isNotBlank(),
    ) {
        Text("Next")
    }
}

@Composable
private fun StepVerifyCode(
    waitingForMessage: Boolean,
    enteredCode: String,
    onCodeChange: (String) -> Unit,
    errorMessage: String?,
    onVerify: () -> Unit,
) {
    Text(
        text = "Step 2: Verify Ownership",
        style = MaterialTheme.typography.headlineSmall,
    )
    Spacer(Modifier.height(16.dp))

    if (waitingForMessage) {
        Text(
            text = "Open Telegram and send any message to your bot.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Waiting for your message...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
    } else {
        Text(
            text = "A verification code was sent to your Telegram chat. Enter it below to confirm you own this bot.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = enteredCode,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Verification Code") },
            singleLine = true,
        )
        if (errorMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onVerify,
            modifier = Modifier.fillMaxWidth(),
            enabled = enteredCode.isNotBlank(),
        ) {
            Text("Verify")
        }
    }

    if (!waitingForMessage && errorMessage == null) return
    // Show error for polling failures even while waiting
    if (waitingForMessage && errorMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private fun generateCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no ambiguous 0/O/1/I
    return (1..6).map { chars.random() }.joinToString("")
}

private suspend fun pollForVerification(
    client: TelegramBotClient,
    onMessageReceived: (chatId: Long, code: String) -> Unit,
    onError: (String) -> Unit,
) {
    // First, consume any pending updates so we only react to NEW messages
    var offset: Long? = null
    try {
        val stale = client.getUpdates(offset = null, timeout = 0)
        if (stale.isNotEmpty()) {
            offset = stale.last().updateId + 1
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to flush stale updates: ${e.message}")
    }

    var attempts = 0
    val maxAttempts = 60 // ~30 minutes with 30s long-poll timeouts
    while (kotlinx.coroutines.currentCoroutineContext().isActive && attempts < maxAttempts) {
        attempts++
        try {
            val updates = client.getUpdates(offset = offset, timeout = 30)
            if (updates.isNotEmpty()) {
                val first = updates.first()
                offset = updates.last().updateId + 1

                val code = generateCode()
                val sent = client.sendMessage(
                    first.chatId,
                    "Your verification code is: *$code*\n\nEnter this code in the AndyClaw app to complete setup.",
                )
                if (sent) {
                    onMessageReceived(first.chatId, code)
                    return
                } else {
                    onError("Failed to send verification code. Check your bot token.")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Polling error: ${e.message}")
            delay(3000) // brief back-off on error
        }
    }
    onError("Timed out waiting for a message. Please try again.")
}
