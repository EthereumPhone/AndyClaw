package org.ethereumphone.andyclaw.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ethereumphone.andyclaw.NodeApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onNavigateToSessions: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val currentTool by viewModel.currentToolExecution.collectAsState()
    val error by viewModel.error.collectAsState()
    val insufficientBalance by viewModel.insufficientBalance.collectAsState()
    val approvalRequest by viewModel.approvalRequest.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as NodeApp
    val aiName by app.securePrefs.aiName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            viewModel.loadSession(sessionId)
        } else if (viewModel.sessionId.value == null) {
            // Only create a new session when there isn't one already
            // (avoids re-creating on back-navigation from the sessions list)
            viewModel.newSession()
        }
    }

    // Auto-scroll to bottom on new messages or streaming
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            listState.animateScrollToItem(maxOf(0, messages.size + (if (isStreaming) 1 else 0) - 1))
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Approval dialog
    approvalRequest?.let { request ->
        ApprovalDialog(
            description = request.description,
            onApprove = { viewModel.respondToApproval(true) },
            onDeny = { viewModel.respondToApproval(false) },
        )
    }

    // Insufficient balance dialog
    if (insufficientBalance) {
        AlertDialog(
            onDismissRequest = { viewModel.clearInsufficientBalance() },
            title = { Text("Paymaster Depleted") },
            text = { Text("The paymaster that covers your AI usage has been depleted. Please fill it up to continue using $aiName.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearInsufficientBalance()
                    val intent = Intent().apply {
                        setClassName("io.freedomfactory.paymaster", "io.freedomfactory.paymaster.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Top Up")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearInsufficientBalance() }) {
                    Text("Dismiss")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(aiName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateToSessions) {
                        Icon(Icons.Default.Menu, contentDescription = "Sessions")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatMessageItem(message = message)
                }

                // Streaming display
                if (isStreaming && streamingText.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            StreamingTextDisplay(
                                text = streamingText,
                                modifier = Modifier.fillMaxWidth(0.9f),
                            )
                        }
                    }
                }

                // Tool execution indicator
                if (currentTool != null) {
                    item {
                        ToolExecutionIndicator(toolName = currentTool!!)
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            ChatInputBar(
                isStreaming = isStreaming,
                onSend = { viewModel.sendMessage(it) },
                onCancel = { viewModel.cancel() },
            )
        }
    }
}
