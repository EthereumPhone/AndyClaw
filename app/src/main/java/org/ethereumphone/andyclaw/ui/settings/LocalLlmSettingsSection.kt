package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.dgenlibrary.ui.theme.dgenWhite
import org.ethereumphone.andyclaw.llm.GgufModel
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.components.DgenSquareSwitch

/**
 * Local-LLM (on-device, llama.cpp via Llamatik) settings sub-page.
 *
 * Three blocks:
 *  - **Active model**: list of GGUFs in `filesDir/models/`, pick one, import a
 *    new one from a SAF picker, delete imported ones.
 *  - **Sampling**: temperature / top_p / top_k / max_tokens / repeat_penalty —
 *    pushed to llama.cpp per-generation via `updateGenerateParams`, no reload.
 *  - **Hardware**: n_ctx / n_batch / n_threads / n_gpu_layers / use_mmap —
 *    applied at init via `initGenerateModelWithConfig`. `LocalLlmClient`
 *    detects a config change and reloads the model on the next request.
 */
@Composable
fun LocalLlmSettingsContent(
    viewModel: SettingsViewModel,
    onImportClick: () -> Unit,
    primaryColor: Color,
    sectionTitleStyle: TextStyle,
    contentTitleStyle: TextStyle,
    contentBodyStyle: TextStyle,
) {
    val models by viewModel.ggufModels.collectAsState()
    val selected by viewModel.selectedGgufFilename.collectAsState()

    val temperature   by viewModel.localLlmTemperature.collectAsState()
    val topP          by viewModel.localLlmTopP.collectAsState()
    val topK          by viewModel.localLlmTopK.collectAsState()
    val maxTokens     by viewModel.localLlmMaxTokens.collectAsState()
    val repeatPenalty by viewModel.localLlmRepeatPenalty.collectAsState()

    val nCtx       by viewModel.localLlmNCtx.collectAsState()
    val nBatch     by viewModel.localLlmNBatch.collectAsState()
    val nThreads   by viewModel.localLlmNThreads.collectAsState()
    val nGpuLayers by viewModel.localLlmNGpuLayers.collectAsState()
    val useMmap    by viewModel.localLlmUseMmap.collectAsState()

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
    ) {
        // ── Active model ──────────────────────────────────────────────
        Text("ACTIVE MODEL", color = primaryColor, style = sectionTitleStyle)
        Spacer(Modifier.height(8.dp))
        if (models.isEmpty()) {
            Text(
                "No GGUF available yet. Download the default model in AI Provider settings, or import your own below.",
                color = dgenWhite,
                style = contentBodyStyle,
            )
        } else {
            models.forEach { m ->
                ModelRow(
                    model = m,
                    isSelected = m.filename == selected,
                    onSelect = { viewModel.selectGguf(m.filename) },
                    onDelete = if (!m.isBuiltin) ({ viewModel.deleteImportedGguf(m.filename) }) else null,
                    primaryColor = primaryColor,
                    contentTitleStyle = contentTitleStyle,
                    contentBodyStyle = contentBodyStyle,
                )
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        DgenSmallPrimaryButton(
            text = "Import .gguf…",
            primaryColor = primaryColor,
            onClick = onImportClick,
        )

        Spacer(Modifier.height(24.dp))

        // ── Sampling ──────────────────────────────────────────────────
        Text("SAMPLING", color = primaryColor, style = sectionTitleStyle)
        Text(
            "Applied per-generation. No model reload.",
            color = dgenWhite.copy(alpha = 0.6f),
            style = contentBodyStyle,
        )
        Spacer(Modifier.height(4.dp))
        FloatSlider("Temperature", temperature, 0f..2f, 0.05f, viewModel::setLocalLlmTemperature,
            primaryColor, contentTitleStyle, contentBodyStyle)
        FloatSlider("Top P", topP, 0f..1f, 0.01f, viewModel::setLocalLlmTopP,
            primaryColor, contentTitleStyle, contentBodyStyle)
        IntInput("Top K", topK, viewModel::setLocalLlmTopK, primaryColor, contentTitleStyle)
        IntInput("Max tokens", maxTokens, viewModel::setLocalLlmMaxTokens, primaryColor, contentTitleStyle)
        FloatSlider("Repeat penalty", repeatPenalty, 0.5f..2f, 0.01f, viewModel::setLocalLlmRepeatPenalty,
            primaryColor, contentTitleStyle, contentBodyStyle)

        Spacer(Modifier.height(24.dp))

        // ── Hardware ──────────────────────────────────────────────────
        Text("HARDWARE", color = primaryColor, style = sectionTitleStyle)
        Text(
            "Applied at model init. Changing any of these reloads the model on next message.",
            color = dgenWhite.copy(alpha = 0.6f),
            style = contentBodyStyle,
        )
        Spacer(Modifier.height(4.dp))
        IntInput("Context size (n_ctx)", nCtx, viewModel::setLocalLlmNCtx,
            primaryColor, contentTitleStyle)
        IntInput("Batch size (n_batch)", nBatch, viewModel::setLocalLlmNBatch,
            primaryColor, contentTitleStyle)
        IntInput("Threads (0 = auto)", nThreads, viewModel::setLocalLlmNThreads,
            primaryColor, contentTitleStyle)
        IntInput("GPU layers (0 = CPU, -1 = all)", nGpuLayers, viewModel::setLocalLlmNGpuLayers,
            primaryColor, contentTitleStyle)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("USE MMAP", color = primaryColor, style = contentTitleStyle)
                Text(
                    "Memory-map the GGUF instead of reading into RAM. Lower RAM, slightly slower startup.",
                    color = dgenWhite,
                    style = contentBodyStyle,
                )
            }
            Spacer(Modifier.width(16.dp))
            DgenSquareSwitch(
                checked = useMmap,
                onCheckedChange = viewModel::setLocalLlmUseMmap,
                activeColor = primaryColor,
            )
        }

        Spacer(Modifier.height(24.dp))
        DgenSmallPrimaryButton(
            text = "Reset to defaults",
            primaryColor = primaryColor,
            onClick = { viewModel.resetLocalLlmConfig() },
        )
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun ModelRow(
    model: GgufModel,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
    primaryColor: Color,
    contentTitleStyle: TextStyle,
    contentBodyStyle: TextStyle,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) primaryColor.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onSelect() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isSelected) "● ${model.displayName}" else "○ ${model.displayName}",
                color = primaryColor,
                style = contentTitleStyle,
            )
            Text(
                text = "${formatSize(model.sizeBytes)}${if (model.isBuiltin) " · builtin" else " · imported"}",
                color = dgenWhite,
                style = contentBodyStyle,
            )
        }
        if (onDelete != null) {
            DgenSmallPrimaryButton(
                text = "Delete",
                primaryColor = primaryColor,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun FloatSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    primaryColor: Color,
    contentTitleStyle: TextStyle,
    contentBodyStyle: TextStyle,
) {
    val steps = ((range.endInclusive - range.start) / step).toInt() - 1
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = primaryColor, style = contentTitleStyle,
                modifier = Modifier.weight(1f))
            Text("%.2f".format(value), color = dgenWhite, style = contentBodyStyle)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps.coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = primaryColor,
                activeTrackColor = primaryColor,
                inactiveTrackColor = primaryColor.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun IntInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    primaryColor: Color,
    contentTitleStyle: TextStyle,
) {
    // Local edit state, *not* re-keyed on every external value change — keying on
    // [value] would reset the field (and the cursor) on every keystroke that
    // round-trips through the ViewModel. Sync from external only when [value]
    // diverges from what the user has typed (e.g. Reset-to-defaults button).
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), color = primaryColor, style = contentTitleStyle,
            modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                val cleaned = newText.filter { it.isDigit() || (it == '-' && newText.indexOf(it) == 0) }
                text = cleaned
                cleaned.toIntOrNull()?.let(onValueChange)
            },
            modifier = Modifier.width(110.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = dgenWhite,
                unfocusedTextColor = dgenWhite,
                focusedBorderColor = primaryColor,
                unfocusedBorderColor = primaryColor.copy(alpha = 0.5f),
                cursorColor = primaryColor,
            ),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000     -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000         -> "%.1f KB".format(bytes / 1_000.0)
    else                   -> "$bytes B"
}
