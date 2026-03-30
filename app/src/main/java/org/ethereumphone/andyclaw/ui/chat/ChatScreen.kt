package org.ethereumphone.andyclaw.ui.chat

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.compositeOver
import org.ethereumphone.andyclaw.ui.components.GlowStyle
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dgenlibrary.ConfirmationOverlay
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.showDgenToast
import com.example.dgenlibrary.ui.theme.body1_fontSize
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.commands.SlashCommandResult
import org.ethereumphone.andyclaw.ui.components.ChadBackground
import org.ethereumphone.andyclaw.ui.components.ThreatConfirmationDialog

@Composable
fun ChatScreen(
    sessionId: String?,
    onNavigateToSessions: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToRoute: (String) -> Unit = { onNavigateToSettings() },
    viewModel: ChatViewModel = viewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val currentTool by viewModel.currentToolExecution.collectAsState()
    val error by viewModel.error.collectAsState()
    val insufficientBalance by viewModel.insufficientBalance.collectAsState()
    val approvalRequest by viewModel.approvalRequest.collectAsState()
    val askUserRequest by viewModel.askUserRequest.collectAsState()
    val displayBitmap by viewModel.agentDisplayBitmap.collectAsState()
    val navigationEvent by viewModel.navigationEvent.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as NodeApp
    val aiName by app.securePrefs.aiName.collectAsState()
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    var autoScroll by remember { mutableStateOf(true) }
    val expandedToolResults = remember { mutableStateListOf<String>() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var inputBarHeightDp by remember { mutableStateOf(0.dp) }
    var showSlashOverlay by remember { mutableStateOf(false) }
    var slashQuery by remember { mutableStateOf("") }
    var clearInput by remember { mutableStateOf(false) }

    // Disable auto-scroll when the user touches/drags the list.
    // NestedScrollConnection.onPreScroll only fires for user gestures,
    // never for programmatic scrollToItem calls.
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    autoScroll = false
                }
                return Offset.Zero
            }
        }
    }

    val primaryColor = SystemColorManager.primaryColor
    val secondaryColor = SystemColorManager.secondaryColor

    LaunchedEffect(Unit) {
        SystemColorManager.refresh(context)
    }

    // Re-enable auto-scroll when a new streaming response begins
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            autoScroll = true
        }
    }

    LaunchedEffect(sessionId) {
        if (sessionId != null) {
            viewModel.loadSession(sessionId)
        } else if (viewModel.sessionId.value == null) {
            viewModel.newSession()
        }
    }

    LaunchedEffect(messages.size, streamingText) {
        if ((messages.isNotEmpty() || streamingText.isNotEmpty()) && autoScroll) {
            val targetIndex = listState.layoutInfo.totalItemsCount - 1
            if (targetIndex >= 0) {
                listState.scrollToItem(targetIndex)
            }
        }
    }

    LaunchedEffect(error) {
        error?.let {
            showDgenToast(context, it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(approvalRequest) {
        if (approvalRequest != null) keyboardController?.hide()
    }

    LaunchedEffect(askUserRequest) {
        if (askUserRequest != null) keyboardController?.hide()
    }

    LaunchedEffect(insufficientBalance) {
        if (insufficientBalance) keyboardController?.hide()
    }

    // Handle navigation events from slash commands
    LaunchedEffect(navigationEvent) {
        navigationEvent?.let { route ->
            viewModel.consumeNavigationEvent()
            onNavigateToRoute(route)
        }
    }

    ChadBackground(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier
            .fillMaxSize()
            .imePadding()) {
            // Top bar overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onNavigateToSessions) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "Sessions",
                        tint = primaryColor,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = aiName.uppercase(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = body1_fontSize,
                        shadow = GlowStyle.title(primaryColor),
                    ),
                    color = primaryColor,
                )

                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = primaryColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Messages area
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(nestedScrollConnection)
                        .padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageItem(
                            message = message,
                            isExpanded = expandedToolResults.contains(message.id),
                            onToggleExpand = if (message.role == "tool") {
                                {
                                    if (expandedToolResults.contains(message.id)) {
                                        expandedToolResults.remove(message.id)
                                    } else {
                                        expandedToolResults.add(message.id)
                                    }
                                }
                            } else null,
                        )
                    }

                    if (isStreaming && streamingText.isNotEmpty()) {
                        item {
                            StreamingTextDisplay(
                                text = streamingText,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    if (currentTool != null) {
                        item {
                            ToolExecutionIndicator(toolName = currentTool!!)
                        }
                    }

                    item { Spacer(Modifier.height(8.dp)) }
                }

                // Agent display live preview
                displayBitmap?.let { bitmap ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .width(150.dp),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Agent display preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }

            val overlayActive = showSlashOverlay || askUserRequest != null
            val inputBarBackground = if (overlayActive) {
                primaryColor.copy(alpha = 0.15f).compositeOver(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f))
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            }

            ChatInputBar(
                isStreaming = isStreaming,
                onSend = { text ->
                    showSlashOverlay = false
                    slashQuery = ""
                    viewModel.sendMessage(text)
                },
                onCancel = { viewModel.cancel() },
                onInputChanged = { text ->
                    if (text.startsWith("/")) {
                        showSlashOverlay = true
                        slashQuery = text.removePrefix("/").lowercase()
                    } else {
                        showSlashOverlay = false
                        slashQuery = ""
                    }
                },
                clearInput = clearInput,
                onInputCleared = { clearInput = false },
                backgroundColor = inputBarBackground,
                modifier = Modifier.onGloballyPositioned { coords ->
                    with(density) {
                        inputBarHeightDp = coords.size.height.toDp()
                    }
                },
            )
        }

        // Slash command overlay — absolutely positioned, floats above the input bar
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(bottom = inputBarHeightDp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SlashCommandOverlay(
                visible = showSlashOverlay,
                query = slashQuery,
                accentColor = primaryColor,
                prefs = app.securePrefs,
                executor = viewModel.slashExecutor,
                onCommandResult = { result ->
                    when (result) {
                        is SlashCommandResult.Toggled -> {
                            showDgenToast(context, result.message)
                        }
                        is SlashCommandResult.CycleSelected -> {
                            showDgenToast(context, result.message)
                        }
                        is SlashCommandResult.ActionDone -> {
                            showSlashOverlay = false
                            slashQuery = ""
                            clearInput = true
                            when (result.message) {
                                "Conversation cleared." -> viewModel.newSession()
                                "Memory reindex started." -> viewModel.triggerReindex()
                            }
                            showDgenToast(context, result.message)
                        }
                        is SlashCommandResult.Navigate -> {
                            showSlashOverlay = false
                            slashQuery = ""
                            clearInput = true
                            viewModel.consumeNavigationEvent()
                            onNavigateToRoute(result.route)
                        }
                        is SlashCommandResult.HelpList -> {
                            // Show all commands by clearing the query filter
                            slashQuery = ""
                        }
                        else -> {}
                    }
                },
                onDismiss = {
                    showSlashOverlay = false
                    slashQuery = ""
                    clearInput = true
                },
            )
        }

        approvalRequest?.let { request ->
            if (request.threatAssessment != null && request.slug != null) {
                ThreatConfirmationDialog(
                    slug = request.slug,
                    assessment = request.threatAssessment,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    confirmButtonText = "APPROVE",
                    cancelButtonText = "DENY",
                    onConfirm = { viewModel.respondToApproval(true) },
                    onDismiss = { viewModel.respondToApproval(false) },
                )
            } else {
                ConfirmationOverlay(
                    visible = true,
                    description = "APPROVAL REQUIRED",
                    extraDescription = request.description,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    cancelButtonText = "DENY",
                    confirmButtonText = "APPROVE",
                    onCancel = { viewModel.respondToApproval(false) },
                    onConfirm = { viewModel.respondToApproval(true) }
                )
            }
        }

        // Ask-user overlay — same positioning as slash command overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(bottom = inputBarHeightDp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AskUserOverlay(
                visible = askUserRequest != null,
                request = askUserRequest,
                accentColor = primaryColor,
                onSubmit = { response -> viewModel.respondToAskUser(response) },
                onSkip = { viewModel.respondToAskUser(null) },
            )
        }

        if (insufficientBalance) {
            ConfirmationOverlay(
                visible = true,
                description = "PAYMASTER DEPLETED",
                extraDescription = "The paymaster that covers your AI usage has been depleted. Please fill it up to continue using $aiName.",
                primaryColor = primaryColor,
                secondaryColor = secondaryColor,
                confirmButtonText = "DISMISS",
                cancelButtonText = "TOP UP",
                onConfirm = { viewModel.clearInsufficientBalance() },
                onCancel = {
                    viewModel.clearInsufficientBalance()
                    val intent = Intent().apply {
                        setClassName("io.freedomfactory.paymaster", "io.freedomfactory.paymaster.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )
        }
    }
}

@Composable
private fun AskUserOverlay(
    visible: Boolean,
    request: org.ethereumphone.andyclaw.agent.AskUserRequest?,
    accentColor: androidx.compose.ui.graphics.Color,
    onSubmit: (org.ethereumphone.andyclaw.agent.AskUserResponse) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val questions = request?.questions ?: emptyList()
    var currentIndex by remember { mutableStateOf(0) }

    // Per-question state: selections for multi/ranked, custom text for single/multi
    val selections = remember { mutableStateListOf<MutableList<String>>() }
    val customTexts = remember { mutableStateListOf<String>() }
    // For ranked choice: the ordering
    val rankedOrders = remember { mutableStateListOf<MutableList<String>>() }

    // Reset state when new request arrives
    LaunchedEffect(request) {
        currentIndex = 0
        selections.clear()
        customTexts.clear()
        rankedOrders.clear()
        questions.forEach { q ->
            selections.add(mutableListOf())
            customTexts.add("")
            rankedOrders.add(q.options.toMutableList())
        }
    }

    val q = questions.getOrNull(currentIndex)
    val hasAnswer = when (q?.type) {
        org.ethereumphone.andyclaw.agent.QuestionType.SINGLE_SELECT ->
            selections.getOrNull(currentIndex)?.isNotEmpty() == true || customTexts.getOrNull(currentIndex)?.isNotBlank() == true
        org.ethereumphone.andyclaw.agent.QuestionType.MULTI_SELECT ->
            selections.getOrNull(currentIndex)?.isNotEmpty() == true || customTexts.getOrNull(currentIndex)?.isNotBlank() == true
        org.ethereumphone.andyclaw.agent.QuestionType.RANKED_CHOICE -> true // always has an order
        null -> false
    }
    val isLastQuestion = currentIndex == questions.size - 1

    fun buildResponse(): org.ethereumphone.andyclaw.agent.AskUserResponse {
        val answers = questions.mapIndexed { i, question ->
            when (question.type) {
                org.ethereumphone.andyclaw.agent.QuestionType.SINGLE_SELECT -> {
                    val sel = selections.getOrNull(i)?.firstOrNull()
                    val custom = customTexts.getOrNull(i) ?: ""
                    val answer = sel ?: custom
                    org.ethereumphone.andyclaw.agent.QuestionAnswer(question.question, answer)
                }
                org.ethereumphone.andyclaw.agent.QuestionType.MULTI_SELECT -> {
                    val sels = selections.getOrNull(i)?.toList() ?: emptyList()
                    val custom = customTexts.getOrNull(i) ?: ""
                    val all = if (custom.isNotBlank()) sels + custom else sels
                    org.ethereumphone.andyclaw.agent.QuestionAnswer(question.question, all.joinToString(", "), all)
                }
                org.ethereumphone.andyclaw.agent.QuestionType.RANKED_CHOICE -> {
                    val order = rankedOrders.getOrNull(i)?.toList() ?: emptyList()
                    org.ethereumphone.andyclaw.agent.QuestionAnswer(question.question, order.joinToString(", "), order)
                }
            }
        }
        return org.ethereumphone.andyclaw.agent.AskUserResponse(answers)
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(150)) { it } + fadeIn(tween(150)),
        exit = slideOutVertically(tween(100)) { it } + fadeOut(tween(100)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(accentColor.copy(alpha = 0.15f).compositeOver(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f)))
                .padding(top = 8.dp),
        ) {
            if (q == null) return@Column

            // Header with question counter
            val headerText = if (questions.size > 1) {
                "QUESTION ${currentIndex + 1} / ${questions.size}"
            } else {
                "CLARIFICATION NEEDED"
            }
            Text(
                text = headerText,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = accentColor.copy(alpha = 0.6f),
                    shadow = GlowStyle.body(accentColor),
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Question text
            Text(
                text = q.question,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = accentColor,
                    shadow = GlowStyle.body(accentColor),
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Question body based on type
            when (q.type) {
                org.ethereumphone.andyclaw.agent.QuestionType.SINGLE_SELECT -> {
                    SingleSelectBody(
                        options = q.options,
                        selectedOption = selections.getOrNull(currentIndex)?.firstOrNull(),
                        customText = customTexts.getOrNull(currentIndex) ?: "",
                        accentColor = accentColor,
                        onSelectOption = { opt ->
                            selections.getOrNull(currentIndex)?.apply { clear(); add(opt) }
                            // Clear custom text when an option is selected
                            if (currentIndex < customTexts.size) customTexts[currentIndex] = ""
                        },
                        onCustomTextChanged = { text ->
                            if (currentIndex < customTexts.size) customTexts[currentIndex] = text
                            // Clear option selection when typing custom
                            if (text.isNotEmpty()) selections.getOrNull(currentIndex)?.clear()
                        },
                    )
                }
                org.ethereumphone.andyclaw.agent.QuestionType.MULTI_SELECT -> {
                    MultiSelectBody(
                        options = q.options,
                        selectedOptions = selections.getOrNull(currentIndex) ?: mutableListOf(),
                        customText = customTexts.getOrNull(currentIndex) ?: "",
                        accentColor = accentColor,
                        onToggleOption = { opt ->
                            selections.getOrNull(currentIndex)?.let { sel ->
                                if (opt in sel) sel.remove(opt) else sel.add(opt)
                            }
                        },
                        onCustomTextChanged = { text ->
                            if (currentIndex < customTexts.size) customTexts[currentIndex] = text
                        },
                    )
                }
                org.ethereumphone.andyclaw.agent.QuestionType.RANKED_CHOICE -> {
                    RankedChoiceBody(
                        order = rankedOrders.getOrNull(currentIndex) ?: mutableListOf(),
                        accentColor = accentColor,
                        onMoveUp = { idx ->
                            rankedOrders.getOrNull(currentIndex)?.let { list ->
                                if (idx > 0) {
                                    val item = list.removeAt(idx)
                                    list.add(idx - 1, item)
                                }
                            }
                        },
                        onMoveDown = { idx ->
                            rankedOrders.getOrNull(currentIndex)?.let { list ->
                                if (idx < list.size - 1) {
                                    val item = list.removeAt(idx)
                                    list.add(idx + 1, item)
                                }
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Navigation / action row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left: SKIP or PREV
                if (currentIndex > 0) {
                    Text(
                        text = "[PREV]",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = accentColor.copy(alpha = 0.5f), shadow = GlowStyle.body(accentColor)),
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentIndex--
                        },
                    )
                } else {
                    Text(
                        text = "[SKIP]",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = accentColor.copy(alpha = 0.5f), shadow = GlowStyle.body(accentColor)),
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSkip()
                        },
                    )
                }

                // Right: NEXT or SEND
                if (isLastQuestion) {
                    val canSubmit = hasAnswer
                    Text(
                        text = if (canSubmit) "[SEND]" else "[SKIP]",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            color = if (canSubmit) accentColor else accentColor.copy(alpha = 0.5f),
                            shadow = GlowStyle.body(accentColor),
                        ),
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (canSubmit) onSubmit(buildResponse()) else onSkip()
                        },
                    )
                } else {
                    Text(
                        text = "[NEXT]",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = accentColor, shadow = GlowStyle.body(accentColor)),
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentIndex++
                        },
                    )
                }
            }
        }
    }
}

// ── Single Select: tap an option or type custom at bottom ────────────

@Composable
private fun SingleSelectBody(
    options: List<String>,
    selectedOption: String?,
    customText: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onSelectOption: (String) -> Unit,
    onCustomTextChanged: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(options, key = { it }) { option ->
                val isSelected = option == selectedOption
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelectOption(option) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isSelected) "●" else "○",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = accentColor),
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = option,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                            color = if (isSelected) accentColor else accentColor.copy(alpha = 0.7f),
                            shadow = if (isSelected) GlowStyle.body(accentColor) else null,
                        ),
                    )
                }
            }
        }
        // Custom text input at bottom (acts as keyboard option)
        androidx.compose.material3.OutlinedTextField(
            value = customText,
            onValueChange = onCustomTextChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = {
                Text("Or type a custom answer...", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = accentColor.copy(alpha = 0.3f)))
            },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor.copy(alpha = 0.5f), unfocusedBorderColor = accentColor.copy(alpha = 0.2f),
                focusedTextColor = androidx.compose.ui.graphics.Color.White, unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
                cursorColor = accentColor,
            ),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            singleLine = true,
        )
    }
}

// ── Multi Select: checkboxes + editable custom entry at bottom ───────

@Composable
private fun MultiSelectBody(
    options: List<String>,
    selectedOptions: MutableList<String>,
    customText: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onToggleOption: (String) -> Unit,
    onCustomTextChanged: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(options, key = { it }) { option ->
                val isSelected = option in selectedOptions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleOption(option) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (isSelected) "☑" else "☐",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = accentColor),
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = option,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                            color = if (isSelected) accentColor else accentColor.copy(alpha = 0.7f),
                            shadow = if (isSelected) GlowStyle.body(accentColor) else null,
                        ),
                    )
                }
            }
        }
        // Editable custom entry at bottom
        androidx.compose.material3.OutlinedTextField(
            value = customText,
            onValueChange = onCustomTextChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = {
                Text("Add custom entry...", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = accentColor.copy(alpha = 0.3f)))
            },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor.copy(alpha = 0.5f), unfocusedBorderColor = accentColor.copy(alpha = 0.2f),
                focusedTextColor = androidx.compose.ui.graphics.Color.White, unfocusedTextColor = androidx.compose.ui.graphics.Color.White,
                cursorColor = accentColor,
            ),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            singleLine = true,
        )
    }
}

// ── Ranked Choice: reorder items with up/down arrows ─────────────────

@Composable
private fun RankedChoiceBody(
    order: MutableList<String>,
    accentColor: androidx.compose.ui.graphics.Color,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
        items(order.size, key = { order[it] }) { idx ->
            val item = order[idx]
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Rank number
                Text(
                    text = "${idx + 1}.",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = accentColor, shadow = GlowStyle.body(accentColor)),
                    modifier = Modifier.width(28.dp),
                )
                // Item name
                Text(
                    text = item,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = accentColor.copy(alpha = 0.8f)),
                    modifier = Modifier.weight(1f),
                )
                // Up arrow
                Text(
                    text = "▲",
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = if (idx > 0) accentColor else accentColor.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, enabled = idx > 0) { onMoveUp(idx) }
                        .padding(horizontal = 8.dp),
                )
                // Down arrow
                Text(
                    text = "▼",
                    style = TextStyle(
                        fontSize = 16.sp,
                        color = if (idx < order.size - 1) accentColor else accentColor.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, enabled = idx < order.size - 1) { onMoveDown(idx) }
                        .padding(horizontal = 8.dp),
                )
            }
        }
    }
}
