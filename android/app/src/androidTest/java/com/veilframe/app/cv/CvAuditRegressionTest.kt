package com.veilframe.app.cv

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.cv.color.ColorEngine
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.document.ColorDetector
import com.veilframe.app.cv.document.DocumentColorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

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
    fun colorTransformsPreserveOriginalAlpha() {
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(73, 30, 60, 90))
        val source = BitmapBridge.toMat(bitmap)
        val exposure = ColorEngine.exposure(source, -1.0)
        val contrast = ColorEngine.contrast(source, 1.7)
        val saturation = ColorEngine.saturation(source, 1.4)
        val temperature = ColorEngine.temperatureTint(source, 0.5, -0.2)
        try {
            for (result in listOf(exposure, contrast, saturation, temperature)) {
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
}
