package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.PitagonsSans
import com.example.dgenlibrary.ui.theme.SpaceMono
import com.example.dgenlibrary.ui.theme.dgenWhite
import com.example.dgenlibrary.ui.theme.label_fontSize
import org.ethereumphone.andyclaw.agent.BudgetPreset
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.DgenSquareSwitch
import org.ethereumphone.andyclaw.ui.components.GlowStyle
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Compact budget mode section shown on the main settings screen.
 * Shows the enable toggle, the selected preset name (clickable to navigate),
 * and a "Modify Preset" button.
 */
@Composable
fun BudgetModeSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    selectedPresetName: String,
    onNavigateToPresetSelection: () -> Unit,
    onNavigateToPresetEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = SystemColorManager.primaryColor
    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)
    val rowControlSpacing = 20.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        // ── Section title ──────────────────────────────────────────────
        Text(
            text = "BUDGET MODE",
            color = primaryColor,
            style = sectionTitleStyle,
        )
        Spacer(Modifier.height(8.dp))

        // ── Enable toggle row ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ENABLE BUDGET MODE",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = "Reduces output token usage by dynamically adjusting response limits, encouraging concise replies, and optimizing tool call patterns.",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }
            Spacer(Modifier.width(rowControlSpacing))
            DgenSquareSwitch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                activeColor = primaryColor,
            )
        }

        // ── Everything below is only visible when budget mode is enabled ──
        if (!enabled) return@Column

        Spacer(Modifier.height(4.dp))

        // ── Selected preset display (clickable to navigate) ──────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToPresetSelection)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "BUDGET PRESET",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = selectedPresetName,
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }
            Spacer(Modifier.width(rowControlSpacing))
            Text(
                text = ">",
                style = TextStyle(
                    fontFamily = SpaceMono,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = primaryColor,
            )
        }

        // ── Modify preset button ─────────────────────────────────────
        Spacer(Modifier.height(4.dp))
        DgenSmallPrimaryButton(
            text = "Modify Preset",
            primaryColor = primaryColor,
            onClick = onNavigateToPresetEditor,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Sub-screen: Preset Selection
// ═══════════════════════════════════════════════════════════════════════

/**
 * Full-screen preset selection list for budget mode presets.
 * Each preset is a row with name, subtitle, and selected indicator.
 * Includes a "New Preset" button at the bottom.
 */
@Composable
fun BudgetPresetSelectionScreen(
    presets: List<BudgetPreset>,
    selectedPresetId: String,
    onSelectPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onRevertPreset: (String) -> Unit,
    onCreateNewPreset: () -> Unit,
    primaryColor: Color,
) {
    val stockDefaults = remember { BudgetPreset.defaults().associateBy { it.id } }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(presets) { preset ->
            val isSelected = preset.id == selectedPresetId
            val isModifiedStock = preset.isStock &&
                stockDefaults[preset.id]?.let { it != preset } == true

            val subtitle = when (preset.id) {
                "stock_off" -> "All optimizations disabled. Full token usage."
                "stock_balanced" -> "Dynamic max tokens, concise prompts, parallel tools, and tool truncation. Best balance of savings and quality."
                "stock_aggressive" -> "All optimizations enabled including history summarization and thinking budget. Maximum savings."
                else -> {
                    val enabledCount = listOf(
                        preset.dynamicMaxTokens,
                        preset.concisePrompt,
                        preset.parallelToolCalls,
                        preset.noPreambleToolCalls,
                        preset.historySummarization,
                        preset.thinkingBudget,
                        preset.toolResultTruncation,
                    ).count { it }
                    "$enabledCount optimization${if (enabledCount != 1) "s" else ""} enabled"
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectPreset(preset.id) }
                    .padding(vertical = 16.dp, horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = preset.name,
                            style = TextStyle(
                                fontFamily = SpaceMono,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor,
                                shadow = if (isSelected) GlowStyle.title(primaryColor) else null,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            style = TextStyle(
                                fontFamily = PitagonsSans,
                                fontSize = label_fontSize,
                                fontWeight = FontWeight.SemiBold,
                                color = dgenWhite.copy(alpha = 0.7f),
                            ),
                        )
                    }

                    // Actions: revert / delete / selected dot
                    if (preset.isStock && isModifiedStock) {
                        DgenSmallPrimaryButton(
                            text = "Revert",
                            primaryColor = primaryColor,
                            onClick = { onRevertPreset(preset.id) },
                        )
                    } else if (!preset.isStock) {
                        DgenSmallPrimaryButton(
                            text = "Delete",
                            primaryColor = Color(0xFFFF4444),
                            onClick = { onDeletePreset(preset.id) },
                        )
                    }

                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    primaryColor,
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
            HorizontalDivider(color = primaryColor.copy(alpha = 0.1f))
        }

        item {
            Spacer(Modifier.height(16.dp))
            DgenSmallPrimaryButton(
                text = "New Preset",
                primaryColor = primaryColor,
                onClick = onCreateNewPreset,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Sub-screen: Preset Editor
// ═══════════════════════════════════════════════════════════════════════

/**
 * Full-screen editor for a budget preset's optimizations.
 * Each optimization has a toggle; some have additional parameter sliders.
 */
@Composable
fun BudgetPresetEditorScreen(
    preset: BudgetPreset,
    onSave: (BudgetPreset) -> Unit,
    onDone: () -> Unit,
    primaryColor: Color,
) {
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)
    val rowControlSpacing = 20.dp

    var workingCopy by remember(preset.id) { mutableStateOf(preset) }
    var hasChanges by remember(preset.id) { mutableStateOf(false) }

    data class OptimizationItem(
        val title: String,
        val description: String,
        val isEnabled: Boolean,
        val onToggle: (Boolean) -> Unit,
    )

    val optimizations = listOf(
        OptimizationItem(
            title = "DYNAMIC MAX TOKENS",
            description = "Automatically lower the max_tokens limit based on query complexity. Simple queries get smaller budgets.",
            isEnabled = workingCopy.dynamicMaxTokens,
            onToggle = { workingCopy = workingCopy.copy(dynamicMaxTokens = it); hasChanges = true },
        ),
        OptimizationItem(
            title = "CONCISE PROMPTS",
            description = "Add instructions to the system prompt that encourage shorter, more direct responses.",
            isEnabled = workingCopy.concisePrompt,
            onToggle = { workingCopy = workingCopy.copy(concisePrompt = it); hasChanges = true },
        ),
        OptimizationItem(
            title = "NO-PREAMBLE TOOL CALLS",
            description = "Skip explanatory text before tool call JSON, reducing unnecessary output tokens.",
            isEnabled = workingCopy.noPreambleToolCalls,
            onToggle = { workingCopy = workingCopy.copy(noPreambleToolCalls = it); hasChanges = true },
        ),
        OptimizationItem(
            title = "TOOL RESULT TRUNCATION",
            description = "Truncate large tool results before feeding them back, reducing follow-up response length.",
            isEnabled = workingCopy.toolResultTruncation,
            onToggle = { workingCopy = workingCopy.copy(toolResultTruncation = it); hasChanges = true },
        ),
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        item {
            Text(
                text = "OPTIMIZATIONS",
                style = contentTitleStyle,
                color = primaryColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Toggle individual output-token-saving optimizations for this preset.",
                style = contentBodyStyle,
                color = dgenWhite.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(16.dp))
        }

        items(optimizations.size) { index ->
            val item = optimizations[index]

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = contentTitleStyle,
                            color = if (item.isEnabled) primaryColor else dgenWhite.copy(alpha = 0.6f),
                        )
                        Text(
                            text = item.description,
                            style = contentBodyStyle,
                            color = dgenWhite.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSquareSwitch(
                        checked = item.isEnabled,
                        onCheckedChange = item.onToggle,
                        activeColor = primaryColor,
                    )
                }

                // ── Tool Result Truncation slider ────────────────────────
                if (item.title == "TOOL RESULT TRUNCATION" && workingCopy.toolResultTruncation) {
                    var sliderValue by remember(workingCopy.id) {
                        mutableFloatStateOf(workingCopy.toolResultMaxChars.toFloat())
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Max chars",
                                style = contentBodyStyle,
                                color = dgenWhite.copy(alpha = 0.7f),
                            )
                            Text(
                                text = "${sliderValue.roundToInt()}",
                                style = TextStyle(
                                    fontFamily = SpaceMono,
                                    fontSize = label_fontSize,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = primaryColor,
                            )
                        }
                        Slider(
                            value = sliderValue,
                            onValueChange = { raw ->
                                val stepped = (raw / 500f).roundToInt() * 500f
                                sliderValue = stepped.coerceIn(1000f, 5000f)
                            },
                            onValueChangeFinished = {
                                workingCopy = workingCopy.copy(toolResultMaxChars = sliderValue.roundToInt())
                                hasChanges = true
                            },
                            valueRange = 1000f..5000f,
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = primaryColor.copy(alpha = 0.2f),
                            ),
                        )
                    }
                }

                HorizontalDivider(color = primaryColor.copy(alpha = 0.08f))
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DgenSmallPrimaryButton(
                    text = if (hasChanges) "Save & Done" else "Done",
                    primaryColor = primaryColor,
                    onClick = {
                        if (hasChanges) {
                            onSave(workingCopy)
                        }
                        onDone()
                    },
                )
            }
        }
    }
}
