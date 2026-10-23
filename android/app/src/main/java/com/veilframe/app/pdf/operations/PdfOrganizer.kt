package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Result of an organize operation.
 */
data class OrganizeResult(
    val originalPageCount: Int,
    val resultPageCount: Int,
    val pagesRemoved: Int = 0,
    val pagesReordered: Boolean = false
)

/**
 * Target format for page extraction.
 */
enum class ExtractTargetFormat {
    PDF,
    IMAGE_WEBP,
    IMAGE_PNG,
    IMAGE_JPEG
}

/**
 * Handles PDF page organization operations:
 * - Removing pages
 * - Reordering pages
 * - Extracting pages as PDF
 * - Extracting pages as individual Images (WebP/PNG/JPEG)
 */
class PdfOrganizer {
    
    /**
     * Remove specific pages from a PDF.
     */
    suspend fun removePages(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        pagesToRemove: Set<Int>,
        onProgress: (Float) -> Unit = {}
    ): Result<OrganizeResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        try {
            onProgress(0.1f)
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open input file"))
            
            document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
            val originalPageCount = document.numberOfPages
            if (originalPageCount == 0) return@withContext Result.failure(IllegalStateException("PDF has no pages"))
            
            val invalidPages = pagesToRemove.filter { it < 1 || it > originalPageCount }
            if (invalidPages.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid page numbers: $invalidPages (total: $originalPageCount)")
                )
            }
            if (pagesToRemove.size >= originalPageCount) {
                return@withContext Result.failure(IllegalArgumentException("Cannot remove all pages from the document"))
            }
            
            onProgress(0.3f)
            val sortedPagesToRemove = pagesToRemove.sortedDescending()
            sortedPagesToRemove.forEachIndexed { index, pageNum ->
                document.removePage(pageNum - 1)
                onProgress(0.3f + (0.5f * (index + 1).toFloat() / sortedPagesToRemove.size))
            }
            
            onProgress(0.9f)
            document.save(outputStream)
            outputStream.flush()
            onProgress(1.0f)
            
            Result.success(
                OrganizeResult(
                    originalPageCount = originalPageCount,
                    resultPageCount = originalPageCount - pagesToRemove.size,
                    pagesRemoved = pagesToRemove.size,
                    pagesReordered = false
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Reorder pages in a PDF according to a new order.
     */
    suspend fun reorderPages(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        newOrder: List<Int>,
        onProgress: (Float) -> Unit = {}
    ): Result<OrganizeResult> = withContext(Dispatchers.IO) {
        var sourceDocument: PDDocument? = null
        var resultDocument: PDDocument? = null
        try {
            onProgress(0.1f)
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open input file"))
            
            sourceDocument = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
            val originalPageCount = sourceDocument.numberOfPages
            if (originalPageCount == 0) return@withContext Result.failure(IllegalStateException("PDF has no pages"))
            
            val invalidPages = newOrder.filter { it < 1 || it > originalPageCount }
            if (invalidPages.isNotEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Invalid page numbers: $invalidPages"))
            }
            if (newOrder.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("New order cannot be empty"))
            }
            
            onProgress(0.2f)
            resultDocument = PDDocument()
            newOrder.forEachIndexed { index, pageNum ->
                val page = sourceDocument.getPage(pageNum - 1)
                resultDocument.importPage(page)
                onProgress(0.2f + (0.7f * (index + 1).toFloat() / newOrder.size))
            }
            
            onProgress(0.95f)
            resultDocument.save(outputStream)
            outputStream.flush()
            onProgress(1.0f)
            
            Result.success(
                OrganizeResult(
                    originalPageCount = originalPageCount,
                    resultPageCount = newOrder.size,
                    pagesRemoved = originalPageCount - newOrder.size,
                    pagesReordered = true
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sourceDocument?.close()
            resultDocument?.close()
        }
    }

    /**
     * Extract specific pages as a new PDF document.
     */
    suspend fun extractPagesAsPdf(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        pagesToExtract: List<Int>,
        onProgress: (Float) -> Unit = {}
    ): Result<OrganizeResult> {
        return reorderPages(context, inputUri, outputStream, pagesToExtract, onProgress)
    }

    /**
     * Extract specific pages directly as separate raster images (WebP, PNG, JPEG).
     */
    suspend fun extractPagesAsImages(
        context: Context,
        inputUri: Uri,
        pagesToExtract: List<Int>,
        format: ImageFormat = ImageFormat.WEBP,
        dpi: Int = 150,
        outputCallback: suspend (pageNumber: Int, bitmap: Bitmap) -> Unit,
        onProgress: (Float) -> Unit = {}
    ): Result<Int> {
        val imageConverter = ImageConverter()
        return imageConverter.pdfToImages(
            context = context,
            inputUri = inputUri,
            format = format,
            dpi = dpi,
            pageNumbers = pagesToExtract,
            outputCallback = outputCallback,
            onProgress = onProgress
        )
    }
}
