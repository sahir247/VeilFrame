package com.veilframe.app.cv.segmentation.rembg

import android.content.Context
import android.graphics.Bitmap
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
    ): RembgResult = removeBackground(RembgImageSource.Bytes(imageBytes), model, trim, tightenEdges, featherEdges, onProgress)

    internal suspend fun removeBackground(
        imageSource: RembgImageSource,
        model: RembgModel,
        trim: Boolean = false,
        tightenEdges: Boolean = false,
        featherEdges: Boolean = false,
        onProgress: suspend (RembgProgress) -> Unit = {},
    ): RembgResult = withContext(Dispatchers.Default) {
        ensureActive()
        val sourceInfo = analyzeSource(imageSource)
        val warnings = sourceInfo.warnings.toMutableList()
        val pixels = sourceInfo.width.toLong() * sourceInfo.height

        if (pixels > LARGE_IMAGE_PIXELS) {
            warnings += "Large image (${sourceInfo.width}×${sourceInfo.height}) — processing with memory limits."
        }

        onProgress(RembgProgress(10, "Loading ${model.displayName}…"))
        ensureActive()

        val modelType = when (model) {
            RembgModel.U2NETP -> NcnnMattingNative.MODEL_TYPE_U2NETP
            RembgModel.MODNET -> NcnnMattingNative.MODEL_TYPE_MODNET
            RembgModel.COLOROS_LIFT_SHIFT -> throw IllegalArgumentException("Lift & Shift uses the LiteRT matting engine, not NCNN")
        }

        val isLoaded = NcnnMattingNative.isModelLoaded(modelType)
        if (!isLoaded) {
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
                val loadContext = kotlin.coroutines.coroutineContext
                val binFile = withContext(Dispatchers.IO) {
                    repository.verifiedModelFile(model) { loadContext.ensureActive() }
                } ?: throw IllegalStateException("Model ${model.displayName} is missing or failed checksum validation. Delete and download it again in Model Manager.")
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
        }

        onProgress(RembgProgress(30, "Decoding image…"))
        ensureActive()
        val sourceBitmap = RembgImageUtils.decodeOrientedBitmap(
            imageSource,
            maxDimension = RembgImageUtils.MAX_MATTING_DIMENSION,
            inMutable = true
        )

        var rgbaBitmap = sourceBitmap
        val cc = kotlin.coroutines.coroutineContext
        val checkpoint: () -> Unit = { cc.ensureActive() }

        var maskBitmap: Bitmap? = null
        var compositeSource: Bitmap? = null
        var cutoutBitmap: Bitmap? = null
        try {
            if (sourceBitmap.config != Bitmap.Config.ARGB_8888) {
                rgbaBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    ?: throw IllegalStateException("Cannot allocate matting input")
            }
            onProgress(RembgProgress(55, "Running neural matting…"))
            ensureActive()

            maskBitmap = MultiPersonMattingEngine.process(context, rgbaBitmap, modelType, useGpu = true)
                ?: throw IllegalStateException("Native neural matting returned null mask for ${model.displayName}")

            onProgress(RembgProgress(80, "Building cutout…"))
            ensureActive()

            val colors = RembgImageUtils.decodeCompositeSource(imageSource, sourceBitmap, sourceInfo, warnings)
            compositeSource = colors
            val originalMatte = if (imageSource is RembgImageSource.Staged &&
                !trim && !tightenEdges && !featherEdges && OriginalResolutionCutout.supports(sourceInfo) &&
                (colors.width != sourceInfo.width || colors.height != sourceInfo.height)) {
                RembgAlphaMatte.fromBitmap(maskBitmap, checkpoint)
            } else null
            cutoutBitmap = applyFullResolutionColors(colors, maskBitmap, checkpoint)
            if (originalMatte != null) warnings.add(0, "Memory-limited preview. Original-resolution PNG/JPEG is available in More options.")
            if (colors !== sourceBitmap) colors.recycle()
            compositeSource = null
            maskBitmap.recycle()
            maskBitmap = null

            onProgress(RembgProgress(90, "Refining edges…"))
            cutoutBitmap = applyEdgePostProcessing(cutoutBitmap, tightenEdges, featherEdges, trim, checkpoint)

            onProgress(RembgProgress(100, "Done"))
            ensureActive()
            val result = RembgResult(cutoutBitmap, sourceInfo, model, warnings, originalMatte)
            cutoutBitmap = null
            result
        } finally {
            cutoutBitmap?.takeUnless { it.isRecycled }?.recycle()
            compositeSource?.takeUnless { it === sourceBitmap || it.isRecycled }?.recycle()
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
        checkpoint: () -> Unit,
    ): Bitmap {
        var resultBitmap = bitmap
        try {
        if (tightenEdges) {
            val tightened = MaskPostprocessor.tightenEdges(resultBitmap, radius = 1, checkpoint = checkpoint)
            if (tightened !== resultBitmap) {
                resultBitmap.recycle()
                resultBitmap = tightened
            }
        }
        if (featherEdges) {
            val feathered = MaskPostprocessor.featherEdges(resultBitmap, radius = 1, checkpoint = checkpoint)
            if (feathered !== resultBitmap) {
                resultBitmap.recycle()
                resultBitmap = feathered
            }
        }
        if (trim) {
            val trimmed = MaskPostprocessor.trimTransparent(resultBitmap, checkpoint)
            if (trimmed !== resultBitmap) {
                resultBitmap.recycle()
                resultBitmap = trimmed
            }
        }
        return resultBitmap
        } catch (failure: Throwable) {
            if (resultBitmap !== bitmap && !resultBitmap.isRecycled) resultBitmap.recycle()
            throw failure
        }
    }

    fun analyzeSource(imageBytes: ByteArray): RembgSourceInfo = analyzeSource(RembgImageSource.Bytes(imageBytes))

    private fun analyzeSource(source: RembgImageSource): RembgSourceInfo {
        val info = RembgImageUtils.sourceInfo(source)
        val warnings = mutableListOf<String>()
        if (source.size < LOW_RES_FILE_SIZE_BYTES) {
            warnings += "Low resolution image — quality depends on original clarity."
        }
        if (info.width < LOW_RES_DIMENSION_PX || info.height < LOW_RES_DIMENSION_PX) {
            warnings += "Image dimensions (${info.width}×${info.height}) are below ${LOW_RES_DIMENSION_PX}px."
        }
        return info.copy(warnings = warnings)
    }

    companion object {
        private const val LOW_RES_FILE_SIZE_BYTES = 100 * 1024
        private const val LOW_RES_DIMENSION_PX = 800
        private const val LARGE_IMAGE_PIXELS = 3_000_000L

        internal fun applyFullResolutionColors(source: Bitmap, alphaSource: Bitmap, checkpoint: () -> Unit = {}): Bitmap {
            val w = source.width
            val h = source.height
            val mw = alphaSource.width
            val mh = alphaSource.height
            val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // Half-pixel bilinear resampling, with only three scanlines and bounded x lookup.
            val rgb = IntArray(w)
            val upper = IntArray(mw)
            val lower = IntArray(mw)
            val x0 = IntArray(w)
            val xWeight = FloatArray(w)
            for (x in 0 until w) {
                val sx = ((x + 0.5) * mw / w - 0.5).coerceIn(0.0, (mw - 1).toDouble())
                x0[x] = sx.toInt()
                xWeight[x] = (sx - x0[x]).toFloat()
            }
            try {
                var lastY0 = -1
                var lastY1 = -1
                for (y in 0 until h) {
                    checkpoint()
                    val sy = ((y + 0.5) * mh / h - 0.5).coerceIn(0.0, (mh - 1).toDouble())
                    val y0 = sy.toInt()
                    val y1 = minOf(y0 + 1, mh - 1)
                    val fy = (sy - y0).toFloat()
                    if (lastY0 != y0) {
                        alphaSource.getPixels(upper, 0, mw, 0, y0, mw, 1)
                        lastY0 = y0
                    }
                    if (lastY1 != y1) {
                        alphaSource.getPixels(lower, 0, mw, 0, y1, mw, 1)
                        lastY1 = y1
                    }
                    source.getPixels(rgb, 0, w, 0, y, w, 1)
                    for (x in 0 until w) {
                        val left = x0[x]
                        val right = minOf(left + 1, mw - 1)
                        val fx = xWeight[x]
                        val a = (upper[left] ushr 24) * (1f - fx) + (upper[right] ushr 24) * fx
                        val b = (lower[left] ushr 24) * (1f - fx) + (lower[right] ushr 24) * fx
                        val maskAlpha = (a * (1f - fy) + b * fy + 0.5f).toInt().coerceIn(0, 255)
                        // Matting must never resurrect pixels transparent in the input.
                        val alpha = ((rgb[x] ushr 24) * maskAlpha + 127) / 255
                        rgb[x] = (rgb[x] and 0x00FFFFFF) or (alpha shl 24)
                    }
                    output.setPixels(rgb, 0, w, 0, y, w, 1)
                }
                return output
            } catch (failure: Throwable) {
                output.recycle()
                throw failure
            }
        }
    }
}
