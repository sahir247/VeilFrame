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
 * Unifies Algorithmic models and ONNX neural models behind a single execution pipeline.
 */
class UpscaleInferenceEngine(
    private val context: Context,
    private val repository: UpscaleModelRepository
) {

    companion object {
        private const val TAG = "VeilFrame.UpscaleEngine"
        private val verifiedShaCache = ConcurrentHashMap<String, String>()

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

                    if (!scalePlan.requiresRefinement) {
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
                }
                ModelType.ALGORITHMIC -> {
                    listener?.onStatus("Applying ${model.name}...")
                    listener?.onProgress(0, 1, 0)
                    listener?.onStage("Rescaling", 0, 1)
                    val fullSource = source.full()
                    val ownsFullSource = source !is BitmapSource
                    try {
                        val targetW = srcW * targetScale
                        val targetH = srcH * targetScale
                        val cc = coroutineContext
                        val checkpoint: () -> Unit = { cc.ensureActive() }
                        val res = when (model.id) {
                            "nearest" -> AlgorithmicUpscaler.scaleNearest(fullSource, targetW, targetH, checkpoint)
                            "bicubic" -> AlgorithmicUpscaler.scaleBicubic(fullSource, targetW, targetH)
                            else -> AlgorithmicUpscaler.scaleLanczos3(fullSource, targetW, targetH, checkpoint)
                        }
                        listener?.onProgress(1, 1, 100)
                        listener?.onStage("Complete", 1, 1)
                        UpscaleOutput.InMemory(res)
                    } finally {
                        if (ownsFullSource && !fullSource.isRecycled) {
                            fullSource.recycle()
                        }
                    }
                }
                ModelType.AI_ONNX -> {
                    val modelFile = repository.getModelFile(model.id)
                    if (!modelFile.exists() || modelFile.length() == 0L) {
                        return@withContext Result.failure(
                            IllegalStateException("Model ${model.name} is not installed on this device.")
                        )
                    }

                    // Cryptographic model SHA-256 verification
                    if (model.sha256.isNotEmpty()) {
                        val cacheKey = "${modelFile.absolutePath}:${modelFile.length()}:${modelFile.lastModified()}"
                        val actualSha = verifiedShaCache.getOrPut(cacheKey) {
                            com.veilframe.app.upscale.download.ModelDownloadVerifier.calculateSha256(modelFile)
                        }
                        if (!actualSha.equals(model.sha256, ignoreCase = true)) {
                            verifiedShaCache.remove(cacheKey)
                            return@withContext Result.failure(
                                IllegalStateException("Model ${model.name} SHA-256 mismatch: expected ${model.sha256}, actual $actualSha")
                            )
                        }
                    }

                    listener?.onStatus("Initializing neural session...")
                    listener?.onStage("Loading Model", 0, 1)

                    val session = withContext(Dispatchers.IO) {
                        // F7: EP ladder (NNAPI -> XNNPACK -> CPU), cached per model.
                        OnnxSessionManager.createSession(modelFile, context)
                    }

                    try {
                        ensureActive()
                        val processor = AiProcessor(context)
                        val scalePlan = HybridScalePlan.create(targetScale, model.nativeScale)

                        // F2/F4: hybrid refinement needs BOTH the AI output and the
                        // refined output in RAM — refuse honestly up front when the
                        // dynamic budget cannot hold them (suggests 4x / smaller source).
                        if (scalePlan.requiresRefinement) {
                            val budget = processor.inRamOutputBudget()
                            val aiPixels = srcW.toLong() * model.nativeScale *
                                (srcH.toLong() * model.nativeScale)
                            val aiBytes = aiPixels * 4L
                            val finalBytes = srcW.toLong() * targetScale *
                                (srcH.toLong() * targetScale) * 4L
                            // Audit fix: the scalar Lanczos pass peaks at
                            // intermediate FloatArray(aiW·refine × aiH × 4) plus
                            // src/dst IntArrays — budget that too, or the job
                            // burns minutes before dying with OOM.
                            val lanczosPeak = aiPixels * scalePlan.refinementScale * 16L +
                                aiPixels * 8L + finalBytes
                            val heap = Runtime.getRuntime().maxMemory()
                            if (aiBytes > budget || finalBytes > budget || lanczosPeak > heap * 80 / 100) {
                                return@withContext Result.failure(
                                    UpscaleMemoryException(
                                        "Hybrid ${targetScale}× refinement needs ~${finalBytes / (1024 * 1024)} MB output " +
                                            "with ~${lanczosPeak / (1024 * 1024)} MB peak working set " +
                                            "(budget ${budget / (1024 * 1024)} MB, heap ${heap / (1024 * 1024)} MB). " +
                                            "Use ${model.nativeScale}× or a smaller source."
                                    )
                                )
                            }
                        }

                        listener?.onStatus("Running ${model.name} via ${OnnxSessionManager.lastBackend}...")
                        listener?.onStage("Neural Inference", 0, 1)

                        // F4: bounded OOM retry with halved tiles, then typed failure.
                        var attemptParams = params
                        var aiOutput: UpscaleOutput? = null
                        var lastOom: Throwable? = null
                        var attempt = 0
                        while (attempt < 2 && aiOutput == null) {
                            try {
                                aiOutput = processor.processImage(
                                    session = session,
                                    source = source,
                                    modelName = modelFile.name,
                                    scaleFactor = model.nativeScale,
                                    params = attemptParams,
                                    onProgress = { current, total ->
                                        val pct = if (total > 0) (current * 100) / total else 0
                                        listener?.onProgress(current, total, pct)
                                        listener?.onStage("Neural Inference", current, total)
                                    },
                                    onStatus = { msg ->
                                        listener?.onStatus(msg)
                                    }
                                )
                            } catch (e: Throwable) {
                                if (e is OutOfMemoryError || e is UpscaleMemoryException) {
                                    lastOom = e
                                    attempt++
                                    if (attemptParams.chunkSize <= 128) break
                                    attemptParams = attemptParams.copy(
                                        chunkSize = (attemptParams.chunkSize / 2).coerceAtLeast(128),
                                        parallelWorkers = 1
                                    )
                                    Log.w(TAG, "Inference memory pressure (${e.message}); retrying with chunk=${attemptParams.chunkSize}")
                                    listener?.onStatus("Memory pressure — retrying with ${attemptParams.chunkSize}px tiles")
                                    listener?.onStage("Neural Inference (reduced tiles)", 0, 1)
                                } else {
                                    throw e
                                }
                            }
                        }
                        if (aiOutput == null) {
                            return@withContext Result.failure(
                                UpscaleMemoryException(
                                    "Neural inference exceeded this device's memory budget " +
                                        "(tiles reduced to ${attemptParams.chunkSize}px)",
                                    lastOom
                                )
                            )
                        }

                        if (scalePlan.requiresRefinement) {
                            val inMemory = aiOutput as? UpscaleOutput.InMemory
                                ?: return@withContext Result.failure(
                                    UpscaleMemoryException("Hybrid refinement requires an in-memory AI pass")
                                )
                            val aiBitmap = inMemory.bitmap
                            listener?.onStatus("Applying Lanczos refinement (${scalePlan.refinementScale}×)...")
                            listener?.onStage("Refinement", 1, 1)
                            val refinedW = aiBitmap.width * scalePlan.refinementScale
                            val refinedH = aiBitmap.height * scalePlan.refinementScale
                            val refinedBitmap = try {
                                val ccRefine = coroutineContext
                                AlgorithmicUpscaler.scaleLanczos3(aiBitmap, refinedW, refinedH) { ccRefine.ensureActive() }
                            } catch (oom: OutOfMemoryError) {
                                return@withContext Result.failure(
                                    UpscaleMemoryException("Lanczos refinement exceeded the memory budget", oom)
                                )
                            }
                            if (!aiBitmap.isRecycled) {
                                aiBitmap.recycle()
                            }
                            UpscaleOutput.InMemory(refinedBitmap)
                        } else {
                            aiOutput
                        }
                    } finally {
                        session.close()
                    }
                }
            }

            val currentBackend = when (model.type) {
                ModelType.AI_NCNN -> if (NcnnNative.getGpuCount() > 0) "NCNN_VULKAN" else "NCNN_CPU"
                ModelType.ALGORITHMIC -> "ALGORITHMIC"
                ModelType.AI_ONNX -> OnnxSessionManager.lastBackend.name
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
                ModelType.AI_ONNX -> OnnxSessionManager.lastBackend.name
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
                    AlgorithmicUpscaler.scaleBicubic(stripBmp, finalW, h * refineScale)
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
}
