package com.vouchflow.sdk.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.vouchflow.sdk.Vouchflow

/**
 * ContentProvider that captures [applicationContext] before [Vouchflow.configure] is ever called.
 *
 * The OS starts ContentProviders before [android.app.Application.onCreate], so by the time the
 * developer calls `Vouchflow.configure(config)`, the context is already captured. This is the
 * same pattern used by Firebase and WorkManager — it keeps the public API context-free.
 *
 * Declared in the SDK's AndroidManifest.xml with `android:authorities="${applicationId}.vouchflowinit"`
 * so it merges into the host app's manifest with a unique authority per app.
 */
internal class VouchflowInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        Vouchflow.applicationContext = ctx.applicationContext
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
