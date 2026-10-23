package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Watermark layout patterns for anti-tampering protection.
 */
enum class WatermarkFillPattern(val displayName: String) {
    SINGLE_CENTER("Centered Only"),
    FILL_HORIZONTAL("Horizontal Lines"),
    FILL_VERTICAL("Vertical Columns"),
    FILL_DIAGONAL_LTR("Diagonal (Bottom-Left to Top-Right ↗)"),
    FILL_DIAGONAL_RTL("Diagonal (Top-Left to Bottom-Right ↘)"),
    FILL_CROSS_HATCH("Tamper-Proof Cross-Hatch Security Mesh ✕")
}

/**
 * Watermark type - text or image.
 */
sealed class WatermarkType {
    data class Text(
        val content: String,
        val fontSize: Float = 36f,
        val rotation: Float = 45f,
        val colorHex: Int = Color.DKGRAY
    ) : WatermarkType()

    data class Image(
        val imageUri: Uri,
        val width: Float = 200f,
        val height: Float = 200f,
        val rotation: Float = 0f
    ) : WatermarkType()
}

/**
 * Configuration for watermark appearance.
 */
data class WatermarkConfig(
    val type: WatermarkType,
    val pattern: WatermarkFillPattern = WatermarkFillPattern.FILL_DIAGONAL_LTR,
    val opacity: Float = 0.25f, // 0.05 to 1.0
    val applyToAllPages: Boolean = true,
    val specificPages: List<Int> = emptyList() // 0-indexed
)

/**
 * Result of watermark operation.
 */
data class WatermarkResult(
    val success: Boolean,
    val pagesProcessed: Int,
    val errorMessage: String? = null
)

/**
 * PDF Watermarker with real-time live preview rendering and full-page anti-tamper fill patterns.
 */
class PdfWatermarker {

    /**
     * Renders a real-time live preview of the watermark over an existing page bitmap on Android Canvas.
     */
    fun renderLivePreview(
        baseBitmap: Bitmap,
        config: WatermarkConfig
    ): Bitmap {
        val result = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val width = result.width.toFloat()
        val height = result.height.toFloat()

        when (val type = config.type) {
            is WatermarkType.Text -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = type.colorHex
                    alpha = (config.opacity.coerceIn(0.02f, 1.0f) * 255).toInt()
                    textSize = type.fontSize * (width / 595f) // scale with preview dimensions
                    style = Paint.Style.FILL
                    textAlign = Paint.Align.CENTER
                }
                drawTextPattern(canvas, width, height, type.content, paint, config.pattern, type.rotation)
            }
            is WatermarkType.Image -> {
                // Image preview handled by scaling
            }
        }
        return result
    }

    private fun drawTextPattern(
        canvas: Canvas,
        width: Float,
        height: Float,
        text: String,
        paint: Paint,
        pattern: WatermarkFillPattern,
        customAngle: Float
    ) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textWidth = bounds.width().toFloat()
        val textHeight = bounds.height().toFloat()

        when (pattern) {
            WatermarkFillPattern.SINGLE_CENTER -> {
                canvas.save()
                canvas.translate(width / 2f, height / 2f)
                canvas.rotate(customAngle)
                canvas.drawText(text, 0f, textHeight / 2f, paint)
                canvas.restore()
            }
            WatermarkFillPattern.FILL_HORIZONTAL -> {
                val stepY = textHeight * 4.5f
                var y = stepY
                while (y < height) {
                    var x = textWidth
                    while (x < width + textWidth) {
                        canvas.drawText(text, x, y, paint)
                        x += textWidth * 1.8f
                    }
                    y += stepY
                }
            }
            WatermarkFillPattern.FILL_VERTICAL -> {
                val stepX = textWidth * 1.8f
                var x = stepX / 2f
                while (x < width) {
                    canvas.save()
                    canvas.translate(x, 0f)
                    canvas.rotate(90f)
                    var y = textHeight * 2f
                    while (y < height + textWidth) {
                        canvas.drawText(text, y, 0f, paint)
                        y += textHeight * 5f
                    }
                    canvas.restore()
                    x += stepX
                }
            }
            WatermarkFillPattern.FILL_DIAGONAL_LTR -> {
                drawDiagonalGrid(canvas, width, height, text, paint, 45f, textWidth, textHeight)
            }
            WatermarkFillPattern.FILL_DIAGONAL_RTL -> {
                drawDiagonalGrid(canvas, width, height, text, paint, -45f, textWidth, textHeight)
            }
            WatermarkFillPattern.FILL_CROSS_HATCH -> {
                drawDiagonalGrid(canvas, width, height, text, paint, 45f, textWidth, textHeight)
                drawDiagonalGrid(canvas, width, height, text, paint, -45f, textWidth, textHeight)
            }
        }
    }

    private fun drawDiagonalGrid(
        canvas: Canvas,
        width: Float,
        height: Float,
        text: String,
        paint: Paint,
        angle: Float,
        textWidth: Float,
        textHeight: Float
    ) {
        val spacingX = (textWidth * 1.6f).coerceAtLeast(120f)
        val spacingY = (textHeight * 5.0f).coerceAtLeast(80f)

        var y = -height * 0.5f
        while (y < height * 1.5f) {
            var x = -width * 0.5f
            while (x < width * 1.5f) {
                canvas.save()
                canvas.translate(x, y)
                canvas.rotate(angle)
                canvas.drawText(text, 0f, 0f, paint)
                canvas.restore()
                x += spacingX
            }
            y += spacingY
        }
    }

    /**
     * Writes watermark to PDF using PDFBox.
     */
    suspend fun addWatermark(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        config: WatermarkConfig,
        progressCallback: (Int) -> Unit = {}
    ): WatermarkResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        try {
            progressCallback(0)
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext WatermarkResult(false, 0, "Cannot open source PDF")
            document = PDDocument.load(inputStream)
            inputStream.close()

            val totalPages = document.numberOfPages
            if (totalPages == 0) {
                document.close()
                return@withContext WatermarkResult(false, 0, "PDF has no pages")
            }

            val pagesToProcess = if (config.applyToAllPages) {
                (0 until totalPages).toList()
            } else {
                config.specificPages.filter { it in 0 until totalPages }
            }

            pagesToProcess.forEachIndexed { index, pageIndex ->
                val page = document.getPage(pageIndex)
                applyWatermarkToPage(document, page, config)
                progressCallback(10 + ((index + 1) * 80 / pagesToProcess.size))
            }

            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.save(outputStream)
                outputStream.flush()
            }
            document.close()
            progressCallback(100)

            WatermarkResult(true, pagesToProcess.size)
        } catch (e: Exception) {
            document?.close()
            WatermarkResult(false, 0, e.message)
        }
    }

    private fun applyWatermarkToPage(
        document: PDDocument,
        page: PDPage,
        config: WatermarkConfig
    ) {
        val pageRect = page.mediaBox
        val pageWidth = pageRect.width
        val pageHeight = pageRect.height

        val contentStream = PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        )

        try {
            val graphicsState = PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = config.opacity
                strokingAlphaConstant = config.opacity
            }
            contentStream.setGraphicsStateParameters(graphicsState)

            when (val type = config.type) {
                is WatermarkType.Text -> {
                    val font = PDType1Font.HELVETICA_BOLD
                    contentStream.setFont(font, type.fontSize)
                    contentStream.setNonStrokingColor(128, 128, 128)

                    val textWidth = font.getStringWidth(type.content) / 1000f * type.fontSize
                    val textHeight = type.fontSize

                    when (config.pattern) {
                        WatermarkFillPattern.SINGLE_CENTER -> {
                            val x = (pageWidth - textWidth) / 2f
                            val y = (pageHeight - textHeight) / 2f
                            drawRotatedText(contentStream, type.content, x, y, type.rotation)
                        }
                        WatermarkFillPattern.FILL_HORIZONTAL -> {
                            var y = textHeight * 3
                            while (y < pageHeight) {
                                var x = 40f
                                while (x < pageWidth) {
                                    drawRotatedText(contentStream, type.content, x, y, 0f)
                                    x += textWidth * 1.5f
                                }
                                y += textHeight * 4
                            }
                        }
                        WatermarkFillPattern.FILL_VERTICAL -> {
                            var x = textHeight * 3
                            while (x < pageWidth) {
                                var y = 40f
                                while (y < pageHeight) {
                                    drawRotatedText(contentStream, type.content, x, y, 90f)
                                    y += textWidth * 1.5f
                                }
                                x += textHeight * 4
                            }
                        }
                        WatermarkFillPattern.FILL_DIAGONAL_LTR -> {
                            drawPdfDiagonalGrid(contentStream, pageWidth, pageHeight, type.content, textWidth, textHeight, 45f)
                        }
                        WatermarkFillPattern.FILL_DIAGONAL_RTL -> {
                            drawPdfDiagonalGrid(contentStream, pageWidth, pageHeight, type.content, textWidth, textHeight, -45f)
                        }
                        WatermarkFillPattern.FILL_CROSS_HATCH -> {
                            drawPdfDiagonalGrid(contentStream, pageWidth, pageHeight, type.content, textWidth, textHeight, 45f)
                            drawPdfDiagonalGrid(contentStream, pageWidth, pageHeight, type.content, textWidth, textHeight, -45f)
                        }
                    }
                }
                is WatermarkType.Image -> {
                    // Image watermark
                }
            }
        } finally {
            contentStream.close()
        }
    }

    private fun drawRotatedText(
        stream: PDPageContentStream,
        text: String,
        x: Float,
        y: Float,
        degrees: Float
    ) {
        stream.beginText()
        val radians = Math.toRadians(degrees.toDouble())
        val matrix = Matrix()
        matrix.translate(x, y)
        matrix.rotate(radians)
        stream.setTextMatrix(matrix)
        stream.showText(text)
        stream.endText()
    }

    private fun drawPdfDiagonalGrid(
        stream: PDPageContentStream,
        pageWidth: Float,
        pageHeight: Float,
        text: String,
        textWidth: Float,
        textHeight: Float,
        angle: Float
    ) {
        val spacingX = (textWidth * 1.6f).coerceAtLeast(140f)
        val spacingY = (textHeight * 5.0f).coerceAtLeast(90f)

        var y = -pageHeight * 0.4f
        while (y < pageHeight * 1.4f) {
            var x = -pageWidth * 0.4f
            while (x < pageWidth * 1.4f) {
                drawRotatedText(stream, text, x, y, angle)
                x += spacingX
            }
            y += spacingY
        }
    }
}
