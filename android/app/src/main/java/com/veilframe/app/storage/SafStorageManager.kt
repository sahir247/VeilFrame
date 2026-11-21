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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
        if (uri.scheme == "file") {
            val pathName = uri.lastPathSegment ?: File(uri.path ?: "").name
            if (pathName.isNotBlank()) return pathName
        }

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
        if (name == "unknown_file") {
            val fallback = uri.lastPathSegment ?: File(uri.path ?: "").name
            if (fallback.isNotBlank()) {
                name = fallback
            }
        }
        return name
    }

    fun queryFileSize(uri: Uri): Long {
        if (uri.scheme == "file") {
            try {
                val f = File(uri.path ?: "")
                if (f.exists()) return f.length()
            } catch (_: Exception) {}
        }

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
        var stage: File? = null
        try {
            val parent = requireNotNull(destination.absoluteFile.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create import directory" }
            val temp = File.createTempFile("saf_import_", ".partial", parent)
            stage = temp
            val cc = kotlin.coroutines.coroutineContext
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Provider returned no input stream")
            input.use { source ->
                temp.outputStream().buffered().use { output ->
                    val available = (parent.usableSpace - 25L * 1024 * 1024).coerceAtLeast(0L)
                    copyChecked(source, output, available) { cc.ensureActive() }
                }
            }
            cc.ensureActive()
            Files.move(temp.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            stage?.delete()
        }
    }

    suspend fun copyFileToUri(sourceFile: File, destUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.isFile) return@withContext false
            val expected = sourceFile.length()
            val cc = kotlin.coroutines.coroutineContext
            sourceFile.inputStream().buffered().use { source ->
                val output = context.contentResolver.openOutputStream(destUri, "wt")
                    ?: throw IOException("Provider returned no output stream")
                output.use {
                    val copied = copyChecked(source, it) { cc.ensureActive() }
                    if (copied != expected || sourceFile.length() != expected) {
                        throw IOException("Source changed during export")
                    }
                    it.flush()
                }
            }
            cc.ensureActive()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun materializeTargetIntoDir(
        targetUri: Uri,
        isFolder: Boolean,
        destDir: File
    ): File = withContext(Dispatchers.IO) {
        // Only a fresh private staging directory may be owned by this operation.
        check(!destDir.exists() && destDir.mkdirs()) { "Cannot create fresh SAF staging directory" }
        var completed = false
        try {
            val cc = kotlin.coroutines.coroutineContext
            require(destDir.usableSpace >= 25L * 1024 * 1024) { "Insufficient internal storage to stage SAF target" }
            if (!isFolder) {
                val destFile = safeStagingChild(destDir, getDisplayName(targetUri))
                if (!copyUriToFile(targetUri, destFile)) throw IOException("Could not stage selected file")
                completed = true
                return@withContext destFile
            }
            val rootDoc = DocumentFile.fromTreeUri(context, targetUri)
                ?: throw IOException("Cannot access selected directory")
            val visited = HashSet<String>()
            var entries = 0
            suspend fun copyDocRecursive(doc: Uri, currentDir: File, depth: Int) {
                cc.ensureActive()
                require(depth <= 128 && visited.add(doc.toString())) { "Folder depth or duplicate directory identity exceeded" }
                // Query directly: DocumentFile.listFiles() can swallow provider exceptions.
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    doc, DocumentsContract.getDocumentId(doc))
                val cursor = context.contentResolver.query(childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null) ?: throw IOException("Provider returned no directory cursor")
                data class Child(val uri: Uri, val name: String, val mime: String)
                val children = mutableListOf<Child>()
                cursor.use {
                    if (it.extras.getBoolean(DocumentsContract.EXTRA_LOADING) ||
                        !it.extras.getString(DocumentsContract.EXTRA_ERROR).isNullOrBlank()) {
                        throw IOException("Provider directory listing is incomplete")
                    }
                    while (it.moveToNext()) {
                        cc.ensureActive()
                        require(++entries <= 50_000) { "Folder staging entry limit exceeded" }
                        val id = it.getString(0) ?: throw IOException("Missing document identity")
                        val name = it.getString(1) ?: throw IOException("Missing document name")
                        val mime = it.getString(2) ?: throw IOException("Missing document MIME type")
                        children += Child(DocumentsContract.buildDocumentUriUsingTree(doc, id), name, mime)
                    }
                }
                for (child in children) {
                    cc.ensureActive()
                    val target = safeStagingChild(currentDir, child.name)
                    require(!target.exists()) { "Duplicate folder entry name" }
                    if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        check(target.mkdir()) { "Cannot create staged subdirectory" }
                        copyDocRecursive(child.uri, target, depth + 1)
                    } else {
                        if (!copyUriToFile(child.uri, target)) throw IOException("Could not stage a selected file")
                        if (destDir.usableSpace < 25L * 1024 * 1024) throw IOException("Insufficient staging storage")
                    }
                }
            }
            copyDocRecursive(rootDoc.uri, destDir, 0)
            completed = true
            destDir
        } finally {
            if (!completed) destDir.deleteRecursively()
        }
    }

    fun zipDirectory(sourceDir: File, zipFile: File, checkpoint: () -> Unit = {}) {
        zipFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val entryName = file.relativeTo(sourceDir).path.replace('\\', '/')
                checkpoint()
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { fis ->
                    copyChecked(fis, zos, checkpoint = checkpoint)
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
                clipData = android.content.ClipData.newUri(context.contentResolver, title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun getMimeTypeForFile(file: File): String = getExportMimeType(file)

    fun getExportMimeType(file: File): String = Companion.getExportMimeType(file)

    companion object {
        internal fun safeStagingChild(parent: File, name: String): File {
            require(name.isNotBlank() && name != "." && name != ".." &&
                name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) {
                "Unsafe provider entry name"
            }
            val child = File(parent, name)
            require(child.canonicalFile.parentFile == parent.canonicalFile) { "Provider entry escapes staging directory" }
            return child
        }

        internal fun copyChecked(input: java.io.InputStream, output: java.io.OutputStream,
                                 maxBytes: Long = Long.MAX_VALUE, checkpoint: () -> Unit = {}): Long {
            require(maxBytes >= 0L)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) return total
                if (count == 0) throw IOException("Provider stream made no progress")
                if (count.toLong() > maxBytes - total) throw IOException("Transfer exceeds its available storage budget")
                output.write(buffer, 0, count)
                total = Math.addExact(total, count.toLong())
            }
        }

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
