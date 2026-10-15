package com.veilframe.app.cv

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.cv.color.ColorEngine
import com.veilframe.app.cv.analysis.ImageQualityAnalyzer
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.document.ColorDetector
import com.veilframe.app.cv.document.ColorMode
import com.veilframe.app.cv.document.Mask
import com.veilframe.app.cv.geometry.Point as GeometryPoint
import com.veilframe.app.cv.geometry.Quad
import com.veilframe.app.cv.document.DocumentColorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class CvAuditRegressionTest {
    @Before
    fun initializeCv() {
        assertTrue("OpenCV native runtime must initialize", CvRuntime.initialize())
    }

    @Test
    fun bitmapBridgeKeepsBgraContractAndRoundTripsRed() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val mat = BitmapBridge.toMat(bitmap)
        try {
            val bgra = ByteArray(4)
            mat.get(0, 0, bgra)
            assertEquals(0, bgra[0].toInt() and 0xFF)
            assertEquals(0, bgra[1].toInt() and 0xFF)
            assertEquals(255, bgra[2].toInt() and 0xFF)
            assertEquals(255, bgra[3].toInt() and 0xFF)

            val roundTrip = BitmapBridge.toBitmap(mat)
            try {
                assertEquals(Color.RED, roundTrip.getPixel(0, 0))
            } finally {
                roundTrip.recycle()
            }
        } finally {
            mat.release()
            bitmap.recycle()
        }
    }


    @Test
    fun alpha8BitmapConvertsItsStoredAlphaSamples() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ALPHA_8)
        try {
            // Write the raw ALPHA_8 plane, respecting any native row padding.
            val storage = ByteArray(bitmap.byteCount)
            val values = intArrayOf(0, 64, 128, 255, 32, 200)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    storage[y * bitmap.rowBytes + x] = values[y * bitmap.width + x].toByte()
                }
            }
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(storage))

            val mat = BitmapBridge.toMat(bitmap)
            try {
                assertEquals(CvType.CV_8UC1, mat.type())
                val pixel = ByteArray(1)
                mat.get(0, 0, pixel)
                assertEquals(0, pixel[0].toInt() and 0xFF)
                mat.get(0, 1, pixel)
                assertEquals(64, pixel[0].toInt() and 0xFF)
                mat.get(0, 2, pixel)
                assertEquals(128, pixel[0].toInt() and 0xFF)
                mat.get(1, 0, pixel)
                assertEquals(255, pixel[0].toInt() and 0xFF)
            } finally {
                mat.release()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun colorTransformsPreserveOriginalAlpha() {
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(73, 30, 60, 90))
        val source = BitmapBridge.toMat(bitmap)
        val exposure = ColorEngine.exposure(source, -1.0)
        val contrast = ColorEngine.contrast(source, 1.7)
        val saturation = ColorEngine.saturation(source, 1.4)
        val temperature = ColorEngine.temperatureTint(source, 0.5, -0.2)
        val whiteBalance = ColorEngine.autoWhiteBalance(source).corrected
        val highlightsShadows = ColorEngine.highlightsShadows(source, highlights = 0.35, shadows = 0.2)
        try {
            for (result in listOf(exposure, contrast, saturation, temperature, whiteBalance, highlightsShadows)) {
                val pixel = ByteArray(4)
                result.get(0, 0, pixel)
                assertEquals("Alpha must remain unchanged", 73, pixel[3].toInt() and 0xFF)
            }
        } finally {
            source.release()
            exposure.release()
            contrast.release()
            saturation.release()
            temperature.release()
            whiteBalance.release()
            highlightsShadows.release()
            bitmap.recycle()
        }
    }

    @Test
    fun autoColorModeWithEmptyQuadAnalyzesFullImage() {
        val grayscale = Mat(96, 96, CvType.CV_8UC3, Scalar(160.0, 160.0, 160.0))
        try {
            assertEquals(
                DocumentColorMode.GRAYSCALE,
                ColorDetector.autoColorMode(grayscale, emptyList())
            )
        } finally {
            grayscale.release()
        }
    }

    @Test
    fun maskOverloadActuallyUsesTheSuppliedMask() {
        val image = Mat(120, 120, CvType.CV_8UC3, Scalar(180.0, 180.0, 180.0))
        val fullMaskMat = Mat(120, 120, CvType.CV_8UC1, Scalar(255.0))
        val excludeColorPatchMaskMat = fullMaskMat.clone()
        val colorPatch = image.submat(Rect(30, 30, 40, 40))
        val excludedPatch = excludeColorPatchMaskMat.submat(Rect(25, 25, 50, 50))
        val quad = Quad(
            GeometryPoint(0.0, 0.0),
            GeometryPoint(120.0, 0.0),
            GeometryPoint(120.0, 120.0),
            GeometryPoint(0.0, 120.0)
        )
        val fullMask = object : Mask {
            override val width = 120
            override val height = 120
            override fun toMat(): Mat = fullMaskMat.clone()
        }
        val excludeColorPatchMask = object : Mask {
            override val width = 120
            override val height = 120
            override fun toMat(): Mat = excludeColorPatchMaskMat.clone()
        }
        try {
            colorPatch.setTo(Scalar(20.0, 20.0, 240.0)) // BGR red patch.
            excludedPatch.setTo(Scalar(0.0))
            val fullImageMode = ColorDetector.autoColorMode(image, fullMask, quad)
            val maskedMode = ColorDetector.autoColorMode(image, excludeColorPatchMask, quad)
            assertEquals("Color inside the mask should be detected", ColorMode.COLOR, fullImageMode)
            assertEquals("Color outside the supplied mask must not affect AUTO mode", ColorMode.GRAYSCALE, maskedMode)
        } finally {
            colorPatch.release()
            excludedPatch.release()
            excludeColorPatchMaskMat.release()
            fullMaskMat.release()
            image.release()
        }
    }

    @Test
    fun darkScenePercentileAndQualityMetricsRemainStable() {
        val luma = Mat(10, 10, CvType.CV_8UC1, Scalar(64.0))
        val blackRow = luma.submat(Rect(0, 0, 10, 1))
        try {
            blackRow.setTo(Scalar(0.0))
            val report = ImageQualityAnalyzer.analyze(luma)
            assertEquals("p5 must remain at the zero-valued bin", 64.0, report.raw.lumaSpreadP5P95, 0.0)
            assertTrue(report.raw.noiseSigma.isFinite())
            assertTrue(report.raw.ringing.isFinite())
            assertTrue(ImageQualityAnalyzer.tenengrad(luma).isFinite())
        } finally {
            blackRow.release()
            luma.release()
        }
    }


    @Test
    fun noiseSigmaRetainsSubIntegerResidualPrecision() {
        val size = 64
        val checkerboard = Mat(size, size, CvType.CV_8UC1)
        val pixels = ByteArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if ((x + y) % 2 == 0) 0 else 1
        }
        try {
            checkerboard.put(0, 0, pixels)
            val sigma = ImageQualityAnalyzer.estimateNoiseSigma(checkerboard)
            assertTrue("Sub-integer residuals should produce a fractional noise estimate: $sigma", sigma in 0.6..0.9)
        } finally {
            checkerboard.release()
        }
    }

}
