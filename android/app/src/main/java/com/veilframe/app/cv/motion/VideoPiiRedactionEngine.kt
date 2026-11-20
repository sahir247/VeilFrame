package com.veilframe.app.cv.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import com.veilframe.app.media.VideoThumbnailPolicy
import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiDetection
import com.veilframe.app.privacy.PiiMultiModalDetector
import com.veilframe.app.privacy.PiiNcnnEngine
import com.veilframe.app.privacy.PiiRedactionStyle
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.floor
import kotlin.math.ceil

/**
 * Timed bounding-box entry for FFmpeg-backed video PII redaction.
 */
data class TimedPiiRegion(
    val startSec: Double,
    val endSec: Double,
    val rect: RectF,
    val piiClass: PiiClass,
    val label: String
)

/**
 * Single timestamped detection observation before temporal track association.
 */
data class TimedPiiObservation(
    val timeSec: Double,
    val rect: RectF,
    val piiClass: PiiClass,
    val label: String,
    val score: Float
)

data class VideoPiiScanResult(
    val regions: List<TimedPiiRegion>,
    val frameWidth: Int,
    val frameHeight: Int,
    val detectedCounts: Map<PiiClass, Int>,
    val trackCount: Int = 0,
    val sampledFrames: Int = 0,
    val attemptedFrames: Int = 0,
    val failedFrameExtractions: Int = 0,
    val failedEngines: Map<String, String> = emptyMap(),
    val successfulEngines: Set<String> = emptySet(),
    val isScanDegraded: Boolean = false,
    val isSamplingCapped: Boolean = false,
    val actualSampleIntervalMs: Long = 0L,
    val isCancelled: Boolean = false
)

/**
 * Tracked temporal multi-modal PII scanner & FFmpeg filter builder.
 *
 * Leverages [PiiMultiModalDetector] ([PiiNcnnEngine] UltraFace + YOLOv8n, WeChatQR + ML Kit barcodes,
 * and ML Kit OCR + `PiiTextClassifier`) across bounded, uniformly spaced keyframes, associates moving
 * detections into motion-aware tracks, bridges short detector dropouts, and emits swept bounding-box
 * intervals to protect targets between observed keyframes. Sampling is not exhaustive: the selected sample
 * ceiling can leave long gaps, so capped scans are explicitly marked incomplete.
 */
object VideoPiiRedactionEngine {
    enum class ScanDensity(val targetStepMs: Long, val maxSamples: Int) {
        STANDARD(350L, 120),
        DENSE(100L, 600)
    }

    private const val MIN_SAMPLE_FRAMES = 6
    private const val MAX_OBSERVATIONS = 12_000
    private const val MAX_FALLBACK_PIXELS = 16_000_000L
    private const val MAX_ANALYSIS_EDGE_PX = 1280
    private const val BOX_MARGIN_RATIO = 0.12f
    private const val MIN_BOX_PAD_PX = 6f
    private const val DEFAULT_MAX_FILTERS = 240

    fun scanVideo(
        context: Context,
        retriever: MediaMetadataRetriever,
        piiEngine: PiiNcnnEngine?,
        trimStartMs: Long,
        trimEndMs: Long,
        fallbackWidth: Int,
        fallbackHeight: Int,
        confThreshold: Float = 0.36f,
        includeOcrText: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: ((sampled: Int, total: Int) -> Unit)? = null,
        density: ScanDensity = ScanDensity.STANDARD
    ): VideoPiiScanResult {
        require(trimStartMs >= 0 && trimEndMs > trimStartMs) { "Invalid video scan interval" }
        val spanMs = trimEndMs - trimStartMs
        require(trimEndMs <= Long.MAX_VALUE / 1000L) { "Video scan timestamp exceeds supported range" }
        val sampleCount = sampleCountForSpan(spanMs, density)
        // The end is exclusive: requesting the container duration can decode no frame.
        val stepMs = (spanMs - 1).toDouble() / (sampleCount - 1).coerceAtLeast(1).toDouble()
        val durationSec = spanMs / 1000.0
        val relativeOffsetSec = trimStartMs / 1000.0

        val encodedW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            ?: fallbackWidth
        val encodedH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            ?: fallbackHeight
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val (frameW, frameH) = displayDimensions(encodedW, encodedH, rotation)
        val observationsByFrame = mutableListOf<Pair<Double, List<PiiDetection>>>()
        val counts = linkedMapOf<PiiClass, Int>()
        var sampledCount = 0
        var observationCount = 0
        var failedFrameCount = 0
        val failedEnginesAggregate = mutableMapOf<String, String>()
        val successfulEnginesAggregate = mutableSetOf<String>()

        for (i in 0 until sampleCount) {
            if (isCancelled()) break
            val timestampMs = (trimStartMs + (i * stepMs).toLong()).coerceIn(trimStartMs, trimEndMs - 1)
            // Request a bounded frame from the decoder itself. Previously every
            // sampled 4K/8K frame was decoded at full resolution and *then*
            // downscaled, causing repeated large native/Java bitmap allocations.
            // Preserve the source coordinate system for FFmpeg redaction.
            val scaledBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val (sampleWidth, sampleHeight) = VideoThumbnailPolicy.fitWithin(
                    frameW, frameH, MAX_ANALYSIS_EDGE_PX
                )
                try {
                    retriever.getScaledFrameAtTime(
                        timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST,
                        sampleWidth, sampleHeight
                    )
                } catch (_: RuntimeException) {
                    null
                }
            } else null
            val scaledCompatible = scaledBmp != null && VideoThumbnailPolicy.matchesSourceAspect(
                frameW, frameH, scaledBmp.width, scaledBmp.height
            )
            if (!scaledCompatible) {
                scaledBmp?.recycle()
            }
            // A failed/rotated/scaled decode falls back to the original path, so
            // a metadata orientation mismatch cannot silently misplace redactions.
            val rawBmp = if (scaledCompatible) scaledBmp else if (
                frameW.toLong() * frameH <= MAX_FALLBACK_PIXELS
            ) try {
                retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (_: RuntimeException) {
                null
            } else null
            if (rawBmp == null) {
                failedFrameCount++
                onProgress?.invoke(i + 1, sampleCount)
                continue
            }

            var analysisBmp: Bitmap? = null
            try {
                // Never switch coordinate systems midway through a scan. If the decoder
                // disagrees with the expected display geometry, no filter can safely be built.
                if (!VideoThumbnailPolicy.matchesSourceAspect(frameW, frameH, rawBmp.width, rawBmp.height)) {
                    throw IllegalStateException("Video decoder display geometry does not match the redaction frame")
                }
                // For scaled frames frameW/H remain the original dimensions, so
                // PiiMultiModalDetector maps observations to full video coordinates.
                sampledCount++

                analysisBmp = downsampleForAnalysis(rawBmp, MAX_ANALYSIS_EDGE_PX)
                val summary = PiiMultiModalDetector.detectAllSummary(
                    context = context,
                    ncnnEngine = piiEngine,
                    bitmap = analysisBmp,
                    targetWidth = frameW,
                    targetHeight = frameH,
                    confThreshold = confThreshold,
                    includeNcnn = true,
                    includeBarcodes = true,
                    includeOcrText = includeOcrText
                )

                failedEnginesAggregate.putAll(summary.failedEngines)
                successfulEnginesAggregate.addAll(summary.successfulEngines)

                val detections = summary.detections
                if (detections.size > MAX_OBSERVATIONS - observationCount) {
                    throw IllegalStateException("Video contains too many observations for a bounded scan. Split the source into shorter clips; no partial masks were applied.")
                }
                observationCount += detections.size
                val relTimeSec = ((timestampMs / 1000.0) - relativeOffsetSec).coerceIn(0.0, durationSec)
                observationsByFrame += relTimeSec to detections
                for (det in detections) {
                    counts[det.piiClass] = (counts[det.piiClass] ?: 0) + 1
                }
            } finally {
                if (analysisBmp != null && analysisBmp !== rawBmp && !analysisBmp.isRecycled) {
                    analysisBmp.recycle()
                }
                if (!rawBmp.isRecycled) {
                    rawBmp.recycle()
                }
            }
            onProgress?.invoke(i + 1, sampleCount)
        }

        if (isCancelled()) return VideoPiiScanResult(
            regions = emptyList(), frameWidth = frameW, frameHeight = frameH,
            detectedCounts = counts, sampledFrames = sampledCount, attemptedFrames = sampleCount,
            failedFrameExtractions = failedFrameCount, failedEngines = failedEnginesAggregate,
            successfulEngines = successfulEnginesAggregate, isScanDegraded = true, isCancelled = true
        )
        val trackResult = buildTrackedRegions(
            frames = observationsByFrame,
            frameW = frameW,
            frameH = frameH,
            durationSec = durationSec,
            sampleStepSec = (stepMs / 1000.0).coerceAtLeast(0.1),
            isCancelled = isCancelled
        )

        val wasCancelled = isCancelled()
        // The selected sample ceiling expands the interval on longer clips. A capped scan is
        // incomplete coverage even when all sampled frames and detectors succeeded.
        val isSamplingCapped = stepMs > density.targetStepMs
        // Any missing frame, detector failure, cancellation, or capped sampling makes the
        // result partial. A partial scan must never clear prior redactions or imply "clean".
        val isScanDegraded = isScanIncomplete(
            attemptedFrames = sampleCount,
            sampledFrames = sampledCount,
            failedFrameExtractions = failedFrameCount,
            failedEngines = failedEnginesAggregate,
            cancelled = wasCancelled,
            samplingCapped = isSamplingCapped
        )

        return VideoPiiScanResult(
            regions = trackResult.first,
            frameWidth = frameW,
            frameHeight = frameH,
            detectedCounts = counts,
            trackCount = trackResult.second,
            sampledFrames = sampledCount,
            attemptedFrames = sampleCount,
            failedFrameExtractions = failedFrameCount,
            failedEngines = failedEnginesAggregate,
            successfulEngines = successfulEnginesAggregate,
            isScanDegraded = isScanDegraded,
            isSamplingCapped = isSamplingCapped,
            actualSampleIntervalMs = kotlin.math.round(stepMs).toLong(),
            isCancelled = wasCancelled
        )
    }

    /**
     * A scan is incomplete if any requested frame was unavailable, any detector failed,
     * or cancellation stopped the requested pass. This is intentionally conservative:
     * partial negative results must never be treated as a clean scan.
     */
    internal fun displayDimensions(width: Int, height: Int, rotation: Int): Pair<Int, Int> {
        require(width >= 2 && height >= 2) { "Invalid video dimensions" }
        val normalized = ((rotation % 360) + 360) % 360
        require(normalized in listOf(0, 90, 180, 270)) { "Unsupported video rotation: $rotation" }
        return if (normalized == 90 || normalized == 270) height to width else width to height
    }

    internal fun sampleCountForSpan(spanMs: Long, density: ScanDensity = ScanDensity.STANDARD): Int {
        require(spanMs > 0)
        val intervals = (spanMs - 1) / density.targetStepMs +
            if ((spanMs - 1) % density.targetStepMs == 0L) 0L else 1L
        return (intervals + 1L).coerceIn(MIN_SAMPLE_FRAMES.toLong(), density.maxSamples.toLong())
            .coerceAtMost(spanMs).toInt()
    }

    internal fun isScanIncomplete(
        attemptedFrames: Int,
        sampledFrames: Int,
        failedFrameExtractions: Int,
        failedEngines: Map<String, String>,
        cancelled: Boolean,
        samplingCapped: Boolean = false
    ): Boolean = cancelled ||
            samplingCapped ||
            failedFrameExtractions > 0 ||
            failedEngines.isNotEmpty() ||
            sampledFrames < attemptedFrames

    /**
     * Associates multi-frame detections into continuous identity tracks, linearly bridges
     * transient detector gaps (`<= 1.35s`), coalesces stationary segments, and subdivides moving
     * intervals using velocity-swept bounding boxes so moving objects stay fully masked.
     */
    fun buildTrackedRegions(
        frames: List<Pair<Double, List<PiiDetection>>>,
        frameW: Int,
        frameH: Int,
        durationSec: Double,
        sampleStepSec: Double = 0.5,
        isCancelled: () -> Boolean = { false }
    ): Pair<List<TimedPiiRegion>, Int> {
        if (frames.isEmpty() || frameW < 4 || frameH < 4) return emptyList<TimedPiiRegion>() to 0

        val sortedFrames = frames.sortedBy { it.first }
        val maxW = frameW.toFloat()
        val maxH = frameH.toFloat()
        val frameDiag = hypot(maxW.toDouble(), maxH.toDouble()).toFloat().coerceAtLeast(1f)
        // Sparse sampling does not justify extending inferred identity continuity.
        val maxTrackGapSec = 1.35

        val tracks = mutableListOf<MutableList<TimedPiiObservation>>()

        val activeTracks = mutableListOf<Int>()
        for ((timeSec, dets) in sortedFrames) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("Video PII tracking cancelled")
            require(timeSec.isFinite() && timeSec >= 0.0 && timeSec < durationSec) { "Observation outside scan timeline" }
            val validDets = dets.filter { it.rect.width() >= 2f && it.rect.height() >= 2f }
            activeTracks.removeAll { timeSec - tracks[it].last().timeSec > maxTrackGapSec }
            if (validDets.isEmpty()) continue

            // Build scored candidate pairs (trackIdx, detIdx, score) for greedy 1-to-1 matching
            data class MatchCandidate(val trackIdx: Int, val detIdx: Int, val matchScore: Float)
            val candidates = mutableListOf<MatchCandidate>()

            for (tIdx in activeTracks) {
                if (isCancelled()) throw kotlinx.coroutines.CancellationException("Video PII tracking cancelled")
                val track = tracks[tIdx]
                val lastObs = track.last()
                val dt = timeSec - lastObs.timeSec
                if (dt <= 0.0 || dt > maxTrackGapSec) continue

                val predictedRect = predictNextRect(track, dt)
                val predDiag = hypot(predictedRect.width().toDouble(), predictedRect.height().toDouble()).toFloat()
                val maxCenterDist = max(predDiag * 1.45f, frameDiag * 0.14f)

                for (dIdx in validDets.indices) {
                    val det = validDets[dIdx]
                    if (det.piiClass != lastObs.piiClass) continue

                    val dist = centerDistance(predictedRect, det.rect)
                    val iou = max(
                        PiiNcnnEngine.calculateIoU(predictedRect, det.rect),
                        PiiNcnnEngine.calculateIoU(lastObs.rect, det.rect)
                    )
                    val areaA = max(1f, lastObs.rect.width() * lastObs.rect.height())
                    val areaB = max(1f, det.rect.width() * det.rect.height())
                    val sizeRatio = min(areaA, areaB) / max(areaA, areaB)

                    val isCompatible = iou >= 0.12f || (dist <= maxCenterDist && sizeRatio >= 0.30f)
                    if (isCompatible) {
                        val normDistPenalty = (dist / maxCenterDist).coerceIn(0f, 1.5f)
                        val score = iou * 2.5f + sizeRatio * 0.6f - normDistPenalty * 0.7f
                        candidates += MatchCandidate(tIdx, dIdx, score)
                    }
                }
            }

            candidates.sortByDescending { it.matchScore }
            val matchedTracks = BooleanArray(tracks.size)
            val matchedDets = BooleanArray(validDets.size)

            for (cand in candidates) {
                if (matchedTracks[cand.trackIdx] || matchedDets[cand.detIdx]) continue
                matchedTracks[cand.trackIdx] = true
                matchedDets[cand.detIdx] = true
                val det = validDets[cand.detIdx]
                tracks[cand.trackIdx] += TimedPiiObservation(
                    timeSec = timeSec,
                    rect = RectF(det.rect),
                    piiClass = det.piiClass,
                    label = det.label,
                    score = det.score
                )
            }

            // Spawn new tracks for unmatched detections
            for (dIdx in validDets.indices) {
                if (matchedDets[dIdx]) continue
                val det = validDets[dIdx]
                activeTracks += tracks.size
                tracks += mutableListOf(
                    TimedPiiObservation(
                        timeSec = timeSec,
                        rect = RectF(det.rect),
                        piiClass = det.piiClass,
                        label = det.label,
                        score = det.score
                    )
                )
            }
        }

        val padLeadSec = min(0.18, sampleStepSec * 0.45)
        val padTrailSec = max(0.20, sampleStepSec * 0.65)
        val totalEndCap = durationSec

        val rawIntervals = mutableListOf<TimedPiiRegion>()
        for (track in tracks) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("Video PII tracking cancelled")
            rawIntervals += materializeTrackRegions(
                track = track,
                maxW = maxW,
                maxH = maxH,
                padLeadSec = padLeadSec,
                padTrailSec = padTrailSec,
                totalEndCap = totalEndCap
            )
        }

        val coalesced = coalesceStationarySegments(rawIntervals, isCancelled = isCancelled)
        return coalesced.sortedBy { it.startSec } to tracks.size
    }

    private fun materializeTrackRegions(
        track: List<TimedPiiObservation>,
        maxW: Float,
        maxH: Float,
        padLeadSec: Double,
        padTrailSec: Double,
        totalEndCap: Double
    ): List<TimedPiiRegion> {
        if (track.isEmpty()) return emptyList()
        val padded = track.map { obs ->
            obs.copy(rect = expandRect(obs.rect, BOX_MARGIN_RATIO, maxW, maxH))
        }

        if (padded.size == 1) {
            val only = padded[0]
            val start = (only.timeSec - padLeadSec).coerceAtLeast(0.0)
            val end = min(totalEndCap, max(start + 0.20, only.timeSec + padTrailSec))
            return listOf(
                TimedPiiRegion(
                    startSec = start,
                    endSec = end,
                    rect = only.rect,
                    piiClass = only.piiClass,
                    label = only.label
                )
            )
        }

        val result = mutableListOf<TimedPiiRegion>()
        val first = padded.first()
        val last = padded.last()
        val firstStart = (first.timeSec - padLeadSec).coerceAtLeast(0.0)
        if (firstStart < first.timeSec) {
            result += TimedPiiRegion(firstStart, first.timeSec, first.rect, first.piiClass, first.label)
        }

        for (i in 0 until padded.size - 1) {
            val a = padded[i]
            val b = padded[i + 1]
            // Lead/trail padding must not change the interpolation timebase.
            val segStart = a.timeSec
            val segEnd = b.timeSec
            val dt = (b.timeSec - a.timeSec).coerceAtLeast(0.05)
            val moveDist = centerDistance(a.rect, b.rect)
            val minDim = min(a.rect.width(), a.rect.height()).coerceAtLeast(8f)

            // If box moved noticeably across the keyframe interval, emit swept linear sub-intervals
            val subCount = if (moveDist > max(14f, minDim * 0.25f) && dt > 0.20) {
                val byMotion = (moveDist / max(14f, minDim * 0.35f)).toInt() + 1
                val byTime = (dt / 0.30).toInt() + 1
                min(max(byMotion, byTime), 6)
            } else {
                1
            }

            if (subCount <= 1) {
                val swept = unionRect(a.rect, b.rect)
                result += TimedPiiRegion(
                    startSec = segStart,
                    endSec = min(totalEndCap, max(segEnd, segStart + 0.08)),
                    rect = swept,
                    piiClass = a.piiClass,
                    label = a.label
                )
            } else {
                val spanStart = segStart
                val spanDur = segEnd - segStart
                val step = spanDur / subCount.toDouble()
                for (s in 0 until subCount) {
                    val alpha0 = (s.toFloat() / subCount.toFloat()).coerceIn(0f, 1f)
                    val alpha1 = ((s + 1).toFloat() / subCount.toFloat()).coerceIn(0f, 1f)
                    val r0 = lerpRect(a.rect, b.rect, alpha0)
                    val r1 = lerpRect(a.rect, b.rect, alpha1)
                    val swept = expandRect(unionRect(r0, r1), 0.04f, maxW, maxH)
                    val t0 = spanStart + s * step
                    val t1 = spanStart + (s + 1) * step + 0.02
                    result += TimedPiiRegion(
                        startSec = t0,
                        endSec = min(totalEndCap, max(t1, t0 + 0.05)),
                        rect = swept,
                        piiClass = a.piiClass,
                        label = a.label
                    )
                }
            }
        }
        val lastEnd = min(totalEndCap, last.timeSec + padTrailSec)
        if (lastEnd > last.timeSec) {
            result += TimedPiiRegion(last.timeSec, lastEnd, last.rect, last.piiClass, last.label)
        }
        return result
    }

    /**
     * Merges consecutive high-overlap intervals of the same class so stationary or nearly-still
     * PII subjects emit one clean continuous FFmpeg timeline filter instead of duplicate segments.
     */
    fun mergeTemporalOverlaps(
        regions: List<TimedPiiRegion>,
        iouThreshold: Float = 0.45f
    ): List<TimedPiiRegion> {
        return coalesceStationarySegments(regions, iouThreshold)
    }

    private fun coalesceStationarySegments(
        regions: List<TimedPiiRegion>,
        iouThreshold: Float = 0.68f,
        isCancelled: () -> Boolean = { false }
    ): List<TimedPiiRegion> {
        if (regions.size <= 1) return regions
        val merged = mutableListOf<TimedPiiRegion>()
        for (region in regions.sortedBy { it.startSec }) {
            if (isCancelled()) throw kotlinx.coroutines.CancellationException("Video PII tracking cancelled")
            // Limit candidate search. Missing a merge adds a mask; it never drops one.
            val matchIdx = (merged.lastIndex downTo maxOf(0, merged.size - 256)).firstOrNull { index ->
                val existing = merged[index]
                existing.piiClass == region.piiClass &&
                    region.startSec <= existing.endSec + 0.35 &&
                    PiiNcnnEngine.calculateIoU(existing.rect, region.rect) >= iouThreshold
            } ?: -1
            if (matchIdx >= 0) {
                val prev = merged[matchIdx]
                merged[matchIdx] = prev.copy(
                    startSec = min(prev.startSec, region.startSec),
                    endSec = max(prev.endSec, region.endSec),
                    rect = unionRect(prev.rect, region.rect)
                )
            } else {
                merged += region
            }
        }
        return merged
    }

    /**
     * Reduces region count down to [maxFilters] by relaxing spatial merge tolerance rather than
     * silently dropping late detections — ensuring every detected timestamp stays protected.
     */
    private fun compressToFilterBudget(
        regions: List<TimedPiiRegion>,
        maxFilters: Int
    ): List<TimedPiiRegion> {
        if (regions.size <= maxFilters) return regions
        var current = regions.sortedBy { it.startSec }
        for (threshold in floatArrayOf(0.55f, 0.40f, 0.25f, 0.12f)) {
            current = coalesceStationarySegments(current, threshold)
            if (current.size <= maxFilters) return current
        }
        // Bounded linear grouping of consecutive temporal windows. Every
        // original span/rectangle is contained in its group union, including
        // late detections. No repeated O(n^2) search for best merges.
        val sorted = current.sortedBy { it.startSec }
        return (0 until maxFilters).mapNotNull { bucket ->
            val from = (bucket.toLong() * sorted.size / maxFilters).toInt()
            val to = ((bucket + 1L) * sorted.size / maxFilters).toInt()
            if (from == to) null else {
                var union = sorted[from]
                for (i in from + 1 until to) {
                    val next = sorted[i]
                    union = union.copy(startSec = min(union.startSec, next.startSec),
                        endSec = max(union.endSec, next.endSec), rect = unionRect(union.rect, next.rect))
                }
                union
            }
        }
    }

    /**
     * Converts selected timed bounding boxes into valid inline FFmpeg `-vf` filter strings.
     * Supports Solid Black, Solid White, Gaussian/Box Blur (`avgblur`), and Pixelate/Mosaic.
     */
    fun generateFfmpegRedactionFilters(
        regions: List<TimedPiiRegion>,
        frameW: Int,
        frameH: Int,
        style: PiiRedactionStyle = PiiRedactionStyle.SOLID_BLACK,
        maxFilters: Int = DEFAULT_MAX_FILTERS
    ): String? {
        if (regions.isEmpty() || frameW < 8 || frameH < 8) return null
        require(regions.all { r ->
            r.startSec.isFinite() && r.endSec.isFinite() && r.startSec >= 0.0 &&
                r.endSec >= r.startSec &&
                listOf(r.rect.left, r.rect.top, r.rect.right, r.rect.bottom).all { it.isFinite() } &&
                r.rect.left < r.rect.right && r.rect.top < r.rect.bottom &&
                r.rect.right > 0f && r.rect.bottom > 0f &&
                r.rect.left < frameW && r.rect.top < frameH
        }) { "Invalid selected video redaction region" }
        val safeBudget = maxFilters.coerceAtLeast(1)
        val bounded = compressToFilterBudget(regions, safeBudget)
        if (bounded.isEmpty()) return null

        return when (style) {
            PiiRedactionStyle.SOLID_BLACK, PiiRedactionStyle.SOLID_WHITE -> {
                val colorName = if (style == PiiRedactionStyle.SOLID_WHITE) "white" else "black"
                val filters = bounded.map { r ->
                    val (x, y, w, h) = outwardBox(r.rect, frameW, frameH, 2)
                    val (start, end) = outwardTimes(r)
                    String.format(
                        Locale.US,
                        "drawbox=x=%d:y=%d:w=%d:h=%d:color=%s:t=fill:enable='between(t,%.2f,%.2f)'",
                        x, y, w, h, colorName, start, end
                    )
                }
                filters.joinToString(",")
            }
            PiiRedactionStyle.BLUR -> {
                buildComplexRedactionGraph(bounded, frameW, frameH, isPixelate = false)
            }
            PiiRedactionStyle.PIXELATE -> {
                buildComplexRedactionGraph(bounded, frameW, frameH, isPixelate = true)
            }
        }
    }

    private data class FilterBox(val x: Int, val y: Int, val w: Int, val h: Int)

    // Round outward to chroma-aligned coordinates instead of relying on
    // crop/overlay's inward truncation on subsampled video.
    private fun outwardBox(rect: RectF, frameW: Int, frameH: Int, minimum: Int): FilterBox {
        fun axis(lo: Float, hi: Float, limit: Int): Pair<Int, Int> {
            val start = floor(lo.toDouble() / 2).toInt().coerceIn(0, (limit - minimum) / 2) * 2
            val end = (ceil(hi.toDouble().coerceAtMost(limit.toDouble()) / 2).toLong() * 2)
                .coerceIn((start + minimum).toLong(), limit.toLong()).toInt()
            return start to (end - start)
        }
        val (x, w) = axis(rect.left, rect.right, frameW)
        val (y, h) = axis(rect.top, rect.bottom, frameH)
        return FilterBox(x, y, w, h)
    }

    private fun outwardTimes(region: TimedPiiRegion): Pair<Double, Double> {
        val start = floor(region.startSec * 100.0) / 100.0
        val end = ceil(max(region.startSec + 0.04, region.endSec) * 100.0) / 100.0
        return start to end
    }

    private fun buildComplexRedactionGraph(
        regions: List<TimedPiiRegion>,
        frameW: Int,
        frameH: Int,
        isPixelate: Boolean
    ): String {
        val sb = StringBuilder()
        var currentInputPad = "0:v:0"

        for (i in regions.indices) {
            val r = regions[i]
            val (x, y, w, h) = outwardBox(r.rect, frameW, frameH, 4)
            val (start, end) = outwardTimes(r)

            val basePad = "v_b$i"
            val cropPad = "v_c$i"
            val procPad = "v_p$i"
            val outPad = if (i == regions.size - 1) "v_out" else "v_s$i"

            sb.append("[$currentInputPad]split=2[$basePad][$cropPad];")

            if (isPixelate) {
                val downW = max(2, w / 10)
                val downH = max(2, h / 10)
                sb.append("[$cropPad]crop=w=$w:h=$h:x=$x:y=$y:exact=1,scale=$downW:$downH:flags=neighbor,scale=$w:$h:flags=neighbor[$procPad];")
            } else {
                sb.append("[$cropPad]crop=w=$w:h=$h:x=$x:y=$y:exact=1,avgblur=sizeX=16:sizeY=16[$procPad];")
            }

            sb.append(String.format(
                Locale.US,
                "[%s][%s]overlay=x=%d:y=%d:enable='between(t,%.2f,%.2f)'[%s]",
                basePad, procPad, x, y, start, end, outPad
            ))

            if (i < regions.size - 1) {
                sb.append(";")
            }
            currentInputPad = outPad
        }

        return sb.toString()
    }

    private fun predictNextRect(track: List<TimedPiiObservation>, dtSec: Double): RectF {
        val last = track.last()
        if (track.size < 2 || dtSec <= 0.0) return RectF(last.rect)
        val prev = track[track.size - 2]
        val prevDt = (last.timeSec - prev.timeSec).coerceAtLeast(0.05)
        val vx = ((last.rect.centerX() - prev.rect.centerX()) / prevDt).coerceIn(-1200.0, 1200.0)
        val vy = ((last.rect.centerY() - prev.rect.centerY()) / prevDt).coerceIn(-1200.0, 1200.0)
        val shiftX = (vx * min(dtSec, 1.0)).toFloat()
        val shiftY = (vy * min(dtSec, 1.0)).toFloat()
        return RectF(
            last.rect.left + shiftX,
            last.rect.top + shiftY,
            last.rect.right + shiftX,
            last.rect.bottom + shiftY
        )
    }

    private fun centerDistance(a: RectF, b: RectF): Float {
        return hypot((a.centerX() - b.centerX()).toDouble(), (a.centerY() - b.centerY()).toDouble()).toFloat()
    }

    private fun lerpRect(a: RectF, b: RectF, alpha: Float): RectF {
        val t = alpha.coerceIn(0f, 1f)
        return RectF(
            a.left + (b.left - a.left) * t,
            a.top + (b.top - a.top) * t,
            a.right + (b.right - a.right) * t,
            a.bottom + (b.bottom - a.bottom) * t
        )
    }

    private fun unionRect(a: RectF, b: RectF): RectF {
        return RectF(
            min(a.left, b.left),
            min(a.top, b.top),
            max(a.right, b.right),
            max(a.bottom, b.bottom)
        )
    }

    private fun downsampleForAnalysis(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = max(w, h)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest.toFloat()
        val dstW = (w * scale).roundToInt().coerceAtLeast(2)
        val dstH = (h * scale).roundToInt().coerceAtLeast(2)
        return Bitmap.createScaledBitmap(src, dstW, dstH, true)
    }

    private fun expandRect(rect: RectF, marginRatio: Float, maxW: Float, maxH: Float): RectF {
        val padX = max(MIN_BOX_PAD_PX, rect.width() * marginRatio)
        val padY = max(MIN_BOX_PAD_PX, rect.height() * marginRatio)
        return RectF(
            (rect.left - padX).coerceIn(0f, maxW),
            (rect.top - padY).coerceIn(0f, maxH),
            (rect.right + padX).coerceIn(0f, maxW),
            (rect.bottom + padY).coerceIn(0f, maxH)
        )
    }
}
