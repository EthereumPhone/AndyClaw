package org.ethereumphone.andyclaw

import android.content.pm.PackageManager
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.provider.Settings
import android.app.AlertDialog
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PermissionRequester(private val activity: ComponentActivity) {
  private companion object {
    const val TAG = "PermissionRequester"
  }

  private val mutex = Mutex()
  private var pending: CompletableDeferred<Map<String, Boolean>>? = null

  private val launcher: ActivityResultLauncher<Array<String>> =
    activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
      val p = pending
      pending = null
      p?.complete(result)
    }

  suspend fun requestIfMissing(
    permissions: List<String>,
    timeoutMs: Long = 20_000,
  ): Map<String, Boolean> =
    mutex.withLock {
      val missing =
        permissions.filter { perm ->
          ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED
        }
      if (missing.isEmpty()) {
        return permissions.associateWith { true }
      }

      // Background callers (the heartbeat, a tool in a run the user is not watching)
      // reach this too. With the activity not in front, a system permission dialog
      // cannot come up — the launch is dropped or blocked as a background start and the
      // caller waited out the whole timeout — and a dialog over some other app would be
      // worse. Answer with what is granted now and leave asking to a foreground moment.
      val inForeground = withContext(Dispatchers.Main) {
        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
      }
      if (!inForeground) {
        Log.i(TAG, "Not resumed; not raising a permission prompt for $missing")
        return permissions.associateWith { perm ->
          ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
        }
      }

      val needsRationale =
        missing.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
      if (needsRationale) {
        val proceed = showRationaleDialog(missing)
        if (!proceed) {
          return permissions.associateWith { perm ->
            ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
          }
        }
      }

      val deferred = CompletableDeferred<Map<String, Boolean>>()
      pending = deferred
      withContext(Dispatchers.Main) {
        launcher.launch(missing.toTypedArray())
      }

      val result =
        withContext(Dispatchers.Default) {
          kotlinx.coroutines.withTimeout(timeoutMs) { deferred.await() }
        }

      val merged =
        permissions.associateWith { perm ->
          val nowGranted =
            ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
          result[perm] == true || nowGranted
        }

      val denied =
        merged.filterValues { !it }.keys.filter {
          !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
      if (denied.isNotEmpty()) {
        showSettingsDialog(denied)
      }

      return merged
    }

  private suspend fun showRationaleDialog(permissions: List<String>): Boolean =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { cont ->
        AlertDialog.Builder(activity)
          .setTitle("Permission required")
          .setMessage(buildRationaleMessage(permissions))
          .setPositiveButton("Continue") { _, _ -> cont.resume(true) }
          .setNegativeButton("Not now") { _, _ -> cont.resume(false) }
          .setOnCancelListener { cont.resume(false) }
          .show()
      }
    }

  // Callers run on IO; AlertDialog needs a Looper thread and threw off it.
  private suspend fun showSettingsDialog(permissions: List<String>) = withContext(Dispatchers.Main) {
    AlertDialog.Builder(activity)
      .setTitle("Enable permission in Settings")
      .setMessage(buildSettingsMessage(permissions))
      .setPositiveButton("Open Settings") { _, _ ->
        val intent =
          Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
          )
        activity.startActivity(intent)
      }
      .setNegativeButton("Cancel", null)
      .show()
    Unit
  }

  private fun buildRationaleMessage(permissions: List<String>): String {
    val labels = permissions.map { permissionLabel(it) }
    val aiName = (activity.applicationContext as NodeApp).securePrefs.aiName.value
    return "$aiName needs ${labels.joinToString(", ")} permissions to continue."
  }

  private fun buildSettingsMessage(permissions: List<String>): String {
    val labels = permissions.map { permissionLabel(it) }
    return "Please enable ${labels.joinToString(", ")} in Android Settings to continue."
  }

  private fun permissionLabel(permission: String): String =
    when (permission) {
      Manifest.permission.CAMERA -> "Camera"
      Manifest.permission.RECORD_AUDIO -> "Microphone"
      Manifest.permission.SEND_SMS -> "Send SMS"
      Manifest.permission.READ_SMS -> "Read SMS"
      Manifest.permission.READ_CONTACTS -> "Read Contacts"
      Manifest.permission.WRITE_CONTACTS -> "Write Contacts"
      Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
      Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate Location"
      else -> permission.substringAfterLast('.')
    }
}
