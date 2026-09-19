package com.veilframe.app.upscale.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
    }

    interface InferenceProgressListener {
        fun onProgress(currentTile: Int, totalTiles: Int, percent: Int)
        fun onStatus(message: String)
    }

    suspend fun upscale(
        source: Bitmap,
        model: UpscaleModel,
        targetScale: Int,
        listener: InferenceProgressListener? = null
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        val srcW = source.width
        val srcH = source.height

        // Check memory safety plan
        val plan = UpscaleMemoryPlanner.plan(srcW, srcH, targetScale)
        if (!plan.isSafe) {
            val err = plan.warningMessage ?: "Image resolution exceeds device memory budget."
            return@withContext Result.failure(IllegalArgumentException(err))
        }

        try {
            ensureActive()
            listener?.onStatus("Preparing processing pipeline...")

            val resultBitmap = when (model.type) {
                ModelType.ALGORITHMIC -> {
                    listener?.onStatus("Applying ${model.name}...")
                    listener?.onProgress(0, 1, 0)
                    val targetW = srcW * targetScale
                    val targetH = srcH * targetScale
                    val res = when (model.id) {
                        "nearest" -> AlgorithmicUpscaler.scaleNearest(source, targetW, targetH)
                        "bicubic" -> AlgorithmicUpscaler.scaleBicubic(source, targetW, targetH)
                        else -> AlgorithmicUpscaler.scaleLanczos3(source, targetW, targetH)
                    }
                    listener?.onProgress(1, 1, 100)
                    res
                }
                ModelType.AI_ONNX -> {
                    val modelFile = repository.getModelFile(model.id)
                    if (!modelFile.exists() || modelFile.length() == 0L) {
                        return@withContext Result.failure(
                            IllegalStateException("Model ${model.name} is not installed on this device.")
                        )
                    }

                    listener?.onStatus("Loading ${model.name} neural weights...")
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

                        listener?.onStatus("Processing tiles with ${model.name}...")
                        val aiResult = tileProcessor.processTiles(
                            source = source,
                            scale = model.nativeScale,
                            onTileInfer = { tile, row, col ->
                                runtime.runTile(tile, row, col)
                            },
                            onProgress = { current, total ->
                                val pct = if (total > 0) (current * 100) / total else 0
                                listener?.onProgress(current, total, pct)
                                listener?.onStatus("Processing tile $current / $total...")
                            }
                        )

                        // If user requested a higher scale than native model (e.g. 8x with a 4x model)
                        if (targetScale > model.nativeScale) {
                            val extraScale = targetScale / model.nativeScale
                            listener?.onStatus("Applying secondary refinement (${extraScale}× Lanczos)...")
                            val extraW = aiResult.width * extraScale
                            val extraH = aiResult.height * extraScale
                            val refined = AlgorithmicUpscaler.scaleLanczos3(aiResult, extraW, extraH)
                            aiResult.recycle()
                            refined
                        } else {
                            aiResult
                        }
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
