package com.veilframe.app.upscale.inference

import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.cv.core.CvRuntime
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * AdaptiveDetailEnhancer — Edge-preserving high-frequency texture and detail restoration
 * using in-tree OpenCV 4.14.0 SDK.
 *
 * Recovers micro-textures (foliage, hair strands, fabric weave, masonry, typography) after
 * super-resolution inference without amplifying flat-field background noise or introducing halos.
 * Operates strictly on the luminance (Y) channel in YCrCb color space to preserve chromatic fidelity.
 */
object AdaptiveDetailEnhancer {

    private const val TAG = "AdaptiveDetailEnhancer"

    /**
     * Applies adaptive luminance micro-contrast enhancement to [sourceBitmap].
     *
     * @param sourceBitmap The input image bitmap (ARGB_8888).
     * @param strength Boost intensity in [0.0f..1.0f]. 0.0f = bypass, 0.35f = balanced cinematic enhancement.
     * @return A new enhanced [Bitmap], or [sourceBitmap] if enhancement is bypassed or unavailable.
     */
    fun enhance(sourceBitmap: Bitmap, strength: Float = 0.35f): Bitmap {
        if (strength <= 0.01f) {
            return sourceBitmap
        }

        // 1. First priority: Native ARM NEON SIMD in libveilframe_ncnn.so (16KB page-aligned, C++ OpenMP)
        if (NcnnNative.isAvailable) {
            val copy = if (sourceBitmap.isMutable) sourceBitmap else sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
            if (NcnnNative.adaptiveDetailEnhance(copy, strength)) {
                return copy
            }
        }

        // 2. Second priority: In-tree OpenCV fallback
        if (!CvRuntime.initialize()) {
            return sourceBitmap
        }

        val w = sourceBitmap.width
        val h = sourceBitmap.height
        if (w <= 0 || h <= 0) return sourceBitmap

        val srcMat = Mat()
        val ycrcbMat = Mat()
        val rgbMat = Mat()
        val blurY = Mat()
        val highFreq = Mat()
        val gradX = Mat()
        val gradY = Mat()
        val absGradX = Mat()
        val absGradY = Mat()
        val gradMag = Mat()
        val weightMask = Mat()
        val highFreqFloat = Mat()
        val detailBoost = Mat()
        val yFloat = Mat()
        val enhancedYFloat = Mat()
        val enhancedY = Mat()
        val channels = mutableListOf<Mat>()

        return try {
            Utils.bitmapToMat(sourceBitmap, srcMat)

            // Convert to YCrCb for perceptual luminance separation
            Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgbMat, ycrcbMat, Imgproc.COLOR_RGB2YCrCb)

            Core.split(ycrcbMat, channels)
            val yMat = channels[0]

            // 1. High-frequency micro-contrast: H = Y - GaussianBlur(Y, 3x3)
            Imgproc.GaussianBlur(yMat, blurY, Size(3.0, 3.0), 1.0)
            Core.subtract(yMat, blurY, highFreq)

            // 2. Compute edge/gradient magnitude mask:
            // Flat regions (sky, skin, plain walls) -> gradient ~ 0, suppression applied.
            // Textured regions (fabric, text, edges, hair) -> gradient > 0, enhancement applied.
            Imgproc.Sobel(yMat, gradX, CvType.CV_16S, 1, 0, 3)
            Imgproc.Sobel(yMat, gradY, CvType.CV_16S, 0, 1, 3)
            Core.convertScaleAbs(gradX, absGradX)
            Core.convertScaleAbs(gradY, absGradY)
            Core.addWeighted(absGradX, 0.5, absGradY, 0.5, 0.0, gradMag)

            // Normalize gradient magnitude to [0.0, 1.0] weight mask
            gradMag.convertTo(weightMask, CvType.CV_32F, 1.0 / 128.0)
            Core.min(weightMask, Scalar(1.0), weightMask)

            // 3. Modulate high frequencies by the texture weight mask and desired strength
            highFreq.convertTo(highFreqFloat, CvType.CV_32F)
            Core.multiply(highFreqFloat, weightMask, detailBoost, strength.toDouble())

            yMat.convertTo(yFloat, CvType.CV_32F)
            Core.add(yFloat, detailBoost, enhancedYFloat)
            enhancedYFloat.convertTo(enhancedY, CvType.CV_8U)

            channels[0] = enhancedY
            Core.merge(channels, ycrcbMat)

            // Reconstruct RGB and copy back to RGBA
            Imgproc.cvtColor(ycrcbMat, rgbMat, Imgproc.COLOR_YCrCb2RGB)

            // If source had transparency, preserve original alpha channel
            if (sourceBitmap.hasAlpha()) {
                val alphaMat = Mat()
                Core.extractChannel(srcMat, alphaMat, 3)
                val rgbChannels = mutableListOf<Mat>()
                Core.split(rgbMat, rgbChannels)
                rgbChannels.add(alphaMat)
                Core.merge(rgbChannels, srcMat)
                for (ch in rgbChannels) ch.release()
            } else {
                Imgproc.cvtColor(rgbMat, srcMat, Imgproc.COLOR_RGB2RGBA)
            }

            val resultBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(srcMat, resultBitmap)
            resultBitmap
        } catch (t: Throwable) {
            Log.w(TAG, "AdaptiveDetailEnhancer bypassed: ${t.message}")
            sourceBitmap
        } finally {
            srcMat.release()
            ycrcbMat.release()
            rgbMat.release()
            blurY.release()
            highFreq.release()
            gradX.release()
            gradY.release()
            absGradX.release()
            absGradY.release()
            gradMag.release()
            weightMask.release()
            highFreqFloat.release()
            detailBoost.release()
            yFloat.release()
            enhancedYFloat.release()
            enhancedY.release()
            for (ch in channels) ch.release()
        }
    }
}
