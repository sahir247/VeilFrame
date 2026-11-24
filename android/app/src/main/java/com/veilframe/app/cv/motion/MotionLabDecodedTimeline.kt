package com.veilframe.app.cv.motion

import java.io.IOException
import kotlin.math.abs

/** Verify presentation-order frames from FFmpeg's showinfo filter against source samples. */
internal object MotionLabDecodedTimeline {
    private val frame = Regex("""\bn:\s*(\d+)\s+pts:\s*(-?\d+)\s+pts_time:\s*([-+0-9.eE]+)""")
    private const val MAX_LOG_CHARS = 4 * 1024 * 1024
    private const val TOLERANCE_US = 1_000.0

    fun verify(log: String, presentationUs: List<Long>) {
        if (log.length > MAX_LOG_CHARS) throw IOException("Decoder timestamp evidence exceeds safety budget")
        val expected = presentationUs.sorted()
        val firstExpected = expected.firstOrNull() ?: throw IOException("No source timestamps")
        var firstObserved: Double? = null
        var count = 0
        for (match in frame.findAll(log)) {
            if (count >= expected.size || match.groupValues[1].toIntOrNull() != count)
                throw IOException("Decoder presentation sequence changed")
            val timestamp = match.groupValues[3].toDoubleOrNull()?.times(1_000_000.0)
                ?: throw IOException("Invalid decoded presentation time")
            if (!timestamp.isFinite()) throw IOException("Non-finite decoded presentation time")
            val origin = firstObserved ?: timestamp.also { firstObserved = it }
            if (abs((timestamp-origin)-(expected[count]-firstExpected)) > TOLERANCE_US)
                throw IOException("Decoded frame presentation time disagrees with admitted source at frame ${count+1}")
            count++
        }
        if (count != expected.size) throw IOException("Missing decoder timestamp evidence ($count of ${expected.size})")
    }
}
