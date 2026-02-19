package org.ethereumphone.andyclaw.skills.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Outcome of a single Termux command invocation.
 */
data class TermuxCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val internalError: String? = null,
) {
    val isSuccess: Boolean get() = exitCode == 0 && internalError == null
}

/**
 * Reusable Termux command execution engine.
 *
 * Sends commands via Termux's `RUN_COMMAND` intent and waits for results
 * through a [PendingIntent]-based broadcast callback.  Shared by the
 * built-in [TermuxSkill], [ClawHubTermuxSkillAdapter], and [TermuxSkillSync].
 */
class TermuxCommandRunner(private val context: Context) {

    companion object {
        private const val TAG = "TermuxCmdRunner"
        const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
        const val TERMUX_HOME = "/data/data/com.termux/files/home"

        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_TIMEOUT_MS = 300_000L
        const val MAX_OUTPUT_CHARS = 50_000

        private const val RESULT_ACTION_PREFIX =
            "org.ethereumphone.andyclaw.TERMUX_RESULT_"

        /**
         * Preamble prepended to every command.  Background-mode commands
         * have no TTY, so any interactive prompt (debconf, dpkg conffile
         * do_select, `read`, etc.) will hang forever.
         *
         * - `DEBIAN_FRONTEND`  suppresses debconf prompts
         * - `DPKG_FORCE`       tells dpkg to keep old configs and use
         *                      package defaults without asking
         *
         * This protects all callers — TermuxSkill, ClawHub adapters,
         * and any ad-hoc `pkg install` the agent might run.
         */
        private const val NON_INTERACTIVE_PREAMBLE =
            "export DEBIAN_FRONTEND=noninteractive " +
                "DPKG_FORCE=confold,confdef; "
    }

    // ── Public API ──────────────────────────────────────────────────

    fun isTermuxInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun getVersionInfo(): Pair<String?, Long?> = try {
        val info = context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        Pair(info.versionName, info.longVersionCode)
    } catch (_: Exception) {
        Pair(null, null)
    }

    /**
     * Run [command] inside Termux's bash shell and wait for the result.
     *
     * Returns immediately with an error result when Termux is not installed.
     */
    suspend fun run(
        command: String,
        workdir: String = TERMUX_HOME,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): TermuxCommandResult {
        Log.d(TAG, "run() called — command=${command.take(100)}, workdir=$workdir, timeout=$timeoutMs")

        if (!isTermuxInstalled()) {
            Log.e(TAG, "Termux is not installed")
            return TermuxCommandResult(
                exitCode = -1, stdout = "", stderr = "",
                internalError = "Termux is not installed.",
            )
        }
        Log.d(TAG, "Termux is installed")

        val effectiveTimeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_MS)
        return try {
            withTimeout(effectiveTimeout) {
                executeViaIntent(command, workdir)
            }
        } catch (_: TimeoutCancellationException) {
            Log.e(TAG, "Command timed out after ${effectiveTimeout}ms")
            TermuxCommandResult(
                exitCode = -1, stdout = "", stderr = "",
                internalError = "Command timed out after ${effectiveTimeout}ms.",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed", e)
            TermuxCommandResult(
                exitCode = -1, stdout = "", stderr = "",
                internalError = "Execution failed: ${e.message}",
            )
        }
    }

    // ── Intent-based execution ──────────────────────────────────────

    private suspend fun executeViaIntent(
        command: String,
        workdir: String,
    ): TermuxCommandResult = suspendCancellableCoroutine { cont ->
        val wrappedCommand = "$NON_INTERACTIVE_PREAMBLE$command"
        val requestId = UUID.randomUUID().toString()
        val action = "$RESULT_ACTION_PREFIX$requestId"

        Log.d(TAG, "executeViaIntent — requestId=$requestId")
        Log.d(TAG, "  wrappedCommand=${wrappedCommand.take(200)}")
        Log.d(TAG, "  action=$action")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                Log.d(TAG, "BroadcastReceiver.onReceive — requestId=$requestId")
                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                val result = extractResult(intent)
                Log.d(TAG, "  exitCode=${result.exitCode}, stdout=${result.stdout.take(200)}, stderr=${result.stderr.take(200)}, err=${result.internalError}")
                if (cont.isActive) cont.resume(result)
            }
        }

        Log.d(TAG, "Registering broadcast receiver for action=$action")
        context.registerReceiver(
            receiver,
            IntentFilter(action),
            Context.RECEIVER_NOT_EXPORTED,
        )

        val resultIntent = Intent(action).apply { setPackage(context.packageName) }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestId.hashCode(),
            resultIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
        )

        val serviceIntent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_BIN/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", wrappedCommand))
            putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }
        Log.d(TAG, "Built serviceIntent: component=${serviceIntent.component}, extras=${serviceIntent.extras?.keySet()}")

        try {
            Log.d(TAG, "Trying startForegroundService...")
            context.startForegroundService(serviceIntent)
            Log.d(TAG, "startForegroundService succeeded")
        } catch (e1: Exception) {
            Log.w(TAG, "startForegroundService failed: ${e1::class.simpleName}: ${e1.message}", e1)
            try {
                Log.d(TAG, "Trying startService fallback...")
                context.startService(serviceIntent)
                Log.d(TAG, "startService succeeded")
            } catch (e2: Exception) {
                Log.e(TAG, "startService also failed: ${e2::class.simpleName}: ${e2.message}", e2)
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                if (cont.isActive) {
                    cont.resume(
                        TermuxCommandResult(
                            exitCode = -1, stdout = "", stderr = "",
                            internalError = "Failed to start Termux service: ${e2.message}. " +
                                "Make sure Termux is running and the RUN_COMMAND permission " +
                                "is granted to AndyClaw.",
                        )
                    )
                }
                return@suspendCancellableCoroutine
            }
        }

        cont.invokeOnCancellation {
            Log.d(TAG, "Coroutine cancelled — unregistering receiver for requestId=$requestId")
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    /**
     * Extracts stdout, stderr, and exit code from the Termux result intent.
     * Handles both direct extras and the nested "result" Bundle format that
     * different Termux versions may use.
     */
    private fun extractResult(intent: Intent): TermuxCommandResult {
        val source: Bundle? = intent.getBundleExtra("result") ?: intent.extras

        val stdout = source?.getString("stdout") ?: ""
        val stderr = source?.getString("stderr") ?: ""
        val exitCode = source?.getInt("exitCode", -1) ?: -1
        val err = source?.getInt("err", 0) ?: 0
        val errmsg = source?.getString("errmsg")

        return TermuxCommandResult(
            exitCode = exitCode,
            stdout = stdout.take(MAX_OUTPUT_CHARS),
            stderr = stderr.take(MAX_OUTPUT_CHARS),
            internalError = if (err != 0 && errmsg != null) errmsg else null,
        )
    }
}
