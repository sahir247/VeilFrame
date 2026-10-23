package com.veilframe.app.cv.segmentation.rembg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.veilframe.app.cv.segmentation.ncnn.NcnnMattingNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * NCNN-powered neural background removal pipeline:
 * - Decodes image with EXIF orientation correction
 * - Runs native NCNN matting (U^2-Net-P bundled in assets or MODNet on-demand) via Vulkan GPU / OpenMP
 * - Merges generated alpha mask with full-resolution original image
 * - Applies edge post-processing: tighten edges (1px erosion), feather edges (1px blur), trim transparent
 */
class CelBackgroundRemover(
    private val context: Context,
    private val repository: RembgModelRepository = RembgModelRepository(context),
    private val sessionManager: RembgSessionManager? = null,
) {
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

        onProgress(RembgProgress(10, "Loading ${model.displayName}…"))
        ensureActive()

        val modelType = if (model == RembgModel.U2NETP) {
            NcnnMattingNative.MODEL_TYPE_U2NETP
        } else {
            NcnnMattingNative.MODEL_TYPE_MODNET
        }

        val initSuccess = if (model.isBundled) {
            NcnnMattingNative.nativeInit(
                context.assets,
                "models/u2netp/${model.paramFileName}",
                "models/u2netp/${model.fileName}",
                modelType,
                true,
                true
            )
        } else {
            if (!repository.isModelReady(model)) {
                throw IllegalStateException("Model ${model.displayName} is not downloaded or incomplete. Download it in Model Manager first.")
            }
            val binFile = repository.getModelFile(model)
            val paramFile = repository.getParamFile(model)
            NcnnMattingNative.nativeInit(
                null,
                paramFile.absolutePath,
                binFile.absolutePath,
                modelType,
                true,
                false
            )
        }

        if (!initSuccess) {
            throw IllegalStateException("Failed to initialize NCNN neural matting engine for ${model.displayName}")
        }

        onProgress(RembgProgress(30, "Decoding image…"))
        ensureActive()
        val sourceBitmap = RembgImageUtils.decodeOrientedBitmap(imageBytes, inMutable = true)

        val rgbaBitmap = if (sourceBitmap.config == Bitmap.Config.ARGB_8888) {
            sourceBitmap
        } else {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        var maskBitmap: Bitmap? = null
        try {
            onProgress(RembgProgress(55, "Running neural matting…"))
            ensureActive()

            maskBitmap = NcnnMattingNative.nativeProcess(rgbaBitmap, modelType, true)
                ?: throw IllegalStateException("Native neural matting returned null mask for ${model.displayName}")

            onProgress(RembgProgress(80, "Building cutout…"))
            ensureActive()

            var cutoutBitmap = applyFullResolutionColors(sourceBitmap, maskBitmap)

            onProgress(RembgProgress(90, "Refining edges…"))
            cutoutBitmap = applyEdgePostProcessing(cutoutBitmap, tightenEdges, featherEdges, trim)

            onProgress(RembgProgress(100, "Done"))
            RembgResult(cutoutBitmap, sourceInfo, model, warnings)
        } finally {
            maskBitmap?.recycle()
            if (rgbaBitmap !== sourceBitmap) {
                rgbaBitmap.recycle()
            }
            sourceBitmap.recycle()
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
