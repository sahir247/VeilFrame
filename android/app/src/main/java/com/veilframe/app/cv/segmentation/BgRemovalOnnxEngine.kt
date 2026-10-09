package com.veilframe.app.cv.segmentation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import com.veilframe.app.cv.core.CvRuntime
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer

/**
 * Metadata for letterbox-preprocessed images:
 * tracks scaling factor, border padding, original image dimensions, and target model size.
 */
data class LetterboxMeta(
    val scale: Float,
    val padX: Int,
    val padY: Int,
    val origW: Int,
    val origH: Int,
    val targetSize: Int
) {
    val roiWidth: Int get() = targetSize - 2 * padX
    val roiHeight: Int get() = targetSize - 2 * padY
}

/**
 * Normalization scheme for neural vision inputs.
 */
enum class NormalizationMode {
    /** Maps [0..255] to [0.0..1.0]. Standard for MODNet and general vision networks. */
    ZERO_TO_ONE,

    /** Maps [0..255] to [-1.0..1.0] via `(x / 255 - 0.5) / 0.5`. Standard for RMBG-1.4. */
    IMAGE_NET_CENTER
}

/**
 * BgRemovalOnnxEngine — High-performance SOTA neural alpha matting & background removal engine.
 * Supports BRIA RMBG-1.4 ([DEFAULT_TARGET_SIZE]=1024) and MODNet ([MODNET_TARGET_SIZE]=512) architectures.
 *
 * Implements:
 * 1. Zero-allocation planar FloatBuffer tensor pipeline (NCHW format).
 * 2. Aspect-preserving Letterbox preprocessing with neutral padding.
 * 3. Fast unletterbox ROI extraction, anti-aliased edge smoothing, and alpha merging.
 * 4. Drop-in integration with [BackgroundRemover.ForegroundSegmenter] SPI and [MaskOps].
 */
class BgRemovalOnnxEngine(
    private val ortSession: OrtSession,
    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment(),
    val targetSize: Int = DEFAULT_TARGET_SIZE,
    val normMode: NormalizationMode = NormalizationMode.IMAGE_NET_CENTER
) : AutoCloseable {

    companion object {
        const val DEFAULT_ASSET_NAME = "bg_removal_rmbg.onnx"
        const val MODNET_ASSET_NAME = "bg_removal_modnet.onnx"
        const val DEFAULT_TARGET_SIZE = 1024
        const val MODNET_TARGET_SIZE = 512
        const val DEFAULT_INPUT_NAME = "input"

        /**
         * Calculates aspect-ratio preserving letterbox dimensions and centering padding.
         * Pure mathematical evaluator.
         */
        fun calculateLetterboxMeta(
            srcW: Int,
            srcH: Int,
            targetSize: Int = DEFAULT_TARGET_SIZE
        ): LetterboxMeta {
            require(srcW > 0 && srcH > 0) { "Dimensions must be positive (got ${srcW}x$srcH)" }
            val scale = minOf(targetSize.toFloat() / srcW, targetSize.toFloat() / srcH)
            val newW = (srcW * scale).toInt().coerceIn(1, targetSize)
            val newH = (srcH * scale).toInt().coerceIn(1, targetSize)
            val padX = (targetSize - newW) / 2
            val padY = (targetSize - newH) / 2
            return LetterboxMeta(scale, padX, padY, srcW, srcH, targetSize)
        }

        /**
         * Letterbox-preprocesses a [Bitmap] into a [targetSize]x[targetSize] canvas
         * with neutral gray (128, 128, 128) padding.
         */
        fun letterboxPreprocess(
            bitmap: Bitmap,
            targetSize: Int = DEFAULT_TARGET_SIZE
        ): Pair<Bitmap, LetterboxMeta> {
            val meta = calculateLetterboxMeta(bitmap.width, bitmap.height, targetSize)
            val padded = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            padded.eraseColor(Color.rgb(128, 128, 128))

            val newW = (bitmap.width * meta.scale).toInt()
            val newH = (bitmap.height * meta.scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

            val scaledPixels = IntArray(newW * newH)
            scaled.getPixels(scaledPixels, 0, newW, 0, 0, newW, newH)
            padded.setPixels(scaledPixels, 0, newW, meta.padX, meta.padY, newW, newH)
            if (scaled != bitmap) {
                scaled.recycle()
            }
            return Pair(padded, meta)
        }

        /**
         * Letterbox-preprocesses an OpenCV [Mat] into a [targetSize]x[targetSize] canvas
         * with neutral border padding. Safely gated through [CvRuntime.isNativeAvailable].
         */
        fun letterboxPreprocessMat(
            srcMat: Mat,
            targetSize: Int = DEFAULT_TARGET_SIZE
        ): Pair<Mat, LetterboxMeta> {
            if (!CvRuntime.isNativeAvailable) {
                throw IllegalStateException("OpenCV native runtime is unavailable")
            }
            val meta = calculateLetterboxMeta(srcMat.cols(), srcMat.rows(), targetSize)
            val newW = (srcMat.cols() * meta.scale).toInt()
            val newH = (srcMat.rows() * meta.scale).toInt()

            val resized = Mat()
            Imgproc.resize(srcMat, resized, Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

            val padded = Mat()
            val top = meta.padY
            val bottom = targetSize - newH - meta.padY
            val left = meta.padX
            val right = targetSize - newW - meta.padX

            Core.copyMakeBorder(
                resized,
                padded,
                top,
                bottom,
                left,
                right,
                Core.BORDER_CONSTANT,
                Scalar(128.0, 128.0, 128.0)
            )
            resized.release()
            return Pair(padded, meta)
        }

        /**
         * Unletterboxes a [targetSize]x[targetSize] float mask back to original [meta.origW]x[meta.origH]
         * dimensions using pure bilinear interpolation without external dependencies.
         */
        fun unletterboxMask(
            letterboxMask: FloatArray,
            meta: LetterboxMeta
        ): FloatArray {
            val targetSize = meta.targetSize
            val origW = meta.origW
            val origH = meta.origH
            val padX = meta.padX
            val padY = meta.padY
            val roiW = meta.roiWidth
            val roiH = meta.roiHeight

            val out = FloatArray(origW * origH)
            if (roiW <= 0 || roiH <= 0 || origW <= 0 || origH <= 0) return out

            val scaleX = roiW.toFloat() / origW
            val scaleY = roiH.toFloat() / origH

            for (y in 0 until origH) {
                val srcY = (padY + y * scaleY).coerceIn(padY.toFloat(), (padY + roiH - 1).toFloat())
                val y0 = srcY.toInt().coerceIn(0, targetSize - 1)
                val y1 = (y0 + 1).coerceIn(0, targetSize - 1)
                val dy = srcY - y0

                val rowOffset = y * origW
                val y0Offset = y0 * targetSize
                val y1Offset = y1 * targetSize

                for (x in 0 until origW) {
                    val srcX = (padX + x * scaleX).coerceIn(padX.toFloat(), (padX + roiW - 1).toFloat())
                    val x0 = srcX.toInt().coerceIn(0, targetSize - 1)
                    val x1 = (x0 + 1).coerceIn(0, targetSize - 1)
                    val dx = srcX - x0

                    val p00 = letterboxMask[y0Offset + x0]
                    val p01 = letterboxMask[y0Offset + x1]
                    val p10 = letterboxMask[y1Offset + x0]
                    val p11 = letterboxMask[y1Offset + x1]

                    val top = p00 + dx * (p01 - p00)
                    val bottom = p10 + dx * (p11 - p10)
                    val value = top + dy * (bottom - top)

                    out[rowOffset + x] = value.coerceIn(0f, 1f)
                }
            }
            return out
        }

        /**
         * Unletterboxes an OpenCV [maskMat] by cropping padding and resizing back
         * to original dimensions. Safely gated through [CvRuntime.isNativeAvailable].
         */
        fun unletterboxMaskMat(maskMat: Mat, meta: LetterboxMeta): Mat {
            if (!CvRuntime.isNativeAvailable) {
                throw IllegalStateException("OpenCV native runtime is unavailable")
            }
            val roi = Rect(meta.padX, meta.padY, meta.roiWidth, meta.roiHeight)
            val cropped = Mat(maskMat, roi)
            val finalMask = Mat()
            Imgproc.resize(
                cropped,
                finalMask,
                Size(meta.origW.toDouble(), meta.origH.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_LINEAR
            )
            cropped.release()
            return finalMask
        }

        /**
         * Merges a letterboxed float mask directly into the original [Bitmap] as its Alpha channel.
         * Pure JVM/Robolectric testable.
         */
        fun applyAlphaMatte(
            originalBitmap: Bitmap,
            letterboxMask: FloatArray,
            meta: LetterboxMeta
        ): Bitmap {
            val width = originalBitmap.width
            val height = originalBitmap.height
            val unletterboxed = unletterboxMask(letterboxMask, meta)

            val result = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(width * height)
            result.getPixels(pixels, 0, width, 0, 0, width, height)

            for (i in pixels.indices) {
                val alphaFloat = unletterboxed[i].coerceIn(0f, 1f)
                val alpha = (alphaFloat * 255f).toInt().coerceIn(0, 255)
                val rgb = pixels[i] and 0x00FFFFFF
                pixels[i] = (alpha shl 24) or rgb
            }
            result.setPixels(pixels, 0, width, 0, 0, width, height)
            return result
        }

        /**
         * Merges an OpenCV [maskMat] onto [originalBitmap] with anti-aliasing edge smoothing.
         * Safely gated through [CvRuntime.isNativeAvailable].
         */
        fun applyAlphaMatte(
            originalBitmap: Bitmap,
            maskMat: Mat,
            meta: LetterboxMeta
        ): Bitmap {
            val unletterboxedMat = unletterboxMaskMat(maskMat, meta)
            val smoothedMat = Mat()
            Imgproc.GaussianBlur(unletterboxedMat, smoothedMat, Size(3.0, 3.0), 0.0)
            unletterboxedMat.release()

            val width = originalBitmap.width
            val height = originalBitmap.height
            val u8Mask = Mat()
            if (smoothedMat.type() != CvType.CV_8UC1) {
                smoothedMat.convertTo(u8Mask, CvType.CV_8UC1, 255.0)
            } else {
                smoothedMat.copyTo(u8Mask)
            }
            smoothedMat.release()

            val maskBytes = ByteArray(width * height)
            u8Mask.get(0, 0, maskBytes)
            u8Mask.release()

            val result = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(width * height)
            result.getPixels(pixels, 0, width, 0, 0, width, height)

            for (i in pixels.indices) {
                val alpha = maskBytes[i].toInt() and 0xFF
                val rgb = pixels[i] and 0x00FFFFFF
                pixels[i] = (alpha shl 24) or rgb
            }
            result.setPixels(pixels, 0, width, 0, 0, width, height)
            return result
        }

        /**
         * Verifies that the cutout contains transparent background pixels and opaque subject pixels.
         */
        fun verifyAlphaCutout(bitmap: Bitmap, minForegroundAlphaRatio: Float = 0.05f): Boolean {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            var transparentCount = 0
            var foregroundCount = 0

            for (pixel in pixels) {
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha < 30) {
                    transparentCount++
                } else if (alpha > 200) {
                    foregroundCount++
                }
            }

            val total = (width * height).toFloat()
            val hasTransparentBg = transparentCount > 0
            val hasForegroundSubject = (foregroundCount / total) >= minForegroundAlphaRatio
            return hasTransparentBg && hasForegroundSubject
        }

        /**
         * Decodes output tensor payload into a flat FloatArray mask of length [targetSize] * [targetSize].
         */
        @Suppress("UNCHECKED_CAST")
        fun extractMaskFromOutput(value: Any?, targetSize: Int): FloatArray {
            val totalElements = targetSize * targetSize
            val out = FloatArray(totalElements)
            if (value == null) return out

            when (value) {
                is FloatArray -> {
                    if (value.size >= totalElements) {
                        System.arraycopy(value, 0, out, 0, totalElements)
                    }
                }
                is Array<*> -> {
                    // Check dimension nesting:
                    // 4D: Array<Array<Array<FloatArray>>> [1, 1, H, W]
                    // 3D: Array<Array<FloatArray>> [1, H, W]
                    // 2D: Array<FloatArray> [H, W]
                    val first = value.firstOrNull()
                    if (first is Array<*>) {
                        val second = first.firstOrNull()
                        if (second is Array<*>) {
                            // 4D [1, 1, H, W]
                            val rows = second as Array<FloatArray>
                            for (r in 0 until minOf(rows.size, targetSize)) {
                                val row = rows[r]
                                System.arraycopy(row, 0, out, r * targetSize, minOf(row.size, targetSize))
                            }
                        } else if (second is FloatArray) {
                            // 3D [1, H, W]
                            val rows = first as Array<FloatArray>
                            for (r in 0 until minOf(rows.size, targetSize)) {
                                val row = rows[r]
                                System.arraycopy(row, 0, out, r * targetSize, minOf(row.size, targetSize))
                            }
                        }
                    } else if (first is FloatArray) {
                        // 2D [H, W]
                        val rows = value as Array<FloatArray>
                        for (r in 0 until minOf(rows.size, targetSize)) {
                            val row = rows[r]
                            System.arraycopy(row, 0, out, r * targetSize, minOf(row.size, targetSize))
                        }
                    }
                }
            }
            return out
        }

        fun createDefaultSessionOptions(): OrtSession.SessionOptions {
            val opts = OrtSession.SessionOptions()
            try {
                opts.addNnapi()
            } catch (ignored: Throwable) {
                // Fallback to CPU / XNNPACK
            }
            opts.setIntraOpNumThreads(2)
            opts.setInterOpNumThreads(1)
            return opts
        }

        fun fromAsset(
            context: Context,
            assetName: String = DEFAULT_ASSET_NAME,
            targetSize: Int = DEFAULT_TARGET_SIZE,
            normMode: NormalizationMode = NormalizationMode.IMAGE_NET_CENTER,
            ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
        ): BgRemovalOnnxEngine {
            val bytes = context.assets.open(assetName).use { it.readBytes() }
            val session = ortEnv.createSession(bytes, createDefaultSessionOptions())
            return BgRemovalOnnxEngine(session, ortEnv, targetSize, normMode)
        }

        fun fromFile(
            file: File,
            targetSize: Int = DEFAULT_TARGET_SIZE,
            normMode: NormalizationMode = NormalizationMode.IMAGE_NET_CENTER,
            ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
        ): BgRemovalOnnxEngine {
            require(file.exists() && file.length() > 0) { "Model file does not exist: ${file.absolutePath}" }
            val session = ortEnv.createSession(file.absolutePath, createDefaultSessionOptions())
            return BgRemovalOnnxEngine(session, ortEnv, targetSize, normMode)
        }

        fun fromBytes(
            bytes: ByteArray,
            targetSize: Int = DEFAULT_TARGET_SIZE,
            normMode: NormalizationMode = NormalizationMode.IMAGE_NET_CENTER,
            ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
        ): BgRemovalOnnxEngine {
            val session = ortEnv.createSession(bytes, createDefaultSessionOptions())
            return BgRemovalOnnxEngine(session, ortEnv, targetSize, normMode)
        }
    }

    val inputName: String = ortSession.inputNames.iterator().next() ?: DEFAULT_INPUT_NAME

    // Pre-allocated 1x3xHxW planar float buffer (e.g. 1024*1024*3 = 3,145,728 floats)
    private val imageTensorBuffer = FloatArray(3 * targetSize * targetSize)

    /** Secondary constructor for Android Context */
    constructor(
        context: Context,
        assetName: String = DEFAULT_ASSET_NAME,
        targetSize: Int = DEFAULT_TARGET_SIZE,
        normMode: NormalizationMode = NormalizationMode.IMAGE_NET_CENTER
    ) : this(
        ortSession = OrtEnvironment.getEnvironment().createSession(
            context.assets.open(assetName).use { it.readBytes() },
            createDefaultSessionOptions()
        ),
        targetSize = targetSize,
        normMode = normMode
    )

    /** Secondary constructor from local File */
    constructor(
        modelFile: File,
        targetSize: Int = DEFAULT_TARGET_SIZE,
        normMode: NormalizationMode = NormalizationMode.IMAGE_NET_CENTER
    ) : this(
        ortSession = OrtEnvironment.getEnvironment().createSession(
            modelFile.absolutePath,
            createDefaultSessionOptions()
        ),
        targetSize = targetSize,
        normMode = normMode
    )

    /**
     * Preprocesses a [targetSize]x[targetSize] letterboxed [Bitmap] into the pre-allocated planar float buffer.
     */
    fun preprocessToBuffer(bmp: Bitmap) {
        require(bmp.width == targetSize && bmp.height == targetSize) {
            "Bitmap must be exactly ${targetSize}x${targetSize} (got ${bmp.width}x${bmp.height})"
        }
        val pixels = IntArray(targetSize * targetSize)
        bmp.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        val planeSize = targetSize * targetSize
        val gOffset = planeSize
        val bOffset = planeSize * 2

        if (normMode == NormalizationMode.IMAGE_NET_CENTER) {
            for (i in 0 until planeSize) {
                val pixel = pixels[i]
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                imageTensorBuffer[i] = (r / 255.0f - 0.5f) / 0.5f
                imageTensorBuffer[gOffset + i] = (g / 255.0f - 0.5f) / 0.5f
                imageTensorBuffer[bOffset + i] = (b / 255.0f - 0.5f) / 0.5f
            }
        } else {
            val inv255 = 1f / 255f
            for (i in 0 until planeSize) {
                val pixel = pixels[i]
                imageTensorBuffer[i] = ((pixel ushr 16) and 0xFF) * inv255
                imageTensorBuffer[gOffset + i] = ((pixel ushr 8) and 0xFF) * inv255
                imageTensorBuffer[bOffset + i] = (pixel and 0xFF) * inv255
            }
        }
    }

    /**
     * Preprocesses a [targetSize]x[targetSize] letterboxed OpenCV [Mat] into the pre-allocated planar buffer.
     * Safely gated through [CvRuntime.isNativeAvailable].
     */
    fun preprocessMatToBuffer(mat: Mat) {
        if (!CvRuntime.isNativeAvailable) return
        require(mat.cols() == targetSize && mat.rows() == targetSize) {
            "Mat must be ${targetSize}x${targetSize} (got ${mat.cols()}x${mat.rows()})"
        }

        val rgbMat = Mat()
        val channels = mat.channels()
        when (channels) {
            4 -> Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            3 -> Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB)
            1 -> Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_GRAY2RGB)
            else -> mat.copyTo(rgbMat)
        }

        val planeSize = targetSize * targetSize
        val byteBuffer = ByteArray(planeSize * 3)
        rgbMat.get(0, 0, byteBuffer)
        rgbMat.release()

        val gOffset = planeSize
        val bOffset = planeSize * 2

        if (normMode == NormalizationMode.IMAGE_NET_CENTER) {
            for (i in 0 until planeSize) {
                val r = byteBuffer[i * 3].toInt() and 0xFF
                val g = byteBuffer[i * 3 + 1].toInt() and 0xFF
                val b = byteBuffer[i * 3 + 2].toInt() and 0xFF
                imageTensorBuffer[i] = (r / 255.0f - 0.5f) / 0.5f
                imageTensorBuffer[gOffset + i] = (g / 255.0f - 0.5f) / 0.5f
                imageTensorBuffer[bOffset + i] = (b / 255.0f - 0.5f) / 0.5f
            }
        } else {
            val inv255 = 1f / 255f
            for (i in 0 until planeSize) {
                val r = byteBuffer[i * 3].toInt() and 0xFF
                val g = byteBuffer[i * 3 + 1].toInt() and 0xFF
                val b = byteBuffer[i * 3 + 2].toInt() and 0xFF
                imageTensorBuffer[i] = r * inv255
                imageTensorBuffer[gOffset + i] = g * inv255
                imageTensorBuffer[bOffset + i] = b * inv255
            }
        }
    }

    /**
     * Executes neural inference on a [Bitmap], returning a transparent ARGB_8888 cutout.
     */
    fun runInference(bitmap: Bitmap): Bitmap? {
        val (letterboxed, meta) = letterboxPreprocess(bitmap, targetSize)
        try {
            preprocessToBuffer(letterboxed)

            val shape = longArrayOf(1, 3, targetSize.toLong(), targetSize.toLong())
            val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(imageTensorBuffer), shape)
            val outputMask: FloatArray
            try {
                val results = ortSession.run(mapOf(inputName to tensor))
                outputMask = extractMaskFromOutput(results[0]?.value, targetSize)
                results.close()
            } finally {
                tensor.close()
            }

            if (outputMask.isEmpty()) return null
            return applyAlphaMatte(bitmap, outputMask, meta)
        } finally {
            if (letterboxed != bitmap) {
                letterboxed.recycle()
            }
        }
    }

    /**
     * Executes neural foreground segmentation on an OpenCV [Mat], returning an 8-bit single-channel
     * mask (`CV_8UC1`, 255 = foreground, 0 = background) resized to match the input image.
     * Safely gated through [CvRuntime.isNativeAvailable].
     */
    fun segment(image: Mat): Mat? {
        if (!CvRuntime.isNativeAvailable) return null
        return try {
            val (letterboxedMat, meta) = letterboxPreprocessMat(image, targetSize)
            try {
                preprocessMatToBuffer(letterboxedMat)

                val shape = longArrayOf(1, 3, targetSize.toLong(), targetSize.toLong())
                val tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(imageTensorBuffer), shape)
                val outputFloatArray: FloatArray
                try {
                    val results = ortSession.run(mapOf(inputName to tensor))
                    outputFloatArray = extractMaskFromOutput(results[0]?.value, targetSize)
                    results.close()
                } finally {
                    tensor.close()
                }

                if (outputFloatArray.isEmpty()) return null

                val maskMat = Mat(targetSize, targetSize, CvType.CV_32FC1)
                maskMat.put(0, 0, outputFloatArray)

                val unletterboxed = unletterboxMaskMat(maskMat, meta)
                maskMat.release()

                val u8Mask = Mat()
                unletterboxed.convertTo(u8Mask, CvType.CV_8UC1, 255.0)
                unletterboxed.release()
                u8Mask
            } finally {
                letterboxedMat.release()
            }
        } catch (e: Exception) {
            android.util.Log.e("VeilFrame.BgRemovalOnnx", "Neural segmentation error: ${e.message}", e)
            null
        }
    }

    /**
     * Converts this ONNX engine into a [BackgroundRemover.ForegroundSegmenter] SPI instance.
     */
    fun asForegroundSegmenter(): BackgroundRemover.ForegroundSegmenter {
        return BackgroundRemover.ForegroundSegmenter { img ->
            segment(img)
        }
    }

    override fun close() {
        try {
            ortSession.close()
        } catch (ignored: Throwable) {}
    }
}
