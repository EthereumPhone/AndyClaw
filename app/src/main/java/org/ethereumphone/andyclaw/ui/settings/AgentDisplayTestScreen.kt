package org.ethereumphone.andyclaw.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.IAgentDisplayService
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AgentDisplayTest"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDisplayTestScreen(
    onNavigateBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("Not connected") }
    var displayId by remember { mutableIntStateOf(-1) }
    var displayBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageInfo by remember { mutableStateOf("") }
    var accessibilityTree by remember { mutableStateOf("") }
    var packageToLaunch by remember { mutableStateOf("com.android.settings") }
    var tapX by remember { mutableStateOf("360") }
    var tapY by remember { mutableStateOf("640") }
    var textToInput by remember { mutableStateOf("hello") }
    var viewIdToClick by remember { mutableStateOf("") }

    val service = remember {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "agentdisplay") as? IBinder
            if (binder != null) {
                IAgentDisplayService.Stub.asInterface(binder)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get AgentDisplayService", e)
            null
        }
    }

    fun refreshScreenshot() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val frame = service?.captureFrame()
                    if (frame == null) {
                        statusText = "Capture returned null (no content yet?)"
                        return@withContext
                    }
                    val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size)
                    if (bitmap == null) {
                        statusText = "Failed to decode frame"
                        return@withContext
                    }
                    displayBitmap = bitmap
                    imageInfo = "JPEG from service: ${frame.size} bytes | ${bitmap.width}x${bitmap.height}"
                    statusText = "Screenshot captured"
                } catch (e: Exception) {
                    statusText = "Capture failed: ${e.message}"
                }
            }
        }
    }

    DisposableEffect(Unit) {
        statusText = if (service != null) "Service found" else "Service NOT found"
        onDispose {
            try {
                service?.destroyAgentDisplay()
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Display Test") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleSmall)
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)
                    if (displayId >= 0) {
                        Text("Display ID: $displayId", style = MaterialTheme.typography.bodySmall)
                    }
                    if (imageInfo.isNotEmpty()) {
                        Text(imageInfo, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Create / Destroy display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    service?.createAgentDisplay(720, 720, 240)
                                    displayId = service?.displayId ?: -1
                                    statusText = "Display created (ID: $displayId)"
                                } catch (e: Exception) {
                                    statusText = "Create failed: ${e.message}"
                                    Log.e(TAG, "createAgentDisplay failed", e)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Create Display")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    service?.destroyAgentDisplay()
                                    displayId = -1
                                    displayBitmap = null
                                    imageInfo = ""
                                    statusText = "Display destroyed"
                                } catch (e: Exception) {
                                    statusText = "Destroy failed: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Destroy")
                }
            }

            // Launch app
            OutlinedTextField(
                value = packageToLaunch,
                onValueChange = { packageToLaunch = it },
                label = { Text("Package to launch") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                service?.launchApp(packageToLaunch)
                                statusText = "Launched $packageToLaunch"
                            } catch (e: Exception) {
                                statusText = "Launch failed: ${e.message}"
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Launch App")
            }

            // Tap
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = tapX,
                    onValueChange = { tapX = it },
                    label = { Text("X") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = tapY,
                    onValueChange = { tapY = it },
                    label = { Text("Y") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                service?.tap(
                                    tapX.toFloatOrNull() ?: 360f,
                                    tapY.toFloatOrNull() ?: 640f,
                                )
                                statusText = "Tapped ($tapX, $tapY)"
                            } catch (e: Exception) {
                                statusText = "Tap failed: ${e.message}"
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Tap")
            }

            // Input text
            OutlinedTextField(
                value = textToInput,
                onValueChange = { textToInput = it },
                label = { Text("Text to input") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    service?.inputText(textToInput)
                                    statusText = "Text input sent"
                                } catch (e: Exception) {
                                    statusText = "Input failed: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Input Text")
                }
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    service?.pressBack()
                                    statusText = "Pressed Back"
                                } catch (e: Exception) {
                                    statusText = "Back failed: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Back")
                }
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    service?.pressHome()
                                    statusText = "Pressed Home"
                                } catch (e: Exception) {
                                    statusText = "Home failed: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Home")
                }
            }

            // Capture frame (exact LLM view)
            Button(
                onClick = { refreshScreenshot() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Screenshot")
            }

            // Show captured frame — exactly what the LLM sees
            displayBitmap?.let { bitmap ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Virtual display screenshot",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }

            // Accessibility tree
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                val tree = service?.accessibilityTree ?: "{}"
                                accessibilityTree = tree
                                if (tree == "{}" || tree.contains("\"error\"")) {
                                    statusText = "A11y tree empty/error — check logcat tags: " +
                                        "AgentDisplayService, AgentDisplayA11y"
                                    Log.w(TAG, "Accessibility tree result: $tree")
                                } else {
                                    statusText = "Got accessibility tree (${tree.length} chars)"
                                }
                            } catch (e: Exception) {
                                statusText = "Tree failed: ${e.message}"
                                Log.e(TAG, "getAccessibilityTree failed", e)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Get Accessibility Tree")
            }

            if (accessibilityTree.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = accessibilityTree.take(2000),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // Click node by view ID
            OutlinedTextField(
                value = viewIdToClick,
                onValueChange = { viewIdToClick = it },
                label = { Text("View ID (e.g. com.android.settings:id/search_bar)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    service?.clickNode(viewIdToClick)
                                    statusText = "Clicked node: $viewIdToClick"
                                } catch (e: Exception) {
                                    statusText = "Click node failed: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Click Node")
                }
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                try {
                                    service?.setNodeText(viewIdToClick, textToInput)
                                    statusText = "Set text on: $viewIdToClick"
                                } catch (e: Exception) {
                                    statusText = "Set text failed: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Set Node Text")
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
