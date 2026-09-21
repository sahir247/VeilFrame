package com.veilframe.app.media.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.veilframe.app.media.CropSpec
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.watermark.WatermarkEngine

/**
 * Authoritative non-destructive image transformation engine.
 * Deterministic pipeline:
 * Crop -> Rotate/Flip -> Resize -> Background Fill -> Color Filter -> Watermark
 */
object ImageTransformEngine {
    private const val TAG = "ImageTransformEngine"

    fun calculateTransformPlan(
        origFullW: Int,
        origFullH: Int,
        state: ImageEditState,
        outputConfig: com.veilframe.app.media.ImageOutputConfig
    ): ImageTransformPlan {
        val isPassport = state.cropAspect.contains("Passport", ignoreCase = true)
        val cropRect: CropSpec? = if (isPassport) {
            val minDim = minOf(origFullW, origFullH).toFloat()
            val left = ((origFullW - minDim) / 2f) / origFullW.toFloat().coerceAtLeast(1f)
            val top = ((origFullH - minDim) / 2f) / origFullH.toFloat().coerceAtLeast(1f)
            CropSpec(left, top, 1f - left, 1f - top)
        } else if (state.isCropped()) {
            if (state.cropLeft > 0.001f || state.cropTop > 0.001f || state.cropRight < 0.999f || state.cropBottom < 0.999f) {
                CropSpec(state.cropLeft, state.cropTop, state.cropRight, state.cropBottom)
            } else {
                val ratio = when (state.cropAspect) {
                    "1:1" -> 1.0f
                    "4:3" -> 4f / 3f
                    "3:4" -> 3f / 4f
                    "16:9" -> 16f / 9f
                    "9:16" -> 9f / 16f
                    else -> null
                }
                if (ratio != null) {
                    var targetW = origFullW.toFloat()
                    var targetH = origFullW / ratio
                    if (targetH > origFullH) {
                        targetH = origFullH.toFloat()
                        targetW = origFullH * ratio
                    }
                    val left = ((origFullW - targetW) / 2f) / origFullW.toFloat().coerceAtLeast(1f)
                    val top = ((origFullH - targetH) / 2f) / origFullH.toFloat().coerceAtLeast(1f)
                    CropSpec(left, top, 1f - left, 1f - top)
                } else null
            }
        } else null

        val targetW: Int
        val targetH: Int
        if (isPassport) {
            targetW = 600
            targetH = 600
        } else if (state.resizeWidth > 0 && state.resizeHeight > 0) {
            targetW = state.resizeWidth.coerceIn(16, 16384)
            targetH = state.resizeHeight.coerceIn(16, 16384)
        } else if (state.resizeScale != 100) {
            val baseW = if (cropRect != null) (origFullW * cropRect.width).toInt() else origFullW
            val baseH = if (cropRect != null) (origFullH * cropRect.height).toInt() else origFullH
            targetW = ((baseW * state.resizeScale) / 100).coerceIn(16, 16384)
            targetH = ((baseH * state.resizeScale) / 100).coerceIn(16, 16384)
        } else {
            targetW = if (cropRect != null) (origFullW * cropRect.width).toInt().coerceAtLeast(16) else origFullW
            targetH = if (cropRect != null) (origFullH * cropRect.height).toInt().coerceAtLeast(16) else origFullH
        }

        return ImageTransformPlan(
            sourceWidth = origFullW,
            sourceHeight = origFullH,
            cropRect = cropRect,
            rotationDegrees = state.rotationAngle,
            flipH = state.flipH,
            flipV = state.flipV,
            targetWidth = targetW,
            targetHeight = targetH,
            outputFormat = if (isPassport) "JPG" else outputConfig.format,
            quality = outputConfig.quality,
            orientationPolicy = OrientationPolicy.NORMALIZE_EXIF,
            metadataPolicy = if (state.stripExif) MetadataPolicy.STRIP_ALL else MetadataPolicy.CUSTOM_EXIF
        )
    }

    fun transform(
        src: Bitmap,
        state: ImageEditState,
        origFullW: Int = src.width,
        origFullH: Int = src.height,
        useFullRes: Boolean = false
    ): Bitmap {
        var result = src

        // 1. Crop
        if (state.isCropped()) {
            val cropL = (state.cropLeft * result.width).toInt().coerceIn(0, result.width - 1)
            val cropT = (state.cropTop * result.height).toInt().coerceIn(0, result.height - 1)
            val cropR = (state.cropRight * result.width).toInt().coerceIn(cropL + 1, result.width)
            val cropB = (state.cropBottom * result.height).toInt().coerceIn(cropT + 1, result.height)
            try {
                result = Bitmap.createBitmap(result, cropL, cropT, cropR - cropL, cropB - cropT)
            } catch (e: Exception) {
                Log.w(TAG, "Crop error: ${e.message}")
            }
        } else if (state.cropAspect != "Free" && state.cropAspect != "Original") {
            val ratio = when {
                state.cropAspect == "1:1" || state.cropAspect.contains("Passport", ignoreCase = true) -> 1.0f
                state.cropAspect == "4:3" -> 4f / 3f
                state.cropAspect == "3:4" -> 3f / 4f
                state.cropAspect == "16:9" -> 16f / 9f
                state.cropAspect == "9:16" -> 9f / 16f
                else -> null
            }
            if (ratio != null) {
                val origW = result.width.toFloat()
                val origH = result.height.toFloat()
                var targetW = origW
                var targetH = origW / ratio
                if (targetH > origH) {
                    targetH = origH
                    targetW = origH * ratio
                }
                val left = ((origW - targetW) / 2f).toInt().coerceAtLeast(0)
                val top = ((origH - targetH) / 2f).toInt().coerceAtLeast(0)
                val w = targetW.toInt().coerceIn(1, result.width - left)
                val h = targetH.toInt().coerceIn(1, result.height - top)
                try {
                    result = Bitmap.createBitmap(result, left, top, w, h)
                } catch (e: Exception) {
                    Log.w(TAG, "Aspect crop error: ${e.message}")
                }
            }
        }

        // 2. Rotate & Flip
        if (state.rotationAngle != 0f || state.flipH || state.flipV) {
            try {
                val matrix = Matrix()
                if (state.rotationAngle != 0f) {
                    matrix.postRotate(state.rotationAngle)
                }
                if (state.flipH || state.flipV) {
                    val sx = if (state.flipH) -1f else 1f
                    val sy = if (state.flipV) -1f else 1f
                    matrix.postScale(sx, sy)
                }
                result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
            } catch (e: Exception) {
                Log.w(TAG, "Rotate/Flip error: ${e.message}")
            }
        }

        // 3. Resize
        val targetWidth: Int
        val targetHeight: Int
        if (state.cropAspect.contains("Passport", ignoreCase = true) && state.resizeWidth <= 0 && state.resizeScale == 100) {
            targetWidth = if (useFullRes) 600 else (600f * (result.width.toFloat() / origFullW.toFloat().coerceAtLeast(1f))).toInt().coerceIn(16, 600)
            targetHeight = targetWidth
        } else if (state.resizeWidth > 0 && state.resizeHeight > 0) {
            if (useFullRes) {
                targetWidth = state.resizeWidth.coerceIn(16, 16384)
                targetHeight = state.resizeHeight.coerceIn(16, 16384)
            } else {
                val ratioW = result.width.toFloat() / origFullW.toFloat().coerceAtLeast(1f)
                val ratioH = result.height.toFloat() / origFullH.toFloat().coerceAtLeast(1f)
                targetWidth = (state.resizeWidth * ratioW).toInt().coerceIn(16, 16384)
                targetHeight = (state.resizeHeight * ratioH).toInt().coerceIn(16, 16384)
            }
        } else if (state.resizeScale != 100) {
            targetWidth = ((result.width * state.resizeScale) / 100).coerceIn(16, 16384)
            targetHeight = ((result.height * state.resizeScale) / 100).coerceIn(16, 16384)
        } else {
            targetWidth = result.width.coerceAtLeast(16)
            targetHeight = result.height.coerceAtLeast(16)
        }
        if (targetWidth != result.width || targetHeight != result.height) {
            try {
                result = Bitmap.createScaledBitmap(result, targetWidth, targetHeight, true)
            } catch (t: Throwable) {
                Log.w(TAG, "createScaledBitmap error: ${t.message}", t)
                System.gc()
            }
        }

        // 4. Background Fill (for alpha transparency)
        if (state.bgType != "Transparent") {
            try {
                val bgBmp = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bgBmp)
                val bgPaint = Paint().apply {
                    color = if (state.bgType.contains("White", ignoreCase = true)) Color.WHITE else Color.BLACK
                    style = Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, result.width.toFloat(), result.height.toFloat(), bgPaint)
                canvas.drawBitmap(result, 0f, 0f, null)
                result = bgBmp
            } catch (e: Exception) {
                Log.w(TAG, "Background fill error: ${e.message}")
            }
        }

        // 5. Color filter
        if (state.filter != "Default" && state.filter != "None") {
            try {
                val filteredBmp = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(filteredBmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                val colorMatrix = ColorMatrix()
                when (state.filter) {
                    "Grayscale" -> colorMatrix.setSaturation(0f)
                    "Sepia" -> {
                        val sepia = ColorMatrix(
                            floatArrayOf(
                                0.393f, 0.769f, 0.189f, 0f, 0f,
                                0.349f, 0.686f, 0.168f, 0f, 0f,
                                0.272f, 0.534f, 0.131f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorMatrix.postConcat(sepia)
                    }
                    "Cool" -> {
                        val cool = ColorMatrix(
                            floatArrayOf(
                                0.8f, 0f, 0f, 0f, 0f,
                                0f, 0.9f, 0f, 0f, 0f,
                                0f, 0f, 1.2f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorMatrix.postConcat(cool)
                    }
                    "Warm" -> {
                        val warm = ColorMatrix(
                            floatArrayOf(
                                1.2f, 0f, 0f, 0f, 0f,
                                0f, 1.0f, 0f, 0f, 0f,
                                0f, 0f, 0.8f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorMatrix.postConcat(warm)
                    }
                    "Vintage" -> {
                        val vintage = ColorMatrix(
                            floatArrayOf(
                                0.9f, 0f, 0f, 0f, 10f,
                                0f, 0.8f, 0f, 0f, 10f,
                                0f, 0.6f, 0f, 0f, 20f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                        colorMatrix.postConcat(vintage)
                    }
                }
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
                canvas.drawBitmap(result, 0f, 0f, paint)
                result = filteredBmp
            } catch (e: Exception) {
                Log.w(TAG, "Color filter error: ${e.message}")
            }
        }

        // 6. Text Watermark Overlay via WatermarkEngine
        if (state.watermarkText.isNotBlank()) {
            result = WatermarkEngine.applyWatermark(result, state)
        }

        return result
    }
}
