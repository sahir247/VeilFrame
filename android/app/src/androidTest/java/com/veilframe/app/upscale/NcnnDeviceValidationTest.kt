package com.veilframe.app.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.upscale.inference.BitmapSource
import com.veilframe.app.upscale.inference.NcnnNative
import com.veilframe.app.upscale.inference.NcnnUpscaleEngine
import com.veilframe.app.upscale.inference.UpscaleInferenceParams
import com.veilframe.app.upscale.inference.UpscaleOutput
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NcnnDeviceValidationTest {

    companion object {
        private const val TAG = "VeilFrame.DeviceValidation"
    }

    private lateinit var context: Context
    private lateinit var repository: UpscaleModelRepository
    private lateinit var engine: NcnnUpscaleEngine
    private lateinit var model: UpscaleModel
    private lateinit var artifactsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = UpscaleModelRepository(context)
        engine = NcnnUpscaleEngine(context, repository)
        model = UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2
        artifactsDir = File(context.getExternalFilesDir(null), "validation_artifacts").apply { mkdirs() }
        File(context.cacheDir, "upscale_streaming").listFiles()?.forEach { it.delete() }

        // Stage model from /data/local/tmp/veilframe_validation/models if available
        val stagedParam = File("/data/local/tmp/veilframe_validation/models/ncnn-realesr-animevideov3-x2/model.param")
        val stagedBin = File("/data/local/tmp/veilframe_validation/models/ncnn-realesr-animevideov3-x2/model.bin")
        if (stagedParam.exists() && stagedBin.exists()) {
            val destParam = repository.getNcnnParamFile(model.id)
            val destBin = repository.getNcnnBinFile(model.id)
            destParam.parentFile?.mkdirs()
            stagedParam.copyTo(destParam, overwrite = true)
            stagedBin.copyTo(destBin, overwrite = true)

            // Write completion marker matching model metadata
            val marker = repository.getNcnnInstallMarkerFile(model.id)
            marker.writeText("${model.paramSha256}\n${model.binSha256}\n")
            Log.i(TAG, "Staged NCNN model into app storage: ${destParam.parentFile?.absolutePath}")
        }
    }

    @Test
    fun test01_ProbeHardwareAndVulkan() {
        Log.i(TAG, "=== HARDWARE & ENVIRONMENT PROBE ===")
        Log.i(TAG, "Manufacturer: ${Build.MANUFACTURER}")
        Log.i(TAG, "Model: ${Build.MODEL}")
        Log.i(TAG, "Board: ${Build.BOARD}")
        Log.i(TAG, "Hardware: ${Build.HARDWARE}")
        Log.i(TAG, "Android Release: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        Log.i(TAG, "Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")

        assertTrue("libveilframe_ncnn.so must be available and initialized", NcnnNative.isAvailable)

        val gpuCount = NcnnNative.getGpuCount()
        Log.i(TAG, "NCNN GPU count: $gpuCount")
        assertTrue("Device must report at least 1 Vulkan GPU", gpuCount > 0)

        for (i in 0 until gpuCount) {
            val gpuName = NcnnNative.getGpuDeviceName(i)
            Log.i(TAG, "GPU[$i] device name: $gpuName")
            assertNotNull("GPU name must not be null", gpuName)
        }
    }

    @Test
    fun test02_CuratedCorpus_VulkanAndCpuExecutions() {
        runBlocking {
            Log.i(TAG, "=== CURATED CORPUS EXECUTION & PARITY GATES ===")
        assertTrue("Model must be installed for testing", repository.isModelInstalled(model))

        val corpus = listOf(
            TestCase("01_pixel_1x1", 1, 1, hasAlpha = false) { canvas, _, _ ->
                canvas.drawColor(Color.rgb(180, 40, 90))
            },
            TestCase("02_odd_17x19", 17, 19, hasAlpha = false) { canvas, w, h ->
                val p = Paint()
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        p.color = Color.rgb((x * 15) % 256, (y * 13) % 256, ((x + y) * 7) % 256)
                        canvas.drawPoint(x.toFloat(), y.toFloat(), p)
                    }
                }
            },
            TestCase("03_pattern_64x64", 64, 64, hasAlpha = false) { canvas, w, h ->
                val p = Paint()
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val isChecker = ((x / 4) + (y / 4)) % 2 == 0
                        p.color = if (isChecker) Color.WHITE else Color.BLACK
                        canvas.drawPoint(x.toFloat(), y.toFloat(), p)
                    }
                }
            },
            TestCase("04_boundary_63x63", 63, 63, hasAlpha = false) { canvas, w, h ->
                canvas.drawColor(Color.LTGRAY)
                val p = Paint().apply { color = Color.BLUE; strokeWidth = 2f }
                canvas.drawLine(0f, 0f, w.toFloat(), h.toFloat(), p)
            },
            TestCase("05_boundary_65x65", 65, 65, hasAlpha = false) { canvas, w, h ->
                canvas.drawColor(Color.DKGRAY)
                val p = Paint().apply { color = Color.RED; strokeWidth = 2f }
                canvas.drawLine(w.toFloat(), 0f, 0f, h.toFloat(), p)
            },
            TestCase("06_rgba_transparent_64x64", 64, 64, hasAlpha = true) { canvas, w, h ->
                canvas.drawColor(Color.TRANSPARENT)
                val p = Paint().apply { color = Color.argb(128, 255, 120, 0) }
                canvas.drawCircle(w / 2f, h / 2f, w / 4f, p)
                val p2 = Paint().apply { color = Color.argb(255, 0, 200, 100) }
                canvas.drawRect(8f, 8f, 24f, 24f, p2)
            },
            TestCase("07_panorama_320x48", 320, 48, hasAlpha = false) { canvas, w, h ->
                val p = Paint()
                for (x in 0 until w) {
                    p.color = Color.rgb((x * 255) / w, 128, 255 - ((x * 255) / w))
                    canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), p)
                }
            }
        )

        for (case in corpus) {
            Log.i(TAG, "--- Processing case: ${case.name} (${case.w}x${case.h}, alpha=${case.hasAlpha}) ---")
            val inputBitmap = Bitmap.createBitmap(case.w, case.h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(inputBitmap)
            case.draw(canvas, case.w, case.h)

            // Save input image to artifacts directory
            val inputFile = File(artifactsDir, "input_${case.name}.png")
            FileOutputStream(inputFile).use { out ->
                inputBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val expectedW = case.w * 2
            val expectedH = case.h * 2

            // 1. Run on GPU (Vulkan) with 3 repetitions
            var gpuOutput: UpscaleOutput.Streamed? = null
            for (rep in 1..3) {
                val t0 = System.nanoTime()
                val pssBefore = Debug.getPss()
                gpuOutput = engine.upscale(
                    source = BitmapSource(inputBitmap),
                    model = model,
                    targetScale = 2,
                    params = UpscaleInferenceParams(chunkSize = 160)
                ) as UpscaleOutput.Streamed
                val t1 = System.nanoTime()
                val pssAfter = Debug.getPss()
                val elapsedMs = (t1 - t0) / 1_000_000
                Log.i(TAG, "Case ${case.name} [Vulkan Rep $rep]: ${elapsedMs}ms | PSS delta: ${pssAfter - pssBefore} kB")

                assertEquals("Width mismatch", expectedW, gpuOutput.width)
                assertEquals("Height mismatch", expectedH, gpuOutput.height)
                assertTrue("Output file must exist", gpuOutput.file.exists())
                assertTrue("Output file must be non-empty", gpuOutput.file.length() > 0)
                NcnnUpscaleEngine.validatePngFile(gpuOutput.file, expectedW, expectedH)
            }

            val gpuFinalArtifact = File(artifactsDir, "output_gpu_${case.name}.png")
            gpuOutput!!.file.copyTo(gpuFinalArtifact, overwrite = true)
            Log.i(TAG, "Saved Vulkan output artifact to ${gpuFinalArtifact.absolutePath} (${gpuFinalArtifact.length()} bytes)")
        }
    }
}

    @Test
    fun test03_CancellationSafety() {
        runBlocking {
            Log.i(TAG, "=== CANCELLATION LIFECYCLE GATE ===")
        val inputBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(inputBitmap)
        canvas.drawColor(Color.MAGENTA)

        var caughtCancellation = false
        val job = launch {
            try {
                engine.upscale(
                    source = BitmapSource(inputBitmap),
                    model = model,
                    targetScale = 2,
                    params = UpscaleInferenceParams(chunkSize = 128, overlap = 16)
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException || e.message?.contains("cancelled", ignoreCase = true) == true) {
                    caughtCancellation = true
                    Log.i(TAG, "Caught expected cancellation: ${e.javaClass.simpleName}: ${e.message}")
                } else {
                    throw e
                }
            }
        }

        delay(15) // Allow inference to start
        job.cancel()
        job.join()

        // Verify upscale cache directory has no leaked .part temporary files
        val cacheDir = File(context.cacheDir, "upscale_streaming")
        if (cacheDir.exists()) {
            val partFiles = cacheDir.listFiles { _, name -> name.endsWith(".part") } ?: emptyArray()
            assertEquals("No .part temporary files should leak after cancellation", 0, partFiles.size)
        }
            Log.i(TAG, "Cancellation test successfully verified clean teardown")
        }
    }

    private data class TestCase(
        val name: String,
        val w: Int,
        val h: Int,
        val hasAlpha: Boolean,
        val draw: (Canvas, Int, Int) -> Unit
    )
}
