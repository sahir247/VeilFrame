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

    /**
     * Generates a random float in [0.0f, 1.0f) for the given 3N subpixel coordinate ([subX], [subY]).
     * By default dispatches based on [rngMode].
     * Oracle testing suites can override this to inject deterministic oracle random sequences.
     */
    fun sampleRandom(subX: Int, subY: Int, seed: Long): Float = when (rngMode) {
        RngMode.DETERMINISTIC -> ResampleSubpixelEngine.subpixelRandom(seed, subX, subY)
        RngMode.SYSTEM_UNSEEDED -> kotlin.random.Random.nextFloat()
    }
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
        return !ArtisticResampleFunctionalMask.isSubpixelExcluded(
            matrix = matrix,
            subX = subX,
            subY = subY,
            timingShape = timingStyle.shape,
            alignmentShape = alignmentStyle.shape
        )
    }

    override fun shouldDrawAnchor(matrix: QrMatrix, col: Int, row: Int): Boolean {
        if (!matrix.isDark(col, row)) return false
        // Finders never emit data center anchors (they are drawn as full finder eyes)
        if (ArtisticResampleFunctionalMask.isFinderArea(col, row, matrix.size)) return false

        // Timing tracks: drawn exclusively in the QR-structure pass as #Stb / #Btb (prevent duplicate anchors)
        if (ArtisticResampleFunctionalMask.isTimingArea(col, row, matrix.size)) return false

        // Alignment patterns: drawn exclusively in the QR-structure pass as #Sab / #Bab (prevent duplicate anchors)
        if (matrix.version >= 2 && ArtisticResampleFunctionalMask.isAlignmentArea(col, row, matrix.version)) return false

        // Data, Format, Version all emit center anchors!
        return true
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
