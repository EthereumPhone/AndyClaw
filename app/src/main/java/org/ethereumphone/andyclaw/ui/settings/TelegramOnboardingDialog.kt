package org.ethereumphone.andyclaw.ui.settings

import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.example.dgenlibrary.DgenLoadingMatrix
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import org.ethereumphone.andyclaw.ui.components.DgenCursorTextfield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.PitagonsSans
import com.example.dgenlibrary.ui.theme.SpaceMono
import com.example.dgenlibrary.ui.theme.dgenWhite
import com.example.dgenlibrary.ui.theme.label_fontSize
import kotlinx.coroutines.Job
import org.ethereumphone.andyclaw.ui.components.GlowStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ethereumphone.andyclaw.telegram.TelegramBotClient
import org.ethereumphone.andyclaw.telegram.TelegramUpdate
import org.ethereumphone.andyclaw.ui.components.DgenBackNavigationBackground
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton

private const val TAG = "TelegramOnboarding"

private enum class TelegramSubScreen {
    EnterToken,
    VerifyCode,
}

@Composable
fun TelegramOnboardingDialog(
    onComplete: (token: String, ownerChatId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentSubScreen by remember { mutableStateOf(TelegramSubScreen.EnterToken) }
    var token by remember { mutableStateOf("") }

    var pendingChatId by remember { mutableStateOf<Long?>(null) }
    var pendingCode by remember { mutableStateOf<String?>(null) }
    var enteredCode by remember { mutableStateOf("") }
    var isPolling by remember { mutableStateOf(false) }
    var waitingForMessage by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    var pollingJob by remember { mutableStateOf<Job?>(null) }

    val context = LocalContext.current
    LaunchedEffect(Unit) { SystemColorManager.refresh(context) }
    val primaryColor = SystemColorManager.primaryColor
    val secondaryColor = SystemColorManager.secondaryColor

    val sectionTitleStyle = TextStyle(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = label_fontSize,
        lineHeight = label_fontSize,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Left,
        shadow = GlowStyle.subtitle(primaryColor),
    )
    val contentTitleStyle = TextStyle(
        fontFamily = SpaceMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Left,
        shadow = GlowStyle.subtitle(primaryColor),
    )
    val contentBodyStyle = TextStyle(
        fontFamily = PitagonsSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        textAlign = TextAlign.Left,
        shadow = GlowStyle.body(primaryColor),
    )

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
        DgenBackNavigationBackground(
            title = when (currentSubScreen) {
                TelegramSubScreen.EnterToken -> "Telegram Bot Setup"
                TelegramSubScreen.VerifyCode -> "Verify Ownership"
            },
            primaryColor = primaryColor,
            onNavigateBack = {
                when (currentSubScreen) {
                    TelegramSubScreen.EnterToken -> {
                        pollingJob?.cancel()
                        onDismiss()
                    }
                    TelegramSubScreen.VerifyCode -> {
                        pollingJob?.cancel()
                        isPolling = false
                        waitingForMessage = true
                        errorMessage = null
                        currentSubScreen = TelegramSubScreen.EnterToken
                    }
                }
            },
        ) {
            Crossfade(targetState = currentSubScreen, label = "telegram_crossfade") { screen ->
                when (screen) {
                    TelegramSubScreen.EnterToken -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            Text(
                                text = "STEP 1: ENTER BOT TOKEN",
                                style = sectionTitleStyle,
                                color = primaryColor,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Create a Telegram bot and get its token:",
                                style = contentBodyStyle,
                                color = dgenWhite,
                            )

                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = "INSTRUCTIONS",
                                style = contentTitleStyle,
                                color = primaryColor,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "1. Open Telegram and search for @BotFather\n" +
                                    "2. Send /newbot and follow the prompts\n" +
                                    "3. Copy the bot token BotFather gives you\n" +
                                    "4. Paste it below",
                                style = contentBodyStyle,
                                color = dgenWhite.copy(alpha = 0.8f),
                            )

                            Spacer(Modifier.height(24.dp))
                            HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                            Spacer(Modifier.height(16.dp))

                            DgenCursorTextfield(
                                value = token,
                                onValueChange = { token = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = "Bot Token",
                                placeholder = { Text("Enter your bot token", color = dgenWhite.copy(alpha = 0.3f), style = contentBodyStyle.copy(shadow = GlowStyle.placeholder(dgenWhite))) },
                                visualTransformation = PasswordVisualTransformation(),
                                primaryColor = primaryColor,
                            )
                            Spacer(Modifier.height(24.dp))
                            DgenSmallPrimaryButton(
                                text = "Next",
                                primaryColor = primaryColor,
                                enabled = token.isNotBlank(),
                                onClick = {
                                    currentSubScreen = TelegramSubScreen.VerifyCode
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
                                            token = token.trim(),
                                        )
                                    }
                                },
                            )
                        }
                    }

                    TelegramSubScreen.VerifyCode -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            Text(
                                text = "STEP 2: VERIFY OWNERSHIP",
                                style = sectionTitleStyle,
                                color = primaryColor,
                            )
                            Spacer(Modifier.height(16.dp))

                            if (waitingForMessage) {
                                Text(
                                    text = "Open Telegram and send any message to your bot, in a private chat with it — " +
                                        "not in a group: whoever verifies here becomes the bot's owner.",
                                    style = contentBodyStyle,
                                    color = dgenWhite,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "WAITING FOR YOUR MESSAGE...",
                                    style = contentTitleStyle,
                                    color = primaryColor,
                                )
                                Spacer(Modifier.height(16.dp))
                                DgenLoadingMatrix(
                                    size = 24.dp,
                                    LEDSize = 6.dp,
                                    activeLEDColor = primaryColor,
                                    unactiveLEDColor = secondaryColor,
                                )
                            } else {
                                Text(
                                    text = "A verification code was sent to your Telegram chat. Enter it below to confirm you own this bot.",
                                    style = contentBodyStyle,
                                    color = dgenWhite,
                                )

                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(color = primaryColor.copy(alpha = 0.2f))
                                Spacer(Modifier.height(16.dp))

                                DgenCursorTextfield(
                                    value = enteredCode,
                                    onValueChange = { enteredCode = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "Verification Code",
                                    primaryColor = primaryColor,
                                )
                                Spacer(Modifier.height(24.dp))
                                DgenSmallPrimaryButton(
                                    text = "Verify",
                                    primaryColor = primaryColor,
                                    enabled = enteredCode.isNotBlank(),
                                    onClick = {
                                        if (enteredCode.equals(pendingCode, ignoreCase = true)) {
                                            pollingJob?.cancel()
                                            onComplete(token.trim(), pendingChatId!!)
                                        } else {
                                            errorMessage = "Code does not match. Please try again."
                                        }
                                    },
                                )
                            }

                            if (errorMessage != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = errorMessage!!,
                                    style = contentBodyStyle,
                                    color = Color(0xFFFF6B6B),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun generateCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..6).map { chars.random() }.joinToString("")
}

/** Why Telegram refused [token], or null when it accepted it (or could not be asked). */
private suspend fun tokenProblem(token: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val request = okhttp3.Request.Builder().url("https://api.telegram.org/bot$token/getMe").get().build()
    try {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(request).execute().use { response ->
                when (response.code) {
                    401, 404 -> "Telegram did not accept this bot token. Check it and try again."
                    else -> null
                }
            }
    } catch (e: Exception) {
        Log.w(TAG, "Bot token check failed: ${e.message}")
        null
    }
}

private suspend fun pollForVerification(
    client: TelegramBotClient,
    onMessageReceived: (chatId: Long, code: String) -> Unit,
    onError: (String) -> Unit,
    token: String,
) {
    // A wrong token used to show nothing for half an hour and then "Timed out".
    tokenProblem(token)?.let { onError(it); return }

    var offset: Long? = null
    try {
        val stale = client.getUpdates(offset = null, timeout = 0)
        if (stale.isNotEmpty()) {
            offset = stale.last().updateId + 1
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to flush stale updates: ${e.message}")
    }

    // As long as 60 long polls take; a refused poll comes back at once, so the clock decides.
    val deadline = System.currentTimeMillis() + 60 * 30_000L
    var quickEmpties = 0
    while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
        try {
            val started = System.currentTimeMillis()
            val updates = client.getUpdates(offset = offset, timeout = 30)
            if (updates.isEmpty()) {
                // An empty answer long before the long poll's 30 s is a failed request (getUpdates
                // returns nothing on errors): back off rather than ask again at once.
                if (System.currentTimeMillis() - started < 5_000) {
                    quickEmpties++
                    delay((2_000L shl (quickEmpties - 1).coerceAtMost(3)).coerceAtMost(15_000L))
                } else {
                    quickEmpties = 0
                }
            }
            if (updates.isNotEmpty()) {
                quickEmpties = 0
                offset = updates.last().updateId + 1
                // A private chat only. A code posted into a group is read by every member, and
                // the chat id stored then made each of them the owner.
                val first = updates.filterIsInstance<TelegramUpdate.MessageUpdate>()
                    .firstOrNull { it.chatType == "private" }
                    ?: continue

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
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Polling error: ${e.message}")
            delay(3000)
        }
    }
    onError(
        "Timed out waiting for a message. Please try again. If this bot is connected somewhere " +
            "else too, Telegram hands its messages there instead."
    )
}
