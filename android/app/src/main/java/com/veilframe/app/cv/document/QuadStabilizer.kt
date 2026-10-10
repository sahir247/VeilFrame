package com.veilframe.app.cv.document

import kotlin.math.sqrt

/**
 * QuadStabilizer — Phase-3 temporal stabilization for live document detection.
 *
 * Root problem (production-readiness audit): per-frame QuadDetector output
 * jitters — frame 1 quad A, frame 2 quad B, frame 3 nothing, frame 4 quad C —
 * so the overlay flickers and "detected" never means "hold still and shoot".
 *
 * Model:
 *   raw quad ──matches previous?──► EMA-smooth corners (alpha), hits++
 *                 │ no / null
 *                 ▼
 *             restart tracking (hits = 1) or SEARCHING (null)
 *
 *   SEARCHING → no plausible quad this frame
 *   TRACKING  → quad acquired, still converging (hits < stableHits)
 *   STABLE    → smoothed quad held for [stableHits] consecutive matched frames
 *
 * Corners are ordered TL, TR, BR, BL by QuadDetector, so corner-wise matching
 * needs no re-assignment. Tolerance is expressed in ANALYSIS-frame pixels
 * (frames are decimated to ≤1024px before detection).
 *
 * Pure Kotlin/OpenCV-point math — unit-testable on the JVM, no Android deps
 * beyond org.opencv.core.Point.
 */
class QuadStabilizer(
    /** EMA weight of the newest frame (higher = snappier, lower = smoother). */
    private val alpha: Float = 0.4f,
    /** Mean corner distance (analysis px) under which a quad continues the track. */
    private val matchTolerancePx: Double = 48.0,
    /** Consecutive matched frames before STABLE. */
    private val stableHits: Int = 4,
    /** Maximum consecutive frames without detection before resetting track. */
    private val maxConsecutiveMisses: Int = 2,
    /** Maximum duration in ms a stale track can be held before expiring. */
    private val maxAgeMs: Long = 400L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {

    enum class State { SEARCHING, TRACKING, STABLE }

    var state: State = State.SEARCHING
        private set

    /** True if the returned quad was carried over from a previous frame rather than observed freshly. */
    var isPredicted: Boolean = false
        private set

    private var smoothed: Array<DoubleArray>? = null
    private var hits = 0
    private var consecutiveMisses = 0
    private var lastDetectionTimestampMs = 0L

    /**
     * Feeds one frame's detection result.
     * @param raw 4 ordered corners, or null/other-size when nothing was found.
     * @return the smoothed quad (4 points) while tracking/stable, else null.
     */
    fun update(raw: List<org.opencv.core.Point>?): List<org.opencv.core.Point>? {
        val now = timeProvider()
        if (raw == null || raw.size != 4) {
            consecutiveMisses++
            val ageMs = if (lastDetectionTimestampMs > 0L) now - lastDetectionTimestampMs else Long.MAX_VALUE
            // One or two missed frames can be held to prevent overlay flicker;
            // consecutive misses > maxConsecutiveMisses or elapsed time > maxAgeMs immediately reset.
            if (smoothed != null && hits > 0 && consecutiveMisses <= maxConsecutiveMisses && ageMs <= maxAgeMs) {
                hits--
                state = State.TRACKING
                isPredicted = true
                return smoothed?.let { toPoints(it) }
            }
            reset()
            return null
        }

        consecutiveMisses = 0
        lastDetectionTimestampMs = now
        isPredicted = false

        val prev = smoothed
        if (prev == null || !matches(prev, raw)) {
            smoothed = arrayOf(
                doubleArrayOf(raw[0].x, raw[0].y),
                doubleArrayOf(raw[1].x, raw[1].y),
                doubleArrayOf(raw[2].x, raw[2].y),
                doubleArrayOf(raw[3].x, raw[3].y),
            )
            hits = 1
            state = State.TRACKING
        } else {
            for (i in 0 until 4) {
                prev[i][0] = alpha * raw[i].x + (1.0 - alpha) * prev[i][0]
                prev[i][1] = alpha * raw[i].y + (1.0 - alpha) * prev[i][1]
            }
            hits++
            state = if (hits >= stableHits) State.STABLE else State.TRACKING
        }
        return smoothed?.let { toPoints(it) }
    }

    fun reset() {
        smoothed = null
        hits = 0
        consecutiveMisses = 0
        lastDetectionTimestampMs = 0L
        isPredicted = false
        state = State.SEARCHING
    }

    private fun matches(prev: Array<DoubleArray>, raw: List<org.opencv.core.Point>): Boolean {
        var total = 0.0
        for (i in 0 until 4) {
            val dx = prev[i][0] - raw[i].x
            val dy = prev[i][1] - raw[i].y
            total += sqrt(dx * dx + dy * dy)
        }
        return (total / 4.0) <= matchTolerancePx
    }

    private fun toPoints(pts: Array<DoubleArray>): List<org.opencv.core.Point> =
        pts.map { org.opencv.core.Point(it[0], it[1]) }
}
