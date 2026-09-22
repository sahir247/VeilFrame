package com.veilframe.app.qr.validation

import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.encoder.QrEncoder
import com.veilframe.app.qr.model.ErrorCorrectionChoice
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign

data class AutoRepairResult(
    val repairedDesign: QrDesign,
    val changesApplied: List<String>
)

object AutoRepairEngine {

    /**
     * Iteratively repairs a [QrDesign] based on [ScanabilityReport] recommendations.
     *
     * Invariant: When elevating error correction, the QR content must be re-encoded
     * through [QrEncoder] to produce an updated [QrMatrix] and recalculate all visual geometry.
     */
    fun repair(
        currentDesign: QrDesign,
        report: ScanabilityReport,
        content: String
    ): AutoRepairResult {
        var design = currentDesign
        val changes = mutableListOf<String>()

        for (reason in report.repairSuggestions) {
            when (reason) {
                RepairReason.RESTORE_QUIET_ZONE -> {
                    if (design.quietZoneModules < 4) {
                        design = design.copy(quietZoneModules = 4)
                        changes.add("Restored 4-module quiet zone")
                    }
                }

                RepairReason.RESTORE_FINDER_GEOMETRY -> {
                    if (design.eyeStyle.style != FinderStyle.CLASSIC && design.eyeStyle.style != FinderStyle.ROUNDED) {
                        design = design.copy(
                            eyeStyle = design.eyeStyle.copy(style = FinderStyle.ROUNDED)
                        )
                        changes.add("Restored canonical high-contrast finder geometry")
                    }
                }

                RepairReason.INCREASE_MODULE_SCALE -> {
                    val newScale = (design.moduleStyle.scale + 0.1f).coerceAtMost(1.0f)
                    if (newScale > design.moduleStyle.scale) {
                        design = design.copy(
                            moduleStyle = design.moduleStyle.copy(scale = newScale)
                        )
                        changes.add("Increased module fill scale to ${String.format("%.2f", newScale)}")
                    }
                }

                RepairReason.REDUCE_LOGO_SIZE -> {
                    val logo = design.logo
                    if (logo != null && logo.scaleFraction > 0.15f) {
                        val newFraction = (logo.scaleFraction - 0.05f).coerceAtLeast(0.12f)
                        design = design.copy(
                            logo = logo.copy(scaleFraction = newFraction)
                        )
                        changes.add("Reduced logo size to ${String.format("%.0f", newFraction * 100)}% to protect scanability")
                    }
                }

                RepairReason.INCREASE_CONTRAST -> {
                    design = design.copy(
                        palette = design.palette.copy(
                            foreground = Color.BLACK,
                            background = Color.WHITE,
                            gradientStart = null,
                            gradientEnd = null
                        )
                    )
                    changes.add("Maximized luminance contrast (pure black/white palette)")
                }

                RepairReason.REDUCE_DEFORMATION -> {
                    if (design.moduleStyle.shape == ModuleShape.ORGANIC || design.moduleStyle.shape == ModuleShape.LINE) {
                        design = design.copy(
                            moduleStyle = design.moduleStyle.copy(shape = ModuleShape.ROUNDED, cornerRadiusFraction = 0.25f)
                        )
                        changes.add("Normalized module shape from aggressive styling to rounded modules")
                    }
                }

                RepairReason.ELEVATE_ERROR_CORRECTION -> {
                    val nextLevel = when (design.correction) {
                        ErrorCorrectionChoice.L -> ErrorCorrectionChoice.M
                        ErrorCorrectionChoice.AUTO,
                        ErrorCorrectionChoice.M -> ErrorCorrectionChoice.Q
                        ErrorCorrectionChoice.Q -> ErrorCorrectionChoice.H
                        ErrorCorrectionChoice.H -> ErrorCorrectionChoice.H
                    }

                    if (nextLevel != design.correction) {
                        design = design.copy(correction = nextLevel)
                        changes.add("Elevated error correction level to $nextLevel")

                        // Verify re-encoding with new error correction level
                        val zxingLevel = when (nextLevel) {
                            ErrorCorrectionChoice.L -> ErrorCorrectionLevel.L
                            ErrorCorrectionChoice.M -> ErrorCorrectionLevel.M
                            ErrorCorrectionChoice.Q -> ErrorCorrectionLevel.Q
                            ErrorCorrectionChoice.H -> ErrorCorrectionLevel.H
                            ErrorCorrectionChoice.AUTO -> ErrorCorrectionLevel.H
                        }
                        // Re-encode to validate new matrix sizing
                        QrEncoder.encode(content, zxingLevel)
                    }
                }
            }
        }

        return AutoRepairResult(design, changes)
    }
}
