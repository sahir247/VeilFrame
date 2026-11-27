package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import com.veilframe.app.media.MediaDecoder
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import java.io.File
import java.util.UUID

/** Source-coordinate bands with a real kernel halo; only disjoint core rows are emitted. */
internal data class UpscaleBand(val readTop: Int, val readHeight: Int, val outputOffset: Int, val outputRows: Int)

internal class UpscaleBandPlan(
    private val sourceHeight: Int,
    private val scale: Int,
    private val coreHeight: Int,
    private val halo: Int
) {
    init { require(sourceHeight > 0 && scale > 0 && coreHeight > 0 && halo >= 0) }
    val count: Int get() = 1 + (sourceHeight - 1) / coreHeight
    fun bands(): Sequence<UpscaleBand> = sequence {
        var y = 0
        while (y < sourceHeight) {
            val end = y + minOf(coreHeight, sourceHeight - y)
            val top = maxOf(0, y - halo)
            val bottom = minOf(sourceHeight, end + halo)
            yield(UpscaleBand(top, bottom - top, (y - top) * scale, (end - y) * scale))
            y = end
        }
    }

    companion object {
        fun bounded(sourceWidth: Int, sourceHeight: Int, scale: Int, halo: Int,
                    budgetBytes: Long = 64L * 1024 * 1024): UpscaleBandPlan {
            require(sourceWidth > 0 && sourceHeight > 0 && scale > 0)
            // Includes bitmap/tensor/kernel scratch, not just the PNG scanline.
            val bytesPerSourceRow = sourceWidth.toLong() * scale * scale * 40L
            val readRows = (budgetBytes / bytesPerSourceRow).coerceAtMost(sourceHeight.toLong()).toInt()
            val minimumRows = minOf(sourceHeight, 2 * halo + 1)
            if (readRows < minimumRows) throw ResourceLimitExceededException("Image width exceeds the bounded resampling workspace")
            val core = if (readRows == sourceHeight) minOf(128, readRows) else minOf(128, readRows - 2 * halo)
            return UpscaleBandPlan(sourceHeight, scale, core.coerceAtLeast(1), halo)
        }
    }
}

internal object StreamingImagePostProcessor {
    suspend fun write(
        context: Context,
        source: SourceAccess,
        scale: Int,
        algorithm: String,
        detailStrength: Float,
        listener: UpscaleInferenceEngine.InferenceProgressListener?
    ): UpscaleOutput.Streamed {
        val w = Math.multiplyExact(source.width, scale)
        val h = Math.multiplyExact(source.height, scale)
        require(w.toLong() <= UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION &&
            h.toLong() <= UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION &&
            w.toLong() * h <= UpscaleResourceEstimator.MAX_SAFE_TOTAL_PIXELS)
        // Lanczos-4 uses four source taps on either side; detail uses +/-1 output row.
        val halo = (if (scale > 1) 4 else 0) + (if (detailStrength > .01f) 1 else 0)
        val plan = UpscaleBandPlan.bounded(source.width, source.height, scale, halo)
        val outDir = File(context.cacheDir, "upscale_streaming")
        check(outDir.isDirectory || outDir.mkdirs() || outDir.isDirectory)
        val raw = w.toLong() * h * 4L
        if (outDir.usableSpace < raw + raw / 16L + 52L * 1024 * 1024) {
            throw InsufficientDiskSpaceException("Not enough space for staged full-resolution PNG")
        }
        val jobId = UUID.randomUUID().toString()
        val temp = File(outDir, "post_${jobId}.part")
        val final = File(outDir, "post_${jobId}.png")
        val cc = coroutineContext
        val checkpoint: () -> Unit = { cc.ensureActive() }
        val row = IntArray(w)
        var writer: StreamingPngWriter? = null
        var preview: Bitmap? = null
        var completed = false
        try {
            val sink = StreamingPngWriter(temp, w, h,
                if (source.hasAlpha) StreamingPngWriter.ColorType.RGBA else StreamingPngWriter.ColorType.RGB)
            writer = sink
            for ((index, band) in plan.bands().withIndex()) {
                checkpoint()
                val input = source.region(0, band.readTop, source.width, band.readHeight)
                var scaled: Bitmap? = null
                var enhanced: Bitmap? = null
                try {
                    val image = if (scale == 1) input else when (algorithm) {
                        "nearest" -> AlgorithmicUpscaler.scaleNearest(input, w, band.readHeight * scale, checkpoint)
                        "bicubic" -> AlgorithmicUpscaler.scaleBicubic(input, w, band.readHeight * scale, checkpoint)
                        else -> AlgorithmicUpscaler.scaleLanczos4Portable(input, w, band.readHeight * scale, checkpoint)
                    }
                    scaled = image
                    image.setHasAlpha(source.hasAlpha)
                    // One fixed backend for the entire banded pass, including tiny last bands.
                    val output = if (detailStrength > .01f) AdaptiveDetailEnhancer.enhanceScanlines(image, detailStrength, checkpoint) else image
                    enhanced = output
                    require(output.width == w && output.height == band.readHeight * scale)
                    for (y in band.outputOffset until band.outputOffset + band.outputRows) {
                        checkpoint()
                        output.getPixels(row, 0, w, 0, y, w, 1)
                        sink.writeRow(row)
                    }
                } finally {
                    enhanced?.takeIf { it !== scaled && it !== input }?.recycle()
                    scaled?.takeIf { it !== input }?.recycle()
                    input.recycle()
                }
                listener?.onStage("Full-resolution finishing", index + 1, plan.count)
                listener?.onProgress(index + 1, plan.count, ((index + 1L) * 99 / plan.count).toInt())
            }
            sink.close()
            checkpoint()
            NcnnUpscaleEngine.validatePngFile(temp, w, h, if (source.hasAlpha) 4 else 3, checkpoint)
            checkpoint()
            NcnnUpscaleEngine.publishFileAtomically(temp, final)
            preview = MediaDecoder.decodeSampledFile(final)
            checkpoint()
            listener?.onProgress(plan.count, plan.count, 100)
            listener?.onStage("Complete", plan.count, plan.count)
            val result = UpscaleOutput.Streamed(final, preview, w, h)
            completed = true
            return result
        } finally {
            if (!completed) {
                preview?.recycle()
                runCatching { writer?.abort() }
                temp.delete()
                final.delete()
            }
        }
    }
}
