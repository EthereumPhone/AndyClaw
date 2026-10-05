package org.ethereumphone.andyclaw.services

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import org.ethereumphone.andyclaw.BuildConfig
import org.ethereumphone.andyclaw.NodeApp
import org.ethereumphone.andyclaw.llm.reflex.ReflexActivity
import org.ethereumphone.andyclaw.llm.reflex.ReflexRuntime
import java.io.FileNotFoundException

/**
 * Reflex's action model (M2) for Settings › Your dGEN1 › AndyClaw, which downloads it, hands it
 * over, and shows whether and when Reflex is used. The Transaction Guardian's contract
 * (EthOSGuardian README, "The contract"), so Settings drives both with one downloader:
 *
 * - `status`: `api` (1), `supported`, `bundleVersion` ([ReflexRuntime.ACTOR_VERSION]),
 *   `bundleSha256`, `bundleBytes`, `unpackedBytes` (0: the staged file is the model), `urls`
 *   (R2, then GCS), `installedVersion` (0 = none), `ready`, `updateAvailable`, `installing`,
 *   `freeBytes`; and Reflex's own: `enabled`, `instant`, `m1` (ready | loading | failed),
 *   `installedBytes`, `read`, `handled`, `lastReadMs`, `recentTitles` / `recentOutcomes` /
 *   `recentAtMs` / `recentMs` (newest first), `shadowFired`, `shadowAgreed`, `shadowMissed`.
 * - `install`: checks the staged file against the pinned sha256 and moves it into place. `ok`, or
 *   `error`: `no_staging`, `bad_hash`, `busy`, `io`.
 * - `delete`: removes M2. `ok`.
 * - `set` (arg `enabled` | `instant`, extra boolean `value`): the two switches, as the launcher's.
 * - `openFile(…/staging, "w")`: a truncated, writable fd for the download.
 *
 * Every change is announced on [STATE_URI]. Guarded by the signature permission [PERMISSION],
 * which `call()` enforces itself (the manifest's `android:permission` covers `openFile` only).
 * Logs carry methods and outcomes, never what the user asked.
 */
class ReflexProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private val app: NodeApp get() = context!!.applicationContext as NodeApp

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        enforce()
        val runtime = app.reflexRuntime
            ?: return Bundle().apply { putInt("api", API); putBoolean("supported", false) }
        return when (method) {
            "status" -> status(runtime)
            "install" -> Bundle().apply {
                val error = runtime.installStaged()
                putBoolean("ok", error == null)
                error?.let { putString("error", it) }
                changed()
            }
            "delete" -> Bundle().apply {
                putBoolean("ok", runtime.deleteActor())
                Log.i(TAG, "delete ok=${getBoolean("ok")}")
                changed()
            }
            "set" -> set(arg, extras)
            else -> null
        }
    }

    private fun status(runtime: ReflexRuntime) = Bundle().apply {
        val prefs = app.securePrefs
        val installed = runtime.installedActorVersion()
        putInt("api", API)
        putBoolean("supported", true)
        putInt("bundleVersion", ReflexRuntime.ACTOR_VERSION)
        putString("bundleSha256", ReflexRuntime.PINNED.getValue(ReflexRuntime.ACTOR))
        putLong("bundleBytes", ReflexRuntime.ACTOR_BYTES)
        putLong("unpackedBytes", 0)
        putStringArrayList("urls", ArrayList(ReflexRuntime.ACTOR_URLS))
        putInt("installedVersion", installed)
        putBoolean("ready", installed == ReflexRuntime.ACTOR_VERSION)
        putBoolean("updateAvailable", installed != 0 && installed != ReflexRuntime.ACTOR_VERSION)
        putBoolean("installing", runtime.isInstalling)
        putLong("freeBytes", runtime.freeBytes())
        putLong("installedBytes", runtime.actorBytesOnDisk())

        putBoolean("enabled", prefs.reflexEnabled.value)
        putBoolean("instant", prefs.reflexInstantEnabled.value)
        putString("m1", runtime.m1State())

        val activity = runCatching { runtime.activity.stats() }.getOrNull() ?: ReflexActivity.Stats()
        putInt("read", activity.read)
        putInt("handled", activity.handled)
        putLong("lastReadMs", activity.lastReadMs)
        putStringArrayList("recentTitles", ArrayList(activity.recent.map { ReflexActivity.describe(it.label) }))
        putStringArrayList("recentOutcomes", ArrayList(activity.recent.map { it.outcome }))
        putLongArray("recentAtMs", activity.recent.map { it.atMs }.toLongArray())
        putLongArray("recentMs", activity.recent.map { it.ms }.toLongArray())

        runCatching { runtime.shadow.stats() }.getOrNull()?.let { s ->
            putInt("shadowFired", s.fired)
            putInt("shadowAgreed", s.agreed)
            putInt("shadowMissed", s.missed)
        }
    }

    private fun set(key: String?, extras: Bundle?): Bundle {
        val value = extras?.takeIf { it.containsKey("value") }?.getBoolean("value")
            ?: return Bundle().apply { putBoolean("ok", false) }
        val prefs = app.securePrefs
        when (key) {
            "enabled" -> prefs.setReflexEnabled(value)
            "instant" -> prefs.setReflexInstantEnabled(value)
            else -> return Bundle().apply { putBoolean("ok", false) }
        }
        Log.i(TAG, "set $key=$value")
        changed()
        return Bundle().apply { putBoolean("ok", true) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforce()
        if (uri.pathSegments != listOf("staging") || !mode.startsWith("w")) throw FileNotFoundException(uri.toString())
        val runtime = app.reflexRuntime ?: throw FileNotFoundException("not supported")
        val f = runtime.freshStaging() ?: throw FileNotFoundException("install in progress")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
    }

    /** The signature permission; a debug build also answers `adb shell content call`. */
    private fun enforce() {
        if (BuildConfig.DEBUG && Binder.getCallingUid() == Process.SHELL_UID) return
        context!!.enforceCallingOrSelfPermission(PERMISSION, "AndyClaw Reflex")
    }

    private fun changed() {
        context!!.contentResolver.notifyChange(STATE_URI, null)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "ReflexProvider"
        const val AUTHORITY = "org.ethereumphone.andyclaw.reflex"
        const val PERMISSION = "org.ethereumphone.andyclaw.permission.REFLEX_MODEL"
        const val API = 1
        val STATE_URI: Uri = Uri.parse("content://$AUTHORITY/state")

        /** Settings › Your dGEN1 › AndyClaw, where the user downloads and controls Reflex (ethOS only). */
        const val SETTINGS_ACTION = "org.ethereumphone.settings.ANDYCLAW_SETTINGS"
    }
}
