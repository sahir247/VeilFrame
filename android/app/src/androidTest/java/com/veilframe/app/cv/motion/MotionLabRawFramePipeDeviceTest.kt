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

    @Test fun streamedH264RoundTripRetainsFrameCountAndEvenGeometry() = runBlocking {
        withTimeout(60_000) {
            CvRuntime.requireAvailable()
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val output = File.createTempFile("motion_h264_",".mp4",dir)
            val plan = MotionLabSourceGeometry(65,49,0).plan(false)
            val frame = Mat(49,65,CvType.CV_8UC3,Scalar(24.0,90.0,170.0))
            try {
                MotionLabRawFramePipe.encode(plan.geometry,24.0,4,arrayOf(
                    "-map","0:v:0","-an","-c:v","libx264","-preset","ultrafast","-crf","21",
                    "-vf",plan.encodeFilter,"-pix_fmt","yuv420p","-fps_mode","passthrough",
                    output.absolutePath)) { repeat(4) { writeMat(frame) } }
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(output.absolutePath)
                    val format = extractor.getTrackFormat(0)
                    assertEquals(66,format.getInteger(android.media.MediaFormat.KEY_WIDTH))
                    assertEquals(50,format.getInteger(android.media.MediaFormat.KEY_HEIGHT))
                    extractor.selectTrack(0)
                    val pts = mutableListOf<Long>()
                    while (extractor.sampleTime >= 0) {
                        pts.add(extractor.sampleTime)
                        if (!extractor.advance()) break
                    }
                    MotionLabSequenceVerifier.verifyPresentation(pts,4,24.0)
                } finally { extractor.release() }
                MotionLabRawFramePipe.decode(MotionLabRawFramePipe.Geometry(66,50),4,
                    arrayOf("-i",output.absolutePath)) {
                    repeat(4) {
                        val decoded = readBitmap()
                        try {
                            assertEquals(66,decoded.width)
                            assertEquals(50,decoded.height)
                            val rgb = decoded.getPixel(16,16)
                            assertTrue(kotlin.math.abs(android.graphics.Color.red(rgb)-170) < 15)
                            assertTrue(kotlin.math.abs(android.graphics.Color.green(rgb)-90) < 15)
                            assertTrue(kotlin.math.abs(android.graphics.Color.blue(rgb)-24) < 15)
                        } finally { decoded.recycle() }
                    }
                }
            } finally { frame.release(); output.delete() }
        }
    }

    @Test fun variableCadenceH264DecodeMatchesSourcePtsAndResamplesToSixtyFps() = runBlocking {
        withTimeout(60_000) {
            CvRuntime.requireAvailable()
            val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val source = File.createTempFile("vfr_source_",".mp4",dir)
            val output = File.createTempFile("vfr_output_",".mp4",dir)
            val geometry = MotionLabRawFramePipe.Geometry(64,48)
            val frame = Mat(48,64,CvType.CV_8UC3,Scalar(24.0,90.0,170.0))
            fun pts(file: File): Pair<List<Long>,Long> {
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val format = extractor.getTrackFormat(0)
                    val duration = format.getLong(android.media.MediaFormat.KEY_DURATION)
                    extractor.selectTrack(0)
                    val list = mutableListOf<Long>()
                    while (extractor.sampleTime >= 0) {
                        list.add(extractor.sampleTime)
                        if (!extractor.advance()) break
                    }
                    return list to duration
                } finally { extractor.release() }
            }
            try {
                // Input grid 100 Hz; output VFR PTS 0,30,100,140 ms; duration 150 ms.
                // executeWithArgumentsAsync passes argv directly; quotes here belong to
                // the filtergraph parser, not a shell. Do not double-escape its commas.
                MotionLabRawFramePipe.encode(geometry,100.0,4,arrayOf(
                    "-map","0:v:0","-an","-vf","settb=1/1000,setpts='if(eq(N,0),0,if(eq(N,1),30,if(eq(N,2),100,140)))'",
                    "-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p",
                    // Supply a final 10 ms packet duration explicitly in the encoder time base.
                    // MP4 derives earlier durations from the adjacent DTS values (no B frames).
                    "-bf","0","-enc_time_base","1:1000","-bsf:v","setts=duration=10",
                    "-video_track_timescale","1000","-fps_mode","passthrough",source.absolutePath)) {
                    repeat(4) { writeMat(frame) }
                }
                val (sourcePts,durationUs) = pts(source)
                assertEquals("VFR fixture presentation order",listOf(0L,30_000L,100_000L,140_000L),sourcePts)
                assertEquals("VFR fixture duration",150_000L,durationUs)
                val plan = MotionLabTimingContract.plan(sourcePts,durationUs/1000,true,2,durationUs)
                assertTrue(plan.variableCadence)
                assertEquals(60.0,plan.outputFps,0.0)
                assertEquals(9,plan.expectedOutputFrames)
                MotionLabRawFramePipe.encode(geometry,plan.outputFps,plan.expectedOutputFrames,arrayOf(
                    "-map","0:v:0","-an","-c:v","libx264","-preset","ultrafast",
                    "-pix_fmt","yuv420p","-fps_mode","passthrough",output.absolutePath)) {
                    val sink = this
                    MotionLabRawFramePipe.decode(geometry,4,arrayOf("-i",source.absolutePath),
                        expectedPresentationUs=sourcePts) {
                        // Uniform pixels isolate the timeline test from optical-flow quality.
                        repeat(4) { index ->
                            val mat = readMat()
                            try {
                                val count = if (index == 3) plan.tailFrames else plan.pairFractions[index].size
                                repeat(count) { sink.writeMat(mat) }
                            } finally { mat.release() }
                        }
                    }
                }
                MotionLabSequenceVerifier.verifyPresentation(pts(output).first,plan.expectedOutputFrames,60.0)
            } finally { frame.release(); source.delete(); output.delete() }
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
