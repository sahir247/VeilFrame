package com.veilframe.app.pdf.operations

import android.content.Context
import com.tom_roush.pdfbox.cos.COSName
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
            
            requirePdfRewriteAllowed(document)
            if (config.flattenAnnotations && !config.flattenForms &&
                document.documentCatalog.acroForm?.fields?.isNotEmpty() == true) {
                throw IOException("Flattening annotations while retaining form widgets is unsupported")
            }
            if (config.rasterizeContent && (!config.flattenAnnotations || !config.flattenForms)) {
                throw IOException("Rasterization requires explicit flattening of forms and annotations")
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
                    val crop = page.cropBox
                    val dpi = OcrDpiUtil.getSafeOcrDpi(crop.width, crop.height)
                    val bitmap = renderer.renderImageWithDPI(pageIndex, dpi)

                    try {
                        val imageXObject = LosslessFactory.createFromImage(document, bitmap)
                        PDPageContentStream(
                            document, page,
                            PDPageContentStream.AppendMode.OVERWRITE,
                            true, true
                        ).use { cs ->
                            cs.drawImage(imageXObject, rasterPlacement(crop, page.rotation))
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
            exportVerifiedPdf(context, document, totalPages, verify = { saved ->
                if (config.flattenForms && saved.documentCatalog.acroForm?.fields?.isNotEmpty() == true) {
                    throw IOException("Form fields remain after saving")
                }
                if (config.flattenAnnotations && saved.pages.any { it.annotations.isNotEmpty() }) {
                    throw IOException("Annotations remain after saving")
                }
                PdfActiveContentSanitizer.verify(saved, config.removeJavaScript, config.removeEmbeddedFiles)
            }) {
                context.contentResolver.openOutputStream(outputUri)
                    ?: throw IOException("Cannot open destination URI for writing: $outputUri")
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
        if (form.hasXFA()) throw IOException("XFA form flattening is not supported")
        if (form.fields.isEmpty()) return 0
        val fields = form.fieldTree.toList()
        val attached = document.pages.flatMap { page -> page.annotations.map { it.cosObject } }.toSet()
        // PDFBox flatten checks hidden/invisible, but a NoView widget may still
        // be painted. A print-only/NoView or conditional-visibility widget cannot
        // be represented faithfully as unconditional page content.
        for (field in fields) {
            for (widget in field.widgets) {
                if (widget.cosObject !in attached || widget.isHidden || widget.isInvisible) continue
                if (widget.isNoView || widget.isNoRotate || widget.cosObject.containsKey(COSName.getPDFName("OC"))) {
                    throw IOException("Cannot flatten conditional-visibility or non-rotating widget: ${field.fullyQualifiedName}")
                }
                val rect = widget.rectangle
                if (rect == null || !rect.width.isFinite() || !rect.height.isFinite() ||
                    rect.width <= 0f || rect.height <= 0f) {
                    throw IOException("Cannot flatten widget with invalid geometry: ${field.fullyQualifiedName}")
                }
            }
        }
        // Do not regenerate unplaced fields, such as document-level hidden values.
        // Preserve valid original appearance streams unless the source requests refresh.
        val visibleFields = fields.filter { field ->
            field.widgets.any { widget ->
                widget.cosObject in attached && !widget.isHidden && !widget.isInvisible
            }
        }
        val refresh = visibleFields.filter { field ->
            form.needAppearances || field.widgets.any { it.cosObject in attached && it.normalAppearanceStream == null }
        }
        if (refresh.isNotEmpty()) form.refreshAppearances(refresh)
        for (field in visibleFields) {
            for (widget in field.widgets) {
                if (widget.cosObject !in attached || widget.isHidden || widget.isInvisible) continue
                val box = widget.normalAppearanceStream?.bBox
                if (box == null || box.width <= 0f || box.height <= 0f) {
                    throw IOException("Cannot preserve visible form field appearance: ${field.fullyQualifiedName}")
                }
            }
        }
        form.setNeedAppearances(false)
        form.flatten()
        if (form.fields.isNotEmpty()) throw IOException("Some form fields could not be flattened")
        return fields.size
    }

    /**
     * Flatten annotations on a page.
     * This renders annotations into the page content stream.
     */
    internal fun flattenPageAnnotations(document: PDDocument, page: PDPage): Int {
        val annotations = page.annotations.toList()
        // Preflight all annotations: do not silently remove ones with missing geometry.
        for (annotation in annotations) {
            // Hidden annotations should never become visible page content.
            if (annotation.isHidden || annotation.isInvisible || annotation.isNoView) continue
            if (annotation.isNoRotate || annotation.isNoZoom || annotation.cosObject.containsKey(COSName.getPDFName("OC"))) {
                throw IOException("Cannot flatten an annotation with conditional visibility or view-dependent geometry")
            }
            val rect = annotation.rectangle
            val appearance = annotation.normalAppearanceStream
            val bbox = appearance?.bBox
            if (rect == null || appearance == null || bbox == null ||
                listOf(rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY,
                    bbox.lowerLeftX, bbox.lowerLeftY, bbox.upperRightX, bbox.upperRightY).any { !it.isFinite() } ||
                rect.width <= 0f || rect.height <= 0f || bbox.width <= 0f || bbox.height <= 0f) {
                throw IOException("Annotation cannot be flattened without valid geometry and an appearance; use a supported PDF")
            }
        }
        for (annotation in annotations) {
            if (annotation.isHidden || annotation.isInvisible || annotation.isNoView) continue
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
        PdfActiveContentSanitizer.remove(document, javascript = true, attachments = false)
    }

    private fun removeEmbeddedFiles(document: PDDocument) {
        PdfActiveContentSanitizer.remove(document, javascript = false, attachments = true)
    }
}

/** Maps a PDFRenderer image of the rotated crop box back into unrotated page space. */
internal fun rasterPlacement(crop: PDRectangle, rotation: Int): com.tom_roush.pdfbox.util.Matrix {
    val x = crop.lowerLeftX
    val y = crop.lowerLeftY
    val w = crop.width
    val h = crop.height
    require(x.isFinite() && y.isFinite() && w.isFinite() && h.isFinite() && w > 0 && h > 0)
    return when (((rotation % 360) + 360) % 360) {
        0 -> com.tom_roush.pdfbox.util.Matrix(w, 0f, 0f, h, x, y)
        90 -> com.tom_roush.pdfbox.util.Matrix(0f, h, -w, 0f, x + w, y)
        180 -> com.tom_roush.pdfbox.util.Matrix(-w, 0f, 0f, -h, x + w, y + h)
        270 -> com.tom_roush.pdfbox.util.Matrix(0f, -h, w, 0f, x, y + h)
        else -> throw IOException("Unsupported PDF rotation")
    }
}
