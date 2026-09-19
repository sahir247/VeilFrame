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
        val isAiModel = (model.type == ModelType.AI_ONNX)
        val plan = UpscaleMemoryPlanner.plan(srcW, srcH, targetScale, isAiModel = isAiModel)
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

                    listener?.onStatus("Loading ${model.name} weights...")
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
                        listener?.onStatus("Ready: ${model.name} via ${runtime.executionProvider} (tile: ${effectiveTileSize}px, scale: ${targetScale}×)")

                        var lastTileMs = 0L
                        val aiResult = tileProcessor.processTiles(
                            source = source,
                            scale = targetScale,
                            onTileInfer = { tile, row, col ->
                                val t0 = System.currentTimeMillis()
                                val baseTile = runtime.runTile(tile, row, col)
                                val finalTile = if (extraScale > 1) {
                                    val refinedW = baseTile.width * extraScale
                                    val refinedH = baseTile.height * extraScale
                                    val refined = AlgorithmicUpscaler.scaleLanczos3(baseTile, refinedW, refinedH)
                                    baseTile.recycle()
                                    refined
                                } else {
                                    baseTile
                                }
                                lastTileMs = System.currentTimeMillis() - t0
                                finalTile
                            },
                            onProgress = { current, total ->
                                val pct = if (total > 0) (current * 100) / total else 0
                                listener?.onProgress(current, total, pct)
                                val timeInfo = if (lastTileMs > 0) " (${lastTileMs}ms)" else ""
                                listener?.onStatus("Processing tile $current / $total$timeInfo • ${runtime.executionProvider}")
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
