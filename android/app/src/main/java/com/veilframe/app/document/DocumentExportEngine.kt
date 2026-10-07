package com.veilframe.app.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/**
 * Advanced multi-format document export architecture:
 * - Output Formats: Single Combined PDF, Separate Individual PDFs, High-Res Image Package (ZIP)
 * - Standard Paper Sizes: A4, A3, A5, Letter, Legal, Tabloid, Original Bitmap
 * - Orientations: Portrait, Landscape, Auto-Adaptive (aspect-ratio driven)
 * - Fitting: Fit (contain inside margins), Fill (crop to cover), Original
 * - Margin Policies: None, Compact (0.25"), Normal (0.5"), Wide (1.0")
 * - Quality / Compression: High (95%), Medium (80%), Low (60%)
 */
object DocumentExportEngine {

    enum class OutputFormat(val displayName: String, val extension: String, val mimeType: String) {
        SINGLE_PDF("Single PDF Document", "pdf", "application/pdf"),
        SEPARATE_PDFS("Separate PDFs (ZIP Archive)", "zip", "application/zip"),
        IMAGES_ZIP("Image Sequence (ZIP Archive)", "zip", "application/zip")
    }

    enum class PaperSize(val displayName: String, val widthPt: Int, val heightPt: Int) {
        A4("A4 (210 × 297 mm)", 595, 842),
        A3("A3 (297 × 420 mm)", 842, 1191),
        A5("A5 (148 × 210 mm)", 420, 595),
        LETTER("US Letter (8.5 × 11 in)", 612, 792),
        LEGAL("US Legal (8.5 × 14 in)", 612, 1008),
        TABLOID("Tabloid (11 × 17 in)", 792, 1224),
        ORIGINAL_IMAGE("Original Image Dimensions", 0, 0)
    }

    enum class Orientation(val displayName: String) {
        PORTRAIT("Portrait"),
        LANDSCAPE("Landscape"),
        AUTO("Auto (Match Image)")
    }

    enum class PageFit(val displayName: String) {
        FIT_PAGE("Fit (Maintain Aspect Ratio)"),
        FILL_PAGE("Fill (Crop to Page)"),
        ORIGINAL("Original 1:1")
    }

    enum class Margin(val displayName: String, val marginPt: Int) {
        NONE("None (0 pt)", 0),
        COMPACT("Compact (18 pt / 0.25 in)", 18),
        NORMAL("Normal (36 pt / 0.5 in)", 36),
        WIDE("Wide (72 pt / 1.0 in)", 72)
    }

    enum class ExportQuality(val displayName: String, val jpegQuality: Int) {
        HIGH("High (Lossless / 95%)", 95),
        MEDIUM("Medium (Balanced / 80%)", 80),
        LOW("Low (Compact / 60%)", 60)
    }

    data class ExportOptions(
        val format: OutputFormat = OutputFormat.SINGLE_PDF,
        val paperSize: PaperSize = PaperSize.A4,
        val orientation: Orientation = Orientation.AUTO,
        val fit: PageFit = PageFit.FIT_PAGE,
        val margin: Margin = Margin.NORMAL,
        val quality: ExportQuality = ExportQuality.HIGH
    )

    data class ExportResult(
        val file: File,
        val pageCount: Int,
        val mimeType: String
    )

    suspend fun exportDocument(
        context: Context,
        session: DocumentSession,
        options: ExportOptions = ExportOptions()
    ): ExportResult {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()

        return when (options.format) {
            OutputFormat.SINGLE_PDF -> {
                val outFile = File(exportDir, "${sanitizeFileName(session.title)}_$timestamp.pdf")
                createSinglePdf(context, session, options, outFile)
                ExportResult(outFile, session.pageCount, options.format.mimeType)
            }
            OutputFormat.SEPARATE_PDFS -> {
                val outFile = File(exportDir, "${sanitizeFileName(session.title)}_Separate_PDFs_$timestamp.zip")
                createSeparatePdfsZip(context, session, options, outFile)
                ExportResult(outFile, session.pageCount, options.format.mimeType)
            }
            OutputFormat.IMAGES_ZIP -> {
                val outFile = File(exportDir, "${sanitizeFileName(session.title)}_Images_$timestamp.zip")
                createImagesZip(context, session, options, outFile)
                ExportResult(outFile, session.pageCount, options.format.mimeType)
            }
        }
    }

    suspend fun exportSinglePage(
        context: Context,
        page: ScannedPage,
        pageIndex: Int,
        title: String,
        options: ExportOptions = ExportOptions()
    ): ExportResult {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestamp = System.currentTimeMillis()
        val bmp = page.getDisplayBitmap(context) ?: throw IllegalStateException("Page bitmap could not be decoded")

        return when (options.format) {
            OutputFormat.SINGLE_PDF, OutputFormat.SEPARATE_PDFS -> {
                val outFile = File(exportDir, "${sanitizeFileName(title)}_page_${pageIndex + 1}_$timestamp.pdf")
                val pdfDoc = PdfDocument()
                try {
                    val (pageW, pageH) = resolvePageDimensions(bmp, options)
                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
                    val pdfPage = pdfDoc.startPage(pageInfo)
                    renderBitmapToCanvas(pdfPage.canvas, bmp, pageW, pageH, options)
                    pdfDoc.finishPage(pdfPage)
                    FileOutputStream(outFile).use { fos -> pdfDoc.writeTo(fos) }
                } finally {
                    pdfDoc.close()
                }
                ExportResult(outFile, 1, "application/pdf")
            }
            OutputFormat.IMAGES_ZIP -> {
                val outFile = File(exportDir, "${sanitizeFileName(title)}_page_${pageIndex + 1}_$timestamp.jpg")
                FileOutputStream(outFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, options.quality.jpegQuality, fos)
                }
                ExportResult(outFile, 1, "image/jpeg")
            }
        }
    }

    private fun createSinglePdf(
        context: Context,
        session: DocumentSession,
        options: ExportOptions,
        outFile: File
    ) {
        val pdfDoc = PdfDocument()
        try {
            for ((index, page) in session.pages.withIndex()) {
                val bmp = page.getDisplayBitmap(context) ?: continue
                val (pageW, pageH) = resolvePageDimensions(bmp, options)
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, index + 1).create()
                val pdfPage = pdfDoc.startPage(pageInfo)

                renderBitmapToCanvas(pdfPage.canvas, bmp, pageW, pageH, options)
                pdfDoc.finishPage(pdfPage)
            }

            FileOutputStream(outFile).use { fos ->
                pdfDoc.writeTo(fos)
            }
        } finally {
            pdfDoc.close()
        }
    }

    private fun createSeparatePdfsZip(
        context: Context,
        session: DocumentSession,
        options: ExportOptions,
        outFile: File
    ) {
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            for ((index, page) in session.pages.withIndex()) {
                val bmp = page.getDisplayBitmap(context) ?: continue
                val singlePdf = PdfDocument()
                try {
                    val (pageW, pageH) = resolvePageDimensions(bmp, options)
                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
                    val pdfPage = singlePdf.startPage(pageInfo)

                    renderBitmapToCanvas(pdfPage.canvas, bmp, pageW, pageH, options)
                    singlePdf.finishPage(pdfPage)

                    val baos = ByteArrayOutputStream()
                    singlePdf.writeTo(baos)

                    val entryName = "Page_${index + 1}.pdf"
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(baos.toByteArray())
                    zos.closeEntry()
                } finally {
                    singlePdf.close()
                }
            }
        }
    }

    private fun createImagesZip(
        context: Context,
        session: DocumentSession,
        options: ExportOptions,
        outFile: File
    ) {
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            for ((index, page) in session.pages.withIndex()) {
                val bmp = page.getDisplayBitmap(context) ?: continue
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, options.quality.jpegQuality, baos)

                val entryName = "Page_${index + 1}.jpg"
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(baos.toByteArray())
                zos.closeEntry()
            }
        }
    }

    private fun resolvePageDimensions(bitmap: Bitmap, options: ExportOptions): Pair<Int, Int> {
        return resolvePageDimensions(bitmap.width, bitmap.height, options)
    }

    internal fun resolvePageDimensions(bitmapWidth: Int, bitmapHeight: Int, options: ExportOptions): Pair<Int, Int> {
        if (options.paperSize == PaperSize.ORIGINAL_IMAGE) {
            return Pair(bitmapWidth, bitmapHeight)
        }

        val baseW = options.paperSize.widthPt
        val baseH = options.paperSize.heightPt

        val isLandscape = when (options.orientation) {
            Orientation.PORTRAIT -> false
            Orientation.LANDSCAPE -> true
            Orientation.AUTO -> bitmapWidth > bitmapHeight
        }

        return if (isLandscape) {
            Pair(baseH.coerceAtLeast(baseW), baseW.coerceAtMost(baseH))
        } else {
            Pair(baseW.coerceAtMost(baseH), baseH.coerceAtLeast(baseW))
        }
    }

    private fun renderBitmapToCanvas(
        canvas: Canvas,
        bitmap: Bitmap,
        pageW: Int,
        pageH: Int,
        options: ExportOptions
    ) {
        val marginPt = options.margin.marginPt.toFloat()
        val availW = (pageW - 2 * marginPt).coerceAtLeast(1f)
        val availH = (pageH - 2 * marginPt).coerceAtLeast(1f)

        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        when (options.fit) {
            PageFit.FIT_PAGE -> {
                val scale = (availW / bmpW).coerceAtMost(availH / bmpH)
                val drawW = bmpW * scale
                val drawH = bmpH * scale
                val left = marginPt + (availW - drawW) / 2f
                val top = marginPt + (availH - drawH) / 2f
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
            }
            PageFit.FILL_PAGE -> {
                val scale = (availW / bmpW).coerceAtLeast(availH / bmpH)
                val drawW = bmpW * scale
                val drawH = bmpH * scale
                val left = marginPt + (availW - drawW) / 2f
                val top = marginPt + (availH - drawH) / 2f
                canvas.save()
                canvas.clipRect(marginPt, marginPt, marginPt + availW, marginPt + availH)
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
                canvas.restore()
            }
            PageFit.ORIGINAL -> {
                val left = marginPt + (availW - bmpW) / 2f
                val top = marginPt + (availH - bmpH) / 2f
                canvas.drawBitmap(bitmap, left, top, paint)
            }
        }
    }

    internal fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(40)
    }
}
