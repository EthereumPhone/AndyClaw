package org.ethereumphone.andyclaw.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.dgenlibrary.DgenLoadingMatrix
import com.example.dgenlibrary.SystemColorManager
import com.example.dgenlibrary.ui.theme.dgenWhite
import org.ethereumphone.andyclaw.backup.BackupInfo
import org.ethereumphone.andyclaw.ui.components.AppTextStyles
import org.ethereumphone.andyclaw.ui.components.DgenSmallPrimaryButton
import org.ethereumphone.andyclaw.ui.theme.BackgroundDark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupSettingsSection(
    isExporting: Boolean,
    isImporting: Boolean,
    backupInfo: BackupInfo?,
    showExportPasswordDialog: Boolean,
    showImportPasswordDialog: Boolean,
    backupError: String?,
    onExport: () -> Unit,
    onExportWithPassword: (String) -> Unit,
    onDismissExportPassword: () -> Unit,
    onImport: () -> Unit,
    onImportPasswordEntered: (String) -> Unit,
    onDismissImportPassword: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImportDialog: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = SystemColorManager.primaryColor
    val secondaryColor = SystemColorManager.secondaryColor

    val sectionTitleStyle = AppTextStyles.sectionTitle(primaryColor)
    val contentTitleStyle = AppTextStyles.contentTitle(primaryColor)
    val contentBodyStyle = AppTextStyles.contentBody(primaryColor)

    Column(modifier = modifier) {
        Text(
            text = "BACKUP & RESTORE",
            style = sectionTitleStyle,
            color = primaryColor,
        )
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = "DATA PORTABILITY",
                style = contentTitleStyle,
                color = primaryColor,
            )
            Text(
                text = "Export all your settings, memories, sessions, skills, and heartbeat " +
                    "configuration to a password-protected backup file. Import a backup " +
                    "to restore your AndyClaw on this or another device.",
                style = contentBodyStyle,
                color = dgenWhite,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            DgenSmallPrimaryButton(
                text = if (isExporting) "Exporting..." else "Export",
                primaryColor = primaryColor,
                onClick = onExport,
                enabled = !isExporting && !isImporting,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            )
            DgenSmallPrimaryButton(
                text = if (isImporting) "Importing..." else "Import",
                primaryColor = primaryColor,
                onClick = onImport,
                enabled = !isExporting && !isImporting,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
        }

        if (isExporting || isImporting) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DgenLoadingMatrix(
                    size = 14.dp,
                    LEDSize = 3.5.dp,
                    activeLEDColor = primaryColor,
                    unactiveLEDColor = secondaryColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isExporting) "CREATING BACKUP..." else "RESTORING BACKUP...",
                    style = contentTitleStyle,
                    color = primaryColor,
                )
            }
        }
    }

    // ── Export password dialog ───────────────────────────────────────────

    if (showExportPasswordDialog) {
        var password by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        val passwordsMatch = password == confirmPassword
        val canConfirm = password.isNotEmpty() && passwordsMatch

        AlertDialog(
            onDismissRequest = onDismissExportPassword,
            title = {
                Text(
                    text = "Protect Backup",
                    color = primaryColor,
                    style = contentTitleStyle,
                )
            },
            text = {
                Column {
                    Text(
                        text = "Set a password to encrypt your backup. This protects " +
                            "your API keys and other sensitive data.",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password", color = dgenWhite.copy(alpha = 0.6f)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = dgenWhite,
                            unfocusedTextColor = dgenWhite,
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = dgenWhite.copy(alpha = 0.3f),
                            cursorColor = primaryColor,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password", color = dgenWhite.copy(alpha = 0.6f)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = dgenWhite,
                            unfocusedTextColor = dgenWhite,
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = dgenWhite.copy(alpha = 0.3f),
                            cursorColor = primaryColor,
                            errorBorderColor = androidx.compose.ui.graphics.Color.Red,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Passwords do not match",
                            color = androidx.compose.ui.graphics.Color.Red,
                            style = contentBodyStyle,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onExportWithPassword(password) },
                    enabled = canConfirm,
                ) {
                    Text("Encrypt & Export", color = if (canConfirm) primaryColor else dgenWhite.copy(alpha = 0.3f))
                }
            },
            dismissButton = {
                TextButton(onClick = { onExportWithPassword("") }) {
                    Text("Skip (No Encryption)", color = dgenWhite)
                }
            },
            containerColor = BackgroundDark,
        )
    }

    // ── Import password dialog ──────────────────────────────────────────

    if (showImportPasswordDialog) {
        var password by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismissImportPassword,
            title = {
                Text(
                    text = "Enter Backup Password",
                    color = primaryColor,
                    style = contentTitleStyle,
                )
            },
            text = {
                Column {
                    Text(
                        text = "This backup is password-protected. Enter the password " +
                            "that was used when creating it.",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                    if (backupError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = backupError,
                            color = androidx.compose.ui.graphics.Color.Red,
                            style = contentBodyStyle,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password", color = dgenWhite.copy(alpha = 0.6f)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = dgenWhite,
                            unfocusedTextColor = dgenWhite,
                            focusedBorderColor = primaryColor,
                            unfocusedBorderColor = dgenWhite.copy(alpha = 0.3f),
                            cursorColor = primaryColor,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onImportPasswordEntered(password) },
                    enabled = password.isNotEmpty(),
                ) {
                    Text(
                        "Decrypt",
                        color = if (password.isNotEmpty()) primaryColor else dgenWhite.copy(alpha = 0.3f),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissImportPassword) {
                    Text("Cancel", color = dgenWhite)
                }
            },
            containerColor = BackgroundDark,
        )
    }

    // ── Import confirmation dialog ──────────────────────────────────────

    if (backupInfo != null) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val dateString = dateFormat.format(Date(backupInfo.timestamp))

        AlertDialog(
            onDismissRequest = onDismissImportDialog,
            title = {
                Text(
                    text = "Restore Backup?",
                    color = primaryColor,
                    style = contentTitleStyle,
                )
            },
            text = {
                Column {
                    Text(
                        text = "This will replace all current data with the backup contents.",
                        style = contentBodyStyle,
                        color = dgenWhite,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "AI NAME: ${backupInfo.aiName}",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "DEVICE: ${backupInfo.deviceName}",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "DATE: $dateString",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                    Text(
                        text = "VERSION: ${backupInfo.appVersionName}",
                        style = contentTitleStyle,
                        color = primaryColor,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmImport) {
                    Text("Restore", color = primaryColor)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissImportDialog) {
                    Text("Cancel", color = dgenWhite)
                }
            },
            containerColor = BackgroundDark,
        )
    }

    // ── Error dialog ────────────────────────────────────────────────────

    if (backupError != null && !showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = {
                Text(
                    text = "Backup Error",
                    color = primaryColor,
                    style = contentTitleStyle,
                )
            },
            text = {
                Text(
                    text = backupError,
                    style = contentBodyStyle,
                    color = dgenWhite,
                )
            },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text("OK", color = primaryColor)
                }
            },
            containerColor = BackgroundDark,
        )
    }
}
