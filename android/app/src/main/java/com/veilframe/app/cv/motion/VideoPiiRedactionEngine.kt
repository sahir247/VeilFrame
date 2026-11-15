package com.veilframe.app.cv.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
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
 * and ML Kit OCR + `PiiTextClassifier`) across dense adaptive keyframes, associates moving detections
 * into motion-aware tracks, bridges transient detector dropouts linearly, and emits swept bounding-box
 * intervals so moving PII targets stay continuously masked throughout the video without dropping late intervals.
 */
object VideoPiiRedactionEngine {

    private const val MIN_SAMPLE_FRAMES = 6
    private const val MAX_SAMPLE_FRAMES = 120
    private const val TARGET_SAMPLE_STEP_MS = 350L
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
        onProgress: ((sampled: Int, total: Int) -> Unit)? = null
    ): VideoPiiScanResult {
        val safeEndMs = trimEndMs.coerceAtLeast(trimStartMs + 500L)
        val spanMs = (safeEndMs - trimStartMs).coerceAtLeast(500L)
        val sampleCount = ((spanMs / TARGET_SAMPLE_STEP_MS).toInt() + 1)
            .coerceIn(MIN_SAMPLE_FRAMES, MAX_SAMPLE_FRAMES)
        val stepMs = spanMs.toDouble() / (sampleCount - 1).coerceAtLeast(1).toDouble()
        val durationSec = spanMs / 1000.0
        val relativeOffsetSec = trimStartMs / 1000.0

        var frameW = fallbackWidth.coerceAtLeast(2)
        var frameH = fallbackHeight.coerceAtLeast(2)
        val observationsByFrame = mutableListOf<Pair<Double, List<PiiDetection>>>()
        val counts = linkedMapOf<PiiClass, Int>()
        var sampledCount = 0
        var failedFrameCount = 0
        val failedEnginesAggregate = mutableMapOf<String, String>()
        val successfulEnginesAggregate = mutableSetOf<String>()

        for (i in 0 until sampleCount) {
            if (isCancelled()) break
            val timestampMs = (trimStartMs + (i * stepMs)).toLong().coerceIn(trimStartMs, safeEndMs)
            val rawBmp = try {
                retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (_: Throwable) {
                null
            }
            if (rawBmp == null) {
                failedFrameCount++
                onProgress?.invoke(i + 1, sampleCount)
                continue
            }

            var analysisBmp: Bitmap? = null
            try {
                frameW = rawBmp.width.coerceAtLeast(2)
                frameH = rawBmp.height.coerceAtLeast(2)
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

        val trackResult = buildTrackedRegions(
            frames = observationsByFrame,
            frameW = frameW,
            frameH = frameH,
            durationSec = durationSec,
            sampleStepSec = (stepMs / 1000.0).coerceAtLeast(0.1)
        )

        val wasCancelled = isCancelled()
        val isScanDegraded = failedEnginesAggregate.isNotEmpty() || (failedFrameCount > 0 && sampledCount == 0)
        val isSamplingCapped = stepMs > 600.0

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
     * Associates multi-frame detections into continuous identity tracks, linearly bridges
     * transient detector gaps (`<= 1.35s`), coalesces stationary segments, and subdivides moving
     * intervals using velocity-swept bounding boxes so moving objects stay fully masked.
     */
    fun buildTrackedRegions(
        frames: List<Pair<Double, List<PiiDetection>>>,
        frameW: Int,
        frameH: Int,
        durationSec: Double,
        sampleStepSec: Double = 0.5
    ): Pair<List<TimedPiiRegion>, Int> {
        if (frames.isEmpty() || frameW < 4 || frameH < 4) return emptyList<TimedPiiRegion>() to 0

        val sortedFrames = frames.sortedBy { it.first }
        val maxW = frameW.toFloat()
        val maxH = frameH.toFloat()
        val frameDiag = hypot(maxW.toDouble(), maxH.toDouble()).toFloat().coerceAtLeast(1f)
        val maxTrackGapSec = max(sampleStepSec * 2.6, 1.35)

        val tracks = mutableListOf<MutableList<TimedPiiObservation>>()

        for ((timeSec, dets) in sortedFrames) {
            val validDets = dets.filter { it.rect.width() >= 2f && it.rect.height() >= 2f }
            if (validDets.isEmpty()) continue

            // Build scored candidate pairs (trackIdx, detIdx, score) for greedy 1-to-1 matching
            data class MatchCandidate(val trackIdx: Int, val detIdx: Int, val matchScore: Float)
            val candidates = mutableListOf<MatchCandidate>()

            for (tIdx in tracks.indices) {
                val track = tracks[tIdx]
                val lastObs = track.last()
                val dt = timeSec - lastObs.timeSec
                if (dt < 0.0 || dt > maxTrackGapSec) continue

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
        val totalEndCap = (durationSec + 0.25).coerceAtLeast(0.5)

        val rawIntervals = mutableListOf<TimedPiiRegion>()
        for (track in tracks) {
            rawIntervals += materializeTrackRegions(
                track = track,
                maxW = maxW,
                maxH = maxH,
                padLeadSec = padLeadSec,
                padTrailSec = padTrailSec,
                totalEndCap = totalEndCap
            )
        }

        val coalesced = coalesceStationarySegments(rawIntervals)
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
            val end = min(totalEndCap, only.timeSec + padTrailSec).coerceAtLeast(start + 0.20)
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
        val firstStart = (padded.first().timeSec - padLeadSec).coerceAtLeast(0.0)

        for (i in 0 until padded.size - 1) {
            val a = padded[i]
            val b = padded[i + 1]
            val segStart = if (i == 0) firstStart else a.timeSec
            val segEnd = if (i == padded.size - 2) {
                min(totalEndCap, b.timeSec + padTrailSec)
            } else {
                b.timeSec
            }
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
                    endSec = segEnd.coerceAtLeast(segStart + 0.08),
                    rect = swept,
                    piiClass = a.piiClass,
                    label = a.label
                )
            } else {
                val spanStart = segStart
                val spanDur = (segEnd - segStart).coerceAtLeast(0.08)
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
                        endSec = min(totalEndCap, t1).coerceAtLeast(t0 + 0.05),
                        rect = swept,
                        piiClass = a.piiClass,
                        label = a.label
                    )
                }
            }
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
        iouThreshold: Float = 0.68f
    ): List<TimedPiiRegion> {
        if (regions.size <= 1) return regions
        val merged = mutableListOf<TimedPiiRegion>()
        for (region in regions.sortedBy { it.startSec }) {
            val matchIdx = merged.indexOfLast { existing ->
                existing.piiClass == region.piiClass &&
                    region.startSec <= existing.endSec + 0.35 &&
                    PiiNcnnEngine.calculateIoU(existing.rect, region.rect) >= iouThreshold
            }
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
        // Final fallback: pair adjacent same-class windows with smallest temporal span gap
        val working = current.toMutableList()
        while (working.size > maxFilters) {
            var bestI = 0
            var bestJ = 1
            var bestGap = Double.MAX_VALUE
            for (i in 0 until working.size) {
                for (j in i + 1 until min(working.size, i + 8)) {
                    if (working[i].piiClass == working[j].piiClass) {
                        val gap = (working[j].startSec - working[i].endSec).coerceAtLeast(0.0)
                        if (gap < bestGap) {
                            bestGap = gap
                            bestI = i
                            bestJ = j
                        }
                    }
                }
            }
            val a = working[bestI]
            val b = working[bestJ]
            working[bestI] = a.copy(
                startSec = min(a.startSec, b.startSec),
                endSec = max(a.endSec, b.endSec),
                rect = unionRect(a.rect, b.rect)
            )
            working.removeAt(bestJ)
        }
        return working
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
        val safeBudget = maxFilters.coerceAtLeast(1)
        val bounded = compressToFilterBudget(regions, safeBudget)
        if (bounded.isEmpty()) return null

        return when (style) {
            PiiRedactionStyle.SOLID_BLACK, PiiRedactionStyle.SOLID_WHITE -> {
                val colorName = if (style == PiiRedactionStyle.SOLID_WHITE) "white" else "black"
                val filters = bounded.map { r ->
                    val x = r.rect.left.roundToInt().coerceIn(0, frameW - 2)
                    val y = r.rect.top.roundToInt().coerceIn(0, frameH - 2)
                    val w = (r.rect.right.roundToInt() - x).coerceIn(2, frameW - x)
                    val h = (r.rect.bottom.roundToInt() - y).coerceIn(2, frameH - y)
                    val start = max(0.0, r.startSec)
                    val end = max(start + 0.04, r.endSec)
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

    private fun buildComplexRedactionGraph(
        regions: List<TimedPiiRegion>,
        frameW: Int,
        frameH: Int,
        isPixelate: Boolean
    ): String {
        val sb = StringBuilder()
        var currentInputPad = "0:v"

        for (i in regions.indices) {
            val r = regions[i]
            val x = r.rect.left.roundToInt().coerceIn(0, frameW - 4)
            val y = r.rect.top.roundToInt().coerceIn(0, frameH - 4)
            val w = (r.rect.right.roundToInt() - x).coerceIn(4, frameW - x)
            val h = (r.rect.bottom.roundToInt() - y).coerceIn(4, frameH - y)
            val start = max(0.0, r.startSec)
            val end = max(start + 0.04, r.endSec)

            val basePad = "v_b$i"
            val cropPad = "v_c$i"
            val procPad = "v_p$i"
            val outPad = if (i == regions.size - 1) "v_out" else "v_s$i"

            sb.append("[$currentInputPad]split=2[$basePad][$cropPad];")

            if (isPixelate) {
                val downW = max(2, w / 10)
                val downH = max(2, h / 10)
                sb.append("[$cropPad]crop=w=$w:h=$h:x=$x:y=$y,scale=$downW:$downH:flags=neighbor,scale=$w:$h:flags=neighbor[$procPad];")
            } else {
                sb.append("[$cropPad]crop=w=$w:h=$h:x=$x:y=$y,avgblur=sizeX=16:sizeY=16[$procPad];")
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
