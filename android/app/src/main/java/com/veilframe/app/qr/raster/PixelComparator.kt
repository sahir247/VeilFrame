package com.veilframe.app.qr.raster

import kotlin.math.abs
import kotlin.math.log10

/**
 * Result of a differential pixel comparison between two [RasterBuffer] instances.
 */
data class ComparisonResult(
    val exactMatch: Boolean,
    val mismatchCount: Int,
    val totalPixels: Int,
    val mismatchPercentage: Double,
    val maxChannelDelta: Int,
    val meanChannelDelta: Double,
    val psnr: Double,
    val deltaHistogram: IntArray, // counts of max channel deltas grouped: [0, 1-2, 3-5, 6-10, 11-20, >20]
    val mismatchBoundingBox: MismatchBounds?
)

data class MismatchBounds(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
)

/**
 * Precise differential comparison engine between raster buffers.
 * Implements the verification tiering specified in DISCOVERY.txt:
 * - Exact gate: mismatchCount == 0
 * - Diagnostic metrics: PSNR, channel deltas, spatial distribution
 */
object PixelComparator {

    fun compare(expected: RasterBuffer, actual: RasterBuffer): ComparisonResult {
        require(expected.width == actual.width && expected.height == actual.height) {
            "Dimension mismatch: expected ${expected.width}x${expected.height}, got ${actual.width}x${actual.height}"
        }

        val totalPixels = expected.width * expected.height
        var mismatchCount = 0
        var maxDelta = 0
        var totalDeltaSum = 0L
        var sumSquaredError = 0.0

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        val hist = IntArray(6) // [0, 1-2, 3-5, 6-10, 11-20, >20]

        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val p1 = expected.getPixel(x, y)
                val p2 = actual.getPixel(x, y)

                if (p1 != p2) {
                    mismatchCount++
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }

                val da = abs(((p1 ushr 24) and 0xFF) - ((p2 ushr 24) and 0xFF))
                val dr = abs(((p1 ushr 16) and 0xFF) - ((p2 ushr 16) and 0xFF))
                val dg = abs(((p1 ushr 8) and 0xFF) - ((p2 ushr 8) and 0xFF))
                val db = abs((p1 and 0xFF) - (p2 and 0xFF))

                val pixelMaxDelta = maxOf(da, dr, dg, db)
                maxDelta = maxOf(maxDelta, pixelMaxDelta)
                totalDeltaSum += (da + dr + dg + db)

                sumSquaredError += (da * da + dr * dr + dg * dg + db * db) / 4.0

                when {
                    pixelMaxDelta == 0 -> hist[0]++
                    pixelMaxDelta <= 2 -> hist[1]++
                    pixelMaxDelta <= 5 -> hist[2]++
                    pixelMaxDelta <= 10 -> hist[3]++
                    pixelMaxDelta <= 20 -> hist[4]++
                    else -> hist[5]++
                }
            }
        }

        val mse = sumSquaredError / totalPixels
        val psnr = if (mse == 0.0) Double.POSITIVE_INFINITY else 10.0 * log10((255.0 * 255.0) / mse)
        val meanDelta = totalDeltaSum.toDouble() / (totalPixels * 4.0)
        val mismatchPct = (mismatchCount.toDouble() / totalPixels) * 100.0

        val bounds = if (mismatchCount > 0) MismatchBounds(minX, minY, maxX, maxY) else null

        return ComparisonResult(
            exactMatch = mismatchCount == 0,
            mismatchCount = mismatchCount,
            totalPixels = totalPixels,
            mismatchPercentage = mismatchPct,
            maxChannelDelta = maxDelta,
            meanChannelDelta = meanDelta,
            psnr = psnr,
            deltaHistogram = hist,
            mismatchBoundingBox = bounds
        )
    }
}
