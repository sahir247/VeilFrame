package com.veilframe.app.pdf.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Flattening options for PDF.
 */
data class FlattenConfig(
    val flattenAnnotations: Boolean = true,
    val flattenForms: Boolean = true,
    val removeJavaScript: Boolean = true,
    val removeEmbeddedFiles: Boolean = false,
    val rasterizeContent: Boolean = false
)

/**
 * Result of flatten operation.
 */
data class FlattenResult(
    val success: Boolean,
    val annotationsFlattened: Int = 0,
    val formsFlattened: Int = 0,
    val errorMessage: String? = null
)

/**
 * PDF Flattener - Flattens annotations, forms, and interactive elements.
 * This makes the PDF suitable for printing and sharing where interactive
 * elements are not needed.
 * Uses Apache PDFBox-Android (Apache 2.0 License).
 */
class PdfFlattener {
    
    /**
     * Flatten a PDF document.
     * 
     * Flattening merges annotations and form fields into the page content,
     * making them non-editable and ensuring consistent appearance across viewers.
     *
     * @param context Android context for file operations
     * @param inputUri Source PDF file URI
     * @param outputUri Destination PDF file URI
     * @param config Flatten configuration options
     * @param progressCallback Progress callback (0-100)
     * @return FlattenResult with operation status
     */
    suspend fun flattenPdf(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        config: FlattenConfig = FlattenConfig(),
        progressCallback: (Int) -> Unit = {}
    ): FlattenResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var annotationsCount = 0
        var formsCount = 0
        
        try {
            progressCallback(0)
            
            // Load the PDF document
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext FlattenResult(
                    success = false,
                    errorMessage = "Cannot open source PDF"
                )
            
            document = inputStream.use { PDDocument.load(it, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()) }
            
            val totalPages = document.numberOfPages
            if (totalPages == 0) {
                return@withContext FlattenResult(
                    success = false,
                    errorMessage = "PDF has no pages"
                )
            }
            
            progressCallback(10)
            
            // Flatten forms if requested
            if (config.flattenForms) {
                formsCount = flattenForms(document)
                progressCallback(30)
            }
            
            // Flatten annotations on each page
            if (config.flattenAnnotations) {
                for (pageIndex in 0 until totalPages) {
                    ensureActive()
                    val page = document.getPage(pageIndex)
                    annotationsCount += flattenPageAnnotations(document, page)
                    
                    val progress = 30 + ((pageIndex + 1) * 50 / totalPages)
                    progressCallback(progress)
                }
            }
            
            // Rasterize pages if requested — replaces content with images for max compatibility
            if (config.rasterizeContent) {
                val renderer = PDFRenderer(document)
                for (pageIndex in 0 until totalPages) {
                    ensureActive()
                    val page = document.getPage(pageIndex)
                    val dpi = OcrDpiUtil.getSafeOcrDpi(page.mediaBox.width, page.mediaBox.height)
                    val bitmap = renderer.renderImageWithDPI(pageIndex, dpi)

                    try {
                        val imageXObject = LosslessFactory.createFromImage(document, bitmap)
                        val pageRect = page.mediaBox
                        PDPageContentStream(
                            document, page,
                            PDPageContentStream.AppendMode.OVERWRITE,
                            true, true
                        ).use { cs ->
                            cs.drawImage(imageXObject, pageRect.lowerLeftX, pageRect.lowerLeftY, pageRect.width, pageRect.height)
                        }
                        page.setAnnotations(emptyList())
                    } finally {
                        bitmap.recycle()
                    }
                }
            }

            progressCallback(80)
            
            // Remove JavaScript if requested
            if (config.removeJavaScript) {
                removeJavaScript(document)
            }
            
            // Remove embedded files if requested
            if (config.removeEmbeddedFiles) {
                removeEmbeddedFiles(document)
            }
            
            progressCallback(90)
            
            // Verify requested interactivity removal before exposing a result.
            if (config.flattenForms && document.documentCatalog.acroForm?.fields?.isNotEmpty() == true) {
                throw IOException("Some form fields could not be flattened")
            }
            if (config.flattenAnnotations && document.pages.any { it.annotations.isNotEmpty() }) {
                throw IOException("Some annotations could not be flattened")
            }
            ensureActive()
            // Save the flattened document
            val outputStream = context.contentResolver.openOutputStream(outputUri)
                ?: return@withContext FlattenResult(
                    success = false,
                    errorMessage = "Cannot open destination URI for writing: $outputUri"
                )

            outputStream.use { stream ->
                document.save(stream)
                stream.flush()
            }

            progressCallback(100)

            FlattenResult(
                success = true,
                annotationsFlattened = annotationsCount,
                formsFlattened = formsCount,
                errorMessage = null
            )
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            FlattenResult(
                success = false,
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            FlattenResult(
                success = false,
                errorMessage = "Error: ${e.message}"
            )
        } finally {
            document?.close()
        }
    }
    
    /**
     * Flatten all form fields in the document.
     * This renders the field values as regular text and removes the form interactivity.
     */
    internal fun flattenForms(document: PDDocument): Int {
        val form = document.documentCatalog.acroForm ?: return 0
        if (form.fields.isEmpty()) return 0
        val total = form.fieldTree.count()
        // Refresh appearances before flattening so filled values are visible.
        form.refreshAppearances()
        form.flatten()
        if (form.fields.isNotEmpty()) throw IOException("Some form fields could not be flattened")
        return total
    }

    /**
     * Flatten annotations on a page.
     * This renders annotations into the page content stream.
     */
    internal fun flattenPageAnnotations(document: PDDocument, page: PDPage): Int {
        val annotations = page.annotations.toList()
        // Preflight all annotations: do not silently remove ones with missing geometry.
        for (annotation in annotations) {
            val rect = annotation.rectangle
            val appearance = annotation.normalAppearanceStream
            val bbox = appearance?.bBox
            if (rect == null || appearance == null || bbox == null ||
                rect.width <= 0f || rect.height <= 0f || bbox.width <= 0f || bbox.height <= 0f) {
                throw IOException("Annotation cannot be flattened without valid geometry and an appearance; use a supported PDF")
            }
        }
        for (annotation in annotations) {
            val rect = annotation.rectangle
            val appearance = annotation.normalAppearanceStream
            val bbox = appearance.bBox
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                stream.saveGraphicsState()
                // PDF appearance streams may have a non-identity Matrix. Transform
                // the four BBox corners into appearance space before fitting the Rect.
                val m = appearance.matrix
                val points = listOf(
                    m.transformPoint(bbox.lowerLeftX, bbox.lowerLeftY),
                    m.transformPoint(bbox.lowerLeftX, bbox.upperRightY),
                    m.transformPoint(bbox.upperRightX, bbox.lowerLeftY),
                    m.transformPoint(bbox.upperRightX, bbox.upperRightY)
                )
                val minX = points.minOf { it.x }
                val minY = points.minOf { it.y }
                val width = points.maxOf { it.x } - minX
                val height = points.maxOf { it.y } - minY
                if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) throw IOException("Invalid annotation appearance transform")
                val sx = rect.width / width
                val sy = rect.height / height
                stream.transform(com.tom_roush.pdfbox.util.Matrix(
                    sx, 0f, 0f, sy,
                    rect.lowerLeftX - minX * sx,
                    rect.lowerLeftY - minY * sy
                ))
                stream.drawForm(appearance)
                stream.restoreGraphicsState()
            }
        }
        page.setAnnotations(emptyList())
        return annotations.size
    }

    /**
     * Remove JavaScript from the document.
     */
    private fun removeJavaScript(document: PDDocument) {
        try {
            // Remove document-level JavaScript
            val names = document.documentCatalog.names
            if (names != null) {
                // Try to set JavaScript to null
                try {
                    names.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.JAVA_SCRIPT)
                } catch (e: Exception) {
                    // Ignore if cannot remove
                }
            }
            
            // Remove OpenAction only if it contains JavaScript, preserving navigation/destinations
            try {
                val openAction = document.documentCatalog.openAction
                if (openAction is com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript) {
                    document.documentCatalog.setOpenAction(null)
                }
            } catch (e: Exception) {
                // Ignore if cannot remove
            }
            
        } catch (e: Exception) {
            // JavaScript removal may fail for some documents
        }
    }
    
    /**
     * Remove embedded files from the document.
     */
    private fun removeEmbeddedFiles(document: PDDocument) {
        try {
            val names = document.documentCatalog.names
            if (names != null) {
                names.embeddedFiles = null
            }
        } catch (e: Exception) {
            // Embedded files removal may fail
        }
    }
}
