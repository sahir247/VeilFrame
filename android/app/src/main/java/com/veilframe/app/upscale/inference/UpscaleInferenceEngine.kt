package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.upscale.model.ModelCapability
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
        listener: InferenceProgressListener? = null
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        val srcW = source.width
        val srcH = source.height

        // Validate execution plan: scales, dimensions, and capability constraints
        validateExecutionPlan(srcW, srcH, model, targetScale)

        // Check memory safety plan
        val isAiModel = (model.type == ModelType.AI_ONNX)
        val plan = UpscaleMemoryPlanner.plan(srcW, srcH, targetScale, isAiModel = isAiModel)
        if (!plan.isSafe) {
            val err = plan.warningMessage ?: "Image resolution exceeds device memory budget."
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        try {
            ensureActive()
            listener?.onStatus("Preparing processing pipeline...")
            listener?.onStage("Preparing", 0, 1)

            val resultBitmap = when (model.type) {
                ModelType.ALGORITHMIC -> {
                    listener?.onStatus("Applying ${model.name}...")
                    listener?.onProgress(0, 1, 0)
                    listener?.onStage("Rescaling", 0, 1)
                    val targetW = srcW * targetScale
                    val targetH = srcH * targetScale
                    val res = when (model.id) {
                        "nearest" -> AlgorithmicUpscaler.scaleNearest(source, targetW, targetH)
                        "bicubic" -> AlgorithmicUpscaler.scaleBicubic(source, targetW, targetH)
                        else -> AlgorithmicUpscaler.scaleLanczos3(source, targetW, targetH)
                    }
                    listener?.onProgress(1, 1, 100)
                    listener?.onStage("Complete", 1, 1)
                    res
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
                        val actualSha = com.veilframe.app.upscale.download.ModelDownloadVerifier.calculateSha256(modelFile)
                        if (!actualSha.equals(model.sha256, ignoreCase = true)) {
                            return@withContext Result.failure(
                                IllegalStateException("Model ${model.name} SHA-256 mismatch: expected ${model.sha256}, actual $actualSha")
                            )
                        }
                    }

                    val runtimeSpec = try {
                        model.toRuntimeSpec()
                    } catch (e: Exception) {
                        Log.w(TAG, "Notice parsing model runtime spec: ${e.message}")
                        null
                    }

                    listener?.onStatus("Planning adaptive execution profile...")
                    listener?.onStage("Planning execution", 0, 1)

                    val scalePlan = HybridScalePlan.create(targetScale, model.nativeScale)
                    val planner = AdaptiveExecutionPlanner()
                    val modelCaps = ModelExecutionCapabilities(
                        nativeScale = model.nativeScale,
                        supportedOutputScales = model.supportedOutputScales,
                        minSpatialSize = model.minInputDimension,
                        tileCompatible = model.tileCompatible
                    )

                    val executionProfile = planner.planExecution(
                        context = context,
                        modelFile = modelFile,
                        modelId = model.id,
                        targetScale = targetScale,
                        sourceWidth = srcW,
                        sourceHeight = srcH,
                        modelCapabilities = modelCaps,
                        modelHash = model.sha256,
                        runtimeSpec = runtimeSpec
                    )

                    listener?.onStatus("Loading ${model.name} weights...")
                    listener?.onStage("Initializing neural runtime", 0, 1)
                    val runtime = OnnxUpscaleRuntime(
                        modelFile = modelFile,
                        scale = model.nativeScale,
                        profile = executionProfile,
                        runtimeSpec = runtimeSpec
                    )

                    try {
                        ensureActive()
                        val effectiveTileSize = if (!runtime.isDynamicSpatial && runtime.expectedWidth > 0L) {
                            minOf(executionProfile.tileSize, runtime.expectedWidth.toInt())
                        } else {
                            executionProfile.tileSize
                        }
                        val tileProcessor = UpscaleTileProcessor(
                            tileSize = effectiveTileSize,
                            overlap = executionProfile.overlap
                        )

                        val scaleDesc = if (scalePlan.requiresRefinement) {
                            "${scalePlan.aiScale}× AI + ${scalePlan.refinementScale}× Lanczos refinement"
                        } else {
                            "${targetScale}× AI"
                        }
                        listener?.onStatus("Ready: ${model.name} ($scaleDesc via ${runtime.executionProvider}, ${executionProfile.workers} workers, tile: ${effectiveTileSize}px)")

                        val totalDurationMs = java.util.concurrent.atomic.AtomicLong(0L)

                        val memPlan = UpscaleMemoryPlanner.plan(
                            sourceWidth = srcW,
                            sourceHeight = srcH,
                            scale = targetScale,
                            isAiModel = true
                        )
                        val outputSink: OutputSink = when (memPlan.outputSinkMode) {
                            OutputSinkMode.TILED_SINK -> {
                                val scratchDir = File(context.cacheDir, "upscale_tiled_${System.currentTimeMillis()}")
                                TiledIntermediateSink(srcW * targetScale, srcH * targetScale, scratchDir)
                            }
                            OutputSinkMode.STREAMING_STRIP -> {
                                val scratchDir = File(context.cacheDir, "upscale_strip_${System.currentTimeMillis()}")
                                val stripH = minOf(effectiveTileSize * targetScale, srcH * targetScale)
                                StripOutputSink(
                                    targetWidth = srcW * targetScale,
                                    targetHeight = srcH * targetScale,
                                    stripHeight = stripH,
                                    scratchDir = scratchDir
                                )
                            }
                            OutputSinkMode.MEMORY_BUFFER -> {
                                BitmapOutputSink(srcW * targetScale, srcH * targetScale)
                            }
                        }

                        val aiResult = try {
                            tileProcessor.processTiles(
                                source = source,
                                scale = targetScale,
                                workers = executionProfile.workers,
                                sink = outputSink,
                                onTileInfer = { tile, row, col, index, total ->
                                    val t0 = System.currentTimeMillis()
                                    listener?.onStage("Neural inference", index + 1, total)
                                    listener?.onStatus("Tile ${index + 1} of $total: Running inference via ${runtime.executionProvider}...")

                                    val baseProcessed = runtime.runTile(tile, row, col)
                                    val elapsedMs = System.currentTimeMillis() - t0
                                    totalDurationMs.addAndGet(elapsedMs)
                                    baseProcessed
                                },
                                onTileRefine = if (scalePlan.requiresRefinement) {
                                    { baseProcessed, row, col, index, total ->
                                        listener?.onStage("Lanczos refinement", index + 1, total)
                                        val baseBmp = baseProcessed.bitmap
                                        val refinedW = baseBmp.width * scalePlan.refinementScale
                                        val refinedH = baseBmp.height * scalePlan.refinementScale
                                        val refinedBmp = AlgorithmicUpscaler.scaleLanczos3(baseBmp, refinedW, refinedH)
                                        if (!baseBmp.isRecycled) {
                                            baseBmp.recycle()
                                        }
                                        ProcessedTile(refinedBmp, targetScale)
                                    }
                                } else null,
                                onProgress = { current, total ->
                                    val pct = if (total > 0) (current * 100) / total else 0
                                    listener?.onProgress(current, total, pct)

                                    val done = current.coerceAtLeast(1)
                                    val avgMs = totalDurationMs.get() / done
                                    val remainingTiles = (total - current).coerceAtLeast(0)
                                    val etaSeconds = if (executionProfile.workers > 1) {
                                        ((remainingTiles * avgMs) / (1000L * executionProfile.workers)).coerceAtLeast(0L)
                                    } else {
                                        ((remainingTiles * avgMs) / 1000L).coerceAtLeast(0L)
                                    }

                                    val etaStr = if (current > 0 && remainingTiles > 0) {
                                        val sec = String.format(java.util.Locale.US, "%02ds", etaSeconds)
                                        " • ~$sec left"
                                    } else ""

                                    listener?.onStatus("Tile $current of $total • ${runtime.executionProvider}$etaStr")
                                }
                            )
                        } finally {
                            outputSink.close()
                        }

                        aiResult ?: throw IllegalStateException("Upscale failed: Output sink produced null bitmap")
                    } finally {
                        runtime.close()
                    }
                }
            }

            Result.success(resultBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Upscale failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
