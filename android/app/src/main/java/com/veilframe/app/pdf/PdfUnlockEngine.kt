package com.veilframe.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Production offline engine for removing PDF passwords, permissions, and encryption restrictions.
 * Decrypts documents 100% locally on-device without cloud transmission.
 * Utilizes MemoryUsageSetting.setupTempFileOnly() to prevent heap exhaustion on large documents.
 */
object PdfUnlockEngine {

    /**
     * Inspects the encryption state of raw PDF bytes.
     */
    fun checkEncryption(pdfBytes: ByteArray): PdfInitialCheck {
        return try {
            PDDocument.load(ByteArrayInputStream(pdfBytes), MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                if (doc.isEncrypted) {
                    PdfInitialCheck.PasswordRequired(pdfBytes)
                } else {
                    PdfInitialCheck.Clear(pdfBytes, doc.numberOfPages, false)
                }
            }
        } catch (e: InvalidPasswordException) {
            PdfInitialCheck.PasswordRequired(pdfBytes)
        } catch (e: Exception) {
            PdfInitialCheck.Failed(e.message ?: "Failed to read PDF document.")
        }
    }

    /**
     * Inspects a PDF stream without treating corrupted or unreadable data as an
     * unencrypted document. The legacy Boolean overload is retained for callers.
     */
    fun checkEncryptionStrict(inputStream: InputStream): Result<Boolean> =
        try {
            PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                Result.success(doc.isEncrypted)
            }
        } catch (e: InvalidPasswordException) {
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Legacy Boolean API; use [checkEncryptionStrict] for untrusted picker input. */
    fun checkEncryption(inputStream: InputStream): Boolean =
        checkEncryptionStrict(inputStream).getOrDefault(false)

    /**
     * Unlocks and strips all encryption/security from the PDF using the supplied password.
     * Uses memory-safe temp file streaming and sets isAllSecurityToBeRemoved = true.
     * Renders a preview bitmap of the first page.
     */
    suspend fun unlockPdf(
        pdfBytes: ByteArray,
        password: String
    ): Result<PdfUnlockResult> = withContext(Dispatchers.IO) {
        try {
            val doc = if (password.isNotEmpty()) {
                PDDocument.load(ByteArrayInputStream(pdfBytes), password, MemoryUsageSetting.setupTempFileOnly())
            } else {
                PDDocument.load(ByteArrayInputStream(pdfBytes), MemoryUsageSetting.setupTempFileOnly())
            }

            doc.use { loadedDoc ->
                // Authoritative security removal: clears encryption dictionary and restrictions
                loadedDoc.isAllSecurityToBeRemoved = true

                val outStream = ByteArrayOutputStream()
                loadedDoc.save(outStream)
                val unlockedBytes = outStream.toByteArray()

                val pageCount = loadedDoc.numberOfPages
                val title = loadedDoc.documentInformation?.title

                // Render first page preview
                var previewBmp: Bitmap? = null
                if (pageCount > 0) {
                    try {
                        val renderer = PDFRenderer(loadedDoc)
                        previewBmp = renderer.renderImageWithDPI(0, 150f)
                    } catch (_: Exception) {}
                }

                Result.success(
                    PdfUnlockResult(
                        unlockedBytes = unlockedBytes,
                        pageCount = pageCount,
                        previewBitmap = previewBmp,
                        originalSizeBytes = pdfBytes.size.toLong(),
                        unlockedSizeBytes = unlockedBytes.size.toLong(),
                        documentTitle = title
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: InvalidPasswordException) {
            Result.failure(IllegalArgumentException("Incorrect password. Please verify the document password and try again."))
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("password") || msg.contains("decrypt")) {
                Result.failure(IllegalArgumentException("Incorrect password. Please verify the document password and try again."))
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * Stream-based memory-safe PDF unlock for large files.
     */
    suspend fun unlockPdfStream(
        inputStream: InputStream,
        outputStream: OutputStream,
        password: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val doc = if (password.isNotEmpty()) {
                PDDocument.load(inputStream, password, MemoryUsageSetting.setupTempFileOnly())
            } else {
                PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
            }

            doc.use { loadedDoc ->
                loadedDoc.isAllSecurityToBeRemoved = true
                loadedDoc.save(outputStream)
                Result.success(loadedDoc.numberOfPages)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: InvalidPasswordException) {
            Result.failure(IllegalArgumentException("Incorrect password"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Bounded on-disk preview for the file-backed unlock workspace.
     * Limits only preview resolution, never decrypted PDF output quality.
     */
    suspend fun renderPagePreview(
        file: File,
        pageIndex: Int = 0,
        maxDimension: Int = 1280
    ): Bitmap? = withContext(Dispatchers.IO) {
        require(maxDimension > 0)
        try {
            file.inputStream().buffered().use { input ->
                PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                    if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
                        null
                    } else {
                        val pageBox = doc.getPage(pageIndex).cropBox
                        val longestPt = maxOf(pageBox.width, pageBox.height).coerceAtLeast(1f)
                        val scale = minOf(150f / 72f, maxDimension / longestPt)
                        PDFRenderer(doc).renderImage(pageIndex, scale)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Renders a preview bitmap of a specific page for an unencrypted or unlocked document.
     */
    suspend fun renderPagePreview(
        pdfBytes: ByteArray,
        pageIndex: Int = 0,
        dpi: Float = 150f
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            PDDocument.load(ByteArrayInputStream(pdfBytes), MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                if (doc.numberOfPages > pageIndex) {
                    val renderer = PDFRenderer(doc)
                    renderer.renderImageWithDPI(pageIndex, dpi)
                } else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            null
        }
    }
}
