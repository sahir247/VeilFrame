package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.upscale.model.ModelCapability
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRepository
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

                    listener?.onStatus("Loading ${model.name} weights...")
                    listener?.onStage("Initializing neural runtime", 0, 1)
                    val runtime = OnnxUpscaleRuntime(modelFile, model.nativeScale)

                    try {
                        ensureActive()
                        val effectiveTileSize = if (!runtime.isDynamicSpatial && runtime.expectedWidth > 0L) {
                            minOf(plan.tileSize, runtime.expectedWidth.toInt())
                        } else {
                            plan.tileSize
                        }
                        val tileProcessor = UpscaleTileProcessor(
                            tileSize = effectiveTileSize,
                            overlap = plan.overlap
                        )

                        val extraScale = if (targetScale > model.nativeScale) targetScale / model.nativeScale else 1
                        val scaleDesc = if (extraScale > 1) {
                            "${model.nativeScale}× AI + ${extraScale}× Lanczos refinement"
                        } else {
                            "${targetScale}× AI"
                        }
                        listener?.onStatus("Ready: ${model.name} ($scaleDesc via ${runtime.executionProvider}, tile: ${effectiveTileSize}px)")

                        // Calculate total tiles and resolve bounded parallel worker count
                        val tileCount = tileProcessor.calculateTiles(srcW, srcH).size.coerceAtLeast(1)
                        val estimatedPerWorkerBytes = (effectiveTileSize.toLong() * effectiveTileSize.toLong() * 4L * 6L) // input + output + buffers
                        val workers = WorkerPolicy.resolve(
                            backend = runtime.backendInfo.backend,
                            memoryBudgetBytes = plan.estimatedWorkingSetBytes,
                            perWorkerBytes = estimatedPerWorkerBytes,
                            tileCount = tileCount
                        )

                        var lastTileMs = 0L
                        val aiResult = tileProcessor.processTiles(
                            source = source,
                            scale = targetScale,
                            workers = workers,
                            onTileInfer = { tile, row, col, index, total ->
                                val t0 = System.currentTimeMillis()
                                listener?.onStage("Neural inference", index + 1, total)
                                listener?.onStatus("Tile ${index + 1} of $total: Running neural inference via ${runtime.executionProvider}...")

                                val baseProcessed = runtime.runTile(tile, row, col)

                                val finalProcessed = if (extraScale > 1) {
                                    listener?.onStage("Lanczos refinement", index + 1, total)
                                    val baseBmp = baseProcessed.bitmap
                                    val refinedW = baseBmp.width * extraScale
                                    val refinedH = baseBmp.height * extraScale
                                    val refinedBmp = AlgorithmicUpscaler.scaleLanczos3(baseBmp, refinedW, refinedH)
                                    baseBmp.recycle()
                                    ProcessedTile(refinedBmp, targetScale)
                                } else {
                                    baseProcessed
                                }

                                lastTileMs = System.currentTimeMillis() - t0
                                listener?.onStage("Composing", index + 1, total)
                                finalProcessed
                            },
                            onProgress = { current, total ->
                                val pct = if (total > 0) (current * 100) / total else 0
                                listener?.onProgress(current, total, pct)
                                val timeInfo = if (lastTileMs > 0) " (${lastTileMs}ms)" else ""
                                listener?.onStatus("Processed tile $current of $total$timeInfo • ${runtime.executionProvider}")
                            }
                        )

                        aiResult
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
