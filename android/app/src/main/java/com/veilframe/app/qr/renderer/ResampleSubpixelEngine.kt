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
     * Protected modules (where [QrMatrix.isProtected] returns true) are skipped to guarantee
     * structural integrity. For dark data modules, the center anchor is always emitted.
     */
    fun traverseSubpixels(
        matrix: QrMatrix,
        pixelSource: PixelSource?,
        style: ImageSourceStyle,
        seed: Long = 42L,
        sink: SubpixelSink
    ) {
        val n = matrix.size

        for (col in 0 until n) {
            for (row in 0 until n) {
                // Structural integrity guarantee: All protected functional patterns are preserved
                if (matrix.isProtected(col, row)) continue

                val isDark = matrix.isDark(col, row)

                // 1. Center subpixel (dx=1, dy=1): Reserved for actual QR data bit
                val centerSubX = 3 * col + 1
                val centerSubY = 3 * row + 1
                if (isDark) {
                    sink.emit(col, row, centerSubX, centerSubY, isCenterAnchor = true)
                }

                // 2. Surrounding 8 subpixels: Stochastic photo dithering
                if (pixelSource != null) {
                    for (dx in 0..2) {
                        for (dy in 0..2) {
                            if (dx == 1 && dy == 1) continue // Skip center anchor

                            val sx = 3 * col + dx
                            val sy = 3 * row + dy

                            val u = (sx + 0.5f) / (3 * n).toFloat()
                            val v = (sy + 0.5f) / (3 * n).toFloat()

                            val sample = ImageScaleResolver.sample(pixelSource, u, v, style.scaleMode)
                            // Explicit padding suppression invariant:
                            // Non-covered margin regions in ASPECT_FIT never emit stochastic photo dots,
                            // regardless of extreme exposure or contrast adjustments.
                            if (sample.isPadding) continue

                            val pixel = sample.color
                            val a = (pixel ushr 24 and 0xFF) / 255.0f
                            val r = (pixel ushr 16 and 0xFF)
                            val g = (pixel ushr 8 and 0xFF)
                            val b = (pixel and 0xFF)

                            val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
                            val weightedGray = gray * a + (1.0f - a) * 255.0f
                            val grayNorm = weightedGray / 255.0f

                            // Exact VeilFrame Art Engine threshold formula with +1.0 contrast multiplier
                            val threshold = ((grayNorm + style.exposure - 0.5f) * (style.contrast + 1.0f) + 0.5f).coerceIn(0.0f, 1.0f)

                            val rnd = subpixelRandom(seed, sx, sy)
                            if (rnd > threshold) {
                                sink.emit(col, row, sx, sy, isCenterAnchor = false)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Traverses the QR matrix and emits all active subpixels into [sink] from a [Bitmap].
     */
    fun traverseSubpixels(
        matrix: QrMatrix,
        source: Bitmap?,
        style: ImageSourceStyle,
        seed: Long = 42L,
        sink: SubpixelSink
    ) {
        val pixelSource = if (source != null && !source.isRecycled) {
            BitmapPixelSource(source)
        } else null
        traverseSubpixels(matrix, pixelSource, style, seed, sink)
    }
}
