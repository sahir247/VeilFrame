package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Color
import com.veilframe.app.qr.model.ImageSourceStyle
import com.veilframe.app.qr.model.QrMatrix

/**
 * Streaming callback sink for 3x3 stochastic subpixel primitives.
 *
 * Avoids allocating large intermediate collections for large QR matrices (e.g. Version 40 = 281,961 subpixels).
 */
fun interface SubpixelSink {
    /**
     * Emits a single subpixel position to be rendered.
     *
     * @param col The QR matrix module column (0 until matrix.size).
     * @param row The QR matrix module row (0 until matrix.size).
     * @param subX The subpixel x coordinate in [0, 3 * matrix.size).
     * @param subY The subpixel y coordinate in [0, 3 * matrix.size).
     * @param isCenterAnchor True if this is the center subpixel (dx=1, dy=1) reserved for the actual QR data bit.
     */
    fun emit(
        col: Int,
        row: Int,
        subX: Int,
        subY: Int,
        isCenterAnchor: Boolean
    )
}

/**
 * Authoritative implementation of VeilFrame Art Engine-inspired 3x3 Stochastic Subpixel Resampling.
 *
 * Architecture & Design Contract:
 * 1. Source image is scaled to (3N) x (3N) subpixels via [ImageScaleResolver].
 * 2. Protected functional modules are strictly excluded via [QrMatrix.isProtected]:
 *    - Follows the complete [QrModuleRole.isProtected] specification: Finders (inner/outer),
 *      Separators, Timing tracks (Row 6 / Column 6), Alignment patterns (center & border),
 *      Format information, Version information, and Quiet Zones.
 *    - This deliberate architectural separation guarantees barcode structural integrity and
 *      flawless camera scanability, while confining artistic dither strictly to data modules.
 * 3. For every dark DATA module, the center subpixel (dx=1, dy=1) is strictly reserved as the QR anchor bit.
 * 4. The surrounding 8 subpixels carry stochastic halftone dithering:
 *    - Gamma luminance: Y = 0.2126*R + 0.7152*G + 0.0722*B
 *    - VeilFrame Art Engine threshold: ((grayNorm + exposure - 0.5) * (contrast + 1.0) + 0.5).coerceIn(0, 1)
 *    - Deterministic 64-bit splitmix hash PRNG: subpixelRandom(seed, subX, subY) > threshold
 *    - Explicit [ImageScaleMode.ASPECT_FIT] padding suppression: coordinates in letterbox/pillarbox
 *      margins are unconditionally suppressed, guaranteeing zero photo dither dots in margins
 *      regardless of extreme contrast or exposure adjustments.
 *
 * Zero memory allocation during streaming traversal via [SubpixelSink].
 */
object ResampleSubpixelEngine {

    /**
     * Deterministic, allocation-free 64-bit splitmix hash generating a uniform float in [0.0f, 1.0f).
     */
    fun subpixelRandom(seed: Long, subX: Int, subY: Int): Float {
        var h = seed xor (subX.toLong() * -7046029254386353131L) xor (subY.toLong() * -4132237089816223339L)
        h = (h xor (h ushr 30)) * -4658895280553007687L
        h = (h xor (h ushr 27)) * -7723596701193187605L
        h = h xor (h ushr 31)
        return ((h ushr 40) and 0xFFFFFFL).toFloat() / 16777216.0f
    }

    /**
     * Traverses the QR matrix and emits all active subpixels into [sink] using an abstract [PixelSource].
     *
     * Protected modules and functional regions are governed by [policy].
     * For dark data modules, the center anchor is emitted if permitted by [policy.shouldDrawAnchor].
     */
    fun traverseSubpixels(
        matrix: QrMatrix,
        pixelSource: PixelSource?,
        style: ImageSourceStyle,
        seed: Long = 42L,
        policy: ResamplePolicy = ArtisticResamplePolicy,
        sink: SubpixelSink
    ) {
        val n = matrix.size
        val targetDim = 3 * n
        var preScaledAuto: PreScaledPixelSource? = null

        val effectiveSource: PixelSource? = when {
            pixelSource == null -> null
            pixelSource is PreScaledPixelSource -> pixelSource
            pixelSource.width == targetDim && pixelSource.height == targetDim -> pixelSource
            pixelSource is BitmapPixelSource -> {
                try {
                    preScaledAuto = ImageScaleResolver.createPreScaledSource(pixelSource.bitmap, targetDim, targetDim, style.scaleMode)
                    preScaledAuto
                } catch (_: Throwable) {
                    preScaledAuto = ImageScaleResolver.createPreScaledArraySource(pixelSource, targetDim, targetDim, style.scaleMode)
                    preScaledAuto
                }
            }
            else -> {
                preScaledAuto = ImageScaleResolver.createPreScaledArraySource(pixelSource, targetDim, targetDim, style.scaleMode)
                preScaledAuto
            }
        }

        try {
            for (col in 0 until n) {
                for (row in 0 until n) {
                    // 1. Center subpixel (dx=1, dy=1): Reserved for actual QR data bit
                    val centerSubX = 3 * col + 1
                    val centerSubY = 3 * row + 1
                    if (policy.shouldDrawAnchor(matrix, col, row)) {
                        sink.emit(col, row, centerSubX, centerSubY, isCenterAnchor = true)
                    }

                    // 2. Surrounding 8 subpixels: Stochastic photo dithering
                    if (effectiveSource != null) {
                        for (dx in 0..2) {
                            for (dy in 0..2) {
                                if (dx == 1 && dy == 1) continue // Skip center anchor

                                val sx = 3 * col + dx
                                val sy = 3 * row + dy

                                if (!policy.shouldSample(matrix, sx, sy)) continue

                                if (effectiveSource is PreScaledPixelSource && effectiveSource.isPadding(sx, sy)) {
                                    continue
                                }

                                val pixel = effectiveSource.getPixel(sx, sy)
                                val grayNorm = ImageScaleResolver.calculatePixelLuminance(pixel)

                                // Exact VeilFrame Art Engine threshold formula with +1.0 contrast multiplier
                                val threshold = ((grayNorm + style.exposure - 0.5f) * (style.contrast + 1.0f) + 0.5f).coerceIn(0.0f, 1.0f)

                                val rnd = when (policy.rngMode) {
                                    RngMode.DETERMINISTIC -> subpixelRandom(seed, sx, sy)
                                    RngMode.SYSTEM_UNSEEDED -> kotlin.random.Random.nextFloat()
                                }
                                if (rnd > threshold) {
                                    sink.emit(col, row, sx, sy, isCenterAnchor = false)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            preScaledAuto?.bitmap?.recycle()
        }
    }

    /**
     * Traverses the QR matrix and emits all active subpixels into [sink] from a [Bitmap].
     * Pre-scales [source] to (3 * matrix.size) x (3 * matrix.size) with Skia bilinear filtering
     * matching pre-rendered 3N x 3N raster context sampling.
     */
    fun traverseSubpixels(
        matrix: QrMatrix,
        source: Bitmap?,
        style: ImageSourceStyle,
        seed: Long = 42L,
        policy: ResamplePolicy = ArtisticResamplePolicy,
        sink: SubpixelSink
    ) {
        val n = matrix.size
        val targetDim = 3 * n
        var preScaledSource: PreScaledPixelSource? = null

        val pixelSource: PixelSource? = if (source != null && !source.isRecycled) {
            try {
                preScaledSource = ImageScaleResolver.createPreScaledSource(source, targetDim, targetDim, style.scaleMode)
                preScaledSource
            } catch (_: Throwable) {
                // Fallback for headless environments without Android Bitmap graphics pipeline
                BitmapPixelSource(source)
            }
        } else null

        try {
            traverseSubpixels(matrix, pixelSource, style, seed, policy, sink)
        } finally {
            preScaledSource?.bitmap?.recycle()
        }
    }
}
