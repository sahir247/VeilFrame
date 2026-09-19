package com.veilframe.app.storage

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Custom SAF ActivityResultContract that accepts Pair(suggestedName, mimeType)
 * and passes the concrete MIME type to ACTION_CREATE_DOCUMENT instead of wildcard.
 */
class CreateDocumentWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (suggestedName, mimeType) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, suggestedName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (intent == null || resultCode != Activity.RESULT_OK) null else intent.data
    }
}

/**
 * Storage and SAF operations manager for VeilFrame Mobile Hub.
 */
class SafStorageManager(private val context: Context) {

    fun persistReadPermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}
    }

    fun persistReadWritePermission(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Provider may only grant read or already persisted
            persistReadPermission(uri)
        }
    }

    fun takePersistablePermission(uri: Uri) {
        // Safe default: request read-write if possible, falling back to read
        persistReadWritePermission(uri)
    }

    fun getDisplayName(uri: Uri): String {
        try {
            if (DocumentsContract.isTreeUri(uri)) {
                val doc = DocumentFile.fromTreeUri(context, uri)
                val docName = doc?.name
                if (!docName.isNullOrBlank()) return docName
            }
        } catch (_: Exception) {
            // Provider-dependent Tree URI fallback
        }

        var name = "unknown_file"
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index) ?: name
                    }
                }
            }
        } catch (_: Exception) {
            // SecurityException / provider failure
        }
        return name
    }

    fun queryFileSize(uri: Uri): Long {
        try {
            if (DocumentsContract.isTreeUri(uri)) {
                val doc = DocumentFile.fromTreeUri(context, uri)
                if (doc != null && doc.isDirectory) {
                    return 0L
                }
            }
        } catch (_: Exception) {}

        var size = 0L
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) {
                        size = it.getLong(index)
                    }
                }
            }
        } catch (_: Exception) {}
        return size
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }

    suspend fun copyUriToFile(uri: Uri, destination: File): Boolean = withContext(Dispatchers.IO) {
        try {
            destination.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun copyFileToUri(sourceFile: File, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(destUri)?.use { outStream ->
                sourceFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun materializeTargetIntoDir(
        targetUri: Uri,
        isFolder: Boolean,
        destDir: File
    ): File = withContext(Dispatchers.IO) {
        destDir.deleteRecursively()
        destDir.mkdirs()

        if (!isFolder) {
            val fileName = getDisplayName(targetUri)
            val destFile = File(destDir, fileName)
            copyUriToFile(targetUri, destFile)
            return@withContext destFile
        }

        val rootDoc = try {
            DocumentFile.fromTreeUri(context, targetUri)
        } catch (e: Exception) {
            null
        } ?: throw IllegalStateException("Cannot access root directory via SAF: provider access failure.")

        suspend fun copyDocRecursive(doc: DocumentFile, currentDir: File) {
            val children = try {
                doc.listFiles()
            } catch (e: Exception) {
                emptyArray()
            }
            for (child in children) {
                val name = child.name ?: continue
                try {
                    if (child.isDirectory) {
                        val sub = File(currentDir, name)
                        sub.mkdirs()
                        copyDocRecursive(child, sub)
                    } else if (child.isFile) {
                        val target = File(currentDir, name)
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip unreadable file or permission error on individual file
                }
            }
        }

        copyDocRecursive(rootDoc, destDir)
        return@withContext destDir
    }

    fun zipDirectory(sourceDir: File, zipFile: File) {
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(sourceDir).path.replace('\\', '/')
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    fun shareFile(file: File, mimeType: String, title: String = "Share Artifact") {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun getMimeTypeForFile(file: File): String = getExportMimeType(file)

    fun getExportMimeType(file: File): String = Companion.getExportMimeType(file)

    companion object {
        fun getExportMimeType(file: File): String {
            return when (file.extension.lowercase(Locale.US)) {
                "aibundle" -> "application/octet-stream"
                "md", "markdown", "mdown", "mkdn" -> "text/markdown"
                "txt" -> "text/plain"
                "json" -> "application/json"
                "html", "htm" -> "text/html"
                "csv" -> "text/csv"
                "zip" -> "application/zip"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "avi" -> "video/x-msvideo"
                "3gp" -> "video/3gpp"
                "flv" -> "video/x-flv"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "tiff", "tif" -> "image/tiff"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                "avif" -> "image/avif"
                else -> "application/octet-stream"
            }
        }
    }
}
