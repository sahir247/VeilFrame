package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream

/**
 * Page sizes for PDF creation.
 */
enum class PageSize(val rectangle: PDRectangle) {
    A4(PDRectangle.A4),
    LETTER(PDRectangle.LETTER),
    LEGAL(PDRectangle.LEGAL),
    FIT_IMAGE(PDRectangle.A4) // Will be adjusted to fit image
}

/**
 * Image formats for PDF to image conversion.
 * WebP is recommended as default for best compression-quality balance.
 */
enum class ImageFormat(val extension: String, val mimeType: String) {
    WEBP("webp", "image/webp"),
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg")
}

/**
 * Handles image to PDF and PDF to image conversion operations.
 */
/** Preflight the pinned PDFBox render geometry before allocating a bitmap. */
internal object PdfImageRasterBudget {
    fun requireFits(widthPt: Float,heightPt: Float,dpi: Int) {
        require(widthPt.isFinite() && heightPt.isFinite() && widthPt>0f && heightPt>0f && dpi in 36..600) {
            "Invalid PDF image geometry or resolution"
        }
        val scale=dpi/72f
        val width=kotlin.math.ceil(widthPt.toDouble()*scale)
        val height=kotlin.math.ceil(heightPt.toDouble()*scale)
        require(width in 1.0..8192.0 && height in 1.0..8192.0 && width*height<=16_000_000.0) {
            "Page exceeds the 16 megapixel raster budget; lower the export DPI"
        }
    }
}

class ImageConverter {
    /** One bounded, oriented source image at a time. Alpha is never discarded. */
    suspend fun imagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        outputStream: OutputStream,
        pageSize: PageSize = PageSize.A4,
        quality: Int = 85,
        onProgress: (Float) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            require(imageUris.size in 1..10_000 && quality in 1..100) { "Invalid image count or quality" }
            PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { document ->
                imageUris.forEachIndexed { index,uri ->
                    ensureActive()
                    MergeImageSource.open(context,uri).use { source ->
                        val bitmap=source.decode()
                        try {
                            addImageAsPage(document,bitmap,pageSize,quality)
                        } finally { bitmap.recycle() }
                    }
                    onProgress((index+1).toFloat()/imageUris.size)
                }
                exportVerifiedPdf(context,document,imageUris.size) {
                    object : java.io.FilterOutputStream(outputStream) {
                        override fun write(b: ByteArray,off: Int,len: Int) = out.write(b,off,len)
                        override fun close() = flush()
                    }
                }
            }
            Result.success(imageUris.size)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: OutOfMemoryError) {
            Result.failure(IllegalStateException("Image conversion exceeded available memory",failure))
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    /** Render one RGB bitmap per page. The callback borrows it until return. */
    suspend fun pdfToImages(
        context: Context,
        inputUri: Uri,
        format: ImageFormat = ImageFormat.PNG,
        dpi: Int = 150,
        pageNumbers: List<Int>? = null,
        outputCallback: suspend (pageNumber: Int, bitmap: Bitmap) -> Unit,
        onProgress: (Float) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            require(dpi in 36..600) { "Unsupported image export DPI" }
            val source = context.contentResolver.openInputStream(inputUri)
                ?: throw java.io.IOException("Cannot open input PDF")
            val doc = source.use { PDDocument.load(it,MemoryUsageSetting.setupTempFileOnly()) }
            doc.use { document ->
                val total=document.numberOfPages
                require(total in 1..10_000) { "Unsupported PDF page count" }
                val pages=pageNumbers ?: (1..total).toList()
                require(pages.isNotEmpty() && pages.size<=total && pages.toSet().size==pages.size &&
                    pages.all { it in 1..total }) { "Invalid or duplicate page numbers" }
                // Admit all page geometry before emitting the first callback.
                for (page in pages) {
                    ensureActive()
                    val crop=document.getPage(page-1).cropBox
                    PdfImageRasterBudget.requireFits(crop.width,crop.height,dpi)
                }
                val renderer=PDFRenderer(document)
                for ((index,page) in pages.withIndex()) {
                    ensureActive()
                    val bitmap=renderer.renderImageWithDPI(page-1,dpi.toFloat())
                    try {
                        ensureActive()
                        require(!bitmap.isRecycled && bitmap.width in 1..8192 && bitmap.height in 1..8192 &&
                            bitmap.width.toLong()*bitmap.height<=16_000_000L) { "Renderer exceeded admitted geometry" }
                        outputCallback(page,bitmap)
                    } finally { if (!bitmap.isRecycled) bitmap.recycle() }
                    onProgress((index+1).toFloat()/pages.size)
                }
                Result.success(pages.size)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: OutOfMemoryError) {
            Result.failure(IllegalStateException("Page rasterization exceeded available memory; lower DPI",failure))
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    private fun addImageAsPage(document: PDDocument,bitmap: Bitmap,pageSize: PageSize,quality: Int) {
        val rect=if (pageSize==PageSize.FIT_IMAGE) PDRectangle(bitmap.width.toFloat(),bitmap.height.toFloat()) else pageSize.rectangle
        val page=PDPage(rect)
        document.addPage(page)
        val image=if (!bitmap.hasAlpha() && bitmap.width.toLong()*bitmap.height>1024L*1024)
            JPEGFactory.createFromImage(document,bitmap,quality/100f)
        else LosslessFactory.createFromImage(document,bitmap)
        val scale=minOf(rect.width/bitmap.width,rect.height/bitmap.height)
        val w=bitmap.width*scale
        val h=bitmap.height*scale
        PDPageContentStream(document,page).use {
            it.drawImage(image,(rect.width-w)/2f,(rect.height-h)/2f,w,h)
        }
    }
}
