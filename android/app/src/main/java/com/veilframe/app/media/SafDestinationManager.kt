package com.veilframe.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

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

    /**
     * Publish a newly created document and verify its bytes by reopening it.
     * Never overwrite a pre-existing document; only the returned created URI
     * belongs to the caller's batch. Providers need not implement atomic writes.
     */
    fun copyVerifiedFileToDocumentTree(
        file: File, treeUri: Uri, mimeType: String, checkpoint: () -> Unit
    ): Uri {
        val expectedLength = file.length()
        require(file.isFile && expectedLength > 0L) { "Invalid encoded output" }
        checkpoint()
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Destination unavailable")
        val created = tree.createFile(mimeType, file.name) ?: throw IOException("Destination refused output")
        try {
            val sourceDigest = MessageDigest.getInstance("SHA-256")
            val targetDigest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            val output = context.contentResolver.openOutputStream(created.uri, "wt")
                ?: throw IOException("Destination returned no output stream")
            output.use { sink ->
                file.inputStream().use { input ->
                    while (true) {
                        checkpoint()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0 || n.toLong() > expectedLength - written) throw IOException("Encoded output changed during transfer")
                        sink.write(buffer, 0, n)
                        sourceDigest.update(buffer, 0, n)
                        written += n
                    }
                }
            }
            check(written == expectedLength && file.length() == expectedLength) { "Incomplete destination write" }
            var read = 0L
            val reopened = context.contentResolver.openInputStream(created.uri)
                ?: throw IOException("Destination cannot be reopened for verification")
            reopened.use { input ->
                while (true) {
                    checkpoint()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0 || n.toLong() > expectedLength - read) throw IOException("Destination length mismatch")
                    targetDigest.update(buffer, 0, n)
                    read += n
                }
            }
            check(read == expectedLength && MessageDigest.isEqual(sourceDigest.digest(), targetDigest.digest())) {
                "Destination content verification failed"
            }
            checkpoint()
            return created.uri
        } catch (failure: Throwable) {
            if (!deleteCreatedDocument(created.uri)) {
                Log.e(TAG, "Provider refused cleanup of an incomplete newly created document")
                if (failure is kotlinx.coroutines.CancellationException) {
                    failure.addSuppressed(IOException("Destination cleanup failed; a partial file may remain"))
                } else {
                    throw IOException("Destination transfer failed and cleanup was refused; a partial file may remain", failure)
                }
            }
            throw failure
        }
    }

    /** Only use with URIs returned from this batch's createFile calls. */
    fun deleteCreatedDocument(uri: Uri): Boolean =
        try { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        catch (failure: Exception) {
            Log.w(TAG, "Provider refused deletion of a newly created document", failure)
            false
        }

    fun copyFileToDocumentTree(file: File, treeUri: Uri, mimeType: String): Uri? {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val newFileDoc = treeDoc.createFile(mimeType, file.name) ?: return null
        return try {
            val outStream = context.contentResolver.openOutputStream(newFileDoc.uri)
                ?: run {
                    Log.e(TAG, "Could not open output stream for ${newFileDoc.uri}")
                    newFileDoc.delete()
                    return null
                }
            outStream.use { target ->
                FileInputStream(file).use { inStream ->
                    inStream.copyTo(target)
                }
            }
            newFileDoc.uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed copying output to document tree: ${e.message}", e)
            runCatching { newFileDoc.delete() }
            null
        }
    }
}
