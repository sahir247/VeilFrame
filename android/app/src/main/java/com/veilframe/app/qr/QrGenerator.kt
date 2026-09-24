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
import com.veilframe.app.qr.model.ErrorCorrectionChoice
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
        val bitmap: Bitmap?,
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
 * Generation execution mode separating exact deterministic reference compatibility
 * from production safe generation.
 */
enum class GenerationMode {
    /**
     * VeilFrame Art Engine generation mode:
     * - Encodes with [com.veilframe.app.qr.encoder.engine.VeilQrEncoder] (100% matrix identity with art specification).
     * - Bypasses parameter mutation or visual auto-repair for deterministic artistic reproduction.
     * - Defaults to 0 quiet-zone modules per art backdrop specifications.
     */
    ARTISTIC_ENGINE,

    /**
     * Production safety generation mode:
     * - Encodes with standard ISO/ZXing encoder.
     * - Enforces quiet zones and scanability validation.
     * - Allows closed-loop auto-repair feedback.
     */
    SAFE
}

/**
 * Public entry point for QR code generation in VeilFrame.
 */
object QrGenerator {

    /**
     * Authoritative generation mode resolver for a given [QrDesign].
     *
     * Ensures all pipelines (preview, gallery PNG, share, SVG export, auto-repair)
     * strictly use [GenerationMode.ARTISTIC_ENGINE] for artistic styles (such as
     * [QrStyle.IMAGE_RESAMPLE]), and [GenerationMode.SAFE] for basic/safe styles.
     */
    fun defaultModeFor(design: QrDesign): GenerationMode =
        if (design.style != QrStyle.BASIC) GenerationMode.ARTISTIC_ENGINE else GenerationMode.SAFE

    /**
     * Directly generates the [QrMatrix] adhering to the specified [GenerationMode] and [design],
     * without requiring an Android [Bitmap] or [Canvas] context.
     */
    fun generateMatrix(
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design)
    ): QrMatrix {
        require(content.isNotBlank()) { "QR content must not be blank" }
        val ecLevel = when (mode) {
            GenerationMode.ARTISTIC_ENGINE -> {
                when (design.correction) {
                    ErrorCorrectionChoice.L -> ErrorCorrectionLevel.L
                    ErrorCorrectionChoice.M -> ErrorCorrectionLevel.M
                    ErrorCorrectionChoice.Q -> ErrorCorrectionLevel.Q
                    ErrorCorrectionChoice.H -> ErrorCorrectionLevel.H
                    ErrorCorrectionChoice.AUTO -> ErrorCorrectionLevel.H // VeilFrame Art default is strictly H
                }
            }
            GenerationMode.SAFE -> {
                val isAggressive = design.effects.is25D || design.imageFillMode
                design.correction.toZxingLevel(
                    hasLogo = design.logo?.bitmap != null,
                    isAggressiveStyle = isAggressive
                )
            }
        }

        return if (mode == GenerationMode.ARTISTIC_ENGINE) {
            com.veilframe.app.qr.encoder.engine.VeilQrEncoder.encode(content, ecLevel).matrix
        } else {
            QrEncoder.encode(content, ecLevel).matrix
        }
    }

    /**
     * Directly generates the VeilFrame artistic [QrMatrix] with default EC level H.
     */
    fun generateArtisticMatrix(
        content: String,
        design: QrDesign = QrDesign()
    ): QrMatrix = generateMatrix(content, design, mode = GenerationMode.ARTISTIC_ENGINE)

    /**
     * Modern domain generation entry point returning typed [QrRenderResult]
     * with automated structural and decode scanability validation.
     */
    fun generateWithResult(
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design)
    ): QrRenderResult {
        if (content.isBlank()) {
            return QrRenderResult.Failure("QR content must not be blank")
        }

        try {
            val matrix = generateMatrix(content, design, mode)

            val size = design.outputSize.coerceIn(256, 4096)
            val quietZone = if (mode == GenerationMode.ARTISTIC_ENGINE || design.style == QrStyle.IMAGE_RESAMPLE) {
                design.explicitQuietZone ?: 1
            } else {
                design.effectiveQuietZone
            }

            val geometry = QrGeometry(
                matrixSize = matrix.size,
                outputWidth = size,
                outputHeight = size,
                quietZoneModules = quietZone
            )

            val bitmap = try {
                Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            } catch (t: Throwable) {
                null
            }

            val report = if (bitmap != null) {
                val canvas = Canvas(bitmap)
                val context = RenderContext()

                // 1. Draw Canvas Background (including Quiet Zone margins)
                drawBackground(canvas, design, size, context)

                // 2. Obtain renderer
                val renderer: QrRenderer = getRendererForDesign(design)

                // 3. Render QR Code
                renderer.render(matrix, design, canvas, geometry, context)

                // 4. Validate scanability (Fast validator)
                runBlocking {
                    ScanabilityValidator.validateFast(bitmap, design, matrix, content)
                }
            } else {
                // Headless unit testing fallback where android.graphics.Bitmap is not available on JVM
                com.veilframe.app.qr.validation.ScanabilityReport(
                    isScanReady = false,
                    validationSkipped = true,
                    quietZone = com.veilframe.app.qr.validation.QuietZoneReport(
                        hasFourModuleMargin = quietZone >= 4,
                        quietZoneModules = quietZone
                    ),
                    contrast = com.veilframe.app.qr.validation.ContrastReport(0f, 0f, 1f, 1f, 1f, isContrastAdequate = false),
                    finders = com.veilframe.app.qr.validation.FinderIntegrityReport(findersIntact = true, separatorsClear = true),
                    logo = com.veilframe.app.qr.validation.LogoOcclusionReport(false, 0, 0f, true),
                    decodeResult = com.veilframe.app.qr.decoder.DecodeResult(success = false, text = null, error = "Bitmap allocation unavailable"),
                    errorCorrection = matrix.errorCorrection,
                    warnings = listOf("Bitmap allocation unavailable; visual and decode validation skipped."),
                    repairSuggestions = emptyList()
                )
            }

            return QrRenderResult.Success(
                bitmap = bitmap,
                report = report,
                matrix = matrix,
                design = design
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            return QrRenderResult.Failure(t.message ?: "Failed to generate QR code", t)
        }
    }

    /**
     * Convenience entry point for generating deterministic, exact VeilFrame artistic QR codes.
     */
    fun generateArtistic(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult = generateWithResult(content, design, mode = GenerationMode.ARTISTIC_ENGINE)

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
            is QrRenderResult.Success -> result.bitmap ?: throw IllegalStateException("Bitmap creation failed")
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
                paint.isAntiAlias = true
                paint.isFilterBitmap = true
                paint.alpha = (background.alpha.coerceIn(0f, 1f) * 255).toInt()
                val targetBounds = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
                val scaleMode = if (background.fitCenter) com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FIT else com.veilframe.app.qr.model.ImageScaleMode.ASPECT_FILL
                val (srcRect, dstRect) = ImageScaleResolver.resolveSrcDst(
                    background.bitmap.width,
                    background.bitmap.height,
                    targetBounds,
                    scaleMode
                )
                canvas.drawBitmap(background.bitmap, srcRect, dstRect, paint)
            }
            BackgroundStyle.Transparent -> {
                // Keep transparent ARGB_8888
            }
        }
    }

    private fun getRendererForDesign(design: QrDesign): QrRenderer {
        return if (design.moduleStyle.fill == ModuleFill.IMAGE_MASKED ||
            design.moduleStyle.shape == ModuleShape.BUBBLE_CLUSTER
        ) {
            ComposableQrRenderer()
        } else {
            QrStyleRegistry.getRenderer(design.style)
        }
    }
}
