package com.veilframe.app.qr.validation

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.decoder.DecodeResult
import com.veilframe.app.qr.decoder.ZxingQrDecoder
import com.veilframe.app.qr.model.FunctionPatternType
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix

enum class RepairReason {
    RESTORE_QUIET_ZONE,
    RESTORE_FINDER_GEOMETRY,
    INCREASE_MODULE_SCALE,
    REDUCE_LOGO_SIZE,
    INCREASE_CONTRAST,
    REDUCE_DEFORMATION,
    ELEVATE_ERROR_CORRECTION
}

data class QuietZoneReport(
    val hasFourModuleMargin: Boolean,
    val quietZoneModules: Int
)

data class ContrastReport(
    val meanDarkLuminance: Float,
    val p90DarkLuminance: Float,
    val meanLightLuminance: Float,
    val p10LightLuminance: Float,
    val separation: Float,
    val isContrastAdequate: Boolean
)

data class FinderIntegrityReport(
    val findersIntact: Boolean,
    val separatorsClear: Boolean
)

data class LogoOcclusionReport(
    val hasProtectedOverlap: Boolean,
    val affectedDataModules: Int,
    val affectedDataFraction: Float,
    val isWithinErrorCorrectionCapacity: Boolean
)

data class ScanabilityReport(
    val isScanReady: Boolean,
    val quietZone: QuietZoneReport,
    val contrast: ContrastReport,
    val finders: FinderIntegrityReport,
    val logo: LogoOcclusionReport,
    val decodeResult: DecodeResult,
    val errorCorrection: ErrorCorrectionLevel,
    val warnings: List<String>,
    val repairSuggestions: List<RepairReason>,
    val validationSkipped: Boolean = false
)

object ScanabilityValidator {

    private val mlKitDecoder by lazy { com.veilframe.app.qr.decoder.MlKitQrDecoder() }
    private val zxingDecoder by lazy { ZxingQrDecoder() }

    var primaryDecoder: com.veilframe.app.qr.decoder.QrDecoder? = null

    /**
     * Fast validation executed during live editing on preview bitmaps (e.g. 512px).
     */
    suspend fun validateFast(
        bitmap: Bitmap,
        design: QrDesign,
        matrix: QrMatrix,
        expectedContent: String
    ): ScanabilityReport {
        return performValidation(bitmap, design, matrix, expectedContent, isStrict = false)
    }

    /**
     * Strict validation executed prior to final export.
     */
    suspend fun validateStrict(
        bitmap: Bitmap,
        design: QrDesign,
        matrix: QrMatrix,
        expectedContent: String
    ): ScanabilityReport {
        return performValidation(bitmap, design, matrix, expectedContent, isStrict = true)
    }

    private suspend fun performValidation(
        bitmap: Bitmap,
        design: QrDesign,
        matrix: QrMatrix,
        expectedContent: String,
        isStrict: Boolean
    ): ScanabilityReport {
        val quietZone = design.effectiveQuietZone
        val geometry = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = bitmap.width,
            outputHeight = bitmap.height,
            quietZoneModules = quietZone
        )

        // 1. Quiet Zone Check
        val quietZoneOk = quietZone >= 4 || (design.explicitQuietZone != null && design.explicitQuietZone >= 0)
        val quietZoneReport = QuietZoneReport(
            hasFourModuleMargin = quietZone >= 4,
            quietZoneModules = quietZone
        )

        // 2. Contrast Distribution Analysis
        val contrastReport = analyzeContrast(bitmap, geometry, matrix)

        // 3. Finder & Separator Integrity Check
        val finderReport = verifyFinderIntegrity(bitmap, geometry, matrix)

        // 4. Logo Occlusion & Hard Function Protection
        val logoReport = analyzeLogoOcclusion(geometry, matrix, design)

        // 5. Decode Validation (Primary: Google ML Kit; Fallback: ZXing)
        val decoder = primaryDecoder ?: mlKitDecoder
        val decodeResult = try {
            decoder.decode(bitmap)
        } catch (e: Throwable) {
            if (decoder != zxingDecoder) {
                try {
                    zxingDecoder.decode(bitmap)
                } catch (ze: Throwable) {
                    DecodeResult(success = false, error = ze.message ?: e.message, decoderId = "ZXing-Fallback")
                }
            } else {
                DecodeResult(success = false, error = e.message, decoderId = decoder.id)
            }
        }
        val decodeMatches = decodeResult.success && decodeResult.text == expectedContent

        // Compile warnings & repair suggestions
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<RepairReason>()

        if (!quietZoneReport.hasFourModuleMargin) {
            warnings.add("Quiet zone is less than the standard 4 modules margin.")
            suggestions.add(RepairReason.RESTORE_QUIET_ZONE)
        }

        if (!contrastReport.isContrastAdequate) {
            warnings.add("Low luminance separation (${String.format("%.2f", contrastReport.separation)}); modules may blend into background.")
            suggestions.add(RepairReason.INCREASE_CONTRAST)
        }

        if (!finderReport.findersIntact || !finderReport.separatorsClear) {
            warnings.add("Finder patterns or separators may be distorted by artistic styling.")
            suggestions.add(RepairReason.RESTORE_FINDER_GEOMETRY)
        }

        if (logoReport.hasProtectedOverlap) {
            warnings.add("Logo overlaps critical protected function patterns (finders/timing).")
            suggestions.add(RepairReason.REDUCE_LOGO_SIZE)
        } else if (!logoReport.isWithinErrorCorrectionCapacity) {
            warnings.add("Logo occludes ${String.format("%.1f", logoReport.affectedDataFraction * 100)}% of data modules, exceeding error correction recovery capacity.")
            suggestions.add(RepairReason.ELEVATE_ERROR_CORRECTION)
            suggestions.add(RepairReason.REDUCE_LOGO_SIZE)
        }

        if (!decodeMatches) {
            warnings.add("${decodeResult.decoderId.ifEmpty { "Barcode decoder" }} failed to decode rendered QR code.")
            if (!suggestions.contains(RepairReason.ELEVATE_ERROR_CORRECTION)) {
                suggestions.add(RepairReason.ELEVATE_ERROR_CORRECTION)
            }
            if (!suggestions.contains(RepairReason.INCREASE_MODULE_SCALE)) {
                suggestions.add(RepairReason.INCREASE_MODULE_SCALE)
            }
            if (!suggestions.contains(RepairReason.REDUCE_DEFORMATION)) {
                suggestions.add(RepairReason.REDUCE_DEFORMATION)
            }
        }

        val isScanReady = finderReport.findersIntact &&
                finderReport.separatorsClear &&
                !logoReport.hasProtectedOverlap &&
                logoReport.isWithinErrorCorrectionCapacity &&
                decodeMatches

        return ScanabilityReport(
            isScanReady = isScanReady,
            quietZone = quietZoneReport,
            contrast = contrastReport,
            finders = finderReport,
            logo = logoReport,
            decodeResult = decodeResult,
            errorCorrection = matrix.ecLevel,
            warnings = warnings,
            repairSuggestions = suggestions.distinct()
        )
    }

    private fun analyzeContrast(
        bitmap: Bitmap,
        geometry: QrGeometry,
        matrix: QrMatrix
    ): ContrastReport {
        val darkLuminances = mutableListOf<Float>()
        val lightLuminances = mutableListOf<Float>()

        val step = maxOf(1, matrix.size / 20) // Sample grid
        val delta = geometry.moduleSize * 0.25f
        val sampleOffsets = listOf(
            Pair(0f, 0f),
            Pair(-delta, -delta),
            Pair(delta, -delta),
            Pair(-delta, delta),
            Pair(delta, delta)
        )

        for (row in 0 until matrix.size step step) {
            for (col in 0 until matrix.size step step) {
                val (cx, cy) = geometry.moduleCenter(col, row)
                for ((ox, oy) in sampleOffsets) {
                    val px = (cx + ox).toInt().coerceIn(0, bitmap.width - 1)
                    val py = (cy + oy).toInt().coerceIn(0, bitmap.height - 1)
                    val pixel = bitmap.getPixel(px, py)

                    // Relative luminance (sRGB standard)
                    val r = Color.red(pixel) / 255f
                    val g = Color.green(pixel) / 255f
                    val b = Color.blue(pixel) / 255f
                    val lum = (0.2126f * r) + (0.7152f * g) + (0.0722f * b)

                    if (matrix.isDark(col, row)) {
                        darkLuminances.add(lum)
                    } else {
                        lightLuminances.add(lum)
                    }
                }
            }
        }

        if (darkLuminances.isEmpty() || lightLuminances.isEmpty()) {
            return ContrastReport(0f, 0f, 1f, 1f, 1f, isContrastAdequate = true)
        }

        darkLuminances.sort()
        lightLuminances.sort()

        val meanDark = darkLuminances.average().toFloat()
        val p90Dark = darkLuminances[(darkLuminances.size * 0.90).toInt().coerceIn(0, darkLuminances.lastIndex)]

        val meanLight = lightLuminances.average().toFloat()
        val p10Light = lightLuminances[(lightLuminances.size * 0.10).toInt().coerceIn(0, lightLuminances.lastIndex)]

        // Distribution separation: Light P10 minus Dark P90
        val separation = p10Light - p90Dark
        val isAdequate = separation >= 0.25f && meanDark < meanLight

        return ContrastReport(
            meanDarkLuminance = meanDark,
            p90DarkLuminance = p90Dark,
            meanLightLuminance = meanLight,
            p10LightLuminance = p10Light,
            separation = separation,
            isContrastAdequate = isAdequate
        )
    }

    private fun getModuleLuminance(bitmap: Bitmap, geometry: QrGeometry, col: Int, row: Int): Float {
        val (cx, cy) = geometry.moduleCenter(col, row)
        val px = cx.toInt().coerceIn(0, bitmap.width - 1)
        val py = cy.toInt().coerceIn(0, bitmap.height - 1)
        val pixel = bitmap.getPixel(px, py)
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    private fun verifyFinderIntegrity(
        bitmap: Bitmap,
        geometry: QrGeometry,
        matrix: QrMatrix
    ): FinderIntegrityReport {
        val n = matrix.size
        val finderCenters = listOf(
            Pair(3, 3),
            Pair(3, n - 4),
            Pair(n - 4, 3)
        )

        var allIntact = true
        var separatorsClear = true

        for ((fcCol, fcRow) in finderCenters) {
            val coreLum = getModuleLuminance(bitmap, geometry, fcCol, fcRow)
            // Center core MUST be dark
            if (coreLum > 0.60f) {
                allIntact = false
                break
            }

            // Light ring (radius 2) should be lighter than core
            val lightRingLums = listOf(
                getModuleLuminance(bitmap, geometry, (fcCol + 2).coerceIn(0, n - 1), fcRow),
                getModuleLuminance(bitmap, geometry, (fcCol - 2).coerceIn(0, n - 1), fcRow),
                getModuleLuminance(bitmap, geometry, fcCol, (fcRow + 2).coerceIn(0, n - 1)),
                getModuleLuminance(bitmap, geometry, fcCol, (fcRow - 2).coerceIn(0, n - 1))
            )
            val avgLightRing = lightRingLums.average().toFloat()
            if (avgLightRing < coreLum || avgLightRing < 0.35f) {
                allIntact = false
                break
            }

            // Outer ring (radius 3) should be darker than light ring
            val outerRingLums = listOf(
                getModuleLuminance(bitmap, geometry, (fcCol + 3).coerceIn(0, n - 1), fcRow),
                getModuleLuminance(bitmap, geometry, (fcCol - 3).coerceIn(0, n - 1), fcRow),
                getModuleLuminance(bitmap, geometry, fcCol, (fcRow + 3).coerceIn(0, n - 1)),
                getModuleLuminance(bitmap, geometry, fcCol, (fcRow - 3).coerceIn(0, n - 1))
            )
            val avgOuterRing = outerRingLums.average().toFloat()
            if (avgOuterRing > 0.65f || avgOuterRing > avgLightRing) {
                allIntact = false
                break
            }
        }

        // Check top-left separator modules
        var separatorDarkCount = 0
        var totalSeparatorSamples = 0
        for (r in 0..7) {
            if (7 < n && r < n) {
                totalSeparatorSamples++
                if (getModuleLuminance(bitmap, geometry, 7, r) < 0.40f) separatorDarkCount++
            }
        }
        for (c in 0..7) {
            if (c < n && 7 < n) {
                totalSeparatorSamples++
                if (getModuleLuminance(bitmap, geometry, c, 7) < 0.40f) separatorDarkCount++
            }
        }
        if (totalSeparatorSamples > 0 && separatorDarkCount.toFloat() / totalSeparatorSamples > 0.35f) {
            separatorsClear = false
        }

        return FinderIntegrityReport(
            findersIntact = allIntact,
            separatorsClear = separatorsClear
        )
    }

    private fun analyzeLogoOcclusion(
        geometry: QrGeometry,
        matrix: QrMatrix,
        design: QrDesign
    ): LogoOcclusionReport {
        val logo = design.logo
        if (logo == null || logo.bitmap == null) {
            return LogoOcclusionReport(
                hasProtectedOverlap = false,
                affectedDataModules = 0,
                affectedDataFraction = 0f,
                isWithinErrorCorrectionCapacity = true
            )
        }

        val logoRect = geometry.computeLogoRect(logo.scaleFraction)
        val (cols, rows) = geometry.mapPixelRectToModules(logoRect)

        var protectedOverlap = false
        var totalDataModules = 0
        var coveredDataModules = 0

        for (r in 0 until matrix.size) {
            for (c in 0 until matrix.size) {
                val isProtected = matrix.functionMask.isProtected(c, r)
                val inLogo = c in cols && r in rows

                if (inLogo && isProtected) {
                    protectedOverlap = true
                }

                if (!isProtected) {
                    totalDataModules++
                    if (inLogo) {
                        coveredDataModules++
                    }
                }
            }
        }

        val coveredFraction = if (totalDataModules > 0) {
            coveredDataModules.toFloat() / totalDataModules
        } else 0f

        // Maximum allowed data loss per Error Correction specification:
        // L: ~7%, M: ~15%, Q: ~25%, H: ~30%
        val maxAllowed = when (matrix.ecLevel) {
            ErrorCorrectionLevel.L -> 0.05f
            ErrorCorrectionLevel.M -> 0.12f
            ErrorCorrectionLevel.Q -> 0.20f
            ErrorCorrectionLevel.H -> 0.25f
        }

        return LogoOcclusionReport(
            hasProtectedOverlap = protectedOverlap,
            affectedDataModules = coveredDataModules,
            affectedDataFraction = coveredFraction,
            isWithinErrorCorrectionCapacity = coveredFraction <= maxAllowed && !protectedOverlap
        )
    }
}
