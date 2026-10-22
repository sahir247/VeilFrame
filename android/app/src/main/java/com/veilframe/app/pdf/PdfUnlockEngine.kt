package com.veilframe.app.pdf

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Offline engine for removing PDF passwords, permissions, and encryption restrictions.
 * Decrypts documents 100% locally on-device without cloud transmission.
 */
object PdfUnlockEngine {

    /**
     * Inspects the encryption state of raw PDF bytes.
     */
    fun checkEncryption(pdfBytes: ByteArray): PdfInitialCheck {
        return try {
            PDDocument.load(ByteArrayInputStream(pdfBytes)).use { doc ->
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
     * Unlocks and strips all encryption/security from the PDF using the supplied password.
     * Renders a preview bitmap of the first page.
     */
    suspend fun unlockPdf(
        pdfBytes: ByteArray,
        password: String
    ): Result<PdfUnlockResult> = withContext(Dispatchers.IO) {
        try {
            val doc = if (password.isNotEmpty()) {
                PDDocument.load(ByteArrayInputStream(pdfBytes), password)
            } else {
                PDDocument.load(ByteArrayInputStream(pdfBytes))
            }

            doc.use { loadedDoc ->
                // Remove all encryption dictionary entries and permissions restrictions
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
                        // Render at 150 DPI for crisp visual presentation
                        previewBmp = renderer.renderImageWithDPI(0, 150f)
                    } catch (e: Exception) {
                        // Preview rendering failure should not block unlock
                    }
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
        } catch (e: InvalidPasswordException) {
            Result.failure(IllegalArgumentException("Incorrect password. Please verify the document password and try again."))
        } catch (e: Exception) {
            Result.failure(e)
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
            PDDocument.load(ByteArrayInputStream(pdfBytes)).use { doc ->
                if (doc.numberOfPages > pageIndex) {
                    val renderer = PDFRenderer(doc)
                    renderer.renderImageWithDPI(pageIndex, dpi)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
