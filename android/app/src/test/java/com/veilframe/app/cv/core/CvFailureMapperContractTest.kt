package com.veilframe.app.cv.core

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.opencv.core.CvException

class CvFailureMapperContractTest {

    @Test
    fun cvCancelledMapsToCancelledErrorCode() {
        val cancelled = CvCancelled("user pressed cancel")
        val result = CvFailureMapper.toResult<Unit>(cancelled)
        assertTrue(result is CvResult.Err)
        val err = result as CvResult.Err
        assertEquals(CvErrorCode.CANCELLED, err.code)
        assertEquals("user pressed cancel", err.message)
    }

    @Test
    fun coroutineCancellationMapsToCancelledErrorCode() {
        val cancellation = CancellationException("job was cancelled")
        val result = CvFailureMapper.toResult<Unit>(cancellation)
        assertTrue(result is CvResult.Err)
        val err = result as CvResult.Err
        assertEquals(CvErrorCode.CANCELLED, err.code)
        assertEquals("job was cancelled", err.message)
    }

    @Test
    fun illegalArgumentMapsToInvalidInputErrorCode() {
        val ex = IllegalArgumentException("dimensions must be positive")
        val result = CvFailureMapper.toResult<Unit>(ex)
        assertTrue(result is CvResult.Err)
        val err = result as CvResult.Err
        assertEquals(CvErrorCode.INVALID_INPUT, err.code)
        assertEquals("dimensions must be positive", err.message)
    }

    @Test
    fun unsupportedOperationMapsToUnsupportedErrorCode() {
        val ex = UnsupportedOperationException("DIS flow requires hardware acceleration")
        val result = CvFailureMapper.toResult<Unit>(ex)
        assertTrue(result is CvResult.Err)
        val err = result as CvResult.Err
        assertEquals(CvErrorCode.UNSUPPORTED, err.code)
        assertEquals("DIS flow requires hardware acceleration", err.message)
    }

    @Test
    fun cvExceptionMapsToInternalErrorCode() {
        val ex = CvException("cv::Mat::create() assertion failed (-215:Assertion failed)")
        val result = CvFailureMapper.toResult<Unit>(ex)
        assertTrue(result is CvResult.Err)
        val err = result as CvResult.Err
        assertEquals(CvErrorCode.INTERNAL, err.code)
        assertTrue(err.message.contains("OpenCV native error"))
    }

    @Test
    fun outOfMemoryErrorMapsToOutOfMemoryErrorCode() {
        val oom = OutOfMemoryError("Failed to allocate 96 MB native buffer")
        val result = CvFailureMapper.toResult<Unit>(oom)
        assertTrue(result is CvResult.Err)
        val err = result as CvResult.Err
        assertEquals(CvErrorCode.OUT_OF_MEMORY, err.code)
        assertTrue(err.message.contains("native/heap OOM"))
    }

    @Test
    fun unexpectedRuntimeExceptionsMapToInternalErrorCode() {
        val ex = IllegalStateException("engine in invalid state")
        val result = CvFailureMapper.toResult<Unit>(ex)
        assertTrue(result is CvResult.Err)
        val err = result as CvResult.Err
        assertEquals(CvErrorCode.INTERNAL, err.code)
        assertEquals("engine in invalid state", err.message)
    }

    @Test
    fun stackOverflowErrorIsPropagatedAndNeverConverted() {
        val soe = StackOverflowError("recursive call limit")
        try {
            CvFailureMapper.toResult<Unit>(soe)
            fail("StackOverflowError must be thrown, never swallowed into CvResult.Err")
        } catch (e: StackOverflowError) {
            assertEquals("recursive call limit", e.message)
        }
    }

    @Test
    fun linkageErrorsArePropagatedAndNeverConverted() {
        val linkage = UnsatisfiedLinkError("Cannot load libopencv_java4.so")
        try {
            CvFailureMapper.toResult<Unit>(linkage)
            fail("LinkageError must be thrown, never swallowed into CvResult.Err")
        } catch (e: LinkageError) {
            assertEquals("Cannot load libopencv_java4.so", e.message)
        }

        val noClass = NoClassDefFoundError("org.opencv.wechat_qrcode.WeChatQRCode")
        try {
            CvFailureMapper.toResult<Unit>(noClass)
            fail("NoClassDefFoundError must be thrown, never swallowed into CvResult.Err")
        } catch (e: LinkageError) {
            assertEquals("org.opencv.wechat_qrcode.WeChatQRCode", e.message)
        }
    }

    @Test
    fun threadDeathIsPropagatedAndNeverConverted() {
        val td = ThreadDeath()
        try {
            CvFailureMapper.toResult<Unit>(td)
            fail("ThreadDeath must be thrown, never swallowed into CvResult.Err")
        } catch (e: ThreadDeath) {
            // expected
        }
    }

    @Test
    fun virtualMachineErrorIsPropagatedAndNeverConverted() {
        val vmError = UnknownError("Fatal JVM error")
        try {
            CvFailureMapper.toResult<Unit>(vmError)
            fail("VirtualMachineError must be thrown, never swallowed into CvResult.Err")
        } catch (e: VirtualMachineError) {
            assertEquals("Fatal JVM error", e.message)
        }
    }
}
