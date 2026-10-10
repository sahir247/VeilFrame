package com.veilframe.app.cv.segmentation.rembg

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 1:1 Port of Cel-Android BackgroundRemover ML pipeline:
 * - Decodes image with EXIF orientation correction
 * - Runs ONNX neural segmentation (BiRefNet Lite, ISNet General, or U2Net Human)
 * - Reconstructs full-resolution image with alpha channel via memory-safe striped processing
 * - Applies edge post-processing: tighten edges (1px erosion), feather edges (1px blur), trim transparent
 * - Handles NNAPI/delegate errors with graceful CPU fallback and retry
 */
class CelBackgroundRemover(
    private val sessionManager: OnnxSessionManager,
) {
    private val ortEnv = OrtEnvironment.getEnvironment()

    suspend fun removeBackground(
        imageBytes: ByteArray,
        model: RembgModel,
        trim: Boolean = false,
        tightenEdges: Boolean = false,
        featherEdges: Boolean = false,
        onProgress: suspend (RembgProgress) -> Unit = {},
    ): RembgResult = withContext(Dispatchers.Default) {
        ensureActive()
        val sourceInfo = analyzeSource(imageBytes)
        val warnings = sourceInfo.warnings.toMutableList()
        val pixels = sourceInfo.width.toLong() * sourceInfo.height

        if (pixels > LARGE_IMAGE_PIXELS) {
            warnings += "Large image (${sourceInfo.width}×${sourceInfo.height}) — processing at full resolution."
        }

        onProgress(RembgProgress(10, "Loading model…"))
        ensureActive()
        val session = loadSession(model)
        val inputName = session.inputNames.iterator().next()

        onProgress(RembgProgress(25, "Preparing image tensor…"))
        ensureActive()
        val inferenceBitmap = decodeForInference(imageBytes, model)

        try {
            onProgress(RembgProgress(45, "Running neural segmentation…"))
            ensureActive()

            var maskedSmall = runInference(session, inputName, inferenceBitmap, model, onProgress)

            onProgress(RembgProgress(85, "Building full resolution…"))
            ensureActive()

            var resultBitmap = mergeToFullResolution(imageBytes, sourceInfo, maskedSmall)
            if (maskedSmall !== resultBitmap) {
                maskedSmall.recycle()
            }

            onProgress(RembgProgress(95, "Refining edges…"))
            resultBitmap = applyEdgePostProcessing(resultBitmap, tightenEdges, featherEdges, trim)

            onProgress(RembgProgress(100, "Done"))
            RembgResult(resultBitmap, sourceInfo, model, warnings)
        } catch (e: Exception) {
            sessionManager.closeSession(model)
            if (isDelegateFailure(e)) {
                val retrySession = sessionManager.recreateSession(model)
                val retryInput = retrySession.inputNames.iterator().next()
                try {
                    var maskedSmall = runInference(retrySession, retryInput, inferenceBitmap, model, onProgress)
                    onProgress(RembgProgress(85, "Building full resolution…"))
                    var resultBitmap = mergeToFullResolution(imageBytes, sourceInfo, maskedSmall)
                    if (maskedSmall !== resultBitmap) maskedSmall.recycle()
                    resultBitmap = applyEdgePostProcessing(resultBitmap, tightenEdges, featherEdges, trim)
                    onProgress(RembgProgress(100, "Done"))
                    return@withContext RembgResult(resultBitmap, sourceInfo, model, warnings)
                } catch (retryError: Exception) {
                    throw friendlyProcessingError(model, retryError)
                }
            }
            throw friendlyProcessingError(model, e)
        } catch (e: OutOfMemoryError) {
            sessionManager.closeAll()
            throw IllegalStateException(
                "Not enough memory for this image with ${model.displayName}. Try a smaller image or another model.",
                e,
            )
        } finally {
            inferenceBitmap.recycle()
        }
    }

    private fun applyEdgePostProcessing(
        bitmap: Bitmap,
        tightenEdges: Boolean,
        featherEdges: Boolean,
        trim: Boolean,
    ): Bitmap {
        var resultBitmap = bitmap
        if (tightenEdges) {
            val tightened = MaskPostprocessor.tightenEdges(resultBitmap, radius = 1)
            if (tightened !== resultBitmap) {
                resultBitmap.recycle()
                resultBitmap = tightened
            }
        }
        if (featherEdges) {
            val feathered = MaskPostprocessor.featherEdges(resultBitmap, radius = 1)
            if (feathered !== resultBitmap) {
                resultBitmap.recycle()
                resultBitmap = feathered
            }
        }
        if (trim) {
            val trimmed = MaskPostprocessor.trimTransparent(resultBitmap)
            if (trimmed !== resultBitmap) {
                resultBitmap.recycle()
                resultBitmap = trimmed
            }
        }
        return resultBitmap
    }

    private fun loadSession(model: RembgModel): ai.onnxruntime.OrtSession {
        return try {
            sessionManager.getSession(model)
        } catch (e: Throwable) {
            throw IllegalStateException(
                "Could not load ${model.displayName}. Check Model Manager to ensure the model is downloaded.",
                e,
            )
        }
    }

    private fun decodeForInference(imageBytes: ByteArray, model: RembgModel): Bitmap {
        val target = max(model.inputWidth, model.inputHeight)
        val decoded = RembgImageUtils.decodeOrientedBitmap(imageBytes, target)
        val fitted = RembgImageUtils.scaleToFit(decoded, model.inputWidth, model.inputHeight)
        if (fitted !== decoded) decoded.recycle()
        return fitted
    }

    private fun mergeToFullResolution(
        imageBytes: ByteArray,
        sourceInfo: RembgSourceInfo,
        maskedSmall: Bitmap,
    ): Bitmap {
        if (maskedSmall.width == sourceInfo.width && maskedSmall.height == sourceInfo.height) {
            return maskedSmall
        }
        val sourceBitmap = RembgImageUtils.decodeOrientedBitmap(imageBytes)
        try {
            val upscaledAlpha = Bitmap.createScaledBitmap(
                maskedSmall,
                sourceBitmap.width,
                sourceBitmap.height,
                true,
            )
            val fullRes = applyFullResolutionColors(sourceBitmap, upscaledAlpha)
            if (upscaledAlpha !== maskedSmall) {
                upscaledAlpha.recycle()
            }
            return fullRes
        } finally {
            sourceBitmap.recycle()
        }
    }

    private suspend fun runInference(
        session: ai.onnxruntime.OrtSession,
        inputName: String,
        bitmap: Bitmap,
        model: RembgModel,
        onProgress: suspend (RembgProgress) -> Unit,
    ): Bitmap {
        onProgress(RembgProgress(50, "Running ${model.displayName}…"))
        val tensorData = ImagePreprocessor.prepareInput(bitmap, model)
        val inputShape = longArrayOf(1, 3, model.inputHeight.toLong(), model.inputWidth.toLong())

        val (mask, maskW, maskH) = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(tensorData), inputShape)
            .use { inputTensor ->
                session.run(mapOf(inputName to inputTensor)).use { results ->
                    MaskPostprocessor.parseMaskFromValue(results[0])
                }
            }

        sessionManager.closeSession(model)
        onProgress(RembgProgress(75, "Extracting alpha mask…"))
        return MaskPostprocessor.applyMask(bitmap, mask, maskW, maskH, model.needsSigmoidOutput)
    }

    private fun isDelegateFailure(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return message.contains("aneuralnetworks") ||
            message.contains("nnapi") ||
            message.contains("ort_fail")
    }

    private fun friendlyProcessingError(model: RembgModel, error: Throwable): IllegalStateException {
        val detail = error.message?.take(120)
        return IllegalStateException(
            "Couldn't process this image with ${model.displayName}. " +
                (detail?.let { " ($it)" } ?: ""),
            error,
        )
    }

    fun analyzeSource(imageBytes: ByteArray): RembgSourceInfo {
        val (width, height) = RembgImageUtils.orientedDimensions(imageBytes)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        val warnings = mutableListOf<String>()
        if (imageBytes.size < LOW_RES_FILE_SIZE_BYTES) {
            warnings += "Low resolution image — quality depends on original clarity."
        }
        if (width < LOW_RES_DIMENSION_PX || height < LOW_RES_DIMENSION_PX) {
            warnings += "Image dimensions (${width}×${height}) are below ${LOW_RES_DIMENSION_PX}px."
        }
        return RembgSourceInfo(
            width = width,
            height = height,
            format = options.outMimeType ?: "unknown",
            fileSize = imageBytes.size.toLong(),
            warnings = warnings,
        )
    }

    companion object {
        private const val LOW_RES_FILE_SIZE_BYTES = 100 * 1024
        private const val LOW_RES_DIMENSION_PX = 800
        private const val LARGE_IMAGE_PIXELS = 3_000_000L

        internal fun applyFullResolutionColors(source: Bitmap, alphaSource: Bitmap): Bitmap {
            val w = source.width
            val h = source.height
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val stripeRows = 256
            val rgb = IntArray(w * stripeRows)
            val alpha = IntArray(w * stripeRows)

            var y = 0
            while (y < h) {
                val rows = Math.min(stripeRows, h - y)
                val count = w * rows
                source.getPixels(rgb, 0, w, 0, y, w, rows)
                alphaSource.getPixels(alpha, 0, w, 0, y, w, rows)
                for (i in 0 until count) {
                    rgb[i] = (rgb[i] and 0x00FFFFFF) or (alpha[i] and 0xFF000000.toInt())
                }
                output.setPixels(rgb, 0, w, 0, y, w, rows)
                y += rows
            }
            return output
        }
    }
}
