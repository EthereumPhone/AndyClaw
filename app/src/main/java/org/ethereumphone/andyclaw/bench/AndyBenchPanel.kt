package org.ethereumphone.andyclaw.bench

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.NodeApp

/** Start AndyBench and watch it run. Lives on the Agent Display developer screen. */
@Composable
fun AndyBenchPanel(titleColor: Color, textColor: Color) {
    val app = LocalContext.current.applicationContext as NodeApp
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }

    fun start(mode: AndyBench.Mode) {
        running = true
        lines.clear()
        scope.launch {
            try {
                AndyBench.run(app, mode, runs = 3) { line -> lines.add(0, line) }
            } finally {
                running = false
            }
        }
    }

    Column {
        Text("ANDYBENCH", color = titleColor)
        Spacer(Modifier.height(4.dp))
        Text(
            "Runs ${AndyBench.TASKS.size} Settings/Clock/Calculator tasks 3× each, timed and checked against " +
                "device state. Takes several minutes; uses your model credit.",
            color = textColor, fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(enabled = !running, onClick = { start(AndyBench.Mode.BASELINE) }) { Text("Baseline") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = !running, onClick = { start(AndyBench.Mode.AUTOPILOT) }) { Text("Autopilot") }
        }
        Spacer(Modifier.height(8.dp))
        lines.take(40).forEach { Text(it, color = textColor, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
