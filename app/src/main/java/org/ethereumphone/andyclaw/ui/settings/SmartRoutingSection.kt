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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.ethereumphone.andyclaw.skills.AndyClawSkill
import org.ethereumphone.andyclaw.skills.RoutingMode
import org.ethereumphone.andyclaw.skills.RoutingPreset
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.DgenSquareSwitch
import org.ethereumphone.andyclaw.ui.components.GlowStyle
import java.util.UUID

/**
 * Compact smart routing section shown on the main settings screen.
 * Shows the enable toggle, the selected preset name (clickable to navigate),
 * and a "Modify Preset" button.
 */
@Composable
fun SmartRoutingSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    routingMode: RoutingMode,
    onRoutingModeChange: (RoutingMode) -> Unit,
    onNavigateToRoutingModeSelection: () -> Unit,
    selectedPresetName: String,
    onNavigateToPresetSelection: () -> Unit,
    onNavigateToPresetEditor: () -> Unit,
    useSameModel: Boolean,
    onUseSameModelChange: (Boolean) -> Unit,
    routingModelName: String,
    routingProviderName: String,
    onNavigateToRoutingProvider: () -> Unit,
    onNavigateToRoutingModel: () -> Unit,
    // Model routing (difficulty-based model selection)
    modelRoutingEnabled: Boolean = false,
    onModelRoutingEnabledChange: (Boolean) -> Unit = {},
    modelRoutingLightName: String = "Auto",
    modelRoutingStandardName: String = "Auto",
    modelRoutingPowerfulName: String = "Auto",
    onNavigateToModelRoutingLight: () -> Unit = {},
    onNavigateToModelRoutingStandard: () -> Unit = {},
    onNavigateToModelRoutingPowerful: () -> Unit = {},
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
            text = "SMART ROUTING",
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
                    text = "ENABLE SMART ROUTING",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = "Uses an LLM to intelligently select only the skills and tools needed for each query, reducing token usage by up to 76%.",
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

        // ── Everything below is only visible when routing is enabled ──
        if (!enabled) return@Column

        Spacer(Modifier.height(4.dp))

        // ── Routing mode (clickable row to navigate) ─────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateToRoutingModeSelection)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ROUTING MODE",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = routingMode.name.lowercase().replaceFirstChar { it.uppercase() },
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
                    text = "ALWAYS-ON SKILLS PRESET",
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

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = primaryColor.copy(alpha = 0.15f))
        Spacer(Modifier.height(12.dp))

        // ── Routing model toggle ────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "USE SAME MODEL AS MAIN",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = "Use the same provider as the main model for routing. Disable to choose a cheaper/faster model.",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }
            Spacer(Modifier.width(rowControlSpacing))
            DgenSquareSwitch(
                checked = useSameModel,
                onCheckedChange = onUseSameModelChange,
                activeColor = primaryColor,
            )
        }

        // ── Custom routing provider/model selection ──────────────────
        if (!useSameModel) {
            // Provider row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToRoutingProvider)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ROUTING PROVIDER",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = routingProviderName,
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

            // Model row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToRoutingModel)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ROUTING MODEL",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = routingModelName,
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
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = primaryColor.copy(alpha = 0.15f))
        Spacer(Modifier.height(12.dp))

        // ── Model routing (difficulty-based model selection) ─────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MODEL ROUTING",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
                Text(
                    text = "Automatically switch to cheaper models for simple tasks and more powerful models for complex ones.",
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            }
            Spacer(Modifier.width(rowControlSpacing))
            DgenSquareSwitch(
                checked = modelRoutingEnabled,
                onCheckedChange = onModelRoutingEnabledChange,
                activeColor = primaryColor,
            )
        }

        if (modelRoutingEnabled) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Select a preferred model for each difficulty tier, or leave on Auto to let the router decide based on pricing and capability.",
                style = contentBodyStyle.copy(fontSize = 13.sp),
                color = dgenWhite.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(8.dp))

            // Light tier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToModelRoutingLight)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "EASY TASKS",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = modelRoutingLightName,
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                Text(text = ">", style = TextStyle(fontFamily = SpaceMono, fontSize = 20.sp, fontWeight = FontWeight.Bold), color = primaryColor)
            }

            // Standard tier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToModelRoutingStandard)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MEDIUM TASKS",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = modelRoutingStandardName,
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                Text(text = ">", style = TextStyle(fontFamily = SpaceMono, fontSize = 20.sp, fontWeight = FontWeight.Bold), color = primaryColor)
            }

            // Powerful tier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToModelRoutingPowerful)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HARD TASKS",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = modelRoutingPowerfulName,
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                }
                Spacer(Modifier.width(rowControlSpacing))
                Text(text = ">", style = TextStyle(fontFamily = SpaceMono, fontSize = 20.sp, fontWeight = FontWeight.Bold), color = primaryColor)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Sub-screen: Preset Selection
// ═══════════════════════════════════════════════════════════════════════

/**
 * Full-screen preset selection list, matching the model/provider selection pattern.
 * Each preset is a [SelectionRow]-style row with name, subtitle, and selected indicator.
 * Includes a "New Preset" button at the bottom.
 */
@Composable
fun RoutingPresetSelectionScreen(
    presets: List<RoutingPreset>,
    selectedPresetId: String,
    onSelectPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onRevertPreset: (String) -> Unit,
    onCreateNewPreset: () -> Unit,
    primaryColor: Color,
) {
    val stockDefaults = remember { RoutingPreset.defaults().associateBy { it.id } }

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
                "stock_full_llm" -> "No always-on skills. Relies entirely on the LLM router for every query."
                "stock_minimal" -> "Only code execution and memory are always active. Best balance of savings and reliability."
                "stock_expanded" -> "Includes device info, apps, shell, clipboard, settings, and all dGEN1 hardware skills."
                else -> {
                    val coreCount = preset.coreSkillIds.size + preset.coreDgen1SkillIds.size
                    val toolOverrides = preset.alwaysIncludeTools.size
                    buildString {
                        append("$coreCount always-on skill${if (coreCount != 1) "s" else ""}")
                        if (toolOverrides > 0) {
                            append(", $toolOverrides with tool filters")
                        }
                    }
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
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    primaryColor,
                                    shape = androidx.compose.foundation.shape.CircleShape,
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
// Sub-screen: Routing Mode Selection
// ═══════════════════════════════════════════════════════════════════════

/**
 * Full-screen routing mode selection list, matching the preset selection pattern.
 */
@Composable
fun RoutingModeSelectionScreen(
    selectedMode: RoutingMode,
    onSelectMode: (RoutingMode) -> Unit,
    primaryColor: Color,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(RoutingMode.entries.toList()) { mode ->
            val isSelected = mode == selectedMode
            val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
            val subtitle = when (mode) {
                RoutingMode.STANDARD -> "Includes all tools from matched skills with full dependency expansion. Most reliable."
                RoutingMode.BALANCED -> "Includes all tools from matched skills with precise cross-skill dependencies. Best balance of savings and reliability."
                RoutingMode.STRICT -> "Experimental. Only includes specific tools the router selects. Maximum token savings but may reduce accuracy."
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectMode(mode) }
                    .padding(vertical = 16.dp, horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
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

                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    primaryColor,
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                ),
                        )
                    }
                }
            }
            HorizontalDivider(color = primaryColor.copy(alpha = 0.1f))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Sub-screen: Preset Editor
// ═══════════════════════════════════════════════════════════════════════

/**
 * Full-screen editor for a routing preset's always-on skills and tools.
 */
@Composable
fun RoutingPresetEditorScreen(
    preset: RoutingPreset,
    allSkills: List<AndyClawSkill>,
    onSave: (RoutingPreset) -> Unit,
    onDone: () -> Unit,
    primaryColor: Color,
) {
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)
    val rowControlSpacing = 20.dp

    var workingCopy by remember(preset.id) { mutableStateOf(preset) }
    var hasChanges by remember(preset.id) { mutableStateOf(false) }
    var expandedSkillId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        item {
            Text(
                text = "ALWAYS-ON SKILLS",
                style = contentTitleStyle,
                color = primaryColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Skills toggled on will always be included in every query, bypassing the LLM router. Tap a skill to configure individual tools.",
                style = contentBodyStyle,
                color = dgenWhite.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(16.dp))
        }

        items(allSkills) { skill ->
            val wc = workingCopy
            val isAlwaysOn = skill.id in wc.coreSkillIds ||
                skill.id in wc.coreDgen1SkillIds
            val isExpanded = expandedSkillId == skill.id && isAlwaysOn

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isAlwaysOn) {
                                Modifier.clickable {
                                    expandedSkillId = if (isExpanded) null else skill.id
                                }
                            } else Modifier,
                        )
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = skill.name.uppercase(),
                            style = contentBodyStyle,
                            color = if (isAlwaysOn) primaryColor else dgenWhite.copy(alpha = 0.6f),
                        )
                        if (isAlwaysOn) {
                            val toolInfo = wc.alwaysIncludeTools[skill.id]
                            val allToolCount = skill.baseManifest.tools.size +
                                (skill.privilegedManifest?.tools?.size ?: 0)
                            Text(
                                text = if (toolInfo != null) "${toolInfo.size}/$allToolCount tools" else "All $allToolCount tools",
                                style = contentBodyStyle.copy(fontSize = 13.sp),
                                color = dgenWhite.copy(alpha = 0.5f),
                            )
                        }
                    }
                    Spacer(Modifier.width(rowControlSpacing))
                    DgenSquareSwitch(
                        checked = isAlwaysOn,
                        onCheckedChange = { nowOn ->
                            val newCore = if (nowOn) {
                                wc.coreSkillIds + skill.id
                            } else {
                                wc.coreSkillIds - skill.id
                            }
                            val newDgen1 = if (!nowOn) {
                                wc.coreDgen1SkillIds - skill.id
                            } else {
                                wc.coreDgen1SkillIds
                            }
                            val newTools = if (!nowOn) {
                                wc.alwaysIncludeTools - skill.id
                            } else {
                                wc.alwaysIncludeTools
                            }
                            workingCopy = wc.copy(
                                coreSkillIds = newCore,
                                coreDgen1SkillIds = newDgen1,
                                alwaysIncludeTools = newTools,
                            )
                            hasChanges = true
                            if (!nowOn && expandedSkillId == skill.id) {
                                expandedSkillId = null
                            }
                        },
                        activeColor = primaryColor,
                    )
                }

                // Expandable tool drawer
                if (isExpanded) {
                    val allTools = skill.baseManifest.tools +
                        (skill.privilegedManifest?.tools.orEmpty())
                    val toolAllowList = wc.alwaysIncludeTools[skill.id]

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, bottom = 8.dp),
                    ) {
                        allTools.distinctBy { it.name }.forEach { tool ->
                            val toolEnabled = toolAllowList == null ||
                                tool.name in toolAllowList

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tool.name,
                                    style = contentBodyStyle.copy(fontSize = 14.sp),
                                    color = if (toolEnabled) dgenWhite else dgenWhite.copy(alpha = 0.4f),
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(rowControlSpacing))
                                DgenSquareSwitch(
                                    checked = toolEnabled,
                                    onCheckedChange = { nowOn ->
                                        val currentSet = wc.alwaysIncludeTools[skill.id]
                                            ?: allTools.map { it.name }.toSet()
                                        val newSet = if (nowOn) {
                                            currentSet + tool.name
                                        } else {
                                            currentSet - tool.name
                                        }
                                        val newToolsMap = if (newSet.size == allTools.distinctBy { it.name }.size) {
                                            wc.alwaysIncludeTools - skill.id
                                        } else {
                                            wc.alwaysIncludeTools + (skill.id to newSet)
                                        }
                                        workingCopy = wc.copy(
                                            alwaysIncludeTools = newToolsMap,
                                        )
                                        hasChanges = true
                                    },
                                    activeColor = primaryColor,
                                    trackWidth = 40.dp,
                                    trackHeight = 22.dp,
                                    thumbSize = 16.dp,
                                )
                            }
                        }
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
