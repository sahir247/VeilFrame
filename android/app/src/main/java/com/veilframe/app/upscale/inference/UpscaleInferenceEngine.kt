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
        listener: InferenceProgressListener?
    ): Result<Bitmap> = upscale(source, model, targetScale, UpscaleInferenceParams(), listener)

    suspend fun upscale(
        source: Bitmap,
        model: UpscaleModel,
        targetScale: Int,
        params: UpscaleInferenceParams = UpscaleInferenceParams(),
        listener: InferenceProgressListener? = null
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        val srcW = source.width
        val srcH = source.height

        // Validate execution plan: scales, dimensions, and capability constraints
        validateExecutionPlan(srcW, srcH, model, targetScale)

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

                    listener?.onStatus("Initializing neural session...")
                    listener?.onStage("Loading Model", 0, 1)

                    val session = withContext(Dispatchers.IO) {
                        OnnxSessionManager.createSession(modelFile)
                    }

                    try {
                        ensureActive()
                        val processor = AiProcessor(context)
                        val scalePlan = HybridScalePlan.create(targetScale, model.nativeScale)

                        listener?.onStatus("Running ${model.name}...")
                        listener?.onStage("Neural Inference", 0, 1)

                        val aiBitmap = processor.processImage(
                            session = session,
                            inputBitmap = source,
                            modelName = modelFile.name,
                            scaleFactor = model.nativeScale,
                            params = params,
                            onProgress = { current, total ->
                                val pct = if (total > 0) (current * 100) / total else 0
                                listener?.onProgress(current, total, pct)
                                listener?.onStage("Neural Inference", current, total)
                            },
                            onStatus = { msg ->
                                listener?.onStatus(msg)
                            }
                        )

                        if (scalePlan.requiresRefinement) {
                            listener?.onStatus("Applying Lanczos refinement (${scalePlan.refinementScale}×)...")
                            listener?.onStage("Refinement", 1, 1)
                            val refinedW = aiBitmap.width * scalePlan.refinementScale
                            val refinedH = aiBitmap.height * scalePlan.refinementScale
                            val refinedBitmap = AlgorithmicUpscaler.scaleLanczos3(aiBitmap, refinedW, refinedH)
                            if (!aiBitmap.isRecycled) {
                                aiBitmap.recycle()
                            }
                            refinedBitmap
                        } else {
                            aiBitmap
                        }
                    } finally {
                        session.close()
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
