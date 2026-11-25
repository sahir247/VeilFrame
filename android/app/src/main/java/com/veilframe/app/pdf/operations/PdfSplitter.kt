package com.veilframe.app.pdf.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.File
import java.io.InputStream
import com.veilframe.app.pdf.util.MemoryGuard

/**
 * Defines different split modes for PDF splitting.
 */
sealed class SplitMode {
    /** Split into individual pages - one file per page */
    object AllPages : SplitMode()
    
    /** Split by specific page ranges */
    data class ByRanges(val ranges: List<PageRange>) : SplitMode()
    
    /** Split every N pages */
    data class EveryNPages(val n: Int) : SplitMode()
    
    /** Extract specific pages */
    data class SpecificPages(val pages: List<Int>) : SplitMode()
}

/**
 * Represents a range of pages (1-indexed, inclusive).
 */
data class PageRange(val start: Int, val end: Int) {
    init {
        require(start >= 1) { "Start page must be >= 1" }
        require(end >= start) { "End page must be >= start page" }
    }
}

/**
 * Result of a split operation.
 */
data class SplitResult(
    val totalFilesCreated: Int,
    val totalPagesProcessed: Int
)

/**
 * Handles PDF split operations.
 * Splits a PDF into multiple documents based on various criteria.
 */
class PdfSplitter {
    
    /**
     * Split a PDF into individual pages.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to split
     * @param outputCallback Callback for each split document (pageNumber, inputStream -> Unit)
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Result with split statistics
     */
    suspend fun splitAllPages(
        context: Context,
        inputUri: Uri,
        outputCallback: suspend (pageNumber: Int, inputStream: java.io.InputStream) -> Unit,
        onProgress: (Float) -> Unit = {}
    ): Result<SplitResult> = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("splitAllPages")
        var document: PDDocument? = null
        
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )

            inputStream.use {
                document = PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly())
            }
            val loadedDocument = document ?: return@withContext Result.failure(
                IllegalStateException("Failed to load input PDF")
            )
            val totalPages = loadedDocument.numberOfPages
            
            if (totalPages == 0) {
                return@withContext Result.failure(
                    IllegalStateException("PDF has no pages")
                )
            }
            
            for (pageIndex in 0 until totalPages) {
                ensureActive()
                // Create new document with single page
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { newDoc ->
                    val page = loadedDocument.getPage(pageIndex)
                    newDoc.importPageWithResources(page)
                    
                    publishPart(context, newDoc, pageIndex + 1, outputCallback)
                }
                
                onProgress((pageIndex + 1).toFloat() / totalPages)
            }
            
            Result.success(SplitResult(totalPages, totalPages))
            
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Split PDF by page ranges.
     */
    suspend fun splitByRanges(
        context: Context,
        inputUri: Uri,
        ranges: List<PageRange>,
        outputCallback: suspend (rangeIndex: Int, inputStream: java.io.InputStream) -> Unit,
        onProgress: (Float) -> Unit = {}
    ): Result<SplitResult> = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("splitByRanges")
        var document: PDDocument? = null
        
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )

            inputStream.use {
                document = PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly())
            }
            val loadedDocument = document ?: return@withContext Result.failure(
                IllegalStateException("Failed to load input PDF")
            )
            val totalPages = loadedDocument.numberOfPages
            var totalPagesProcessed = 0
            
            require(ranges.isNotEmpty() && ranges.all { it.start <= totalPages && it.end <= totalPages }) {
                "All split ranges must be within the $totalPages source pages"
            }
            ranges.forEachIndexed { rangeIndex, range ->
                ensureActive()
                
                PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { newDoc ->
                    for (pageNum in range.start..range.end) {
                        ensureActive()
                        val page = loadedDocument.getPage(pageNum - 1) // 0-indexed
                        newDoc.importPageWithResources(page)
                        totalPagesProcessed++
                    }
                    
                    publishPart(context, newDoc, rangeIndex + 1, outputCallback)
                }
                
                onProgress((rangeIndex + 1).toFloat() / ranges.size)
            }
            
            Result.success(SplitResult(ranges.size, totalPagesProcessed))
            
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Extract specific pages from a PDF into a new document.
     */
    suspend fun extractPages(
        context: Context,
        inputUri: Uri,
        pageNumbers: List<Int>, // 1-indexed
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("extractPages")
        var document: PDDocument? = null
        
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )

            inputStream.use {
                document = PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly())
            }
            val loadedDocument = document ?: return@withContext Result.failure(
                IllegalStateException("Failed to load input PDF")
            )
            val totalPages = loadedDocument.numberOfPages
            
            require(pageNumbers.isNotEmpty()) { "No pages selected" }
            // Validate page numbers
            val invalidPages = pageNumbers.filter { it < 1 || it > totalPages }
            if (invalidPages.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid page numbers: $invalidPages (document has $totalPages pages)")
                )
            }
            
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { newDoc ->
                pageNumbers.forEachIndexed { index, pageNum ->
                    ensureActive()
                    val page = loadedDocument.getPage(pageNum - 1) // 0-indexed
                    newDoc.importPageWithResources(page)
                    onProgress((index + 1).toFloat() / pageNumbers.size)
                }
                
                ensureActive()
                newDoc.save(outputStream)
            }
            
            Result.success(pageNumbers.size)
            
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Get the page count of a PDF.
     */
    suspend fun getPageCount(
        context: Context,
        uri: Uri
    ): Int = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("getPageCount")
        var document: PDDocument? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
                document?.numberOfPages ?: 0
            } ?: 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            0
        } finally {
            document?.close()
        }
    }
    
    /** A callback may consume the staged file only during its invocation. */
    private suspend fun publishPart(
        context: Context,
        document: PDDocument,
        index: Int,
        callback: suspend (Int, InputStream) -> Unit
    ) {
        val staged = File.createTempFile("vf_split_part_", ".pdf", context.cacheDir)
        try {
            currentCoroutineContext().ensureActive()
            document.save(staged)
            currentCoroutineContext().ensureActive()
            staged.inputStream().buffered().use { callback(index, it) }
            currentCoroutineContext().ensureActive()
        } finally {
            staged.delete()
        }
    }
}
