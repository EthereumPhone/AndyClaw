package org.ethereumphone.andyclaw.services

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import org.ethereumphone.andyclaw.NodeApp

/**
 * Read-only ContentProvider that exposes heartbeat settings to the OS.
 *
 * The OS heartbeat service (AndyClawHeartbeatService running in system_server)
 * queries this provider to read the user-configured heartbeat interval.
 *
 * Authority: org.ethereumphone.andyclaw.heartbeat.settings
 * Path:      /interval_minutes  ->  returns a single-row cursor with the interval in minutes
 */
class HeartbeatSettingsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "org.ethereumphone.andyclaw.heartbeat.settings"
        private const val DEFAULT_INTERVAL_MINUTES = 30
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        // Only the system process may query heartbeat settings
        if (Binder.getCallingUid() != Process.SYSTEM_UID) return null

        if (uri.lastPathSegment != "interval_minutes") return null

        val app = context?.applicationContext as? NodeApp
        val minutes = app?.securePrefs?.heartbeatIntervalMinutes?.value
            ?: DEFAULT_INTERVAL_MINUTES

        return MatrixCursor(arrayOf("interval_minutes")).apply {
            addRow(arrayOf(minutes))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
