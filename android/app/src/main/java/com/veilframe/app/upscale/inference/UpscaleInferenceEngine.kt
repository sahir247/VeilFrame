package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.upscale.model.ModelCapability
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRepository
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
    ): Result<UpscaleOutput> = withOwnedResult(
        Dispatchers.Default, { result: Result<UpscaleOutput> -> result.getOrNull()?.discardUnclaimed() }
    ) {
        val t0 = System.currentTimeMillis()
        val srcW = source.width
        val srcH = source.height

        try {
            // Validate before native allocation and return a typed failure.
            validateExecutionPlan(srcW, srcH, model, targetScale)
            require(srcW > 0 && srcH > 0)
            val plannedW = srcW.toLong() * targetScale
            val plannedH = srcH.toLong() * targetScale
            if (plannedW > UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION ||
                plannedH > UpscaleResourceEstimator.MAX_OUTPUT_DIMENSION ||
                plannedW * plannedH > UpscaleResourceEstimator.MAX_SAFE_TOTAL_PIXELS) {
                throw ResourceLimitExceededException("Output dimensions exceed safe device limits")
            }
            val detail = if (params.detailEnhancement) params.detailStrength else 0f
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

                    if (scalePlan.requiresRefinement || detail > .01f) {
                        val streamed = aiPassOutput as? UpscaleOutput.Streamed
                            ?: throw UpscaleMemoryException("Expected streamed native inference output")
                        try {
                            listener?.onStatus("Finishing full-resolution output…")
                            UriRegionSource(context, android.net.Uri.fromFile(streamed.file), streamed.width, streamed.height).use { region ->
                                StreamingImagePostProcessor.write(
                                    context, region, scalePlan.refinementScale,
                                    "lanczos", detail, listener
                                )
                            }
                        } finally {
                            streamed.preview?.recycle()
                            streamed.file.delete()
                        }
                    } else aiPassOutput
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
                        StreamingImagePostProcessor.write(context, source, targetScale, model.id, detail, listener)
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
                                "bicubic" -> AlgorithmicUpscaler.scaleBicubic(fullSource, targetW, targetH, checkpoint)
                                else -> AlgorithmicUpscaler.scaleLanczos(fullSource, targetW, targetH, checkpoint)
                            }
                            var handedOff = false
                            var output = res
                            try {
                                res.setHasAlpha(source.hasAlpha)
                                if (detail > .01f) output = AdaptiveDetailEnhancer.enhanceScanlines(res, detail, checkpoint)
                                checkpoint()
                                listener?.onProgress(1, 1, 100)
                                listener?.onStage("Complete", 1, 1)
                                handedOff = true
                                UpscaleOutput.InMemory(output)
                            } finally {
                                if (output !== res) res.recycle()
                                if (!handedOff) output.recycle()
                            }
                        } finally {
                            if (ownsFullSource && !fullSource.isRecycled) {
                                fullSource.recycle()
                            }
                        }
                    }
                }
            }

            try {
                ensureActive()
            } catch (failure: Throwable) {
                resultOutput.discardUnclaimed()
                throw failure
            }
            val currentBackend = when (model.type) {
                ModelType.AI_NCNN -> "NCNN"
                ModelType.ALGORITHMIC -> "ALGORITHMIC"
            }

            // Telemetry must not prevent delivery of a valid owned output.
            runCatching { com.veilframe.app.cv.core.CvTelemetry.record(
                linkedMapOf(
                    "ts" to System.currentTimeMillis(), "job" to "upscale",
                    "model" to model.id, "scale" to targetScale,
                    "backend" to currentBackend,
                    "chunk" to params.chunkSize, "workers" to params.parallelWorkers,
                    "srcW" to srcW, "srcH" to srcH,
                    "ms" to (System.currentTimeMillis() - t0), "outcome" to "ok",
                    "streamed" to (resultOutput is UpscaleOutput.Streamed)
                )
            ) }
            Result.success(resultOutput)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Upscale failed: ${e.message}", e)
            val currentBackend = when (model.type) {
                ModelType.AI_NCNN -> "NCNN"
                ModelType.ALGORITHMIC -> "ALGORITHMIC"
            }
            // Failure reporting must survive telemetry disk/provider errors.
            runCatching { com.veilframe.app.cv.core.CvTelemetry.record(
                linkedMapOf(
                    "ts" to System.currentTimeMillis(), "job" to "upscale",
                    "model" to model.id, "scale" to targetScale,
                    "backend" to currentBackend,
                    "chunk" to params.chunkSize, "workers" to params.parallelWorkers,
                    "srcW" to srcW, "srcH" to srcH,
                    "ms" to (System.currentTimeMillis() - t0), "outcome" to "err",
                    "error" to (e.message ?: e.javaClass.simpleName)
                )
            ) }
            Result.failure(e)
        }
    }

}
