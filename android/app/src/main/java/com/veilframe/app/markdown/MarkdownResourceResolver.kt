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
        var current = relativePath.trim()
        for (i in 0..2) {
            val decoded = try {
                URLDecoder.decode(current, "UTF-8")
            } catch (_: Exception) {
                current
            }
            if (decoded == current) break
            current = decoded
        }
        val normalizedPath = File(current).normalize().path.replace('\\', '/')
        return normalizedPath.startsWith("../") || normalizedPath.contains("/../") || normalizedPath.startsWith("/") ||
               normalizedPath == ".." || current.startsWith("..") || current.contains("/..")
    }

    /**
     * Intercepts and resolves a resource request for an image relative to [baseUriString].
     */
    fun resolveResource(relativeRelPath: String, baseUriString: String?): WebResourceResponse? {
        try {
            // Path traversal guard: block paths that start with or navigate above root
            if (isPathTraversal(relativeRelPath)) {
                Log.w(TAG, "Blocked path traversal attempt: $relativeRelPath")
                return createErrorResponse("Path traversal forbidden")
            }

            var current = relativeRelPath.trim()
            for (i in 0..2) {
                val decoded = try {
                    URLDecoder.decode(current, "UTF-8")
                } catch (_: Exception) {
                    current
                }
                if (decoded == current) break
                current = decoded
            }
            val normalizedPath = File(current).normalize().path.replace('\\', '/')

            val ext = normalizedPath.substringAfterLast('.', "").lowercase(Locale.US)
            val mimeType = supportedImageMimes[ext] ?: return null

            if (baseUriString.isNullOrBlank()) return null
            val baseUri = Uri.parse(baseUriString)

            val stream: InputStream? = when (baseUri.scheme) {
                "file" -> {
                    val baseFile = File(baseUri.path ?: "")
                    val parentDir = if (baseFile.isDirectory) baseFile else baseFile.parentFile
                    if (parentDir == null) null else {
                        val parentCanonical = parentDir.canonicalFile
                        val targetFile = File(parentCanonical, normalizedPath).canonicalFile
                        val parentPath = parentCanonical.toPath().normalize()
                        val targetPath = targetFile.toPath().normalize()

                        // Verify target is strictly within parent directory tree
                        if (targetPath.startsWith(parentPath) && targetFile.exists() && targetFile.isFile) {
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
