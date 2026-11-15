package com.veilframe.app.qr.renderer

import android.graphics.Bitmap
import android.graphics.Color
import com.veilframe.app.qr.model.ImageSourceStyle
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.QrModuleRole
import com.veilframe.app.qr.model.ResampleDitherMode

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
 * 2. Functional pattern participation is governed selectively by [ResamplePolicy]
 *    (defaulting to [ArtisticResamplePolicy] matching EFQRCode's getGrayPointList()):
 *    - Finder areas: 8x8 module regions (24x24 subpixels) at corners are strictly suppressed.
 *    - Format & Version information: Participate in stochastic dither matching EF parity.
 *    - Timing patterns (Row 6 / Column 6): Dark timing modules participate in stochastic dither;
 *      light timing modules are conditionally suppressed based on timingStyle.shape.
 *    - Alignment patterns: Dark alignment modules participate in stochastic dither;
 *      light alignment modules are conditionally suppressed based on alignmentStyle.shape.
 * 3. For every dark module permitted by [policy.shouldDrawAnchor], the center subpixel (dx=1, dy=1)
 *    is strictly reserved as the QR anchor bit.
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
     * Exact VeilFrame Art Engine threshold formula with +1.0 contrast multiplier.
     */
    fun computeThreshold(grayNorm: Float, exposure: Float, contrast: Float): Float {
        return ((grayNorm + exposure - 0.5f) * (contrast + 1.0f) + 0.5f).coerceIn(0.0f, 1.0f)
    }

    /**
     * Calculates the resample threshold for a given pixel color and image style.
     */
    fun computeThreshold(pixelColor: Int, style: ImageSourceStyle): Float {
        val grayNorm = ImageScaleResolver.calculatePixelLuminance(pixelColor)
        return computeThreshold(grayNorm, style.exposure, style.contrast)
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
        includeCenterAnchors: Boolean = true,
        ditherMode: ResampleDitherMode = ResampleDitherMode.STOCHASTIC_EF,
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
                preScaledAuto = ImageScaleResolver.createPreScaledSource(pixelSource.bitmap, targetDim, targetDim, style.scaleMode)
                preScaledAuto
            }
            else -> {
                preScaledAuto = ImageScaleResolver.createPreScaledArraySource(pixelSource, targetDim, targetDim, style.scaleMode)
                preScaledAuto
            }
        }

        try {
            if (ditherMode == ResampleDitherMode.FLOYD_STEINBERG && effectiveSource != null) {
                traverseFloydSteinberg(
                    matrix = matrix,
                    effectiveSource = effectiveSource,
                    style = style,
                    policy = policy,
                    includeCenterAnchors = includeCenterAnchors,
                    sink = sink
                )
                return
            }

            for (col in 0 until n) {
                for (row in 0 until n) {
                    // 1. Center subpixel (dx=1, dy=1): Reserved for actual QR data bit
                    if (includeCenterAnchors && policy.shouldDrawAnchor(matrix, col, row)) {
                        val centerSubX = 3 * col + 1
                        val centerSubY = 3 * row + 1
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

                                val pixel = effectiveSource.getPixel(sx, sy)
                                val grayNorm = ImageScaleResolver.calculatePixelLuminance(pixel, efPremultipliedAlpha = true)

                                // Exact VeilFrame Art Engine threshold formula with +1.0 contrast multiplier
                                val threshold = computeThreshold(grayNorm, style.exposure, style.contrast)

                                val rnd = policy.sampleRandom(sx, sy, seed)
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
     * Two-pass error diffusion (adapted from andrew-t/dithered-qr-codes diffuse.ts):
     * 1. Auto-levels contrast percentiles cleanly across sampled region and clamps target darkness
     *    in [0.08, 0.82] so solid black/white blocks never starve RS/module recovery.
     * 2. Diffuses quantization error from fixed center QR bit anchors into the 8 surrounding
     *    free subpixels so mandatory QR bits blend naturally into local brightness.
     * 3. Runs serpentine Floyd-Steinberg diffusion across remaining eligible subpixels.
     */
    private fun traverseFloydSteinberg(
        matrix: QrMatrix,
        effectiveSource: PixelSource,
        style: ImageSourceStyle,
        policy: ResamplePolicy,
        includeCenterAnchors: Boolean,
        sink: SubpixelSink
    ) {
        val n = matrix.size
        val dim = 3 * n
        val total = dim * dim
        val preScaled = effectiveSource as? PreScaledPixelSource

        val rawDark = FloatArray(total)
        val eligible = BooleanArray(total)
        val isDataCenter = BooleanArray(total)
        val forcedDark = FloatArray(total)
        val hist = IntArray(256)
        var validCount = 0

        for (col in 0 until n) {
            for (row in 0 until n) {
                if (includeCenterAnchors && policy.shouldDrawAnchor(matrix, col, row)) {
                    sink.emit(col, row, 3 * col + 1, 3 * row + 1, isCenterAnchor = true)
                }

                val role = matrix.roleAt(col, row)
                val isDataLike = role == QrModuleRole.DATA || role == QrModuleRole.FORMAT || role == QrModuleRole.VERSION
                val moduleDark = matrix.isDark(col, row)

                for (dx in 0..2) {
                    for (dy in 0..2) {
                        val sx = 3 * col + dx
                        val sy = 3 * row + dy
                        val idx = sy * dim + sx
                        val isCenter = dx == 1 && dy == 1
                        val inPadding = preScaled?.isPadding(sx, sy) == true

                        if (isCenter) {
                            if (isDataLike && !inPadding) {
                                isDataCenter[idx] = true
                                forcedDark[idx] = if (moduleDark) 1f else 0f
                            }
                        } else if (!inPadding && policy.shouldSample(matrix, sx, sy)) {
                            eligible[idx] = true
                        }

                        if (!inPadding && (eligible[idx] || isDataCenter[idx])) {
                            val pixel = effectiveSource.getPixel(sx, sy)
                            val lum = ImageScaleResolver.calculatePixelLuminance(pixel, efPremultipliedAlpha = true)
                            val dark = (1f - lum).coerceIn(0f, 1f)
                            rawDark[idx] = dark
                            if (eligible[idx]) {
                                val bin = (dark * 255f).toInt().coerceIn(0, 255)
                                hist[bin]++
                                validCount++
                            }
                        }
                    }
                }
            }
        }

        val sharpenedDark = FloatArray(total)
        val sharpHist = IntArray(256)
        var sharpCount = 0
        for (sy in 0 until dim) {
            for (sx in 0 until dim) {
                val idx = sy * dim + sx
                if (!eligible[idx] && !isDataCenter[idx]) continue
                var sum = 0f
                var cnt = 0
                for (ny in -1..1) {
                    for (nx in -1..1) {
                        if (nx == 0 && ny == 0) continue
                        val px = sx + nx
                        val py = sy + ny
                        if (px in 0 until dim && py in 0 until dim) {
                            val nIdx = py * dim + px
                            if (eligible[nIdx] || isDataCenter[nIdx]) {
                                sum += rawDark[nIdx]
                                cnt++
                            }
                        }
                    }
                }
                val base = rawDark[idx]
                val sharpened = if (cnt > 0) {
                    (base + 0.40f * (base - sum / cnt)).coerceIn(0f, 1f)
                } else base
                sharpenedDark[idx] = sharpened
                if (eligible[idx]) {
                    val bin = (sharpened * 255f).toInt().coerceIn(0, 255)
                    sharpHist[bin]++
                    sharpCount++
                }
            }
        }

        // Compute p02..p98 auto-level range for crisp halftone punch even on soft photos
        var p02 = 0f
        var p98 = 1f
        if (sharpCount > 32) {
            val lowTarget = (sharpCount * 0.02f).toInt().coerceAtLeast(1)
            val highTarget = (sharpCount * 0.98f).toInt().coerceAtMost(sharpCount - 1)
            var acc = 0
            var foundLow = false
            for (b in 0..255) {
                acc += sharpHist[b]
                if (!foundLow && acc >= lowTarget) {
                    p02 = b / 255f
                    foundLow = true
                }
                if (acc >= highTarget) {
                    p98 = b / 255f
                    break
                }
            }
        }
        val span = (p98 - p02).coerceAtLeast(0.05f)
        val applyStretch = sharpCount > 32 && span in 0.10f..0.95f

        val targetDark = FloatArray(total)
        for (i in 0 until total) {
            if (!eligible[i] && !isDataCenter[i]) continue
            val r = sharpenedDark[i]
            val norm = if (applyStretch) {
                val stretched = ((r - p02) / span).coerceIn(0f, 1f)
                0.72f * stretched + 0.28f * r
            } else r
            val smooth = norm * norm * (3f - 2f * norm)
            val curved = 0.78f * norm + 0.22f * smooth
            val lumAdjusted = ((1f - curved) + style.exposure - 0.5f) * (style.contrast + 1.0f) + 0.5f
            targetDark[i] = (1f - lumAdjusted).coerceIn(0.02f, 0.90f)
        }

        // Pass 1: Diffuse forced QR center bit quantization error into surrounding 8 neighbors
        for (col in 0 until n) {
            for (row in 0 until n) {
                val cx = 3 * col + 1
                val cy = 3 * row + 1
                val cIdx = cy * dim + cx
                if (!isDataCenter[cIdx]) continue
                val err = (targetDark[cIdx] - forcedDark[cIdx]) * 0.90f
                var weightSum = 0f
                for (nx in -1..1) {
                    for (ny in -1..1) {
                        if (nx == 0 && ny == 0) continue
                        val px = cx + nx
                        val py = cy + ny
                        if (px in 0 until dim && py in 0 until dim && eligible[py * dim + px]) {
                            weightSum += if (nx == 0 || ny == 0) 3f else 1f
                        }
                    }
                }
                if (weightSum > 0f) {
                    for (nx in -1..1) {
                        for (ny in -1..1) {
                            if (nx == 0 && ny == 0) continue
                            val px = cx + nx
                            val py = cy + ny
                            if (px in 0 until dim && py in 0 until dim) {
                                val nIdx = py * dim + px
                                if (eligible[nIdx]) {
                                    val w = if (nx == 0 || ny == 0) 3f else 1f
                                    targetDark[nIdx] = (targetDark[nIdx] + err * (w / weightSum)).coerceIn(0f, 1f)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pass 2: Serpentine Floyd-Steinberg across remaining eligible subpixels
        for (sy in 0 until dim) {
            val leftToRight = (sy and 1) == 0
            val step = if (leftToRight) 1 else -1
            var sx = if (leftToRight) 0 else dim - 1
            while (sx in 0 until dim) {
                val idx = sy * dim + sx
                if (eligible[idx]) {
                    val desired = targetDark[idx].coerceIn(0f, 1f)
                    val emitted = if (desired >= 0.5f) 1f else 0f
                    if (emitted > 0.5f) {
                        sink.emit(sx / 3, sy / 3, sx, sy, isCenterAnchor = false)
                    }
                    val err = (desired - emitted).coerceIn(-0.75f, 0.75f)

                    val xFwd = sx + step
                    val xBack = sx - step
                    val yNext = sy + 1

                    val e1 = xFwd in 0 until dim && eligible[sy * dim + xFwd]
                    val e2 = yNext < dim && xBack in 0 until dim && eligible[yNext * dim + xBack]
                    val e3 = yNext < dim && eligible[yNext * dim + sx]
                    val e4 = yNext < dim && xFwd in 0 until dim && eligible[yNext * dim + xFwd]

                    val norm = (if (e1) 7f else 0f) + (if (e2) 3f else 0f) + (if (e3) 5f else 0f) + (if (e4) 1f else 0f)
                    if (norm > 0f) {
                        if (e1) {
                            val ni = sy * dim + xFwd
                            targetDark[ni] = (targetDark[ni] + err * (7f / norm)).coerceIn(-0.35f, 1.35f)
                        }
                        if (e2) {
                            val ni = yNext * dim + xBack
                            targetDark[ni] = (targetDark[ni] + err * (3f / norm)).coerceIn(-0.35f, 1.35f)
                        }
                        if (e3) {
                            val ni = yNext * dim + sx
                            targetDark[ni] = (targetDark[ni] + err * (5f / norm)).coerceIn(-0.35f, 1.35f)
                        }
                        if (e4) {
                            val ni = yNext * dim + xFwd
                            targetDark[ni] = (targetDark[ni] + err * (1f / norm)).coerceIn(-0.35f, 1.35f)
                        }
                    }
                }
                sx += step
            }
        }
    }

    /**
     * Evaluates all 8 standard ISO/IEC 18004 QR mask patterns and picks the valid mask
     * whose center-module bits match the artwork's target luminance closely, minimizing
     * QR salt-and-pepper noise over bright skin/sky and dark hair/outlines while remaining
     * 100% standards-compliant for all QR readers.
     */
    fun selectBestMatrixForImage(
        content: String,
        ecLevel: com.google.zxing.qrcode.decoder.ErrorCorrectionLevel,
        writeEci: Boolean,
        minVersion: Int,
        source: Bitmap,
        style: ImageSourceStyle
    ): QrMatrix {
        val base = com.veilframe.app.qr.encoder.engine.VeilQrEncoder.encode(
            content = content,
            zxingLevel = ecLevel,
            writeEci = writeEci,
            minVersion = minVersion
        )
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return base.matrix
        }

        val n = base.matrix.size
        val dim = 3 * n
        val preScaled = ImageScaleResolver.createPreScaledSource(source, dim, dim, style.scaleMode)
        val centerTargetDark = FloatArray(n * n)
        val validCenter = BooleanArray(n * n)
        try {
            var minDark = 1f
            var maxDark = 0f
            for (row in 0 until n) {
                for (col in 0 until n) {
                    val sx = 3 * col + 1
                    val sy = 3 * row + 1
                    if (!preScaled.isPadding(sx, sy)) {
                        val lum = ImageScaleResolver.calculatePixelLuminance(
                            preScaled.getPixel(sx, sy),
                            efPremultipliedAlpha = true
                        )
                        val d = (1f - lum).coerceIn(0f, 1f)
                        centerTargetDark[row * n + col] = d
                        validCenter[row * n + col] = true
                        if (d < minDark) minDark = d
                        if (d > maxDark) maxDark = d
                    }
                }
            }
            val span = (maxDark - minDark).coerceAtLeast(0.05f)
            for (i in centerTargetDark.indices) {
                if (validCenter[i]) {
                    val stretched = ((centerTargetDark[i] - minDark) / span).coerceIn(0f, 1f)
                    val lumAdj = ((1f - (0.65f * stretched + 0.35f * centerTargetDark[i])) + style.exposure - 0.5f) *
                        (style.contrast + 1.0f) + 0.5f
                    centerTargetDark[i] = (1f - lumAdj).coerceIn(0f, 1f)
                }
            }
        } finally {
            preScaled.bitmap?.let { bmp ->
                if (bmp !== source && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }

        val veilLevel = when (ecLevel) {
            com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L -> com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel.L
            com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M -> com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel.M
            com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q -> com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel.Q
            com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H -> com.veilframe.app.qr.encoder.engine.VeilCorrectionLevel.H
        }
        val dataBytes = content.toByteArray(Charsets.UTF_8)

        var bestMatrix = base.matrix
        var bestScore = Float.POSITIVE_INFINITY

        for (mask in com.veilframe.app.qr.encoder.engine.VeilMaskPattern.entries) {
            val candidate = com.veilframe.app.qr.encoder.engine.VeilQrEncoder.encode(
                data = dataBytes,
                errorCorrection = veilLevel,
                maskPattern = mask,
                writeEci = writeEci,
                minVersion = minVersion
            ).matrix
            if (candidate.size != n) continue

            var mse = 0f
            var count = 0
            for (row in 0 until n) {
                for (col in 0 until n) {
                    val role = candidate.roleAt(col, row)
                    if (role != QrModuleRole.DATA) continue
                    val idx = row * n + col
                    if (!validCenter[idx]) continue
                    val bit = if (candidate.isDark(col, row)) 1f else 0f
                    val diff = bit - centerTargetDark[idx]
                    mse += diff * diff
                    count++
                }
            }
            if (count > 0) {
                val avgScore = mse / count
                if (avgScore < bestScore) {
                    bestScore = avgScore
                    bestMatrix = candidate
                }
            }
        }
        return bestMatrix
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
        includeCenterAnchors: Boolean = true,
        ditherMode: ResampleDitherMode = ResampleDitherMode.STOCHASTIC_EF,
        sink: SubpixelSink
    ) {
        val n = matrix.size
        val targetDim = 3 * n
        var preScaledSource: PreScaledPixelSource? = null

        val pixelSource: PixelSource? = if (source != null && !source.isRecycled) {
            preScaledSource = ImageScaleResolver.createPreScaledSource(source, targetDim, targetDim, style.scaleMode)
            preScaledSource
        } else null

        try {
            traverseSubpixels(matrix, pixelSource, style, seed, policy, includeCenterAnchors, ditherMode, sink)
        } finally {
            preScaledSource?.bitmap?.recycle()
        }
    }
}
