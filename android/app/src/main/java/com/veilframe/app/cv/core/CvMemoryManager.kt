package com.veilframe.app.cv.core

import java.io.File

/**
 * CvMemoryManager — resolution tiers, cost estimation and admission control.
 *
 * VeilFrame targets 6–16 GB TOTAL system RAM devices. Decisions must therefore
 * account for currently available memory, not just the process heap, and must
 * never assume the whole device RAM is ours.
 *
 * Pure policy logic: memory numbers arrive through [MemoryProbe], so this class
 * is fully testable with a fake probe.
 */
class CvMemoryManager(
    private val probe: MemoryProbe,
    private val pool: MatPool = MatPool(),
    private val safetyFactor: Double = 0.25,
) {
    val matPool: MatPool get() = pool

    /** Device policy tier derived from total + available RAM. */
    fun tier(): MemoryTier {
        val total = probe.totalMemoryBytes()
        val available = probe.availableMemoryBytes()
        return when {
            total in 1 until 7L * GB || available < 1L * GB -> MemoryTier.CONSERVATIVE
            total in 7L * GB until 11L * GB || available < 2L * GB -> MemoryTier.NORMAL
            else -> MemoryTier.HIGH
        }
    }

    /**
     * Working resolution for a source of [srcWidth] x [srcHeight] for [purpose].
     * Never upscales. Huge sources fall back to tiled processing guidance.
     */
    fun workingResolution(srcWidth: Int, srcHeight: Int, purpose: ResolutionPurpose): ResolutionTier {
        val maxEdge = when (purpose) {
            ResolutionPurpose.PREVIEW -> ResolutionTier.PREVIEW.maxEdge
            ResolutionPurpose.ANALYSIS -> if (tier() == MemoryTier.CONSERVATIVE) 1280 else 1600
            ResolutionPurpose.STANDARD -> when (tier()) {
                MemoryTier.CONSERVATIVE -> 1280
                MemoryTier.NORMAL -> ResolutionTier.NORMAL.maxEdge
                MemoryTier.HIGH -> ResolutionTier.HIGH.maxEdge
            }
            ResolutionPurpose.FULL -> srcWidth.coerceAtLeast(srcHeight)
        }
        val srcEdge = srcWidth.coerceAtLeast(srcHeight)
        return when {
            srcEdge <= maxEdge -> ResolutionTier.SOURCE
            maxEdge <= ResolutionTier.PREVIEW.maxEdge -> ResolutionTier.PREVIEW
            maxEdge <= 1280 -> ResolutionTier.WORKING_1280
            maxEdge <= ResolutionTier.NORMAL.maxEdge -> ResolutionTier.NORMAL
            maxEdge <= ResolutionTier.HIGH.maxEdge -> ResolutionTier.HIGH
            else -> ResolutionTier.TILED
        }
    }

    /** Scaled dimensions that keep aspect ratio within [ResolutionTier] bounds. */
    fun scaledSize(srcWidth: Int, srcHeight: Int, tier: ResolutionTier): Pair<Int, Int> {
        val maxEdge = tier.maxEdge
        val srcEdge = srcWidth.coerceAtLeast(srcHeight)
        if (srcEdge <= maxEdge) return srcWidth to srcHeight
        val scale = maxEdge.toDouble() / srcEdge
        return maxOf(1, Math.round(srcWidth * scale).toInt()) to
            maxOf(1, Math.round(srcHeight * scale).toInt())
    }

    /**
     * Estimated peak native bytes for a job with [intermediateBuffers] live
     * buffers of one Mat spec ([type] is an OpenCV type, e.g. CvType.CV_32FC2).
     *
     * Uses CvType.ELEM_SIZE — channel-count sizing underestimated CV_32F by 4x
     * and CV_64F by 8x, which is unacceptable for the 6–16 GB target.
     */
    fun estimateBytes(width: Int, height: Int, type: Int, intermediateBuffers: Int): Long {
        val perBuffer = width.toLong() * height.toLong() * org.opencv.core.CvType.ELEM_SIZE(type)
        return perBuffer * (intermediateBuffers + 1L) + EXIF_AND_META_OVERHEAD
    }

    /**
     * Estimated peak for heterogeneous buffers (the realistic case: a CV_32FC2
     * flow pair plus CV_8U frames). Each spec is (width, height, type).
     */
    fun estimateBytes(specs: List<Triple<Int, Int, Int>>): Long {
        val total = specs.fold(0L) { acc, (w, h, type) ->
            acc + w.toLong() * h.toLong() * org.opencv.core.CvType.ELEM_SIZE(type)
        }
        return total + EXIF_AND_META_OVERHEAD
    }

    /**
     * Admission control. A job is admitted when its estimated peak fits inside
     * the safety share of currently available memory (plus whatever the pool can
     * recycle). Rejected jobs should be retried at a lower resolution tier.
     */
    fun admit(costBytes: Long): AdmissionDecision {
        val available = probe.availableMemoryBytes()
        val budget = (available * safetyFactor).toLong() + pool.stats().retainedBytes
        return if (costBytes <= budget) {
            AdmissionDecision.Admitted(budgetBytes = budget)
        } else {
            AdmissionDecision.Rejected(
                budgetBytes = budget,
                costBytes = costBytes,
                suggestedTier = if (costBytes > budget * 4) ResolutionTier.TILED else ResolutionTier.NORMAL,
            )
        }
    }

    /** Peak is the high-water mark of outstanding pooled bytes — not just what the pool retains. */
    fun footprint(): MemoryFootprint {
        val stats = pool.stats()
        return MemoryFootprint(
            peakBytes = stats.peakLiveBytes,
            pooledBytes = stats.retainedBytes,
            allocations = stats.allocations,
        )
    }

    companion object {
        const val GB: Long = 1024L * 1024 * 1024
        private const val EXIF_AND_META_OVERHEAD = 4L * 1024 * 1024
    }
}

/** Where the pixels will be used — drives the resolution tier decision. */
enum class ResolutionPurpose {
    /** Live camera preview analysis. */
    PREVIEW,

    /** Detection/analysis that only needs enough resolution for stable features. */
    ANALYSIS,

    /** Default CV operations (quality metrics, motion, template search). */
    STANDARD,

    /** Pixel-level output work (warp, sharpen, export). */
    FULL,
}

/** Resolution tiers named in the VeilFrame CV plan. */
enum class ResolutionTier(val maxEdge: Int) {
    PREVIEW(720),
    WORKING_1280(1280),
    NORMAL(1920),
    HIGH(2560),
    SOURCE(Int.MAX_VALUE),
    TILED(Int.MAX_VALUE),
}

/** Conservative / normal / high-quality processing mode. */
enum class MemoryTier { CONSERVATIVE, NORMAL, HIGH }

/** Source of memory numbers. */
interface MemoryProbe {
    fun totalMemoryBytes(): Long
    fun availableMemoryBytes(): Long
}

/** Reads /proc/meminfo — works on Android and desktop Linux without any Context. */
class MemInfoMemoryProbe(private val memInfoPath: String = "/proc/meminfo") : MemoryProbe {
    override fun totalMemoryBytes(): Long = parse("MemTotal") ?: 0L
    override fun availableMemoryBytes(): Long =
        parse("MemAvailable") ?: (parse("MemFree") ?: 0L)

    private fun parse(key: String): Long? {
        val file = File(memInfoPath)
        if (!file.canRead()) return null
        for (line in file.readLines()) {
            if (line.startsWith(key)) {
                val parts = line.split(Regex("\\s+"))
                val kb = parts.getOrNull(1)?.toLongOrNull()
                if (kb != null) return kb * 1024
            }
        }
        return null
    }
}

/** Explicit numbers for tests and for callers that already know the budget. */
class ManualMemoryProbe(
    private val total: Long,
    private val available: Long,
) : MemoryProbe {
    override fun totalMemoryBytes(): Long = total
    override fun availableMemoryBytes(): Long = available
}

sealed class AdmissionDecision {
    data class Admitted(val budgetBytes: Long) : AdmissionDecision()

    data class Rejected(
        val budgetBytes: Long,
        val costBytes: Long,
        val suggestedTier: ResolutionTier,
    ) : AdmissionDecision()
}
