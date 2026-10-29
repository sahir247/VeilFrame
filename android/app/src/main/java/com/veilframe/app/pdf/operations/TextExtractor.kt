package com.veilframe.app.pdf.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.veilframe.app.pdf.util.MemoryGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/**
 * Result of text extraction.
 */
data class TextExtractionResult(
    val text: String,
    val characterCount: Int,
    val wordCount: Int,
    val pageCount: Int,
    val hasText: Boolean
)

/**
 * Options for text extraction.
 */
data class TextExtractionOptions(
    val startPage: Int = 1,
    val endPage: Int = Int.MAX_VALUE,
    val addPageBreaks: Boolean = true,
    val sortByPosition: Boolean = true
)

/**
 * Handles text extraction from PDF documents.
 */
class TextExtractor {
    
    /**
     * Extract text from PDF in memory.
     */
    suspend fun extractText(
        context: Context,
        inputUri: Uri,
        options: TextExtractionOptions = TextExtractionOptions(),
        onProgress: (Float) -> Unit = {}
    ): Result<TextExtractionResult> = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("extractText")
        var inputStream: java.io.InputStream? = null
        var document: PDDocument? = null
        try {
            onProgress(0.1f)
            inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open input file"))
            
            document = PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly())
            val totalPages = document.numberOfPages
            if (totalPages == 0) {
                return@withContext Result.failure(IllegalStateException("PDF has no pages"))
            }
            
            onProgress(0.3f)
            val stripper = PDFTextStripper().apply {
                startPage = options.startPage
                endPage = minOf(options.endPage, totalPages)
                sortByPosition = options.sortByPosition
                if (options.addPageBreaks) {
                    pageEnd = "\n\n--- Page Break ---\n\n"
                }
            }
            
            onProgress(0.6f)
            val extractedText = stripper.getText(document)
            onProgress(0.9f)
            
            val trimmed = extractedText.trim()
            val hasText = trimmed.isNotEmpty()
            val wordCount = if (hasText) trimmed.split("\\s+".toRegex()).size else 0
            
            onProgress(1.0f)
            Result.success(
                TextExtractionResult(
                    text = extractedText,
                    characterCount = trimmed.length,
                    wordCount = wordCount,
                    pageCount = totalPages,
                    hasText = hasText
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                document?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
            try {
                inputStream?.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }
    }

    /**
     * Save raw text string to output stream as UTF-8.
     */
    suspend fun saveTextToFile(
        text: String,
        outputStream: OutputStream
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            OutputStreamWriter(outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(text)
                writer.flush()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
