package org.ethereumphone.andyclaw.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ethereumphone.andyclaw.SecurePrefs
import org.ethereumphone.andyclaw.commands.SlashCommand
import org.ethereumphone.andyclaw.commands.SlashCommandExecutor
import org.ethereumphone.andyclaw.commands.SlashCommandRegistry
import org.ethereumphone.andyclaw.commands.SlashCommandResult
import org.ethereumphone.andyclaw.llm.AnthropicModels
import org.ethereumphone.andyclaw.llm.LlmProvider
import org.ethereumphone.andyclaw.ui.components.DgenSquareSwitch
import org.ethereumphone.andyclaw.ui.components.GlowStyle

// ── Overlay modes ─────────────────────────────────────────────────────────

private enum class OverlayMode {
    CommandList,
    CycleView,
}

// ── Main overlay composable ───────────────────────────────────────────────

@Composable
fun SlashCommandOverlay(
    visible: Boolean,
    query: String,
    accentColor: Color,
    prefs: SecurePrefs,
    executor: SlashCommandExecutor,
    onCommandResult: (SlashCommandResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var mode by remember { mutableStateOf(OverlayMode.CommandList) }
    var cycleCommand by remember { mutableStateOf<SlashCommand?>(null) }
    var cycleIndex by remember { mutableIntStateOf(0) }

    // Reset to command list when the user edits the query away from the
    // selected cycle command (e.g. backspaces or types something new).
    // We only reset if the query no longer matches the command at all.
    if (mode == OverlayMode.CycleView) {
        val cmdId = cycleCommand?.id ?: ""
        if (query.isNotEmpty() && !cmdId.startsWith(query, ignoreCase = true)) {
            mode = OverlayMode.CommandList
            cycleCommand = null
        }
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
                .background(accentColor.copy(alpha = 0.15f).compositeOver(Color.Black.copy(alpha = 0.92f)))
                .padding(top = 8.dp),
        ) {
                when (mode) {
                    OverlayMode.CommandList -> {
                        CommandListContent(
                            query = query,
                            accentColor = accentColor,
                            prefs = prefs,
                            onToggle = { cmd ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val result = executor.execute("/${cmd.id}")
                                if (result != null) onCommandResult(result)
                            },
                            onCycleSelected = { cmd ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                cycleCommand = cmd
                                cycleIndex = getCurrentCycleIndex(cmd, prefs)
                                mode = OverlayMode.CycleView
                            },
                            onAction = { cmd ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val result = executor.execute("/${cmd.id}")
                                if (result != null) onCommandResult(result)
                                if (result !is SlashCommandResult.HelpList) {
                                    onDismiss()
                                }
                            },
                        )
                    }

                    OverlayMode.CycleView -> {
                        val cmd = cycleCommand ?: return@Column
                        val options = getCycleOptions(cmd, prefs)
                        if (options.isEmpty()) {
                            Text(
                                text = "No options available",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 16.sp,
                                    color = Color.White.copy(alpha = 0.6f),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            CycleViewContent(
                                command = cmd,
                                options = options,
                                currentIndex = cycleIndex,
                                accentColor = accentColor,
                                onCycle = { newIndex ->
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    cycleIndex = newIndex
                                },
                                onBack = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    mode = OverlayMode.CommandList
                                    cycleCommand = null
                                },
                                onDone = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val result = executor.selectCycleOption(cmd.id, cycleIndex)
                                    onCommandResult(result)
                                    mode = OverlayMode.CommandList
                                    cycleCommand = null
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

// ── Command list mode ─────────────────────────────────────────────────────

@Composable
private fun CommandListContent(
    query: String,
    accentColor: Color,
    prefs: SecurePrefs,
    onToggle: (SlashCommand) -> Unit,
    onCycleSelected: (SlashCommand) -> Unit,
    onAction: (SlashCommand) -> Unit,
) {
    val filtered = SlashCommandRegistry.matching(query)

    if (filtered.isEmpty()) {
        Text(
            text = "No matching commands",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.4f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp),
    ) {
        items(filtered, key = { it.id }) { cmd ->
            CommandRow(
                command = cmd,
                accentColor = accentColor,
                isToggled = if (cmd is SlashCommand.Toggle) getToggleState(cmd, prefs) else null,
                currentValue = if (cmd is SlashCommand.Cycle) getCurrentCycleDisplay(cmd, prefs) else null,
                onClick = {
                    when (cmd) {
                        is SlashCommand.Toggle -> onToggle(cmd)
                        is SlashCommand.Cycle -> onCycleSelected(cmd)
                        is SlashCommand.Action -> onAction(cmd)
                    }
                },
                onToggleChange = { if (cmd is SlashCommand.Toggle) onToggle(cmd) },
            )
        }
    }
}

@Composable
private fun CommandRow(
    command: SlashCommand,
    accentColor: Color,
    isToggled: Boolean?,
    currentValue: String?,
    onClick: () -> Unit,
    onToggleChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Command name
        Text(
            text = "/${command.id}",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp,
                color = Color.White,
                shadow = GlowStyle.body(Color.White),
            ),
            modifier = Modifier.width(140.dp),
        )

        // Description + current value
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = command.description,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.7f),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!currentValue.isNullOrBlank()) {
                Text(
                    text = currentValue,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Normal,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        shadow = GlowStyle.body(Color.White),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right side: toggle switch or chevron
        if (isToggled != null) {
            DgenSquareSwitch(
                checked = isToggled,
                onCheckedChange = { onToggleChange() },
                activeColor = accentColor,
                trackWidth = 40.dp,
                trackHeight = 22.dp,
                thumbSize = 16.dp,
            )
        } else {
            Text(
                text = ">",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.5f),
                ),
            )
        }
    }
}

// ── Cycle view mode ───────────────────────────────────────────────────────

@Composable
private fun CycleViewContent(
    command: SlashCommand,
    options: List<Pair<String, String>>,
    currentIndex: Int,
    accentColor: Color,
    onCycle: (newIndex: Int) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Command label
        Text(
            text = "/${command.id}",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.6f),
                shadow = GlowStyle.body(Color.White),
            ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Tappable cycle area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    val next = (currentIndex + 1) % options.size
                    onCycle(next)
                }
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "<",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 20.sp,
                    color = Color.White.copy(alpha = 0.4f),
                ),
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    val prev = if (currentIndex > 0) currentIndex - 1 else options.size - 1
                    onCycle(prev)
                },
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Current value
            val (_, displayName) = options.getOrElse(currentIndex) { "" to "—" }
            Text(
                text = displayName,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 18.sp,
                    color = Color.White,
                    shadow = GlowStyle.title(Color.White),
                ),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = ">",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 20.sp,
                    color = Color.White.copy(alpha = 0.4f),
                ),
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    val next = (currentIndex + 1) % options.size
                    onCycle(next)
                },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Position indicator
        Text(
            text = "${currentIndex + 1} / ${options.size}",
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.3f),
            ),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Back / Done row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "[BACK]",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    shadow = GlowStyle.body(Color.White),
                ),
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onBack,
                ),
            )
            Text(
                text = "[DONE]",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    color = Color.White,
                    shadow = GlowStyle.body(Color.White),
                ),
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDone,
                ),
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────

private val heartbeatOptions = listOf(
    "-1" to "OFF",
    "15" to "15 min",
    "30" to "30 min",
    "60" to "1 hour",
    "120" to "2 hours",
)

@Composable
private fun getToggleState(cmd: SlashCommand.Toggle, prefs: SecurePrefs): Boolean = when (cmd.id) {
    "yolo" -> prefs.yoloMode.collectAsState().value
    "safety" -> prefs.safetyEnabled.collectAsState().value
    "notify" -> prefs.notificationReplyEnabled.collectAsState().value
    // A plain pref, not a flow: read again whenever any such pref is written.
    "memory" -> prefs.rawStringsVersion.collectAsState().value.let { prefs.getString("memory.autoStore") != "false" }
    "routing" -> prefs.smartRoutingEnabled.collectAsState().value
    else -> false
}

private fun getCycleOptions(cmd: SlashCommand, prefs: SecurePrefs): List<Pair<String, String>> =
    when (cmd.id) {
        "model" -> AnthropicModels.forProvider(prefs.selectedProvider.value)
            .map { it.modelId to it.modelId }
        // The providers Settings offers, in its order: the executor picks by index from the same list.
        "provider" -> providerChoices().map { it.name to it.displayName }
        "heartbeat" -> heartbeatOptions
        "preset" -> prefs.routingPresets.value.map { it.id to it.name }
        else -> emptyList()
    }

private fun getCurrentCycleIndex(cmd: SlashCommand, prefs: SecurePrefs): Int = when (cmd.id) {
    "model" -> {
        val models = AnthropicModels.forProvider(prefs.selectedProvider.value)
        models.indexOfFirst { it.modelId == prefs.selectedModel.value }.coerceAtLeast(0)
    }
    "provider" -> providerChoices().indexOf(prefs.selectedProvider.value).coerceAtLeast(0)
    "heartbeat" -> {
        val minutes = prefs.heartbeatIntervalMinutes.value.toString()
        heartbeatOptions.indexOfFirst { it.first == minutes }.coerceAtLeast(0)
    }
    "preset" -> {
        val presets = prefs.routingPresets.value
        presets.indexOfFirst { it.id == prefs.selectedRoutingPresetId.value }.coerceAtLeast(0)
    }
    else -> 0
}

private fun getCurrentCycleDisplay(cmd: SlashCommand, prefs: SecurePrefs): String? = when (cmd.id) {
    "model" -> prefs.selectedModel.value.ifBlank { null }
    "provider" -> prefs.selectedProvider.value.displayName
    "heartbeat" -> {
        val minutes = prefs.heartbeatIntervalMinutes.value.toString()
        heartbeatOptions.firstOrNull { it.first == minutes }?.second ?: minutes
    }
    "preset" -> {
        val presets = prefs.routingPresets.value
        presets.firstOrNull { it.id == prefs.selectedRoutingPresetId.value }?.name
    }
    else -> null
}

private fun providerChoices() =
    org.ethereumphone.andyclaw.ui.settings.ProviderSwitch.choices(
        org.ethereumphone.andyclaw.skills.tier.OsCapabilities.hasPrivilegedAccess
    )
