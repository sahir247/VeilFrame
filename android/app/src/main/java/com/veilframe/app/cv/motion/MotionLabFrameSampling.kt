package com.veilframe.app.cv.motion

/**
 * Power-of-two decoder sampling for RIFE. BitmapFactory may round power-of-two
 * sampling internally, so admission is based on the maximum decoded edge.
 * Prevent 8K/12K inputs from silently becoming 4K/6K bitmaps on the GPU path.
 */
object MotionLabFrameSampling {
    const val MAX_EDGE = 1920
    private const val MAX_SAMPLE = 64

    fun sampleSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "Invalid video frame dimensions" }
        var sample = 1
        val larger = maxOf(width, height).toLong()
        while ((larger + sample - 1L) / sample > MAX_EDGE) {
            check(sample < MAX_SAMPLE) {
                "Source video exceeds safe RIFE decoder sampling capacity"
            }
            sample *= 2
        }
        return sample
    }
}
