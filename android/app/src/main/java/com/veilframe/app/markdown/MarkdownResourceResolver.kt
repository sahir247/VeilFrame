package com.veilframe.app.markdown

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

/**
 * Safely resolves relative image paths embedded in Markdown documents.
 * Strictly prevents directory traversal (../..) beyond permitted directory boundaries.
 */
class MarkdownResourceResolver(private val context: Context?) {

    companion object {
        private const val TAG = "VeilFrame.MdResolver"
    }

    private val supportedImageMimes = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif"
    )

    fun isPathTraversal(relativePath: String): Boolean {
        val decodedPath = try { URLDecoder.decode(relativePath, "UTF-8").trim() } catch (_: Exception) { relativePath.trim() }
        val normalizedPath = File(decodedPath).normalize().path.replace('\\', '/')
        return normalizedPath.startsWith("../") || normalizedPath.contains("/../") || normalizedPath.startsWith("/")
    }

    /**
     * Intercepts and resolves a resource request for an image relative to [baseUriString].
     */
    fun resolveResource(relativeRelPath: String, baseUriString: String?): WebResourceResponse? {
        try {
            val decodedPath = URLDecoder.decode(relativeRelPath, "UTF-8").trim()
            val normalizedPath = File(decodedPath).normalize().path.replace('\\', '/')

            // Path traversal guard: block paths that start with or navigate above root
            if (isPathTraversal(relativeRelPath)) {
                Log.w(TAG, "Blocked path traversal attempt: $relativeRelPath")
                return createErrorResponse("Path traversal forbidden")
            }

            val ext = normalizedPath.substringAfterLast('.', "").lowercase(Locale.US)
            val mimeType = supportedImageMimes[ext] ?: return null

            if (baseUriString.isNullOrBlank()) return null
            val baseUri = Uri.parse(baseUriString)

            val stream: InputStream? = when (baseUri.scheme) {
                "file" -> {
                    val baseFile = File(baseUri.path ?: "")
                    val parentDir = if (baseFile.isDirectory) baseFile else baseFile.parentFile
                    if (parentDir == null) null else {
                        val targetFile = File(parentDir, normalizedPath).canonicalFile
                        // Verify target is strictly within parent directory tree
                        if (targetFile.canonicalPath.startsWith(parentDir.canonicalPath) && targetFile.exists() && targetFile.isFile) {
                            targetFile.inputStream()
                        } else {
                            Log.w(TAG, "Target file outside permitted directory: ${targetFile.canonicalPath}")
                            null
                        }
                    }
                }
                "content" -> {
                    resolveContentUriRelative(baseUri, normalizedPath)
                }
                else -> null
            }

            return if (stream != null) {
                WebResourceResponse(mimeType, "UTF-8", stream)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving resource: ${e.message}", e)
            return null
        }
    }

    private fun resolveContentUriRelative(baseUri: Uri, relativePath: String): InputStream? {
        val ctx = context ?: return null
        return try {
            val baseDoc = DocumentFile.fromSingleUri(ctx, baseUri)
            val parentUri = baseDoc?.uri ?: baseUri

            // Split segments and traverse DocumentFile children
            val segments = relativePath.split('/').filter { it.isNotEmpty() && it != "." }
            var currentDoc: DocumentFile? = DocumentFile.fromTreeUri(ctx, parentUri) ?: baseDoc

            for ((index, segment) in segments.withIndex()) {
                if (currentDoc == null) break
                val isLast = index == segments.size - 1
                currentDoc = currentDoc.findFile(segment)
                if (isLast && currentDoc != null && currentDoc.isFile) {
                    return ctx.contentResolver.openInputStream(currentDoc.uri)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun createErrorResponse(message: String): WebResourceResponse {
        val stream = ByteArrayInputStream(message.toByteArray(Charsets.UTF_8))
        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", mapOf("Access-Control-Allow-Origin" to "*"), stream)
    }
}
