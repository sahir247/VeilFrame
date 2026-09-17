package com.veilframe.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

/**
 * Manages Storage Access Framework (SAF) tree URIs, persistable permissions,
 * display names, and output file streaming for Video and Image studios.
 */
class SafDestinationManager(private val context: Context) {

    companion object {
        private const val TAG = "VeilFrame.SAF"
        private const val PREFS_NAME = "veilframe_storage_prefs"
        const val KEY_VIDEO_DEST_URI = "veilframe_video_dest_uri"
        const val KEY_IMAGE_DEST_URI = "veilframe_image_dest_uri"
        const val KEY_VIDEO_DEST_NAME = "veilframe_video_dest_name"
        const val KEY_IMAGE_DEST_NAME = "veilframe_image_dest_name"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var videoDestinationUri: Uri?
        get() {
            val uriStr = prefs.getString(KEY_VIDEO_DEST_URI, null) ?: return null
            return try { Uri.parse(uriStr) } catch (_: Exception) { null }
        }
        set(value) {
            prefs.edit().apply {
                if (value != null) putString(KEY_VIDEO_DEST_URI, value.toString())
                else remove(KEY_VIDEO_DEST_URI)
            }.apply()
        }

    var videoDestinationName: String
        get() = prefs.getString(KEY_VIDEO_DEST_NAME, "Movies/VeilFrame") ?: "Movies/VeilFrame"
        set(value) = prefs.edit().putString(KEY_VIDEO_DEST_NAME, value).apply()

    var imageDestinationUri: Uri?
        get() {
            val uriStr = prefs.getString(KEY_IMAGE_DEST_URI, null) ?: return null
            return try { Uri.parse(uriStr) } catch (_: Exception) { null }
        }
        set(value) {
            prefs.edit().apply {
                if (value != null) putString(KEY_IMAGE_DEST_URI, value.toString())
                else remove(KEY_IMAGE_DEST_URI)
            }.apply()
        }

    var imageDestinationName: String
        get() = prefs.getString(KEY_IMAGE_DEST_NAME, "Pictures/VeilFrame") ?: "Pictures/VeilFrame"
        set(value) = prefs.edit().putString(KEY_IMAGE_DEST_NAME, value).apply()

    fun takePersistablePermission(uri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to take persistable URI permission: ${e.message}")
        }
    }

    fun getFolderDisplayName(uri: Uri): String {
        return try {
            val docFile = DocumentFile.fromTreeUri(context, uri)
            val name = docFile?.name
            if (!name.isNullOrEmpty()) name else uri.lastPathSegment ?: "Custom Folder"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Custom Folder"
        }
    }

    fun copyFileToDocumentTree(file: File, treeUri: Uri, mimeType: String): Uri? {
        return try {
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val newFileDoc = treeDoc.createFile(mimeType, file.name) ?: return null
            context.contentResolver.openOutputStream(newFileDoc.uri)?.use { outStream ->
                FileInputStream(file).use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            newFileDoc.uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying output to document tree: ${e.message}", e)
            null
        }
    }
}
