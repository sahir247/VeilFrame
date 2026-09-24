package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.model.QrMatrix

/**
 * Defines the functional suppression and center-anchor emission policies for subpixel-based
 * QR resampling styles.
 *
 * This decouples the low-level 3x3 stochastic subpixel sampling engine from specific style rules,
 * keeping generic QR structural safety completely separate from artistic visual parity policies.
 */
interface ResamplePolicy {
    /**
     * Determines whether the subpixel at coordinate ([subX], [subY]) in 3N x 3N coordinate space
     * is eligible for stochastic image sampling.
     */
    fun shouldSample(matrix: QrMatrix, subX: Int, subY: Int): Boolean

    /**
     * Determines whether a dark module at ([col], [row]) should emit a solid center subpixel anchor.
     */
    fun shouldDrawAnchor(matrix: QrMatrix, col: Int, row: Int): Boolean
}

/**
 * Artistic resample policy engineered for full visual parity with artistic QR image stylization.
 *
 * Suppression rules:
 * - Finder areas: 8x8 module area (24x24 subpixel units) at each position corner.
 * - Timing patterns: Row 6 and Column 6 between finders (modules 8 until size - 8).
 * - Alignment patterns: 5x5 module area around alignment centers (Version >= 2).
 * - Format & Version information: Explicitly NOT suppressed. Traversed just like data modules,
 *   emitting center subpixel anchors for dark bits while allowing stochastic photo dithering around them.
 */
object ArtisticResamplePolicy : ResamplePolicy {

    override fun shouldSample(matrix: QrMatrix, subX: Int, subY: Int): Boolean {
        val col = subX / 3
        val row = subY / 3
        return !ArtisticResampleFunctionalMask.isExcluded(col, row, matrix.size, matrix.version)
    }

    override fun shouldDrawAnchor(matrix: QrMatrix, col: Int, row: Int): Boolean {
        if (!matrix.isDark(col, row)) return false
        return !ArtisticResampleFunctionalMask.isExcluded(col, row, matrix.size, matrix.version)
    }
}
