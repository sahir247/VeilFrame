package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.upscale.model.ModelCapability
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * High-level coordinator for image upscaling inference.
 * Coordinates Algorithmic models and native NCNN neural models behind a unified execution pipeline.
 */
class UpscaleInferenceEngine(
    private val context: Context,
    private val repository: UpscaleModelRepository
) {

    companion object {
        private const val TAG = "VeilFrame.UpscaleEngine"
        private val verifiedShaCache = ConcurrentHashMap<String, String>()

        /**
         * Threshold (16 Megapixels) above which algorithmic scaling switches to the
         * progressive disk-backed tile pipeline via StreamingPngWriter to prevent RAM-induced OOM.
         */
        const val STREAMING_PIXEL_THRESHOLD = 16_000_000L

        /**
         * Capability-aware execution validator.
         * Validates scale compatibility, minimum input dimensions, and model-specific capability
         * constraints (e.g. rejecting arbitrary tiled super-resolution on CodeFormer and face restoration models).
         */
        fun validateExecutionPlan(
            srcW: Int,
            srcH: Int,
            model: UpscaleModel,
            targetScale: Int
        ) {
            // 1. Validate scale compatibility
            require(targetScale in model.supportedOutputScales) {
                "Output scale ${targetScale}× is not supported by ${model.name}. Supported output scales: ${model.supportedOutputScales.joinToString(", ")}×"
            }

            // 2. Validate input dimensions against model minimum
            require(srcW >= model.minInputDimension && srcH >= model.minInputDimension) {
                "Input image dimensions (${srcW}×${srcH}) do not meet the minimum required dimensions (${model.minInputDimension}×${model.minInputDimension}px) for ${model.name}."
            }

            // 3. Capability-aware execution path validation
            if (model.capability == ModelCapability.FACE_RESTORATION) {
                if (!model.tileCompatible) {
                    require(srcW == model.minInputDimension && srcH == model.minInputDimension) {
                        "Model ${model.name} is a specialized face restoration model (${model.capability}) requiring aligned ${model.minInputDimension}×${model.minInputDimension} face crops. Arbitrary tiled super-resolution is rejected before inference."
                    }
                }
            } else if (!model.tileCompatible) {
                require(srcW == model.minInputDimension && srcH == model.minInputDimension) {
                    "Model ${model.name} does not support spatial tiling and requires exact dimensions of ${model.minInputDimension}×${model.minInputDimension}."
                }
            }
        }
    }

    interface InferenceProgressListener {
        fun onProgress(currentTile: Int, totalTiles: Int, percent: Int)
        fun onStatus(message: String)
        fun onStage(stage: String, currentTile: Int, totalTiles: Int) {}
    }

    suspend fun upscale(
        source: Bitmap,
        model: UpscaleModel,
        targetScale: Int,
        listener: InferenceProgressListener?
    ): Result<UpscaleOutput> = upscale(BitmapSource(source) as SourceAccess, model, targetScale, UpscaleInferenceParams(), listener)

    suspend fun upscale(
        source: Bitmap,
        model: UpscaleModel,
        targetScale: Int,
        params: UpscaleInferenceParams = UpscaleInferenceParams(),
        listener: InferenceProgressListener? = null
    ): Result<UpscaleOutput> = upscale(BitmapSource(source) as SourceAccess, model, targetScale, params, listener)

    /**
     * F1 primary entry point: streams the source via [SourceAccess] so huge
     * photos never need to live fully in the Java heap.
     * F4: neural inference is retried once with halved tiles on OutOfMemoryError
     * before failing with a typed [UpscaleMemoryException] (the controller then
     * offers an honest Lanczos degrade instead of crashing).
     */
    suspend fun upscale(
        source: SourceAccess,
        model: UpscaleModel,
        targetScale: Int,
        params: UpscaleInferenceParams = UpscaleInferenceParams(),
        listener: InferenceProgressListener? = null
    ): Result<UpscaleOutput> = withContext(Dispatchers.Default) {
        val t0 = System.currentTimeMillis()
        val srcW = source.width
        val srcH = source.height

        // Validate execution plan: scales, dimensions, and capability constraints
        validateExecutionPlan(srcW, srcH, model, targetScale)

        try {
            ensureActive()
            listener?.onStatus("Preparing processing pipeline...")
            listener?.onStage("Preparing", 0, 1)

            val resultOutput = when (model.type) {
                ModelType.AI_NCNN -> {
                    val scalePlan = HybridScalePlan.create(targetScale, model.nativeScale)
                    val ncnnEngine = NcnnUpscaleEngine(context, repository)
                    val aiPassOutput = ncnnEngine.upscale(
                        source = source,
                        model = model,
                        targetScale = model.nativeScale,
                        params = params,
                        listener = listener
                    )

                    val unenhanced = if (!scalePlan.requiresRefinement) {
                        aiPassOutput
                    } else {
                        // Streamed refinement pass from the NCNN intermediate output
                        val refineScale = scalePlan.refinementScale
                        val finalW = srcW * targetScale
                        val finalH = srcH * targetScale
                        if (finalW > UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION ||
                            finalH > UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION ||
                            finalW.toLong() * finalH.toLong() > UpscaleResourceEstimator.MAX_SAFE_TOTAL_PIXELS
                        ) {
                            (aiPassOutput as? UpscaleOutput.Streamed)?.file?.delete()
                            throw ResourceLimitExceededException(
                                "Hybrid ${targetScale}× output (${finalW}x${finalH}) exceeds safe device pixel bounds."
                            )
                        }
                        listener?.onStatus("Applying ${refineScale}× Lanczos refinement...")
                        listener?.onStage("Refinement", 0, 1)
                        refineStreamedOutput(aiPassOutput, refineScale, finalW, finalH)
                    }

                    if (params.detailEnhancement && params.detailStrength > 0f) {
                        when (unenhanced) {
                            is UpscaleOutput.InMemory -> {
                                val enhanced = AdaptiveDetailEnhancer.enhance(unenhanced.bitmap, params.detailStrength)
                                UpscaleOutput.InMemory(enhanced)
                            }
                            is UpscaleOutput.Streamed -> {
                                val enhancedPreview = unenhanced.preview?.let { AdaptiveDetailEnhancer.enhance(it, params.detailStrength) }
                                unenhanced.copy(preview = enhancedPreview)
                            }
                        }
                    } else {
                        unenhanced
                    }
                }
                ModelType.ALGORITHMIC -> {
                    val targetW = srcW * targetScale
                    val targetH = srcH * targetScale
                    val totalPixels = targetW.toLong() * targetH.toLong()

                    if (targetW > UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION ||
                        targetH > UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION ||
                        totalPixels > UpscaleResourceEstimator.MAX_SAFE_TOTAL_PIXELS
                    ) {
                        throw ResourceLimitExceededException(
                            "Output (${targetW}x${targetH}) exceeds safe device pixel bounds (${UpscaleResourceEstimator.MAX_SAFE_TOTAL_PIXELS} px)."
                        )
                    }

                    if (totalPixels > STREAMING_PIXEL_THRESHOLD) {
                        // Progressive disk-backed tile pipeline: eliminates RAM-induced OOM on >16MP images & 8x upscaling
                        upscaleAlgorithmicStreamed(source, model, targetScale, targetW, targetH, params, listener)
                    } else {
                        listener?.onStatus("Applying ${model.name}...")
                        listener?.onProgress(0, 1, 0)
                        listener?.onStage("Rescaling", 0, 1)
                        val fullSource = source.full()
                        val ownsFullSource = source !is BitmapSource
                        try {
                            val cc = coroutineContext
                            val checkpoint: () -> Unit = { cc.ensureActive() }
                            val res = when (model.id) {
                                "nearest" -> AlgorithmicUpscaler.scaleNearest(fullSource, targetW, targetH, checkpoint)
                                "bicubic" -> AlgorithmicUpscaler.scaleBicubic(fullSource, targetW, targetH)
                                else -> AlgorithmicUpscaler.scaleLanczos(fullSource, targetW, targetH, checkpoint)
                            }
                            val enhanced = if (params.detailEnhancement && params.detailStrength > 0f) {
                                AdaptiveDetailEnhancer.enhance(res, params.detailStrength)
                            } else {
                                res
                            }
                            listener?.onProgress(1, 1, 100)
                            listener?.onStage("Complete", 1, 1)
                            UpscaleOutput.InMemory(enhanced)
                        } finally {
                            if (ownsFullSource && !fullSource.isRecycled) {
                                fullSource.recycle()
                            }
                        }
                    }
                }
            }

            val currentBackend = when (model.type) {
                ModelType.AI_NCNN -> if (NcnnNative.getGpuCount() > 0) "NCNN_VULKAN" else "NCNN_CPU"
                ModelType.ALGORITHMIC -> "ALGORITHMIC"
            }

            com.veilframe.app.cv.core.CvTelemetry.record(
                linkedMapOf(
                    "ts" to System.currentTimeMillis(), "job" to "upscale",
                    "model" to model.id, "scale" to targetScale,
                    "backend" to currentBackend,
                    "chunk" to params.chunkSize, "workers" to params.parallelWorkers,
                    "srcW" to srcW, "srcH" to srcH,
                    "ms" to (System.currentTimeMillis() - t0), "outcome" to "ok",
                    "streamed" to (resultOutput is UpscaleOutput.Streamed)
                )
            )
            Result.success(resultOutput)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Upscale failed: ${e.message}", e)
            val currentBackend = when (model.type) {
                ModelType.AI_NCNN -> if (NcnnNative.getGpuCount() > 0) "NCNN_VULKAN" else "NCNN_CPU"
                ModelType.ALGORITHMIC -> "ALGORITHMIC"
            }
            com.veilframe.app.cv.core.CvTelemetry.record(
                linkedMapOf(
                    "ts" to System.currentTimeMillis(), "job" to "upscale",
                    "model" to model.id, "scale" to targetScale,
                    "backend" to currentBackend,
                    "chunk" to params.chunkSize, "workers" to params.parallelWorkers,
                    "srcW" to srcW, "srcH" to srcH,
                    "ms" to (System.currentTimeMillis() - t0), "outcome" to "err",
                    "error" to (e.message ?: e.javaClass.simpleName)
                )
            )
            Result.failure(e)
        }
    }

    /**
     * Streams a secondary horizontal-band Lanczos/Bicubic refinement pass over a
     * streamed NCNN output PNG without holding the full output bitmap in memory.
     */
    private suspend fun refineStreamedOutput(
        aiPassOutput: UpscaleOutput,
        refineScale: Int,
        finalW: Int,
        finalH: Int
    ): UpscaleOutput = withContext(Dispatchers.Default) {
        val streamed = aiPassOutput as? UpscaleOutput.Streamed
            ?: throw UpscaleMemoryException("Expected streamed NCNN output for hybrid refinement")
        val uri = android.net.Uri.fromFile(streamed.file)
        val regionSource = UriRegionSource(context, uri, streamed.width, streamed.height)
        val outDir = File(context.cacheDir, "upscale_streaming").apply { mkdirs() }
        val tempFile = File(outDir, "ncnn_hybrid_${java.util.UUID.randomUUID()}.part")
        val finalFile = File(outDir, "ncnn_hybrid_${java.util.UUID.randomUUID()}.png")
        val bandSrcH = 128
        val rowPixels = IntArray(finalW)
        var writer: StreamingPngWriter? = null
        try {
            writer = StreamingPngWriter(tempFile, finalW, finalH, StreamingPngWriter.ColorType.RGBA)
            var y0 = 0
            while (y0 < streamed.height) {
                ensureActive()
                val h = minOf(bandSrcH, streamed.height - y0)
                val stripBmp = regionSource.region(0, y0, streamed.width, h)
                val scaledStrip = try {
                    AlgorithmicUpscaler.scaleLanczos(stripBmp, finalW, h * refineScale)
                } finally {
                    stripBmp.recycle()
                }
                try {
                    for (r in 0 until scaledStrip.height) {
                        scaledStrip.getPixels(rowPixels, 0, finalW, 0, r, finalW, 1)
                        writer.writeRow(rowPixels)
                    }
                } finally {
                    scaledStrip.recycle()
                }
                y0 += h
            }
            writer.close()
            NcnnUpscaleEngine.validatePngFile(tempFile, finalW, finalH)
            NcnnUpscaleEngine.publishFileAtomically(tempFile, finalFile)
            val preview = com.veilframe.app.media.MediaDecoder.decodeSampledFile(finalFile)
            UpscaleOutput.Streamed(finalFile, preview, finalW, finalH)
        } catch (t: Throwable) {
            runCatching { writer?.abort() }
            runCatching { tempFile.delete() }
            throw t
        } finally {
            regionSource.close()
            runCatching { streamed.file.delete() }
        }
    }

    /**
     * Progressive disk-backed tile pipeline via [StreamingPngWriter].
     * Eliminates RAM-induced OOM on >16MP images and high-scale (e.g. 8x) upscaling
     * by processing bounded horizontal strips and streaming output scanlines directly to disk.
     */
    private suspend fun upscaleAlgorithmicStreamed(
        source: SourceAccess,
        model: UpscaleModel,
        targetScale: Int,
        targetW: Int,
        targetH: Int,
        params: UpscaleInferenceParams,
        listener: InferenceProgressListener?
    ): UpscaleOutput = withContext(Dispatchers.Default) {
        val outDir = File(context.cacheDir, "upscale_streaming").apply { mkdirs() }
        val jobId = java.util.UUID.randomUUID().toString()
        val tempFile = File(outDir, "algorithmic_${jobId}.part")
        val finalFile = File(outDir, "algorithmic_${jobId}.png")
        val bandSrcH = 128
        val rowPixels = IntArray(targetW)
        var writer: StreamingPngWriter? = null
        try {
            listener?.onStatus("Streaming ${model.name} to disk (${targetW}x${targetH})...")
            listener?.onStage("Disk-Backed Rescaling", 0, 1)
            writer = StreamingPngWriter(tempFile, targetW, targetH, StreamingPngWriter.ColorType.RGBA)
            var y0 = 0
            val totalBands = (source.height + bandSrcH - 1) / bandSrcH
            var bandIdx = 0
            while (y0 < source.height) {
                ensureActive()
                val h = minOf(bandSrcH, source.height - y0)
                val stripBmp = source.region(0, y0, source.width, h)
                val scaledStrip = try {
                    val cc = coroutineContext
                    val checkpoint: () -> Unit = { cc.ensureActive() }
                    when (model.id) {
                        "nearest" -> AlgorithmicUpscaler.scaleNearest(stripBmp, targetW, h * targetScale, checkpoint)
                        "bicubic" -> AlgorithmicUpscaler.scaleBicubic(stripBmp, targetW, h * targetScale)
                        else -> AlgorithmicUpscaler.scaleLanczos(stripBmp, targetW, h * targetScale, checkpoint)
                    }
                } finally {
                    stripBmp.recycle()
                }
                try {
                    for (r in 0 until scaledStrip.height) {
                        scaledStrip.getPixels(rowPixels, 0, targetW, 0, r, targetW, 1)
                        writer.writeRow(rowPixels)
                    }
                } finally {
                    scaledStrip.recycle()
                }
                y0 += h
                bandIdx++
                val pct = ((bandIdx.toFloat() / totalBands.toFloat()) * 100f).toInt().coerceIn(0, 99)
                listener?.onProgress(bandIdx, totalBands, pct)
                listener?.onStage("Disk-Backed Rescaling", bandIdx, totalBands)
            }
            writer.close()
            NcnnUpscaleEngine.validatePngFile(tempFile, targetW, targetH)
            NcnnUpscaleEngine.publishFileAtomically(tempFile, finalFile)
            val preview = com.veilframe.app.media.MediaDecoder.decodeSampledFile(finalFile)
            val enhancedPreview = if (params.detailEnhancement && params.detailStrength > 0f) {
                preview?.let { AdaptiveDetailEnhancer.enhance(it, params.detailStrength) } ?: preview
            } else {
                preview
            }
            listener?.onProgress(totalBands, totalBands, 100)
            listener?.onStage("Complete", totalBands, totalBands)
            UpscaleOutput.Streamed(finalFile, enhancedPreview, targetW, targetH)
        } catch (t: Throwable) {
            runCatching { writer?.abort() }
            runCatching { tempFile.delete() }
            throw t
        }
    }
}
