package com.veilframe.app.cv.color

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * ColorEngine — colour correction and grading primitives.
 *
 * All operations work on 8-bit BGR/BGRA/GRAY Mats and return new Mats. Final
 * rendering/compositing stays with VeilFrame's existing Skia pipeline — OpenCV
 * owns the deterministic pixel math, not the universal renderer.
 *
 * Processing happens in the right space:
 *   white balance / exposure / temperature-tint  → linear-ish RGB gains
 *   contrast / highlights / shadows              → Lab L channel
 *   saturation                                   → HSV S channel
 */
object ColorEngine {

    data class WhiteBalanceResult(
        val corrected: Mat,
        /** Per-channel gains that were applied (B, G, R). */
        val gains: DoubleArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Auto white balance: gray-world scaling in RGB with a green anchor.
     * `strength` blends between the original (0) and fully corrected (1).
     */
    fun autoWhiteBalance(source: Mat, strength: Double = 1.0): WhiteBalanceResult {
        require(strength in 0.0..1.0)
        val bgr = toBgr(source)
        try {
            val means = Core.mean(bgr).`val`
            val meanB = means[0]
            val meanG = means[1]
            val meanR = means[2]
            val anchor = (meanB + meanG + meanR) / 3.0
            var gainB = anchor / maxOf(meanB, 1.0)
            var gainG = anchor / maxOf(meanG, 1.0)
            var gainR = anchor / maxOf(meanR, 1.0)
            // Blend with identity per strength.
            gainB = 1.0 + (gainB - 1.0) * strength
            gainG = 1.0 + (gainG - 1.0) * strength
            gainR = 1.0 + (gainR - 1.0) * strength
            val planes = ArrayList<Mat>()
            Core.split(bgr, planes)
            planes[0].convertTo(planes[0], -1, gainB, 0.0)
            planes[1].convertTo(planes[1], -1, gainG, 0.0)
            planes[2].convertTo(planes[2], -1, gainR, 0.0)
            val merged = Mat()
            Core.merge(planes, merged)
            planes.forEach { it.release() }
            return WhiteBalanceResult(
                corrected = if (source.channels() == 1) toGray(merged) else merged,
                gains = doubleArrayOf(gainB, gainG, gainR),
            ).also { if (merged !== it.corrected) merged.release() }
        } finally {
            if (bgr !== source) bgr.release()
        }
    }

    /** Exposure: linear gain in stops (±2 typical). */
    fun exposure(source: Mat, stops: Double): Mat {
        val out = Mat()
        source.convertTo(out, -1, Math.pow(2.0, stops), 0.0)
        return out
    }

    /** Contrast around mid-grey. 1.0 = unchanged. */
    fun contrast(source: Mat, contrast: Double, pivot: Double = 128.0): Mat {
        val out = Mat()
        source.convertTo(out, -1, contrast, pivot * (1.0 - contrast))
        return out
    }

    /** Saturation multiplier (0 = grayscale, 1 = unchanged, >1 = boosted) via HSV S. */
    fun saturation(source: Mat, multiplier: Double): Mat {
        if (source.channels() == 1 || multiplier == 1.0) return source.clone()
        val bgr = toBgr(source)
        val hsv = Mat()
        val planes = ArrayList<Mat>()
        try {
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.split(hsv, planes)
            planes[1].convertTo(planes[1], planes[1].type(), multiplier, 0.0)
            val merged = Mat()
            Core.merge(planes, merged)
            val out = Mat()
            Imgproc.cvtColor(merged, out, Imgproc.COLOR_HSV2BGR)
            merged.release()
            return out
        } finally {
            if (bgr !== source) bgr.release()
            hsv.release()
            planes.forEach { it.release() }
        }
    }

    /**
     * Temperature (warm/cool) and tint (green/magenta) as RGB gains.
     * `temperature` > 0 warms (R up, B down); `tint` > 0 adds magenta (G down).
     */
    fun temperatureTint(source: Mat, temperature: Double, tint: Double): Mat {
        require(temperature in -1.0..1.0 && tint in -1.0..1.0)
        val bgr = toBgr(source)
        val planes = ArrayList<Mat>()
        try {
            Core.split(bgr, planes)
            planes[2].convertTo(planes[2], -1, 1.0 + 0.25 * temperature, 0.0) // R
            planes[0].convertTo(planes[0], -1, 1.0 - 0.25 * temperature, 0.0) // B
            planes[1].convertTo(planes[1], -1, 1.0 - 0.25 * tint, 0.0) // G
            val merged = Mat()
            Core.merge(planes, merged)
            return merged
        } finally {
            if (bgr !== source) bgr.release()
            planes.forEach { it.release() }
        }
    }

    /**
     * Highlights / shadows recovery on the Lab L channel.
     * Positive [highlights] darkens only bright regions; positive [shadows]
     * lifts only dark regions. Range -1..1.
     */
    fun highlightsShadows(source: Mat, highlights: Double = 0.0, shadows: Double = 0.0): Mat {
        require(highlights in -1.0..1.0 && shadows in -1.0..1.0)
        if (highlights == 0.0 && shadows == 0.0) return source.clone()
        val bgr = toBgr(source)
        val lab = Mat()
        val planes = ArrayList<Mat>()
        try {
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, planes)
            val l = planes[0]
            val lFloat = Mat()
            l.convertTo(lFloat, CvType.CV_32F)
            val rows = lFloat.rows()
            val cols = lFloat.cols()
            val buffer = FloatArray(cols)
            for (y in 0 until rows) {
                lFloat.get(y, 0, buffer)
                for (x in buffer.indices) {
                    val v = buffer[x] / 255.0f // L in 0..1 (8-bit Lab storage)
                    val highlightMask = smoothstep(0.5f, 1.0f, v)
                    val shadowMask = 1.0f - smoothstep(0.0f, 0.5f, v)
                    val delta = ((highlights * highlightMask + shadows * shadowMask) * 40.0).toFloat()
                    buffer[x] = (buffer[x] + delta).coerceIn(0f, 255f)
                }
                lFloat.put(y, 0, buffer)
            }
            lFloat.convertTo(l, l.type())
            lFloat.release()
            val merged = Mat()
            Core.merge(planes, merged)
            val out = Mat()
            Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2BGR)
            merged.release()
            return out
        } finally {
            if (bgr !== source) bgr.release()
            lab.release()
            planes.forEach { it.release() }
        }
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun toBgr(source: Mat): Mat = when (source.channels()) {
        3 -> source
        4 -> {
            val out = Mat()
            Imgproc.cvtColor(source, out, Imgproc.COLOR_BGRA2BGR)
            out
        }
        1 -> {
            val out = Mat()
            Imgproc.cvtColor(source, out, Imgproc.COLOR_GRAY2BGR)
            out
        }
        else -> throw UnsupportedOperationException("unsupported channel count ${source.channels()}")
    }

    private fun toGray(bgr: Mat): Mat {
        val out = Mat()
        Imgproc.cvtColor(bgr, out, Imgproc.COLOR_BGR2GRAY)
        return out
    }
}
