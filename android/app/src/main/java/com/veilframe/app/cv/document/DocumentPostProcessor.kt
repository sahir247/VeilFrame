package com.veilframe.app.cv.document

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * State-of-the-art document enhancement algorithms ported and adapted from FairScan.
 *
 * Implements:
 * 1. Multi-scale Retinex on L channel in Lab space with percentile normalization for Color mode.
 *    Flattens shadows and illumination variation without shifting document colors or washing out ink.
 * 2. Log-space Retinex with exp() tone compensation, white stretching, and bilateral smoothing for Grayscale.
 * 3. Sauvola local dynamic binarization with flatFill hole protection for crisp Black & White text,
 *    preventing solid black headers/logos from turning white in the center.
 */
enum class ColorMode {
    COLOR,
    GRAYSCALE,
    BLACK_AND_WHITE,
}

fun enhanceCapturedImage(img: Mat, colorMode: ColorMode, maxPixels: Long = 0L): Mat {
    return when (colorMode) {
        ColorMode.COLOR -> DocumentPostProcessor.multiScaleRetinexOnL(img)
        ColorMode.GRAYSCALE -> DocumentPostProcessor.enhanceGrayscaleImage(img)
        ColorMode.BLACK_AND_WHITE -> DocumentPostProcessor.binarizeDocument(img, maxPixels)
    }
}

object DocumentPostProcessor {

    /**
     * Color document enhancement using multi-scale Retinex strictly on the Lab L channel.
     * Preserves chrominance (a and b channels) while flattening harsh shadows and balancing contrast.
     */
    fun multiScaleRetinexOnL(src: Mat): Mat {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable || src.nativeObj == 0L) return src

        val is4Channel = src.channels() == 4
        val bgr = Mat()
        if (is4Channel) {
            Imgproc.cvtColor(src, bgr, Imgproc.COLOR_BGRA2BGR)
        } else if (src.channels() == 1) {
            Imgproc.cvtColor(src, bgr, Imgproc.COLOR_GRAY2BGR)
        } else {
            src.copyTo(bgr)
        }

        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        bgr.release()

        val labChannels = ArrayList<Mat>(3)
        Core.split(lab, labChannels)
        val l = labChannels[0] // CV_8U [0..255]

        val lFloat = Mat()
        l.convertTo(lFloat, CvType.CV_32F)
        Core.add(lFloat, Scalar(1.0), lFloat)

        // Downscale for efficient multi-scale filtering
        val scaleFactor = 2.0
        val smallSize = Size(
            max(1.0, lFloat.cols() / scaleFactor),
            max(1.0, lFloat.rows() / scaleFactor)
        )
        val lSmall = Mat()
        Imgproc.resize(lFloat, lSmall, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)

        val logLSmall = Mat()
        Core.log(lSmall, logLSmall)
        lSmall.release()

        val maxDimSmall = max(smallSize.width, smallSize.height)
        val kernelSizes = listOf(
            maxDimSmall / 80.0,
            maxDimSmall / 10.0,
            maxDimSmall / 2.0,
        )

        val weight = 1.0 / kernelSizes.size
        val retinexSmall = Mat.zeros(smallSize, CvType.CV_32F)
        val blurLog = Mat()
        val diff = Mat()

        for (ks in kernelSizes) {
            val k = ks.toInt().coerceAtLeast(3) or 1
            Imgproc.boxFilter(logLSmall, blurLog, -1, Size(k.toDouble(), k.toDouble()))
            Core.subtract(logLSmall, blurLog, diff)
            Core.addWeighted(retinexSmall, 1.0, diff, weight, 0.0, retinexSmall)
        }
        logLSmall.release()
        blurLog.release()
        diff.release()

        // Normalize Retinex relative to [0..1]
        val minMax = Core.minMaxLoc(retinexSmall)
        val retinexNormSmall = Mat()
        Core.subtract(retinexSmall, Scalar(minMax.minVal), retinexNormSmall)
        retinexSmall.release()

        val range = minMax.maxVal - minMax.minVal
        if (range > 1e-6) {
            Core.multiply(retinexNormSmall, Scalar(1.0 / range), retinexNormSmall)
        }

        // Upscale Retinex back to full resolution
        val retinexNorm = Mat()
        Imgproc.resize(retinexNormSmall, retinexNorm, lFloat.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
        retinexNormSmall.release()

        // Re-center around original luminance
        val lOriginalFloat = Mat()
        l.convertTo(lOriginalFloat, CvType.CV_32F)
        lFloat.release()

        val meanL = Core.mean(lOriginalFloat).`val`[0]
        val amplitude = 60.0

        val correctedL = Mat()
        Core.multiply(retinexNorm, Scalar(amplitude), correctedL)
        retinexNorm.release()
        Core.add(correctedL, Scalar(meanL - amplitude / 2.0), correctedL)

        // Blend with original L (alpha = 0.6)
        val alpha = 0.6
        Core.addWeighted(lOriginalFloat, 1.0 - alpha, correctedL, alpha, 0.0, correctedL)

        // Contrast restoration with percentiles
        val pLowOrig = percentileL(lOriginalFloat, 0.001)
        val pLow = percentileL(correctedL, 0.001)
        val pHigh = percentileL(correctedL, 0.995)
        lOriginalFloat.release()

        val targetLow = min(pLow, pLowOrig)
        val targetHigh = 245.0
        val scale = (targetHigh - targetLow) / (pHigh - pLow + 1e-6)

        Core.subtract(correctedL, Scalar(pLow), correctedL)
        Core.multiply(correctedL, Scalar(scale), correctedL)
        Core.add(correctedL, Scalar(targetLow), correctedL)

        Core.min(correctedL, Scalar(255.0), correctedL)
        Core.max(correctedL, Scalar(0.0), correctedL)

        correctedL.convertTo(labChannels[0], CvType.CV_8U)
        correctedL.release()

        Core.merge(labChannels, lab)
        labChannels.forEach { it.release() }

        val resultBgr = Mat()
        Imgproc.cvtColor(lab, resultBgr, Imgproc.COLOR_Lab2BGR)
        lab.release()

        if (is4Channel) {
            val resultBgra = Mat()
            Imgproc.cvtColor(resultBgr, resultBgra, Imgproc.COLOR_BGR2BGRA)
            resultBgr.release()
            return resultBgra
        }
        return resultBgr
    }

    /**
     * Grayscale document enhancement using log-space multi-scale Retinex,
     * bright tone exp() compensation, mode white stretching, and bilateral smoothing.
     */
    fun enhanceGrayscaleImage(img: Mat): Mat {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable || img.nativeObj == 0L) return img
        val gray = flattenedGrayscale(img)
        val is4Channel = img.channels() == 4
        val result = Mat()
        if (is4Channel) {
            Imgproc.cvtColor(gray, result, Imgproc.COLOR_GRAY2BGRA)
        } else if (img.channels() == 3) {
            Imgproc.cvtColor(gray, result, Imgproc.COLOR_GRAY2BGR)
        } else {
            gray.copyTo(result)
        }
        gray.release()
        return result
    }

    /**
     * Crisp black-and-white binarization using Sauvola dynamic local thresholding
     * with flatFill protection for large ink blocks and hole filling.
     */
    fun binarizeDocument(img: Mat, upscaleTo: Long = 0L): Mat {
        if (!com.veilframe.app.cv.core.CvRuntime.isNativeAvailable || img.nativeObj == 0L) return img

        val flattened = flattenedGrayscale(img)
        val gray = upscaleToPixels(flattened, upscaleTo)
        flattened.release()
        val window = sauvolaWindow(max(gray.cols(), gray.rows()))

        val src = Mat()
        gray.convertTo(src, CvType.CV_32F)
        gray.release()

        val binary = sauvolaThreshold(src, window)
        val fill = flatFill(src, window)
        src.release()

        // Protect solid flat fills from turning white
        Core.subtract(binary, fill, binary)
        fillHoles(binary, fill, window)
        fill.release()

        val is4Channel = img.channels() == 4
        val result = Mat()
        if (is4Channel) {
            Imgproc.cvtColor(binary, result, Imgproc.COLOR_GRAY2BGRA)
        } else if (img.channels() == 3) {
            Imgproc.cvtColor(binary, result, Imgproc.COLOR_GRAY2BGR)
        } else {
            binary.copyTo(result)
        }
        binary.release()
        return result
    }

    private fun flattenedGrayscale(img: Mat): Mat {
        val gray = Mat()
        when (img.channels()) {
            4 -> Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGRA2GRAY)
            3 -> Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
            else -> img.copyTo(gray)
        }

        val maxDim = max(gray.cols(), gray.rows()).toDouble()
        val imgFloat = Mat()
        gray.convertTo(imgFloat, CvType.CV_32F)
        Core.add(imgFloat, Scalar(1.0), imgFloat)

        val logImg = Mat()
        Core.log(imgFloat, logImg)

        val kernelSizes = listOf(maxDim / 6.0, maxDim / 50.0)
        val weight = 1.0 / kernelSizes.size
        val retinex = Mat.zeros(gray.size(), CvType.CV_32F)
        val blur = Mat()
        val logBlur = Mat()
        val diff = Mat()

        for (ks in kernelSizes) {
            val k = ks.toInt().coerceAtLeast(3) or 1
            Imgproc.boxFilter(imgFloat, blur, -1, Size(k.toDouble(), k.toDouble()))
            Core.add(blur, Scalar(1.0), blur)
            Core.log(blur, logBlur)
            Core.subtract(logImg, logBlur, diff)
            Core.addWeighted(retinex, 1.0, diff, weight, 0.0, retinex)
        }
        imgFloat.release()
        logImg.release()
        blur.release()
        logBlur.release()
        diff.release()

        // exp() tone compensation
        val retinexExp = Mat()
        Core.exp(retinex, retinexExp)
        retinex.release()

        val (pLow, pHigh) = percentiles(retinexExp, 0.004, 0.99)
        val normalized = Mat()
        Core.subtract(retinexExp, Scalar(pLow), normalized)
        retinexExp.release()

        val scale = if (pHigh > pLow) 255.0 / (pHigh - pLow) else 1.0
        Core.multiply(normalized, Scalar(scale), normalized)
        Core.min(normalized, Scalar(255.0), normalized)
        Core.max(normalized, Scalar(0.0), normalized)

        val result8u = Mat()
        normalized.convertTo(result8u, CvType.CV_8U)
        normalized.release()

        // Stretch toward white
        val hist = Mat()
        Imgproc.calcHist(listOf(result8u), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))

        var modeVal = 220
        var modeCount = 0.0
        for (i in 180 until 256) {
            val c = hist.get(i, 0)[0]
            if (c > modeCount) {
                modeCount = c
                modeVal = i
            }
        }
        hist.release()

        val stretched8u = Mat()
        if (modeVal >= 254) {
            val grayF = Mat()
            gray.convertTo(grayF, CvType.CV_32F)
            val (gLow, gHigh) = percentiles(grayF, 0.01, 0.99)
            Core.subtract(grayF, Scalar(gLow), grayF)
            Core.multiply(grayF, Scalar(255.0 / (gHigh - gLow + 1e-6)), grayF)
            Core.min(grayF, Scalar(255.0), grayF)
            Core.max(grayF, Scalar(0.0), grayF)
            grayF.convertTo(stretched8u, CvType.CV_8U)
            grayF.release()
        } else {
            val stretchedF = Mat()
            result8u.convertTo(stretchedF, CvType.CV_32F)
            Core.multiply(stretchedF, Scalar(255.0 / modeVal), stretchedF)
            Core.min(stretchedF, Scalar(255.0), stretchedF)
            stretchedF.convertTo(stretched8u, CvType.CV_8U)
            stretchedF.release()
        }
        result8u.release()
        gray.release()

        // Bilateral denoising: smooths background texture while preserving character edges
        val denoised = Mat()
        Imgproc.bilateralFilter(stretched8u, denoised, 9, 20.0, 10.0)
        stretched8u.release()
        return denoised
    }

    private const val SAUVOLA_K = 0.25
    private const val SAUVOLA_R = 128.0

    private fun sauvolaThreshold(src: Mat, window: Int): Mat {
        val windowSize = Size(window.toDouble(), window.toDouble())
        val mean = Mat()
        Imgproc.boxFilter(src, mean, CvType.CV_32F, windowSize)

        val squares = Mat()
        Core.multiply(src, src, squares)
        val deviation = Mat()
        Imgproc.boxFilter(squares, deviation, CvType.CV_32F, windowSize)
        squares.release()

        val meanSquared = Mat()
        Core.multiply(mean, mean, meanSquared)
        Core.subtract(deviation, meanSquared, deviation)
        meanSquared.release()

        Core.max(deviation, Scalar(0.0), deviation)
        Core.sqrt(deviation, deviation)

        Core.multiply(deviation, Scalar(SAUVOLA_K / SAUVOLA_R), deviation)
        Core.add(deviation, Scalar(1.0 - SAUVOLA_K), deviation)

        val threshold = Mat()
        Core.multiply(mean, deviation, threshold)
        mean.release()
        deviation.release()

        val binary = Mat()
        Core.compare(src, threshold, binary, Core.CMP_GT)
        threshold.release()
        return binary
    }

    internal fun sauvolaWindow(maxDim: Int): Int = (maxDim / 10).coerceIn(15, 1001) or 1

    private const val FILL_WINDOW = 9.0
    private const val FILL_DEVIATION = 12.0
    private const val FILL_LEVEL = 155.0

    private fun flatFill(src: Mat, window: Int): Mat {
        val dark = Mat()
        Core.compare(src, Scalar(FILL_LEVEL), dark, Core.CMP_LT)

        val deviation = localDeviation(src, FILL_WINDOW)
        val seeds = Mat()
        Core.compare(deviation, Scalar(FILL_DEVIATION), seeds, Core.CMP_LT)
        deviation.release()
        Core.bitwise_and(seeds, dark, seeds)

        val reach = (window / 2).coerceAtLeast(3).toDouble()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(reach, reach))
        val fill = Mat()
        Imgproc.dilate(seeds, fill, kernel)
        seeds.release()
        kernel.release()

        Core.bitwise_and(fill, dark, fill)
        dark.release()
        return fill
    }

    private fun localDeviation(src: Mat, window: Double): Mat {
        val windowSize = Size(window, window)
        val mean = Mat()
        Imgproc.boxFilter(src, mean, CvType.CV_32F, windowSize)
        val squares = Mat()
        Core.multiply(src, src, squares)
        val deviation = Mat()
        Imgproc.boxFilter(squares, deviation, CvType.CV_32F, windowSize)
        squares.release()
        Core.multiply(mean, mean, mean)
        Core.subtract(deviation, mean, deviation)
        mean.release()
        Core.max(deviation, Scalar(0.0), deviation)
        Core.sqrt(deviation, deviation)
        return deviation
    }

    private fun despeckleMinArea(maxDim: Int): Int {
        val scale = maxDim / 3508.0
        return max(2, (12.0 * scale * scale).roundToInt())
    }

    private const val FILL_HOLE_FACTOR = 5
    private const val LETTER_AREA_FACTOR = 4

    private fun fillHoles(binary: Mat, fill: Mat, window: Int) {
        val minArea = despeckleMinArea(max(binary.cols(), binary.rows()))
        val letters = binary.clone()
        removeSpecks(letters, minArea * LETTER_AREA_FACTOR, ink = false)

        val nearLetters = Mat()
        val dotGap = (8.0 * sqrt(minArea.toDouble())).coerceAtLeast(3.0)
        val dotKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(dotGap, dotGap))
        Imgproc.dilate(letters, nearLetters, dotKernel)
        letters.release()
        dotKernel.release()

        val reach = (window / 4).coerceAtLeast(3).toDouble()
        val inside = Mat()
        val reachKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(reach, reach))
        Imgproc.dilate(fill, inside, reachKernel)
        reachKernel.release()

        val lonely = Mat()
        Core.bitwise_not(nearLetters, lonely)
        nearLetters.release()
        Core.bitwise_and(inside, lonely, inside)
        lonely.release()

        removeSpecks(binary, minArea * FILL_HOLE_FACTOR, ink = false, within = inside)
        inside.release()
    }

    private fun removeSpecks(binary: Mat, minArea: Int, ink: Boolean, within: Mat? = null) {
        val subject = Mat()
        if (ink) Core.bitwise_not(binary, subject) else binary.copyTo(subject)

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(subject, labels, stats, centroids, 8, CvType.CV_32S)
        subject.release()

        if (count > 1) {
            val statsData = IntArray(count * 5)
            stats.get(0, 0, statsData)
            val centres = DoubleArray(count * 2)
            centroids.get(0, 0, centres)
            val replacement = Scalar(if (ink) 255.0 else 0.0)

            for (label in 1 until count) {
                val offset = label * 5
                if (statsData[offset + Imgproc.CC_STAT_AREA] >= minArea) continue
                val cx = centres[label * 2]
                val cy = centres[label * 2 + 1]
                if (within != null && !isSet(within, cx, cy)) continue

                val box = Rect(
                    statsData[offset + Imgproc.CC_STAT_LEFT],
                    statsData[offset + Imgproc.CC_STAT_TOP],
                    statsData[offset + Imgproc.CC_STAT_WIDTH],
                    statsData[offset + Imgproc.CC_STAT_HEIGHT],
                )
                val labelBox = labels.submat(box)
                val speck = Mat()
                Core.compare(labelBox, Scalar(label.toDouble()), speck, Core.CMP_EQ)
                val target = binary.submat(box)
                target.setTo(replacement, speck)
                labelBox.release()
                speck.release()
                target.release()
            }
        }
        labels.release()
        stats.release()
        centroids.release()
    }

    private fun isSet(mask: Mat, x: Double, y: Double): Boolean {
        val col = x.toInt().coerceIn(0, mask.cols() - 1)
        val row = y.toInt().coerceIn(0, mask.rows() - 1)
        return mask.get(row, col)[0] != 0.0
    }

    fun percentileL(l: Mat, p: Double): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(l), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val total = l.total()
        var sum = 0.0
        for (i in 0 until 256) {
            sum += hist.get(i, 0)[0]
            if (sum / total >= p) {
                hist.release()
                return i.toDouble()
            }
        }
        hist.release()
        return 255.0
    }

    private fun percentiles(src: Mat, low: Double, high: Double): Pair<Double, Double> {
        val sample = resizeForMaxPixels(src, 500_000.0)
        val flat = Mat()
        sample.reshape(1, 1).copyTo(flat)
        sample.release()
        val sorted = Mat()
        Core.sort(flat, sorted, Core.SORT_ASCENDING)
        flat.release()
        val n = sorted.cols()
        val pLow = sorted.get(0, (n * low).toInt().coerceIn(0, n - 1))[0]
        val pHigh = sorted.get(0, (n * high).toInt().coerceIn(0, n - 1))[0]
        sorted.release()
        return pLow to pHigh
    }

    private fun resizeForMaxPixels(img: Mat, maxPixels: Double): Mat {
        val pixels = img.cols().toDouble() * img.rows().toDouble()
        if (pixels <= maxPixels) return img.clone()
        val scale = sqrt(maxPixels / pixels)
        val out = Mat()
        Imgproc.resize(img, out, Size(img.cols() * scale, img.rows() * scale), 0.0, 0.0, Imgproc.INTER_NEAREST)
        return out
    }

    private fun upscaleToPixels(img: Mat, targetPixels: Long): Mat {
        if (targetPixels <= 0L) return img.clone()
        val pixels = img.cols().toLong() * img.rows().toLong()
        if (targetPixels <= pixels) return img.clone()
        val scale = sqrt(targetPixels.toDouble() / pixels)
        val out = Mat()
        Imgproc.resize(img, out, Size(img.cols() * scale, img.rows() * scale), 0.0, 0.0, Imgproc.INTER_CUBIC)
        return out
    }
}
