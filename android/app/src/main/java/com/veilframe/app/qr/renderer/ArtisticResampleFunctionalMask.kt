package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.encoder.engine.QRPatternLocator

/**
 * Functional exclusion mask specifically engineered for 3x3 Stochastic Subpixel Resampling
 * with visual parity to EFQRCode's getGrayPointList().
 *
 * Architectural Invariants:
 * 1. Finders: Excludes exactly an 8x8 module area (24x24 subpixels in 3x coordinate space)
 *    at each of the three position pattern corners:
 *    - Top-Left: [0 until 8, 0 until 8]
 *    - Top-Right: [(size - 8) until size, 0 until 8]
 *    - Bottom-Left: [0 until 8, (size - 8) until size]
 * 2. Timing Patterns: Excludes timing track modules on Row 6 and Column 6 between finders
 *    (modules 8 until size - 8), preserving dedicated timing rendering.
 * 3. Alignment Patterns: For QR versions >= 2, excludes the 5x5 module area around
 *    valid alignment pattern centers, avoiding overlap with finders.
 * 4. Format & Version Information: Deliberately NOT excluded. Unlike standard QR structural
 *    protection (which blankets format/version with solid modules), stochastic image resampling
 *    traverses format and version modules just like data modules, emitting center subpixel
 *    anchors for dark bits while allowing stochastic photo dithering dots around them.
 */
object ArtisticResampleFunctionalMask {

    /**
     * Returns true if (col, row) is part of a functional area that must be suppressed
     * from stochastic photo dithering in the image resample engine.
     */
    fun isExcluded(col: Int, row: Int, size: Int, version: Int): Boolean {
        if (col !in 0 until size || row !in 0 until size) return true

        // 1. Finder areas: 8x8 modules (24x24 subpixel units) at each corner
        if (isFinderArea(col, row, size)) return true

        // 2. Timing tracks: row 6 and col 6 between finders (modules 8 until size - 8)
        if (isTimingArea(col, row, size)) return true

        // 3. Alignment patterns: 5x5 area around alignment centers (Version >= 2)
        if (version >= 2 && isAlignmentArea(col, row, version)) return true

        // Format and Version modules are NOT suppressed in the resample stochastic pass!
        return false
    }

    private fun isFinderArea(col: Int, row: Int, size: Int): Boolean {
        // Top-Left 8x8
        if (col < 8 && row < 8) return true
        // Top-Right 8x8
        if (col >= size - 8 && row < 8) return true
        // Bottom-Left 8x8
        if (col < 8 && row >= size - 8) return true
        return false
    }

    private fun isTimingArea(col: Int, row: Int, size: Int): Boolean {
        if (row == 6 && col in 8 until (size - 8)) return true
        if (col == 6 && row in 8 until (size - 8)) return true
        return false
    }

    private fun isAlignmentArea(col: Int, row: Int, version: Int): Boolean {
        val centers = QRPatternLocator[version]
        val lastCenter = centers.lastOrNull() ?: return false

        for (cy in centers) {
            for (cx in centers) {
                // Skip positions that collide with finders (corners)
                if ((cx == 6 && cy == 6) ||
                    (cx == 6 && cy == lastCenter) ||
                    (cx == lastCenter && cy == 6)
                ) continue

                // 5x5 module area centered at (cx, cy)
                if (col in (cx - 2)..(cx + 2) && row in (cy - 2)..(cy + 2)) {
                    return true
                }
            }
        }
        return false
    }
}
