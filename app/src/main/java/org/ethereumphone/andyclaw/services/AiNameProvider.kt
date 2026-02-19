package org.ethereumphone.andyclaw.services

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.ethereumphone.andyclaw.NodeApp

/**
 * Read-only ContentProvider that exposes the user-chosen AI name.
 *
 * The ethOS launcher queries this to display the custom name instead of
 * the static manifest label.
 *
 * Authority: org.ethereumphone.andyclaw.ai.settings
 * Path:      /ai_name  ->  single-row cursor with column "ai_name"
 */
class AiNameProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "org.ethereumphone.andyclaw.ai.settings"
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.lastPathSegment != "ai_name") return null

        val app = context?.applicationContext as? NodeApp
        val name = app?.securePrefs?.aiName?.value ?: "AndyClaw"

        return MatrixCursor(arrayOf("ai_name")).apply {
            addRow(arrayOf(name))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
