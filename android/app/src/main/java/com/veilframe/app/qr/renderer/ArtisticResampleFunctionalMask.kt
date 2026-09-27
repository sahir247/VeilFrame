package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.encoder.engine.QRPatternLocator
import com.veilframe.app.qr.model.ModuleShape

/**
 * Functional exclusion mask specifically engineered for 3x3 Stochastic Subpixel Resampling
 * with visual parity to canonical artistic subpixel gray point sampling.
 *
 * Architectural Invariants:
 * 1. Finders: Excludes exactly an 8x8 module area (24x24 subpixels in 3x coordinate space)
 *    at each of the three position pattern corners:
 *    - Top-Left: [0 until 8, 0 until 8]
 *    - Top-Right: [(size - 8) until size, 0 until 8]
 *    - Bottom-Left: [0 until 8, (size - 8) until size]
 * 2. Timing Patterns: Excludes timing track modules on Row 6 and Column 6 between finders
 *    (modules 8 until size - 8), preserving dedicated timing rendering. If timing shape is
 *    [ModuleShape.NONE], light timing modules are not excluded from stochastic dithering.
 * 3. Alignment Patterns: For QR versions >= 2, excludes the 5x5 module area around
 *    valid alignment pattern centers, avoiding overlap with finders. If alignment shape is
 *    [ModuleShape.NONE], light alignment modules are not excluded from stochastic dithering.
 * 4. Format & Version Information: Deliberately NOT excluded. Unlike standard QR structural
 *    protection (which blankets format/version with solid modules), stochastic image resampling
 *    traverses format and version modules just like data modules, emitting center subpixel
 *    anchors for dark bits while allowing stochastic photo dithering dots around them.
 */
object ArtisticResampleFunctionalMask {

    /**
     * Checks if subpixel ([subX], [subY]) in [0, 3N) x [0, 3N) is filtered out from stochastic sampling.
     * Strictly driven by the canonical [com.veilframe.app.qr.model.QrMatrix.roleAt] single source of truth.
     */
    fun isSubpixelExcluded(
        matrix: com.veilframe.app.qr.model.QrMatrix,
        subX: Int,
        subY: Int,
        timingShape: ModuleShape = ModuleShape.SQUARE,
        alignmentShape: ModuleShape = ModuleShape.SQUARE
    ): Boolean {
        val nCount = matrix.size
        val maxCoord = 3 * nCount
        if (subX !in 0 until maxCoord || subY !in 0 until maxCoord) return true

        val col = subX / 3
        val row = subY / 3
        val role = matrix.roleAt(col, row)

        // 1. posOrigins (24x24 subpixels / 8x8 modules around each of the 3 finders)
        if (role == com.veilframe.app.qr.model.QrModuleRole.FINDER_INNER ||
            role == com.veilframe.app.qr.model.QrModuleRole.FINDER_OUTER ||
            role == com.veilframe.app.qr.model.QrModuleRole.SEPARATOR
        ) {
            return true
        }

        val isDark = matrix.isDark(col, row)

        // 2. Timing tracks
        if (role == com.veilframe.app.qr.model.QrModuleRole.TIMING) {
            if (isDark) {
                // Upstream EF getGrayPointList never suppresses dark timing pixels.
                // Non-center subpixels are sampled stochastically, then dedicated geometry is drawn on top.
                return false
            } else {
                return if (timingShape == ModuleShape.NONE) {
                    subX % 3 == 1 && subY % 3 == 1
                } else {
                    true
                }
            }
        }

        // 3. Alignment patterns
        if (role == com.veilframe.app.qr.model.QrModuleRole.ALIGNMENT_CENTER ||
            role == com.veilframe.app.qr.model.QrModuleRole.ALIGNMENT_BORDER
        ) {
            if (isDark) {
                // Upstream EF getGrayPointList never suppresses dark alignment pixels.
                // Non-center subpixels are sampled stochastically, then dedicated geometry is drawn on top.
                return false
            } else {
                return if (alignmentShape == ModuleShape.NONE) {
                    subX % 3 == 1 && subY % 3 == 1
                } else {
                    true
                }
            }
        }

        return false
    }

    /**
     * Module-level check for functional area exclusion using matrix role classification.
     */
    fun isExcluded(
        matrix: com.veilframe.app.qr.model.QrMatrix,
        col: Int,
        row: Int,
        timingShape: ModuleShape = ModuleShape.SQUARE,
        alignmentShape: ModuleShape = ModuleShape.SQUARE
    ): Boolean {
        if (col !in 0 until matrix.size || row !in 0 until matrix.size) return true
        return when (matrix.roleAt(col, row)) {
            com.veilframe.app.qr.model.QrModuleRole.FINDER_INNER,
            com.veilframe.app.qr.model.QrModuleRole.FINDER_OUTER,
            com.veilframe.app.qr.model.QrModuleRole.SEPARATOR -> true
            com.veilframe.app.qr.model.QrModuleRole.TIMING -> timingShape != ModuleShape.NONE
            com.veilframe.app.qr.model.QrModuleRole.ALIGNMENT_CENTER,
            com.veilframe.app.qr.model.QrModuleRole.ALIGNMENT_BORDER -> alignmentShape != ModuleShape.NONE
            else -> false
        }
    }

    /**
     * Module-level check for functional area exclusion (e.g. for dedicated geometry suppression).
     */
    fun isExcluded(
        col: Int,
        row: Int,
        size: Int,
        version: Int,
        timingShape: ModuleShape = ModuleShape.SQUARE,
        alignmentShape: ModuleShape = ModuleShape.SQUARE
    ): Boolean {
        if (col !in 0 until size || row !in 0 until size) return true
        val mask = com.veilframe.app.qr.model.FunctionPatternMask(size, version)
        return when (mask.roleAt(col, row)) {
            com.veilframe.app.qr.model.QrModuleRole.FINDER_INNER,
            com.veilframe.app.qr.model.QrModuleRole.FINDER_OUTER,
            com.veilframe.app.qr.model.QrModuleRole.SEPARATOR -> true
            com.veilframe.app.qr.model.QrModuleRole.TIMING -> timingShape != ModuleShape.NONE
            com.veilframe.app.qr.model.QrModuleRole.ALIGNMENT_CENTER,
            com.veilframe.app.qr.model.QrModuleRole.ALIGNMENT_BORDER -> alignmentShape != ModuleShape.NONE
            else -> false
        }
    }

    fun isFinderArea(col: Int, row: Int, size: Int): Boolean {
        if (col !in 0 until size || row !in 0 until size) return false
        val role = com.veilframe.app.qr.model.FunctionPatternMask(size, 1).roleAt(col, row)
        return role == com.veilframe.app.qr.model.QrModuleRole.FINDER_INNER ||
                role == com.veilframe.app.qr.model.QrModuleRole.FINDER_OUTER ||
                role == com.veilframe.app.qr.model.QrModuleRole.SEPARATOR
    }

    fun isTimingArea(col: Int, row: Int, size: Int): Boolean {
        if (col !in 0 until size || row !in 0 until size) return false
        return com.veilframe.app.qr.model.FunctionPatternMask(size, 1).roleAt(col, row) == com.veilframe.app.qr.model.QrModuleRole.TIMING
    }

    fun isAlignmentArea(col: Int, row: Int, version: Int): Boolean {
        val size = version * 4 + 17
        if (col !in 0 until size || row !in 0 until size || version < 2) return false
        val role = com.veilframe.app.qr.model.FunctionPatternMask(size, version).roleAt(col, row)
        return role == com.veilframe.app.qr.model.QrModuleRole.ALIGNMENT_CENTER ||
                role == com.veilframe.app.qr.model.QrModuleRole.ALIGNMENT_BORDER
    }
}
