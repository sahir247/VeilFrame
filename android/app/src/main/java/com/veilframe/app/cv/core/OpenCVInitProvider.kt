package com.veilframe.app.cv.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * OpenCVInitProvider — loads the OpenCV native library at process start,
 * BEFORE Application.onCreate runs (ContentProvider ordering guarantee).
 *
 * This is the safety net that WeChatQrEngine's comments always claimed existed
 * (CV_RELIABILITY_UPGRADE_PLAN finding CV-2): previously the ONLY initLocal()
 * call lived inside the QR engine install; if it failed, every non-QR CV path
 * (document scanner, background remover, image quality) died with
 * UnsatisfiedLinkError inside silent catch-alls.
 *
 * Registered in AndroidManifest.xml with initOrder so it runs ahead of other
 * providers. All data methods are no-ops; this provider exists purely for its
 * onCreate() side effect.
 */
class OpenCVInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        CvRuntime.initialize()
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
