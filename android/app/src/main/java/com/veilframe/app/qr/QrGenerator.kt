package com.veilframe.app.qr

import android.content.Context
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
import com.veilframe.app.qr.model.BasicGeometryProfile
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

import com.veilframe.app.qr.error.QrError
import com.veilframe.app.qr.model.QrOutputFormat
import com.veilframe.app.qr.model.QrOutputResult

/**
 * Typed result of a QR code generation operation.
 */
sealed interface QrRenderResult {
    sealed interface Success : QrRenderResult {
        val bitmap: Bitmap?
        val report: ScanabilityReport
        val matrix: QrMatrix
        val design: QrDesign
        val isVerified: Boolean get() = report.isScanReady
        val isStrictlyVerified: Boolean get() = report.isStrictlyCompliant

        open class Verified(
            override val bitmap: Bitmap?,
            override val report: ScanabilityReport,
            override val matrix: QrMatrix,
            override val design: QrDesign
        ) : Success {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Verified) return false
                return bitmap == other.bitmap && report == other.report && matrix == other.matrix && design == other.design
            }
            override fun hashCode(): Int {
                var res = bitmap?.hashCode() ?: 0
                res = 31 * res + report.hashCode()
                res = 31 * res + matrix.hashCode()
                res = 31 * res + design.hashCode()
                return res
            }
            override fun toString(): String = "Verified(bitmap=$bitmap, report=$report, matrix=$matrix, design=$design)"
        }

        class StrictVerified(
            bitmap: Bitmap?,
            report: ScanabilityReport,
            matrix: QrMatrix,
            design: QrDesign
        ) : Verified(bitmap, report, matrix, design)

        class PreviewVerified(
            bitmap: Bitmap?,
            report: ScanabilityReport,
            matrix: QrMatrix,
            design: QrDesign
        ) : Verified(bitmap, report, matrix, design)

        data class Unverified(
            override val bitmap: Bitmap?,
            override val report: ScanabilityReport,
            override val matrix: QrMatrix,
            override val design: QrDesign
        ) : Success

        companion object {
            operator fun invoke(
                bitmap: Bitmap?,
                report: ScanabilityReport,
                matrix: QrMatrix,
                design: QrDesign
            ): Success = when {
                report.isStrictlyCompliant -> StrictVerified(bitmap, report, matrix, design)
                report.isScanReady -> PreviewVerified(bitmap, report, matrix, design)
                else -> Unverified(bitmap, report, matrix, design)
            }
        }
    }

    data class Failure(
        val qrError: QrError,
        val error: String = qrError.description,
        val throwable: Throwable? = qrError.cause
    ) : QrRenderResult {
        constructor(error: String, throwable: Throwable? = null) : this(
            qrError = QrError.fromThrowable(throwable, error),
            error = error,
            throwable = throwable
        )
    }
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
     * - Defaults to 1 quiet-zone module (matching canonical artistic 1-module margin specifications, or explicitQuietZone if set).
     */
    ARTISTIC_ENGINE,

    /**
     * Production safety generation mode:
     * - Encodes with standard ISO/ZXing encoder.
     * - Enforces quiet zones and scanability validation.
     * - Allows closed-loop auto-repair feedback.
     */
    SAFE,

    /**
     * EFQRCode 7.0.3 exact behavioral parity mode:
     * - Defaults to error correction level H (30%) when AUTO is specified (matching EFQRCode's default errorCorrectLevel = .h).
     * - Defaults to 1 quiet-zone module across all styles including BASIC (matching EFQRCode's EFStyleParamBackdrop default quietzone = nil -> 1 module).
     * - Uses exact reference generator matrix encoding.
     */
    PARITY_EF
}

/**
 * Public entry point for QR code generation in VeilFrame.
 */
object QrGenerator {

    /**
     * Authoritative generation mode resolver for a given [QrDesign].
     *
     * Ensures all pipelines (preview, gallery PNG, share, SVG export, auto-repair)
     * use [GenerationMode.ARTISTIC_ENGINE] for artistic styles (such as [QrStyle.IMAGE_RESAMPLE]),
     * and [GenerationMode.PARITY_EF] for [QrStyle.BASIC] to guarantee canonical EFQRCode 7.0.3
     * bit-for-bit parity (via [VeilQrEncoder] with EC Level H default).
     * If safe production encoding is required (ISO/IEC 18004 ZXing with standard margins), callers
     * can explicitly specify [GenerationMode.SAFE] or call [generateSafe].
     */
    fun defaultModeFor(design: QrDesign, preferSafe: Boolean = false): GenerationMode =
        if (preferSafe) {
            GenerationMode.SAFE
        } else if (design.style == QrStyle.BASIC || design.style == QrStyle.IMAGE || design.style == QrStyle.IMAGE_FILL) {
            GenerationMode.PARITY_EF
        } else {
            GenerationMode.ARTISTIC_ENGINE
        }

    /**
     * Directly generates the [QrMatrix] adhering to the specified [GenerationMode] and [design],
     * without requiring an Android [Bitmap] or [Canvas] context.
     */
    fun generateMatrix(
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design)
    ): QrMatrix {
        if (content.isBlank()) {
            throw QrError.Input.EmptyContent
        }
        val ecLevel = when (mode) {
            GenerationMode.ARTISTIC_ENGINE, GenerationMode.PARITY_EF -> {
                when (design.correction) {
                    ErrorCorrectionChoice.L -> ErrorCorrectionLevel.L
                    ErrorCorrectionChoice.M -> ErrorCorrectionLevel.M
                    ErrorCorrectionChoice.Q -> ErrorCorrectionLevel.Q
                    ErrorCorrectionChoice.H -> ErrorCorrectionLevel.H
                    ErrorCorrectionChoice.AUTO -> ErrorCorrectionLevel.H // EFQRCode default is strictly H
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

        return when (mode) {
            GenerationMode.ARTISTIC_ENGINE -> {
                val hasNonAscii = content.any { it.code > 127 }
                val writeEci = design.forceEci ?: hasNonAscii
                com.veilframe.app.qr.encoder.engine.VeilQrEncoder.encode(content, ecLevel, writeEci = writeEci).matrix
            }
            GenerationMode.PARITY_EF -> {
                // Parity with EFQRCode 7.0.3 / QRCodeSwift 2.3.1: Upstream byte mode does not write ECI blocks by default.
                // Callers can explicitly specify forceEci = true to emit ISO/IEC 18004 ECI headers.
                val writeEci = design.forceEci ?: false
                com.veilframe.app.qr.encoder.engine.VeilQrEncoder.encode(content, ecLevel, writeEci = writeEci).matrix
            }
            GenerationMode.SAFE -> {
                QrEncoder.encode(content, ecLevel).matrix
            }
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
     * Directly generates the EFQRCode 7.0.3 parity [QrMatrix] with default EC level H.
     */
    fun generateParityMatrix(
        content: String,
        design: QrDesign = QrDesign()
    ): QrMatrix = generateMatrix(content, design, mode = GenerationMode.PARITY_EF)

    /**
     * Generates a list of [QrFrame] items for an animated QR design.
     *
     * Uses [effectiveDesignForMode] so that PARITY_EF mode applies the EF profile
     * to both matrix encoding AND frame rendering — they cannot diverge.
     *
     * @deprecated Use [generateAnimatedFramesResult] for fail-closed typed error handling.
     */
    @Deprecated(
        message = "Use generateAnimatedFramesResult for fail-closed typed error handling.",
        replaceWith = ReplaceWith("generateAnimatedFramesResult(content, design, outputSize, mode, policy)")
    )
    fun generateAnimatedFrames(
        content: String,
        design: QrDesign,
        outputSize: Int = 512,
        mode: GenerationMode = defaultModeFor(design),
        policy: AnimatedQrGenerator.FrameDropPolicy = AnimatedQrGenerator.FrameDropPolicy.FailFast
    ): List<com.veilframe.app.qr.model.QrFrame> {
        val effectiveDesign = effectiveDesignForMode(design, mode)
        val matrix = generateMatrix(content, effectiveDesign, mode)
        return AnimatedQrGenerator.renderDesign(matrix, effectiveDesign, outputSize, policy)
    }

    /**
     * Generates a list of [com.veilframe.app.qr.model.QrFrame] items returning typed [QrOutputResult].
     * Adheres to the specified [policy]: fails fast with typed [QrError] on any frame failure,
     * or skips failed frames when explicitly configured (GUIDE.txt Issue 11).
     */
    fun generateAnimatedFramesResult(
        content: String,
        design: QrDesign,
        outputSize: Int = 512,
        mode: GenerationMode = defaultModeFor(design),
        policy: AnimatedQrGenerator.FrameDropPolicy = AnimatedQrGenerator.FrameDropPolicy.FailFast
    ): QrOutputResult<List<com.veilframe.app.qr.model.QrFrame>> {
        val effectiveDesign = effectiveDesignForMode(design, mode)
        val matrix = generateMatrix(content, effectiveDesign, mode)
        return AnimatedQrGenerator.renderDesignResult(matrix, effectiveDesign, outputSize, policy)
    }

    /**
     * Generates a fully animated vector SVG string for an animated QR design.
     *
     * Uses [effectiveDesignForMode] so that PARITY_EF mode applies the EF profile
     * to both matrix encoding AND SVG rendering — they cannot diverge (GUIDE.txt Issue 2).
     */
    fun generateAnimatedSvg(
        content: String,
        design: QrDesign,
        mode: GenerationMode = defaultModeFor(design),
        geometry: com.veilframe.app.qr.model.QrGeometry? = null
    ): String {
        val effectiveDesign = effectiveDesignForMode(design, mode)
        val matrix = generateMatrix(content, effectiveDesign, mode)
        return AnimatedQrGenerator.generateAnimatedSvg(matrix, effectiveDesign, geometry = geometry)
    }

    /**
     * Generates a true vector SVG document directly for a [matrix] and [design].
     */
    fun generateSvg(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: com.veilframe.app.qr.model.QrGeometry? = null
    ): String {
        return if (AnimatedQrGenerator.isDesignAnimated(design)) {
            AnimatedQrGenerator.generateAnimatedSvg(matrix, design, geometry = geometry)
        } else {
            com.veilframe.app.qr.exporter.SvgExporter.generateSvg(matrix, design, geometry = geometry)
        }
    }

    /**
     * Generates a true vector SVG document directly for [content] and [design].
     * Propagates effective EF_PARITY quiet zone and profile settings when running in PARITY_EF mode.
     */
    fun generateSvg(
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design),
        geometry: com.veilframe.app.qr.model.QrGeometry? = null
    ): String {
        val effectiveDesign = effectiveDesignForMode(design, mode)
        val matrix = generateMatrix(content, effectiveDesign, mode)
        return generateSvg(matrix, effectiveDesign, geometry = geometry)
    }

    /**
     * Generates an exact EFQRCode 7.0.3 parity SVG document directly for [content] and [design].
     */
    fun generateEfCompatibleSvg(
        content: String,
        design: QrDesign = QrDesign()
    ): String = generateSvg(content, design, mode = GenerationMode.PARITY_EF)

    /**
     * Convenience entry point for generating parity SVG with EFQRCode 7.0.3 exact behavioral parity.
     */
    fun generateParitySvg(
        content: String,
        design: QrDesign = QrDesign()
    ): String = generateSvg(content, design, mode = GenerationMode.PARITY_EF)

    /**
     * Modern domain generation entry point returning typed [QrRenderResult]
     * with automated structural and decode scanability validation.
     */
    /**
     * Canonical internal suspend generation pipeline (Audit Pass 2 architectural unification).
     * All synchronous and suspend public API entry points delegate to this single implementation,
     * eliminating implementation duplication and preventing behavioral drift.
     */
    private suspend fun generateCore(
        content: String,
        design: QrDesign,
        mode: GenerationMode,
        strictValidation: Boolean
    ): QrRenderResult = withContext(Dispatchers.Default) {
        if (content.isBlank()) {
            return@withContext QrRenderResult.Failure(QrError.Input.EmptyContent)
        }
        try {
            val effectiveDesign = effectiveDesignForMode(design, mode)
            val matrix = generateMatrix(content, effectiveDesign, mode)
            val size = effectiveDesign.outputSize.coerceIn(256, 4096)
            val geometry = QrGeometry.fromDesign(
                matrixSize = matrix.size,
                outputWidth = size,
                outputHeight = size,
                design = effectiveDesign
            )
            when (val bitmapResult = generateBitmapResult(matrix, effectiveDesign, geometry)) {
                is BitmapRenderResult.Failure -> QrRenderResult.Failure(bitmapResult.error)
                is BitmapRenderResult.Success -> {
                    val bitmap = bitmapResult.bitmap
                    val report = if (strictValidation) {
                        ScanabilityValidator.validateStrict(bitmap, effectiveDesign, matrix, content)
                    } else {
                        ScanabilityValidator.validateFast(bitmap, effectiveDesign, matrix, content)
                    }
                    QrRenderResult.Success(
                        bitmap = bitmap,
                        report = report,
                        matrix = matrix,
                        design = effectiveDesign
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            QrRenderResult.Failure(QrError.fromThrowable(t))
        }
    }

    /**
     * Modern domain generation entry point returning typed [QrRenderResult]
     * with automated structural and decode scanability validation.
     * Synchronous compatibility wrapper around canonical [generateCore].
     */
    fun generateWithResult(
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design),
        strictValidation: Boolean = false
    ): QrRenderResult = runBlocking(Dispatchers.Default) {
        generateCore(content, design, mode, strictValidation)
    }

    /**
     * Canonical entry point for generating QR codes with strict compliance validation (Audit C-01 / C-04).
     */
    fun generateStrictWithResult(
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design)
    ): QrRenderResult = generateWithResult(content, design, mode = mode, strictValidation = true)

    /**
     * Context-aware overload that safely materializes any [com.veilframe.app.qr.model.ImageSource.Uri] or
     * [com.veilframe.app.qr.model.ImageSource.Resource] via [com.veilframe.app.qr.image.ImageSourceLoader]
     * before generating the QR code.
     */
    fun generateWithResult(
        context: Context,
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design),
        strictValidation: Boolean = false
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateWithResult(content, materialized, mode, strictValidation)
    }

    /**
     * Context-aware overload that safely materializes unmaterialized image sources and executes strict validation.
     */
    fun generateStrictWithResult(
        context: Context,
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design)
    ): QrRenderResult = generateWithResult(context, content, design, mode = mode, strictValidation = true)

    /**
     * Convenience entry point for generating deterministic, exact VeilFrame artistic QR codes.
     */
    fun generateArtistic(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult = generateWithResult(content, design, mode = GenerationMode.ARTISTIC_ENGINE)

    suspend fun generateArtisticSuspend(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult = generateWithResultSuspend(content, design, mode = GenerationMode.ARTISTIC_ENGINE)

    fun generateArtistic(
        context: Context,
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateArtistic(content, materialized)
    }

    suspend fun generateArtisticSuspend(
        context: Context,
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateArtisticSuspend(content, materialized)
    }

    /**
     * Convenience entry point for generating QR code with EFQRCode 7.0.3 exact behavioral parity.
     */
    fun generateParity(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult = generateWithResult(content, design, mode = GenerationMode.PARITY_EF)

    suspend fun generateParitySuspend(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult = generateWithResultSuspend(content, design, mode = GenerationMode.PARITY_EF)

    fun generateParity(
        context: Context,
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateParity(content, materialized)
    }

    suspend fun generateParitySuspend(
        context: Context,
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateParitySuspend(content, materialized)
    }

    /**
     * Coroutine-native generation entry point returning typed [QrRenderResult]
     * executing off the main looper on [Dispatchers.Default] without blocking threads.
     */
    suspend fun generateWithResultSuspend(
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design),
        strictValidation: Boolean = false
    ): QrRenderResult = generateCore(content, design, mode, strictValidation)

    /**
     * Coroutine-native entry point for generating QR codes with strict compliance validation.
     */
    suspend fun generateStrictWithResultSuspend(
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design)
    ): QrRenderResult = generateWithResultSuspend(content, design, mode = mode, strictValidation = true)

    /**
     * Context-aware coroutine entry point that safely materializes unmaterialized image sources via
     * [com.veilframe.app.qr.image.ImageSourceLoader] before generating the QR code.
     */
    suspend fun generateWithResultSuspend(
        context: Context,
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design),
        strictValidation: Boolean = false
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateWithResultSuspend(content, materialized, mode, strictValidation)
    }

    /**
     * Context-aware coroutine entry point that safely materializes unmaterialized image sources and executes strict validation.
     */
    suspend fun generateStrictWithResultSuspend(
        context: Context,
        content: String,
        design: QrDesign = QrDesign(),
        mode: GenerationMode = defaultModeFor(design)
    ): QrRenderResult = generateWithResultSuspend(context, content, design, mode = mode, strictValidation = true)

    private fun prepareSafeDesign(design: QrDesign): QrDesign = design.copy(
        quietZoneModules = maxOf(4, design.quietZoneModules),
        explicitQuietZone = maxOf(4, design.explicitQuietZone ?: 4),
        directionalQuietZone = null,
        backdropStyle = design.backdropStyle.copy(fractionalQuietZone = null)
    )

    private fun verifySafeResult(result: QrRenderResult): QrRenderResult = when (result) {
        is QrRenderResult.Failure -> result
        is QrRenderResult.Success -> {
            if (!result.report.isScanReady || !result.report.decodeResult.success) {
                QrRenderResult.Failure(
                    QrError.Validation.ScanabilityFailed(
                        reason = "Safe QR contract violation: strict scanability failed",
                        warnings = result.report.warnings
                    )
                )
            } else {
                result
            }
        }
    }

    /**
     * Canonical entry point for generating safe, production-grade QR codes.
     *
     * Enforces the complete safe contract:
     * 1. ISO/IEC 18004 ZXing encoding ([GenerationMode.SAFE])
     * 2. Mandatory 4-module quiet zone (overriding any user zero/custom margin)
     * 3. Strict scanability and compliance validation
     * 4. Closed failure if the strict contract fails.
     */
    fun generateSafe(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult = runBlocking(Dispatchers.Default) {
        generateSafeSuspend(content, design)
    }

    fun generateSafe(
        context: Context,
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateSafe(content, materialized)
    }

    suspend fun generateSafeSuspend(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val safeDesign = prepareSafeDesign(design)
        val result = generateCore(content, safeDesign, mode = GenerationMode.SAFE, strictValidation = true)
        return verifySafeResult(result)
    }

    suspend fun generateSafeSuspend(
        context: Context,
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateSafeSuspend(content, materialized)
    }

    /**
     * Canonical entry point for generating exact EFQRCode 7.0.3 parity QR codes
     * using VeilQrEncoder bit-for-bit parity, EC Level H default, and 1-module quiet zone.
     */
    fun generateEfCompatible(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult = generateWithResult(content, design, mode = GenerationMode.PARITY_EF)

    suspend fun generateEfCompatibleSuspend(
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult = generateWithResultSuspend(content, design, mode = GenerationMode.PARITY_EF)

    fun generateEfCompatible(
        context: Context,
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateEfCompatible(content, materialized)
    }

    suspend fun generateEfCompatibleSuspend(
        context: Context,
        content: String,
        design: QrDesign = QrDesign()
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateEfCompatibleSuspend(content, materialized)
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
    ): QrRenderResult = runBlocking(Dispatchers.Default) {
        generateWithAutoRepairSuspend(content, design, maxAttempts)
    }

    fun generateWithAutoRepair(
        context: Context,
        content: String,
        design: QrDesign = QrDesign(),
        maxAttempts: Int = 3
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateWithAutoRepair(content, materialized, maxAttempts)
    }

    suspend fun generateWithAutoRepairSuspend(
        content: String,
        design: QrDesign = QrDesign(),
        maxAttempts: Int = 3
    ): QrRenderResult = withContext(Dispatchers.Default) {
        if (content.isBlank()) {
            return@withContext QrRenderResult.Failure(QrError.Input.EmptyContent)
        }

        var currentDesign = design
        var lastSuccess: QrRenderResult.Success? = null
        val repairTrail = mutableListOf<String>()

        for (attempt in 1..maxAttempts) {
            val result = generateCore(content, currentDesign, defaultModeFor(currentDesign), strictValidation = false)
            when (result) {
                is QrRenderResult.Failure -> return@withContext result
                is QrRenderResult.Success -> {
                    lastSuccess = result
                    // Check if decode succeeded and report is scan-ready
                    if (result.report.isScanReady && result.report.decodeResult.success) {
                        return@withContext result
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

        lastSuccess ?: QrRenderResult.Failure("Auto-repair failed to produce a valid QR code")
    }

    suspend fun generateWithAutoRepairSuspend(
        context: Context,
        content: String,
        design: QrDesign = QrDesign(),
        maxAttempts: Int = 3
    ): QrRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateWithAutoRepairSuspend(content, materialized, maxAttempts)
    }

    /**
     * Typed result for bitmap rendering operations (Audit M-01).
     */
    sealed interface BitmapRenderResult {
        data class Success(val bitmap: Bitmap) : BitmapRenderResult
        data class Failure(val error: QrError) : BitmapRenderResult
    }

    /**
     * Context-aware overload that safely materializes unmaterialized image sources via [com.veilframe.app.qr.image.ImageSourceLoader]
     * before rendering native bitmap.
     */
    fun generateBitmapResult(
        context: Context,
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry = QrGeometry.fromDesign(matrix.size, design.outputSize, design.outputSize, design)
    ): BitmapRenderResult {
        val materialized = com.veilframe.app.qr.image.ImageSourceLoader.materializeDesign(context, design)
        return generateBitmapResult(matrix, materialized, geometry)
    }

    /**
     * Authoritative generation method producing typed [BitmapRenderResult].
     * Explicitly isolates allocation and canvas failures as [BitmapRenderResult.Failure] (Audit M-01).
     */
    fun generateBitmapResult(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry = QrGeometry.fromDesign(matrix.size, design.outputSize, design.outputSize, design)
    ): BitmapRenderResult {
        val width = geometry.outputWidth
        val height = geometry.outputHeight

        // Fail-closed validation for unmaterialized image sources (Audit Item 9)
        val imgSource = design.imageSource.source
        if ((design.style == QrStyle.IMAGE || design.style == QrStyle.IMAGE_FILL || design.style == QrStyle.IMAGE_RESAMPLE) && design.imageSource.bitmap == null) {
            if (imgSource is com.veilframe.app.qr.model.ImageSource.Uri || imgSource is com.veilframe.app.qr.model.ImageSource.Resource) {
                val desc = when (imgSource) {
                    is com.veilframe.app.qr.model.ImageSource.Uri -> "Uri(${imgSource.value})"
                    is com.veilframe.app.qr.model.ImageSource.Resource -> "Resource(id=${imgSource.id})"
                    else -> imgSource.toString()
                }
                return BitmapRenderResult.Failure(QrError.Image.UnmaterializedSource(desc))
            }
        }

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return BitmapRenderResult.Failure(
                QrError.Rendering.BitmapAllocationFailed(width = width, height = height, cause = t)
            )
        } ?: return BitmapRenderResult.Failure(
            QrError.Rendering.BitmapAllocationFailed(width = width, height = height)
        )

        return try {
            val canvas = Canvas(bitmap)
            renderToCanvas(matrix, design, canvas, geometry)
            BitmapRenderResult.Success(bitmap)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            try { bitmap.recycle() } catch (_: Throwable) {}
            BitmapRenderResult.Failure(
                QrError.Rendering.CanvasRenderFailed(stage = "QR bitmap rendering", cause = t)
            )
        }
    }

    /**
     * Directly renders a [QrMatrix] into a styled [Bitmap] according to [design] and [geometry].
     * Returns null if native Bitmap allocation fails.
     */
    @Deprecated(
        message = "Use generateBitmapResult(matrix, design, geometry) for typed error handling.",
        replaceWith = ReplaceWith("generateBitmapResult(matrix, design, geometry)")
    )
    fun generateBitmap(
        matrix: QrMatrix,
        design: QrDesign,
        geometry: QrGeometry = QrGeometry.fromDesign(matrix.size, design.outputSize, design.outputSize, design)
    ): Bitmap? {
        return when (val res = generateBitmapResult(matrix, design, geometry)) {
            is BitmapRenderResult.Success -> res.bitmap
            is BitmapRenderResult.Failure -> null
        }
    }

    /**
     * Directly renders a [matrix] and [design] onto an existing [canvas] and [geometry].
     * Allows test harnesses, print/PDF engines, and custom drawing pipelines to render
     * without requiring an intermediate Bitmap allocation.
     */
    fun renderToCanvas(
        matrix: QrMatrix,
        design: QrDesign,
        canvas: Canvas,
        geometry: QrGeometry = QrGeometry.fromDesign(matrix.size, design.outputSize, design.outputSize, design)
    ) {
        val width = geometry.outputWidthFloat
        val height = geometry.outputHeightFloat
        val context = RenderContext()

        val renderer: QrRenderer = getRendererForDesign(design)
        val ownsBackdrop = renderer is com.veilframe.app.qr.renderer.IrBackedQrRenderer && renderer.ownsBackdrop

        // EF generic backdrop contract: corner clipping for renderers that do not manage their own backdrop in IR
        val crPx = if (!ownsBackdrop && design.backdropStyle.cornerRadius > 0f) {
            design.backdropStyle.cornerRadius
        } else 0f
        val count = if (crPx > 0f) {
            val saveCount = canvas.save()
            val clipPath = android.graphics.Path().apply {
                addRoundRect(0f, 0f, width, height, crPx, crPx, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            saveCount
        } else null

        try {
            // 1. Draw Canvas Background (including Quiet Zone margins) only if renderer does not own backdrop
            if (!ownsBackdrop) {
                drawBackground(canvas, design, width, height, context)
            }

            // 2. Render QR Code
            renderer.render(matrix, design, canvas, geometry, context)
        } finally {
            if (count != null) canvas.restoreToCount(count)
        }
    }

    /**
     * Legacy synchronous generation overload returning a raw [Bitmap].
     */
    @Deprecated(
        message = "Use generateWithResult(content, design) or generateEfCompatible(content, design) for typed error handling and closed-loop verification.",
        replaceWith = ReplaceWith("generateWithResult(content, QrDesign.fromQrStyleParams(params))")
    )
    fun generate(
        content: String,
        params: QrStyleParams = QrStyleParams(),
        ecLevel: ErrorCorrectionLevel = if (params.logo != null || params.style == QrStyle.BASIC || params.style == QrStyle.IMAGE || params.style == QrStyle.IMAGE_FILL) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M
    ): Bitmap {
        require(content.isNotBlank()) { "QR content must not be blank" }

        val baseDesign = QrDesign.fromQrStyleParams(params)
        val design = baseDesign.copy(correction = ErrorCorrectionChoice.fromZxing(ecLevel))
        val result = generateWithResult(content, design)
        return when (result) {
            is QrRenderResult.Success -> result.bitmap ?: throw IllegalStateException("Bitmap creation failed")
            is QrRenderResult.Failure -> throw IllegalStateException(result.error, result.throwable)
        }
    }

    /**
     * Generates a QR code as a PNG [ByteArray].
     */
    @Deprecated(
        message = "Use QrExporter.exportTyped(context, content, design, QrOutputFormat.Png) for typed error handling.",
        replaceWith = ReplaceWith("generateWithResult(content, QrDesign.fromQrStyleParams(params))")
    )
    fun generatePng(
        content: String,
        params: QrStyleParams = QrStyleParams(),
        quality: Int = 100,
        ecLevel: ErrorCorrectionLevel = if (params.logo != null) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M
    ): ByteArray {
        val bmp = generate(content, params, ecLevel)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, quality, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    /**
     * Generates a QR code as a JPEG [ByteArray].
     */
    @Deprecated(
        message = "Use QrExporter.exportTyped(context, content, design, QrOutputFormat.Jpeg(quality)) for typed error handling.",
        replaceWith = ReplaceWith("generateWithResult(content, QrDesign.fromQrStyleParams(params))")
    )
    fun generateJpeg(
        content: String,
        params: QrStyleParams = QrStyleParams(),
        quality: Int = 90,
        ecLevel: ErrorCorrectionLevel = if (params.logo != null) ErrorCorrectionLevel.H else ErrorCorrectionLevel.M
    ): ByteArray {
        val bmp = generate(content, params, ecLevel)
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        bmp.recycle()
        return baos.toByteArray()
    }

    /**
     * Authoritative generation and parity normalization helper (GUIDE.txt Issue 2 / AGENTS.md §4 / AUDIT C-02).
     *
     * For [GenerationMode.PARITY_EF]:
     *   - Sets [QrDesign.basicProfile] to [BasicGeometryProfile.EF_PARITY].
     *   - Defaults quiet zone to 1 module only when no explicit, directional, or
     *     fractional quiet-zone override is present.
     *   - Preserves all other design fields unchanged.
     *
     * For [GenerationMode.SAFE]:
     *   - Sets quiet zone to standard ISO/IEC 18004 margin (4 modules) only when no
     *     explicit, directional, or fractional quiet-zone override is present.
     *   - Preserves explicit quiet-zone intent and all other design fields unchanged.
     *
     * For [GenerationMode.ARTISTIC_ENGINE]: returns [design] as-is.
     *
     * This is the single location that performs mode normalization.
     * All entry points (generateWithResult, generateSvg, generateAnimatedSvg,
     * generateAnimatedFrames) must call this and nowhere else.
     */
    internal fun effectiveDesignForMode(
        design: QrDesign,
        mode: GenerationMode
    ): QrDesign = when (mode) {
        GenerationMode.PARITY_EF -> {
            val defaultQz = QrGeometry.resolveDefaultQuietZone(design.style, defaultFallback = 1)
            val qz = if (
                design.explicitQuietZone == null &&
                design.directionalQuietZone == null &&
                design.backdropStyle.fractionalQuietZone == null
            ) defaultQz else design.quietZoneModules
            val effImgScale = if (design.style == QrStyle.IMAGE && design.imageDataScale == null) 1.0f else design.imageDataScale
            design.copy(
                basicProfile = BasicGeometryProfile.EF_PARITY,
                quietZoneModules = qz,
                imageDataScale = effImgScale
            )
        }
        GenerationMode.SAFE -> {
            val qz = if (
                design.explicitQuietZone == null &&
                design.directionalQuietZone == null &&
                design.backdropStyle.fractionalQuietZone == null
            ) 4 else design.quietZoneModules
            design.copy(
                quietZoneModules = qz
            )
        }
        GenerationMode.ARTISTIC_ENGINE -> design
    }

    private fun drawBackground(
        canvas: Canvas,
        design: QrDesign,
        width: Float,
        height: Float,
        context: RenderContext
    ) {
        val resolvedBackdropColor = design.backdropStyle.color ?: design.palette.background
        when (val background = design.background) {
            is BackgroundStyle.Solid -> {
                val effectiveColor = if (design.backdropStyle.color != null) resolvedBackdropColor else background.color
                val paint = context.fillPaint
                paint.reset()
                paint.isAntiAlias = true
                paint.color = effectiveColor
                canvas.drawRect(0f, 0f, width, height, paint)
            }
            is BackgroundStyle.LinearGradient -> {
                val paint = context.fillPaint
                paint.reset()
                paint.isAntiAlias = true
                // Issue 6 fix: use angleDegrees from the model instead of hardcoded diagonal.
                // GeometryFill.LinearGradient.fromAngle() computes the same endpoints used
                // by the SVG path, keeping Canvas and SVG mathematically identical.
                val spec = com.veilframe.app.qr.geometry.GeometryFill.LinearGradient.fromAngle(
                    width = width,
                    height = height,
                    startColor = background.startColor,
                    endColor = background.endColor,
                    angleDegrees = background.angleDegrees
                )
                paint.shader = LinearGradient(
                    spec.x0, spec.y0, spec.x1, spec.y1,
                    background.startColor, background.endColor,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width, height, paint)
            }
            is BackgroundStyle.RadialGradient -> {
                val paint = context.fillPaint
                paint.reset()
                paint.isAntiAlias = true
                val radius = maxOf(width, height) / 2f
                paint.shader = RadialGradient(
                    width / 2f, height / 2f, radius,
                    background.centerColor, background.edgeColor,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, 0f, width, height, paint)
            }
            is BackgroundStyle.Image -> {
                // Clear with configured backdrop background color first, then draw background image with specified alpha
                val clearPaint = context.fillPaint
                clearPaint.reset()
                clearPaint.color = resolvedBackdropColor
                canvas.drawRect(0f, 0f, width, height, clearPaint)
                val paint = context.tempPaint
                paint.reset()
                paint.isAntiAlias = true
                paint.isFilterBitmap = true
                paint.alpha = (background.alpha.coerceIn(0f, 1f) * 255).toInt()
                val targetBounds = android.graphics.RectF(0f, 0f, width, height)
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

        // Draw backdrop image if present and background is not already BackgroundStyle.Image,
        // and style is not already a dedicated backdrop style (IMAGE or IMAGE_FILL) which renders its own backdrop
        val isDedicatedBackdropRenderer = design.style == QrStyle.IMAGE || design.style == QrStyle.IMAGE_FILL
        val backdropImg = design.backdropStyle.image
        if (backdropImg != null && !backdropImg.isRecycled && design.background !is BackgroundStyle.Image && !isDedicatedBackdropRenderer) {
            val preprocessedBackdrop = com.veilframe.app.qr.image.EfImagePreprocessor.preprocess(
                source = backdropImg,
                canvasWidth = width,
                canvasHeight = height,
                mode = design.backdropStyle.imageScaleMode
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = false
                alpha = (design.backdropStyle.imageAlpha.coerceIn(0f, 1f) * 255).toInt()
            }
            // Preprocessed backdrop matches canvas aspect ratio; draw 1:1 without second crop/scale pass.
            canvas.drawBitmap(preprocessedBackdrop, null, android.graphics.RectF(0f, 0f, width, height), paint)
        }
    }

    internal fun getRendererForDesign(design: QrDesign): QrRenderer {
        return if (design.style == QrStyle.IMAGE_FILL) {
            ImageFillRenderer()
        } else if (design.style == QrStyle.BUBBLE) {
            BubbleRenderer()
        } else if (design.moduleStyle.fill == ModuleFill.IMAGE_MASKED ||
            (design.style == QrStyle.BASIC && design.moduleStyle.shape == ModuleShape.BUBBLE_CLUSTER)
        ) {
            ComposableQrRenderer()
        } else {
            QrStyleRegistry.getRenderer(design.style)
        }
    }

    /**
     * Unified public export dispatcher mirroring EFQRCode generator surface.
     * Encodes and renders [design] with [content], exporting to [format].
     */
    suspend fun export(
        context: android.content.Context,
        content: String,
        design: QrDesign = QrDesign(),
        format: QrOutputFormat = QrOutputFormat.Png
    ): QrOutputResult<android.net.Uri> = com.veilframe.app.qr.exporter.QrExporter.exportTyped(context, content, design, format)
}
