package com.veilframe.app.pdf.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import com.veilframe.app.pdf.util.MemoryGuard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * PDF metadata information.
 */
data class PdfMetadata(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val creator: String?,
    val producer: String?,
    val creationDate: String?,
    val modificationDate: String?,
    val pageCount: Int,
    val fileSize: Long,
    val isEncrypted: Boolean,
    val pdfVersion: String?
)

/**
 * Editable metadata fields.
 */
data class EditableMetadata(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null
)

/**
 * Removes the document Info dictionary and explicit PDF metadata/private
 * application-data entries throughout the reachable catalog graph. This is
 * not a content redactor; metadata within opaque image or attachment bytes
 * is outside the scope of this operation.
 */
internal fun stripPdfMetadata(document: PDDocument) {
    document.documentInformation = PDDocumentInformation()
    visitMetadata(document, true)
}

internal fun verifyStrippedPdfMetadata(document: PDDocument) {
    if (document.documentInformation.cosObject.keySet().isNotEmpty()) {
        throw java.io.IOException("Document information remains after metadata removal")
    }
    visitMetadata(document, false)
}

private fun visitMetadata(document: PDDocument, mutate: Boolean) {
    val metadata = COSName.METADATA
    val pieceInfo = COSName.getPDFName("PieceInfo")
    val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
    val pending = ArrayDeque<COSBase>()
    pending.add(document.documentCatalog.cosObject)
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (!seen.add(node)) continue
        when (node) {
            is COSObject -> node.getObject()?.let { pending.add(it) }
            is COSDictionary -> {
                if (mutate) {
                    node.removeItem(metadata)
                    node.removeItem(pieceInfo)
                } else if (node.containsKey(metadata) || node.containsKey(pieceInfo)) {
                    throw java.io.IOException("Metadata remains after saving PDF")
                }
                for (key in node.keySet()) {
                    node.getItem(key)?.let { pending.add(it) }
                }
            }
            is COSArray -> for (index in 0 until node.size()) {
                node.get(index)?.let { pending.add(it) }
            }
        }
    }
}

/**
 * Handles PDF metadata operations (read/write).
 */
class PdfMetadataManager {
    

    
    /**
     * Read metadata from a PDF.
     * 
     * @param context Android context
     * @param uri URI of the PDF
     * @return PdfMetadata object with all available information
     */
    suspend fun readMetadata(
        context: Context,
        uri: Uri
    ): Result<PdfMetadata> = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("Read Metadata")
        var document: PDDocument? = null
        
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            document = inputStream.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
            val info = document.documentInformation
            
            val metadata = PdfMetadata(
                title = info.title?.takeIf { it.isNotBlank() },
                author = info.author?.takeIf { it.isNotBlank() },
                subject = info.subject?.takeIf { it.isNotBlank() },
                keywords = info.keywords?.takeIf { it.isNotBlank() },
                creator = info.creator?.takeIf { it.isNotBlank() },
                producer = info.producer?.takeIf { it.isNotBlank() },
                creationDate = info.creationDate?.let { formatDate(it) },
                modificationDate = info.modificationDate?.let { formatDate(it) },
                pageCount = document.numberOfPages,
                fileSize = getFileSize(context, uri),
                isEncrypted = document.isEncrypted,
                pdfVersion = document.version.toString()
            )
            
            Result.success(metadata)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Update metadata in a PDF.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to update
     * @param outputStream Output stream for the updated PDF
     * @param metadata New metadata values (null or blank values clear fields)
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return Result indicating success or failure
     */
    suspend fun updateMetadata(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        metadata: EditableMetadata,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("Update Metadata")
        var document: PDDocument? = null
        
        try {
            onProgress(0.2f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            document = inputStream.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
            requirePdfRewriteAllowed(document)
            val info = document.documentInformation
            
            onProgress(0.4f)
            
            // Update fields (null or blank values clear/remove the field)
            info.title = if (metadata.title.isNullOrBlank()) null else metadata.title
            info.author = if (metadata.author.isNullOrBlank()) null else metadata.author
            info.subject = if (metadata.subject.isNullOrBlank()) null else metadata.subject
            info.keywords = if (metadata.keywords.isNullOrBlank()) null else metadata.keywords
            info.creator = if (metadata.creator.isNullOrBlank()) null else metadata.creator
            info.producer = if (metadata.producer.isNullOrBlank()) null else metadata.producer
            
            // Update modification date
            info.modificationDate = Calendar.getInstance()
            
            onProgress(0.7f)
            
            // Save with updated metadata
            ensureActive()
            document.save(outputStream)
            outputStream.flush()
            
            onProgress(1.0f)
            
            Result.success(Unit)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Remove PDF Info, XMP metadata streams and private application data entries.
     * Does not redact visible text or metadata embedded inside attachment/image bytes.
     */
    suspend fun removeMetadata(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        MemoryGuard.checkMemory("Remove Metadata")
        var document: PDDocument? = null
        
        try {
            onProgress(0.2f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            document = inputStream.use { PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly()) }
            
            onProgress(0.4f)
            
            requirePdfRewriteAllowed(document)
            stripPdfMetadata(document)
            
            onProgress(0.7f)
            
            exportVerifiedPdf(context, document, document.numberOfPages, ::verifyStrippedPdfMetadata) {
                // This public API does not own the caller's output stream.
                object : java.io.FilterOutputStream(outputStream) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                    override fun close() = flush()
                }
            }
            
            onProgress(1.0f)
            
            Result.success(Unit)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Format a Calendar date to a readable string.
     */
    private fun formatDate(calendar: Calendar): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(calendar.time)
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Get file size from URI.
     */
    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.available().toLong()
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
