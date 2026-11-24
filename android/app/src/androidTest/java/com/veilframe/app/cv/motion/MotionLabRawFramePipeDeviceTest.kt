package com.veilframe.app.cv.motion

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veilframe.app.cv.core.CvRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MotionLabRawFramePipeDeviceTest {
    private fun temp() = File.createTempFile("raw_pipe_", ".bgr",
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    private fun rawOutput(file: File) = arrayOf("-map", "0:v:0", "-c:v", "rawvideo",
        "-pix_fmt", "bgr24", "-threads", "1", "-f", "rawvideo", file.absolutePath)

    @Test fun ffmpegReadsExactBitmapAndNonContiguousMatBytesWithBackpressure() = runBlocking {
        withTimeout(60_000) {
            CvRuntime.requireAvailable()
            val geometry = MotionLabRawFramePipe.Geometry(320, 240)
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff123456.toInt())
            val parent = Mat(242, 324, CvType.CV_8UC3, Scalar(7.0, 19.0, 231.0))
            val roi = parent.submat(1, 241, 2, 322)
            val output = temp()
            try {
                assertFalse(roi.isContinuous)
                MotionLabRawFramePipe.encode(geometry, 30.0, 2, rawOutput(output)) {
                    writeBitmap(bitmap)
                    writeMat(roi)
                }
                val frameSize = geometry.frameBytes.toInt()
                val expected = ByteArray(frameSize * 2) { index ->
                    if (index < frameSize) byteArrayOf(0x56, 0x34, 0x12)[index % 3]
                    else byteArrayOf(7, 19, 231.toByte())[(index-frameSize) % 3]
                }
                assertArrayEquals(expected, output.readBytes())
                assertFalse(bitmap.isRecycled)
                assertFalse(roi.empty())
            } finally {
                roi.release()
                parent.release()
                bitmap.recycle()
                output.delete()
            }
        }
    }

    @Test fun incompleteProducerClosesPipeAndRetiresNativeSession() = runBlocking {
        withTimeout(60_000) {
            val geometry = MotionLabRawFramePipe.Geometry(64, 64)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val output = temp()
            try {
                val failure = runCatching {
                    MotionLabRawFramePipe.encode(geometry, 24.0, 2, rawOutput(output)) {
                        writeBitmap(bitmap)
                    }
                }.exceptionOrNull()
                assertTrue("Expected incomplete producer rejection, got $failure", failure is IllegalStateException)
                // A new independent session must still work after the failed producer.
                MotionLabRawFramePipe.encode(geometry, 24.0, 1, rawOutput(output)) { writeBitmap(bitmap) }
                assertEquals(geometry.frameBytes, output.length())
            } finally {
                bitmap.recycle()
                output.delete()
            }
        }
    }

    @Test fun invalidEncoderDoesNotLeaveProducerBlocked() = runBlocking {
        withTimeout(60_000) {
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val output = temp()
            try {
                val failure = runCatching {
                    MotionLabRawFramePipe.encode(
                        MotionLabRawFramePipe.Geometry(320, 240), 24.0, 10,
                        arrayOf("-c:v", "veilframe_nonexistent_codec", output.absolutePath),
                        stallTimeoutMs = 5_000
                    ) { repeat(10) { writeBitmap(bitmap) } }
                }.exceptionOrNull()
                assertTrue("Expected native encoder failure, got $failure", failure is IOException)
            } finally {
                bitmap.recycle()
                output.delete()
            }
        }
    }
}
