package org.ethereumphone.andyclaw.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.IAgentDisplayService
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import org.ethereumphone.andyclaw.ui.components.DgenCursorTextfield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.dgenWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.DgenBackNavigationBackground
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton

private const val TAG = "AgentDisplayTest"

private enum class AgentDisplaySubScreen {
    Main,
    DisplayControl,
    Interaction,
    Accessibility,
}

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
    var currentSubScreen by remember { mutableStateOf(AgentDisplaySubScreen.Main) }

    val context = LocalContext.current
    LaunchedEffect(Unit) { SystemColorManager.refresh(context) }
    val primaryColor = SystemColorManager.primaryColor

    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)
    val rowControlSpacing = 20.dp

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

    DgenBackNavigationBackground(
        title = when (currentSubScreen) {
            AgentDisplaySubScreen.Main -> "Agent Display"
            AgentDisplaySubScreen.DisplayControl -> "Display Control"
            AgentDisplaySubScreen.Interaction -> "Interaction"
            AgentDisplaySubScreen.Accessibility -> "Accessibility"
        },
        primaryColor = primaryColor,
        onNavigateBack = {
            when (currentSubScreen) {
                AgentDisplaySubScreen.Main -> onNavigateBack()
                else -> { currentSubScreen = AgentDisplaySubScreen.Main }
            }
        },
    ) {
        Crossfade(targetState = currentSubScreen, label = "agent_display_crossfade") { screen ->
            when (screen) {
                AgentDisplaySubScreen.Main -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        // Status
                        Text(text = "STATUS", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        Text(text = statusText, style = contentBodyStyle, color = dgenWhite)
                        if (displayId >= 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Display ID: $displayId",
                                style = contentBodyStyle.copy(fontSize = 13.sp),
                                color = dgenWhite.copy(alpha = 0.7f),
                            )
                        }
                        if (imageInfo.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = imageInfo,
                                style = contentBodyStyle.copy(fontSize = 13.sp),
                                color = dgenWhite.copy(alpha = 0.7f),
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        org.ethereumphone.andyclaw.bench.AndyBenchPanel(titleColor = primaryColor, textColor = dgenWhite)

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        // Display Control
                        Text(text = "DISPLAY CONTROL", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentSubScreen = AgentDisplaySubScreen.DisplayControl }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "CREATE & MANAGE DISPLAY",
                                    style = contentTitleStyle,
                                    color = primaryColor,
                                )
                                Text(
                                    text = "Create, destroy virtual displays and launch apps",
                                    style = contentBodyStyle,
                                    color = dgenWhite,
                                )
                            }
                            Spacer(Modifier.width(rowControlSpacing))
                            DgenSmallPrimaryButton(
                                text = "Open",
                                primaryColor = primaryColor,
                                onClick = { currentSubScreen = AgentDisplaySubScreen.DisplayControl },
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        // Interaction
                        Text(text = "INTERACTION", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentSubScreen = AgentDisplaySubScreen.Interaction }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "TAP, INPUT & NAVIGATE",
                                    style = contentTitleStyle,
                                    color = primaryColor,
                                )
                                Text(
                                    text = "Send taps, text input, back/home actions, and capture screenshots",
                                    style = contentBodyStyle,
                                    color = dgenWhite,
                                )
                            }
                            Spacer(Modifier.width(rowControlSpacing))
                            DgenSmallPrimaryButton(
                                text = "Open",
                                primaryColor = primaryColor,
                                onClick = { currentSubScreen = AgentDisplaySubScreen.Interaction },
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        // Accessibility
                        Text(text = "ACCESSIBILITY", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentSubScreen = AgentDisplaySubScreen.Accessibility }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "ACCESSIBILITY TREE & NODE ACTIONS",
                                    style = contentTitleStyle,
                                    color = primaryColor,
                                )
                                Text(
                                    text = "Inspect the accessibility tree and interact with nodes by view ID",
                                    style = contentBodyStyle,
                                    color = dgenWhite,
                                )
                            }
                            Spacer(Modifier.width(rowControlSpacing))
                            DgenSmallPrimaryButton(
                                text = "Open",
                                primaryColor = primaryColor,
                                onClick = { currentSubScreen = AgentDisplaySubScreen.Accessibility },
                            )
                        }

                        // Screenshot preview
                        displayBitmap?.let { bitmap ->
                            Spacer(Modifier.height(24.dp))
                            HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                            Spacer(Modifier.height(16.dp))
                            Text(text = "LAST SCREENSHOT", style = sectionTitleStyle, color = primaryColor)
                            Spacer(Modifier.height(8.dp))
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Virtual display screenshot",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                            )
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }

                AgentDisplaySubScreen.DisplayControl -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(text = "VIRTUAL DISPLAY", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DgenSmallPrimaryButton(
                                text = "Create Display",
                                primaryColor = primaryColor,
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
                            )
                            DgenSmallPrimaryButton(
                                text = "Destroy",
                                primaryColor = Color(0xFFFF6B6B),
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
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(text = statusText, style = contentBodyStyle, color = dgenWhite)

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        Text(text = "LAUNCH APP", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        DgenCursorTextfield(
                            value = packageToLaunch,
                            onValueChange = { packageToLaunch = it },
                            label = "Package to launch",
                            modifier = Modifier.fillMaxWidth(),
                            primaryColor = primaryColor,
                        )
                        Spacer(Modifier.height(8.dp))
                        DgenSmallPrimaryButton(
                            text = "Launch App",
                            primaryColor = primaryColor,
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
                        )

                        Spacer(Modifier.height(32.dp))
                    }
                }

                AgentDisplaySubScreen.Interaction -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        // Tap
                        Text(text = "TAP", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DgenCursorTextfield(
                                value = tapX,
                                onValueChange = { tapX = it },
                                label = "X",
                                modifier = Modifier.weight(1f),
                                primaryColor = primaryColor,
                            )
                            DgenCursorTextfield(
                                value = tapY,
                                onValueChange = { tapY = it },
                                label = "Y",
                                modifier = Modifier.weight(1f),
                                primaryColor = primaryColor,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        DgenSmallPrimaryButton(
                            text = "Tap",
                            primaryColor = primaryColor,
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
                        )

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        // Text input
                        Text(text = "TEXT INPUT", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        DgenCursorTextfield(
                            value = textToInput,
                            onValueChange = { textToInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            primaryColor = primaryColor,
                        )
                        Spacer(Modifier.height(8.dp))
                        DgenSmallPrimaryButton(
                            text = "Input Text",
                            primaryColor = primaryColor,
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
                        )

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        // Navigation
                        Text(text = "NAVIGATION", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DgenSmallPrimaryButton(
                                text = "Back",
                                primaryColor = primaryColor,
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
                            )
                            DgenSmallPrimaryButton(
                                text = "Home",
                                primaryColor = primaryColor,
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
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        // Screenshot
                        Text(text = "SCREENSHOT", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        DgenSmallPrimaryButton(
                            text = "Capture Screenshot",
                            primaryColor = primaryColor,
                            onClick = { refreshScreenshot() },
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(text = statusText, style = contentBodyStyle, color = dgenWhite)

                        displayBitmap?.let { bitmap ->
                            Spacer(Modifier.height(12.dp))
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Virtual display screenshot",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth,
                            )
                        }

                        Spacer(Modifier.height(32.dp))
                    }
                }

                AgentDisplaySubScreen.Accessibility -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text(text = "ACCESSIBILITY TREE", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        DgenSmallPrimaryButton(
                            text = "Get Tree",
                            primaryColor = primaryColor,
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
                        )

                        if (accessibilityTree.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = accessibilityTree.take(2000),
                                style = contentBodyStyle.copy(fontSize = 12.sp),
                                color = dgenWhite.copy(alpha = 0.8f),
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                        Spacer(Modifier.height(16.dp))

                        // Click node
                        Text(text = "NODE ACTIONS", style = sectionTitleStyle, color = primaryColor)
                        Spacer(Modifier.height(8.dp))
                        DgenCursorTextfield(
                            value = viewIdToClick,
                            onValueChange = { viewIdToClick = it },
                            label = "View ID",
                            placeholder = { Text("e.g. com.android.settings:id/search_bar", color = dgenWhite.copy(alpha = 0.3f)) },
                            modifier = Modifier.fillMaxWidth(),
                            primaryColor = primaryColor,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DgenSmallPrimaryButton(
                                text = "Click Node",
                                primaryColor = primaryColor,
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
                            )
                            DgenSmallPrimaryButton(
                                text = "Set Node Text",
                                primaryColor = primaryColor,
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
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(text = statusText, style = contentBodyStyle, color = dgenWhite)

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}
