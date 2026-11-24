package com.veilframe.app.cv.motion

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veilframe.app.cv.core.CvRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
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
                val first = byteArrayOf(0x56, 0x34, 0x12)
                val second = byteArrayOf(7, 19, 231.toByte())
                val expected = ByteArray(frameSize * 2) { index ->
                    if (index < frameSize) first[index % 3]
                    else second[(index-frameSize) % 3]
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

    @Test fun explicitCancellationDeliversEofAndRetiresOwnedSession() = runBlocking {
        withTimeout(60_000) {
            val geometry = MotionLabRawFramePipe.Geometry(320, 240)
            val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            val output = temp()
            val ready = CompletableDeferred<Unit>()
            try {
                val worker = launch(Dispatchers.IO) {
                    MotionLabRawFramePipe.encode(geometry, 24.0, 10, rawOutput(output)) {
                        writeBitmap(bitmap)
                        ready.complete(Unit)
                        awaitCancellation()
                    }
                }
                ready.await()
                worker.cancelAndJoin()
                assertTrue(worker.isCancelled)
                MotionLabRawFramePipe.encode(geometry, 24.0, 1, rawOutput(output)) { writeBitmap(bitmap) }
                assertEquals(geometry.frameBytes, output.length())
            } finally {
                bitmap.recycle()
                output.delete()
            }
        }
    }

    @Test fun simultaneousDecoderAndEncoderPreserveFramesAndRetireBothSessions() = runBlocking {
        withTimeout(60_000) {
            CvRuntime.requireAvailable()
            val geometry = MotionLabRawFramePipe.Geometry(320,240)
            val source = temp()
            val target = temp()
            val frameBytes = geometry.frameBytes.toInt()
            val expected = ByteArray(frameBytes*2) { (it*37).toByte() }
            source.writeBytes(expected)
            try {
                MotionLabRawFramePipe.encode(geometry,24.0,2,rawOutput(target)) {
                    val output = this
                    MotionLabRawFramePipe.decode(geometry,2,arrayOf("-f","rawvideo",
                        "-pixel_format","bgr24","-video_size","320x240","-framerate","24",
                        "-i",source.absolutePath)) {
                        repeat(2) {
                            val mat = readMat()
                            try { output.writeMat(mat) } finally { mat.release() }
                        }
                    }
                }
                assertArrayEquals(expected,target.readBytes())
            } finally { source.delete(); target.delete() }
        }
    }

    @Test fun decoderRejectsExtraFramesRatherThanDiscardingThem() = runBlocking {
        withTimeout(60_000) {
            CvRuntime.requireAvailable()
            val geometry = MotionLabRawFramePipe.Geometry(64,64)
            val source = temp()
            source.writeBytes(ByteArray(geometry.frameBytes.toInt()*2))
            try {
                val result = runCatching {
                    MotionLabRawFramePipe.decode(geometry,1,arrayOf("-f","rawvideo",
                        "-pixel_format","bgr24","-video_size","64x64","-framerate","24",
                        "-i",source.absolutePath)) { readMat().release() }
                }
                assertTrue(result.isFailure)
            } finally { source.delete() }
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
