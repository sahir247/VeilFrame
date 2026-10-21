package com.veilframe.app.upscale

import android.content.Context
import android.graphics.Bitmap
import com.veilframe.app.upscale.inference.BitmapSource
import com.veilframe.app.upscale.inference.NcnnNative
import com.veilframe.app.upscale.inference.NcnnUpscaleEngine
import com.veilframe.app.upscale.inference.SourceAccess
import com.veilframe.app.upscale.inference.UpscaleInferenceEngine
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
    fun testNcnnEngine_RejectsNonNcnnModelType() = runBlocking {
        val nonNcnnModel = UpscaleModelRegistry.LANCZOS
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val source = BitmapSource(bitmap)

        try {
            engine.upscale(source, nonNcnnModel, targetScale = 2)
            fail("Expected IllegalArgumentException for non-AI_NCNN model")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("only processes AI_NCNN models") == true)
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
    fun testNcnnEngine_RejectsScaleMismatch() = runBlocking {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val source = BitmapSource(bitmap)

        // Install model state to pass installation check
        installTestNcnnModel(model)

        try {
            engine.upscale(source, model, targetScale = 2)
            fail("Expected IllegalArgumentException for scale mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("natively outputs 4x") == true)
        }
    }

    @Test
    fun testNcnnEngine_RejectsExcessiveSourceDimension() = runBlocking {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        installTestNcnnModel(model)

        val oversizedSource = object : SourceAccess {
            override val width: Int = 20_000
            override val height: Int = 500
            override fun full(): Bitmap = throw UnsupportedOperationException()
            override fun region(x: Int, y: Int, width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
        }

        try {
            engine.upscale(oversizedSource, model, targetScale = 4)
            fail("Expected IllegalArgumentException for oversized source dimension")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("maximum supported dimension") == true)
        }
    }

    @Test
    fun testNcnnEngine_RejectsExcessiveTotalPixels() = runBlocking {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        installTestNcnnModel(model)

        // 6000x6000 -> 24000x24000 = 576 Megapixels (exceeds 100 MP limit)
        val excessivePixelSource = object : SourceAccess {
            override val width: Int = 6000
            override val height: Int = 6000
            override fun full(): Bitmap = throw UnsupportedOperationException()
            override fun region(x: Int, y: Int, width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
        }

        try {
            engine.upscale(excessivePixelSource, model, targetScale = 4)
            fail("Expected IllegalArgumentException for exceeding max safe pixels")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("exceeds maximum safe pixel budget") == true)
        }
    }

    @Test
    fun testNcnnEngine_FailsClosedWhenNativeLibraryNotLoaded() = runBlocking {
        val model = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val source = BitmapSource(bitmap)

        installTestNcnnModel(model)
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

    @Test
    fun testRgbaByteLayoutExtraction_DeterministicChannelOrder() {
        // Deterministic fixture: Opaque Red, Green, Blue, Transparent, Semi-transparent
        val colors = intArrayOf(
            0xFFFF0000.toInt(), // Opaque Red
            0xFF00FF00.toInt(), // Opaque Green
            0xFF0000FF.toInt(), // Opaque Blue
            0x00000000.toInt(), // Transparent Black
            0x80FF0000.toInt(), // 50% Semi-transparent Red
            0x4000FF00.toInt(), // 25% Semi-transparent Green
            0xFFFFFFFF.toInt(), // Opaque White
        )
        val bitmap = Bitmap.createBitmap(colors, colors.size, 1, Bitmap.Config.ARGB_8888)

        val srcW = colors.size
        val pixelRow = IntArray(srcW)
        bitmap.getPixels(pixelRow, 0, srcW, 0, 0, srcW, 1)

        val stripRowBytes = ByteArray(srcW * 4)
        var ri = 0
        for (x in 0 until srcW) {
            val p = pixelRow[x]
            stripRowBytes[ri++] = ((p ushr 16) and 0xFF).toByte() // R
            stripRowBytes[ri++] = ((p ushr 8) and 0xFF).toByte()  // G
            stripRowBytes[ri++] = (p and 0xFF).toByte()          // B
            stripRowBytes[ri++] = ((p ushr 24) and 0xFF).toByte() // A
        }

        // Verify Opaque Red (R=255, G=0, B=0, A=255)
        assertEquals(255, stripRowBytes[0].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[1].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[2].toInt() and 0xFF)
        assertEquals(255, stripRowBytes[3].toInt() and 0xFF)

        // Verify Opaque Green (R=0, G=255, B=0, A=255)
        assertEquals(0, stripRowBytes[4].toInt() and 0xFF)
        assertEquals(255, stripRowBytes[5].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[6].toInt() and 0xFF)
        assertEquals(255, stripRowBytes[7].toInt() and 0xFF)

        // Verify Opaque Blue (R=0, G=0, B=255, A=255)
        assertEquals(0, stripRowBytes[8].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[9].toInt() and 0xFF)
        assertEquals(255, stripRowBytes[10].toInt() and 0xFF)
        assertEquals(255, stripRowBytes[11].toInt() and 0xFF)

        // Verify Fully Transparent (R=0, G=0, B=0, A=0)
        assertEquals(0, stripRowBytes[12].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[13].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[14].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[15].toInt() and 0xFF)

        // Verify 50% Semi-transparent Red (R=255, G=0, B=0, A=128)
        assertEquals(255, stripRowBytes[16].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[17].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[18].toInt() and 0xFF)
        assertEquals(128, stripRowBytes[19].toInt() and 0xFF)

        // Verify 25% Semi-transparent Green (R=0, G=255, B=0, A=64)
        assertEquals(0, stripRowBytes[20].toInt() and 0xFF)
        assertEquals(255, stripRowBytes[21].toInt() and 0xFF)
        assertEquals(0, stripRowBytes[22].toInt() and 0xFF)
        assertEquals(64, stripRowBytes[23].toInt() and 0xFF)

        bitmap.recycle()
    }

    @Test
    fun testNcnnNative_GlobalDestroySafeWhenUnavailable() {
        // Must never throw or crash even if native library was not loaded
        NcnnNative.globalDestroy()
        assertFalse(NcnnNative.isAvailable)
    }

    @Test
    fun testNcnnNative_CancelSafeWhenUnavailable() {
        // Must never throw or crash even if native library was not loaded
        NcnnNative.cancel(99999L)
        assertFalse(NcnnNative.isAvailable)
    }

    private fun installTestNcnnModel(model: com.veilframe.app.upscale.model.UpscaleModel) {
        val paramFile = repository.getNcnnParamFile(model.id)
        val binFile = repository.getNcnnBinFile(model.id)
        val markerFile = repository.getNcnnInstallMarkerFile(model.id)
        paramFile.parentFile?.mkdirs()
        paramFile.writeBytes(ByteArray(model.paramSizeBytes.toInt()))
        binFile.writeBytes(ByteArray(model.binSizeBytes.toInt()))
        markerFile.writeText("${model.paramSha256}\n${model.binSha256}\n")
    }
}
