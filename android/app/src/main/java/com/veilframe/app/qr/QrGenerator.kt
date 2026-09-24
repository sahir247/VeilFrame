package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.encoder.QrEncoder
import com.veilframe.app.qr.model.BackgroundStyle
import com.veilframe.app.qr.model.ModuleFill
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.registry.QrStyleRegistry
import com.veilframe.app.qr.renderer.*
import com.veilframe.app.qr.validation.AutoRepairEngine
import com.veilframe.app.qr.validation.ScanabilityReport
import com.veilframe.app.qr.validation.ScanabilityValidator
import kotlinx.coroutines.runBlocking

/**
 * Typed result of a QR code generation operation.
 */
sealed interface QrRenderResult {
    data class Success(
        val bitmap: Bitmap,
        val report: ScanabilityReport,
        val matrix: QrMatrix,
        val design: QrDesign
    ) : QrRenderResult

    data class Failure(
        val error: String,
        val throwable: Throwable? = null
    ) : QrRenderResult
}

/**
 * Public entry point for QR code generation in VeilFrame.
 */
object QrGenerator {

    /**
     * Modern domain generation entry point returning typed [QrRenderResult]
     * with automated structural and decode scanability validation.
     */
    fun generateWithResult(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        if (content.isBlank()) {
            return QrRenderResult.Failure("QR content must not be blank")
        }

        try {
            val isAggressive = design.effects.is25D || design.imageFillMode
            val ecLevel = design.correction.toZxingLevel(
                hasLogo = design.logo?.bitmap != null,
                isAggressiveStyle = isAggressive
            )

            val encoded = QrEncoder.encode(content, ecLevel)
            val matrix = encoded.matrix
            val size = design.outputSize.coerceIn(256, 4096)

            val geometry = QrGeometry(
                matrixSize = matrix.size,
                outputWidth = size,
                outputHeight = size,
                quietZoneModules = design.quietZoneModules
            )

            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val context = RenderContext()

            // 1. Draw Canvas Background (including Quiet Zone margins)
            drawBackground(canvas, design, size, context)

            // 2. Obtain renderer
            val renderer: QrRenderer = getRendererForDesign(design)

            // 3. Render QR Code
            renderer.render(matrix, design, canvas, geometry, context)

            // 4. Validate scanability (Fast validator)
            val report = runBlocking {
                ScanabilityValidator.validateFast(bitmap, design, matrix, content)
            }

            return QrRenderResult.Success(
                bitmap = bitmap,
                report = report,
                matrix = matrix,
                design = design
            )
        } catch (t: Throwable) {
            return QrRenderResult.Failure(t.message ?: "Failed to generate QR code", t)
        }
    }

    /**
     * Generates a QR code with an automated closed-loop repair feedback pipeline.
     *
     * If the candidate visual style fails scanability checks or real ZXing decode tests,
     * the [AutoRepairEngine] automatically refines parameters (restores quiet zones,
     * increases contrast, softens extreme shapes, elevates error correction) across up to
     * [maxAttempts] iterations until a verified scannable QR is produced.
     */
    fun generateWithAutoRepair(
        content: String,
        design: QrDesign = QrDesign(),
        maxAttempts: Int = 3
    ): QrRenderResult {
        if (content.isBlank()) {
            return QrRenderResult.Failure("QR content must not be blank")
        }

        var currentDesign = design
        var lastSuccess: QrRenderResult.Success? = null
        val repairTrail = mutableListOf<String>()

        for (attempt in 1..maxAttempts) {
            val result = generateWithResult(content, currentDesign)
            when (result) {
                is QrRenderResult.Failure -> return result
                is QrRenderResult.Success -> {
                    lastSuccess = result
                    // Check if decode succeeded and report is scan-ready
                    if (result.report.isScanReady && result.report.decodeResult.success) {
                        return result
                    }
                    if (attempt < maxAttempts) {
                        val repair = AutoRepairEngine.repair(currentDesign, result.report, content)
                        if (repair.changesApplied.isEmpty() || repair.repairedDesign == currentDesign) {
                            break
                        }
                        repairTrail.addAll(repair.changesApplied)
                        currentDesign = repair.repairedDesign
                    }
                }
            }
        }

        return lastSuccess ?: QrRenderResult.Failure("Auto-repair failed to produce a valid QR code")
    }

    /**
     * Legacy synchronous generation overload returning a raw [Bitmap].
     */
    fun generate(
        content: String,
        params: QrStyleParams = QrStyleParams(),
        ecLevel: ErrorCorrectionLevel = if (params.logo != null) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M
    ): Bitmap {
        require(content.isNotBlank()) { "QR content must not be blank" }

        val design = QrDesign.fromQrStyleParams(params)
        val result = generateWithResult(content, design)
        return when (result) {
            is QrRenderResult.Success -> result.bitmap
            is QrRenderResult.Failure -> throw IllegalStateException(result.error, result.throwable)
        }
    }

    /**
     * Generates a QR code as a PNG [ByteArray].
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

    private fun drawBackground(
        canvas: Canvas,
        design: QrDesign,
        size: Int,
        context: RenderContext
    ) {
        when (val background = design.background) {
            is BackgroundStyle.Solid -> {
                canvas.drawColor(background.color)
            }
            is BackgroundStyle.LinearGradient -> {
                val paint = context.fillPaint
                paint.reset()
                paint.isAntiAlias = true
                paint.shader = LinearGradient(
                    0f, 0f, size.toFloat(), size.toFloat(),
                    background.startColor, background.endColor,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            }
            is BackgroundStyle.RadialGradient -> {
                val paint = context.fillPaint
                paint.reset()
                paint.isAntiAlias = true
                paint.shader = RadialGradient(
                    size / 2f, size / 2f, size / 2f,
                    background.centerColor, background.edgeColor,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            }
            is BackgroundStyle.Image -> {
                // Clear with configured backdrop background color first, then draw background image with specified alpha
                canvas.drawColor(design.palette.background)
                val paint = context.tempPaint
                paint.reset()
                paint.alpha = (background.alpha.coerceIn(0f, 1f) * 255).toInt()
                canvas.drawBitmap(background.bitmap, null, android.graphics.Rect(0, 0, size, size), paint)
            }
            BackgroundStyle.Transparent -> {
                // Keep transparent ARGB_8888
            }
        }
    }

    private fun getRendererForDesign(design: QrDesign): QrRenderer {
        return if (design.moduleStyle.fill == ModuleFill.IMAGE_MASKED ||
            design.moduleStyle.shape == ModuleShape.BUBBLE_CLUSTER ||
            design.style == QrStyle.IMAGE_RESAMPLE
        ) {
            ComposableQrRenderer()
        } else {
            QrStyleRegistry.getRenderer(design.style)
        }
    }
}
