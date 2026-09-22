package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.renderer.*

/**
 * Public entry point for QR code generation.
 *
 * Usage:
 * ```kotlin
 * val params = QrStyleParams(
 *     outputSize = 512,
 *     style = QrStyle.BUBBLE,
 *     foreground = Color.BLACK,
 *     background = Color.WHITE,
 *     logo = myLogoBitmap
 * )
 * val bitmap: Bitmap = QrGenerator.generate("https://example.com", params)
 * ```
 *
 * All rendering is synchronous (call from a background coroutine).
 */
object QrGenerator {

    /**
     * Generates a QR code bitmap for the given [content] using the visual
     * style described by [params].
     *
     * @param content   The string to encode (URL, text, WiFi SSID string, etc.)
     * @param params    Visual and size parameters.
     * @param ecLevel   Error correction level (default M — ~15% recovery).
     *                  Use H for QR codes with a logo overlay.
     * @throws IllegalArgumentException if content is blank.
     */
    fun generate(
        content: String,
        params: QrStyleParams = QrStyleParams(),
        ecLevel: ErrorCorrectionLevel = if (params.logo != null) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M
    ): Bitmap {
        require(content.isNotBlank()) { "QR content must not be blank" }

        val matrix = QrMatrix(content, ecLevel)
        val n = matrix.size
        val cellSize = params.outputSize.toFloat() / n

        val bitmap = Bitmap.createBitmap(params.outputSize, params.outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val renderer: com.veilframe.app.qr.renderer.QrRenderer = when (params.style) {
            QrStyle.BASIC            -> BasicRenderer()
            QrStyle.BUBBLE           -> BubbleRenderer()
            QrStyle.D25              -> Renderer25D()
            QrStyle.DSJ              -> DsjRenderer()
            QrStyle.IMAGE_FILL       -> ImageFillRenderer()
            QrStyle.IMAGE            -> ImageRenderer()
            QrStyle.IMAGE_RESAMPLE   -> ResampleImageRenderer()
            QrStyle.LINE             -> LineRenderer()
            QrStyle.RANDOM_RECTANGLE -> RandomRectangleRenderer()
            QrStyle.FUNCTION         -> FunctionRenderer()
            QrStyle.STYLE_FUNCTION   -> StyleFunctionRenderer()
        }

        renderer.render(matrix, params, canvas, cellSize)
        return bitmap
    }

    /**
     * Generates a QR code as a PNG [ByteArray].
     * Convenience overload that calls [generate] then compresses to PNG.
     */
    fun generatePng(
        content: String,
        params: QrStyleParams = QrStyleParams(),
        quality: Int = 100
    ): ByteArray {
        val bmp = generate(content, params)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, quality, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    /**
     * Generates a QR code as a JPEG [ByteArray].
     * Use for smaller file sizes when transparency is not needed.
     */
    fun generateJpeg(
        content: String,
        params: QrStyleParams = QrStyleParams(),
        quality: Int = 90
    ): ByteArray {
        val bmp = generate(content, params)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bmp.recycle()
        return baos.toByteArray()
    }
}
