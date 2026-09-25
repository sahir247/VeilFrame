package com.veilframe.app.qr.renderer

import com.veilframe.app.qr.model.AlignmentStyle
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.TimingStyle

/**
 * Defines the functional suppression and center-anchor emission policies for subpixel-based
 * QR resampling styles.
 *
 * This decouples the low-level 3x3 stochastic subpixel sampling engine from specific style rules,
 * keeping generic QR structural safety completely separate from artistic visual parity policies.
 */
/**
 * Random number generator modes for stochastic subpixel resampling.
 */
enum class RngMode {
    /**
     * Deterministic 64-bit SplitMix hash based on module coordinates and seed.
     * Guarantees frame stability for videos/GIFs and identical reproducible exports.
     */
    DETERMINISTIC,

    /**
     * Dynamic unseeded PRNG matching dynamic unseeded runtime behavior.
     */
    SYSTEM_UNSEEDED;

    companion object {
        @JvmField
        val UNSEEDED_STOCHASTIC = SYSTEM_UNSEEDED
    }
}

typealias ResampleRngMode = RngMode

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

    /**
     * The random number generator strategy to use for stochastic dot emission.
     */
    val rngMode: RngMode get() = RngMode.DETERMINISTIC
}

/**
 * Artistic resample policy engineered for full visual parity with artistic QR image stylization.
 *
 * Suppression rules:
 * - Finder areas: 8x8 module area (24x24 subpixel units) at each position corner.
 * - Timing patterns: Row 6 and Column 6 between finders (modules 8 until size - 8).
 *   When [timingStyle.shape] is [ModuleShape.NONE], light timing modules are sampled.
 * - Alignment patterns: 5x5 module area around alignment centers (Version >= 2).
 *   When [alignmentStyle.shape] is [ModuleShape.NONE], light alignment modules are sampled.
 * - Format & Version information: Explicitly NOT suppressed. Traversed just like data modules,
 *   emitting center subpixel anchors for dark bits while allowing stochastic photo dithering around them.
 */
open class ArtisticResamplePolicy(
    val timingStyle: TimingStyle = TimingStyle(shape = ModuleShape.SQUARE),
    val alignmentStyle: AlignmentStyle = AlignmentStyle(shape = ModuleShape.SQUARE),
    override val rngMode: ResampleRngMode = ResampleRngMode.DETERMINISTIC
) : ResamplePolicy {

    override fun shouldSample(matrix: QrMatrix, subX: Int, subY: Int): Boolean {
        val col = subX / 3
        val row = subY / 3
        val isDark = matrix.isDark(col, row)
        return !ArtisticResampleFunctionalMask.isExcluded(
            col = col,
            row = row,
            size = matrix.size,
            version = matrix.version,
            timingShape = timingStyle.shape,
            alignmentShape = alignmentStyle.shape,
            isDark = isDark
        )
    }

    override fun shouldDrawAnchor(matrix: QrMatrix, col: Int, row: Int): Boolean {
        if (!matrix.isDark(col, row)) return false
        // Finders never emit data center anchors (they are drawn as full finder eyes)
        if (ArtisticResampleFunctionalMask.isFinderArea(col, row, matrix.size)) return false

        // Timing modules: when timing style is NONE or onlyWhite, dedicated timing rendering is skipped,
        // so dark timing modules emit center anchors through the subpixel engine.
        // Otherwise (default), dedicated timing renderer draws them, so anchor emission is suppressed.
        if (ArtisticResampleFunctionalMask.isTimingArea(col, row, matrix.size)) {
            return timingStyle.shape == ModuleShape.NONE || timingStyle.onlyWhite
        }

        // Alignment modules: emit center anchors only when dedicated alignment renderer is skipped.
        if (matrix.version >= 2 && ArtisticResampleFunctionalMask.isAlignmentArea(col, row, matrix.version)) {
            return alignmentStyle.shape == ModuleShape.NONE || alignmentStyle.onlyWhite
        }

        return !ArtisticResampleFunctionalMask.isExcluded(
            col = col,
            row = row,
            size = matrix.size,
            version = matrix.version,
            timingShape = timingStyle.shape,
            alignmentShape = alignmentStyle.shape,
            isDark = true
        )
    }

    companion object : ResamplePolicy {
        private val DEFAULT = ArtisticResamplePolicy()

        override fun shouldSample(matrix: QrMatrix, subX: Int, subY: Int): Boolean =
            DEFAULT.shouldSample(matrix, subX, subY)

        override fun shouldDrawAnchor(matrix: QrMatrix, col: Int, row: Int): Boolean =
            DEFAULT.shouldDrawAnchor(matrix, col, row)

        fun from(design: QrDesign): ArtisticResamplePolicy {
            return ArtisticResamplePolicy(
                timingStyle = design.timingStyle,
                alignmentStyle = design.alignmentStyle,
                rngMode = design.resampleStyle.rngMode
            )
        }
    }
}
