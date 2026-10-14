package com.veilframe.app.upscale

import android.content.Context
import android.graphics.Bitmap
import com.veilframe.app.upscale.inference.BitmapSource
import com.veilframe.app.upscale.inference.NcnnNative
import com.veilframe.app.upscale.inference.NcnnUpscaleEngine
import com.veilframe.app.upscale.inference.UpscaleInferenceEngine
import com.veilframe.app.upscale.inference.UpscaleInferenceParams
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class NcnnUpscaleEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var repository: UpscaleModelRepository
    private lateinit var engine: NcnnUpscaleEngine

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        repository = UpscaleModelRepository(context)
        engine = NcnnUpscaleEngine(context, repository)
    }

    @Test
    fun testNcnnNative_SafeFallbackOnHostJvm() {
        // On host JVM test runners, native Android .so cannot be loaded
        // NcnnNative must fail closed with clean booleans/zeroes, never segfault or throw on property access
        assertFalse(NcnnNative.isAvailable)
        assertEquals(0, NcnnNative.getGpuCount())
        assertNull(NcnnNative.getGpuDeviceName(0))
    }

    @Test
    fun testNcnnNative_ThrowsIllegalStateWhenInvokedWithoutNativeLibrary() {
        try {
            NcnnNative.createInstance(0)
            fail("Expected IllegalStateException when native library is not loaded")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not available") == true)
        }

        try {
            NcnnNative.loadModel(1L, "param", "bin", 4, 160)
            fail("Expected IllegalStateException when native library is not loaded")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not available") == true)
        }
    }

    @Test
    fun testNcnnEngine_RejectsUninstalledModel() = runBlocking {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val source = BitmapSource(bitmap)

        try {
            engine.upscale(source, model, targetScale = 4)
            fail("Expected IllegalStateException for uninstalled model")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("not installed") == true)
        }
    }

    @Test
    fun testNcnnEngine_FailsClosedWhenNativeLibraryNotLoaded() = runBlocking {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val source = BitmapSource(bitmap)

        // Fabricate valid install state
        val paramFile = repository.getNcnnParamFile(model.id)
        val binFile = repository.getNcnnBinFile(model.id)
        val markerFile = repository.getNcnnInstallMarkerFile(model.id)
        paramFile.parentFile?.mkdirs()
        paramFile.writeBytes(ByteArray(model.paramSizeBytes.toInt()))
        binFile.writeBytes(ByteArray(model.binSizeBytes.toInt()))
        markerFile.writeText("${model.paramSha256}\n${model.binSha256}\n")

        assertTrue(repository.isModelInstalled(model))

        try {
            engine.upscale(source, model, targetScale = 4)
            fail("Expected IllegalStateException for unavailable native library on JVM")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("libveilframe_ncnn.so") == true || e.message?.contains("not available") == true)
        }
    }

    @Test
    fun testUpscaleInferenceEngine_RoutesAiNcnnToNcnnEngine() = runBlocking {
        val coordinator = UpscaleInferenceEngine(context, repository)
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)

        val result = coordinator.upscale(bitmap, model, targetScale = 4)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        // Failure message must come from NcnnUpscaleEngine (either not installed or native library not available)
        assertTrue(
            ex?.message?.contains("not installed") == true ||
                ex?.message?.contains("not available") == true
        )
    }
}
