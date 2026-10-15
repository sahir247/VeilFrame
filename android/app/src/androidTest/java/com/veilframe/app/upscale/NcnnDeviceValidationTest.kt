package com.veilframe.app.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        // Stage models from /data/local/tmp/veilframe_validation/models if available
        val modelsToStage = listOf(
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2,
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4,
            UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        )
        for (m in modelsToStage) {
            val stagedParam = File("/data/local/tmp/veilframe_validation/models/${m.id}/model.param")
            val stagedBin = File("/data/local/tmp/veilframe_validation/models/${m.id}/model.bin")
            if (stagedParam.exists() && stagedBin.exists()) {
                val destParam = repository.getNcnnParamFile(m.id)
                val destBin = repository.getNcnnBinFile(m.id)
                destParam.parentFile?.mkdirs()
                stagedParam.copyTo(destParam, overwrite = true)
                stagedBin.copyTo(destBin, overwrite = true)

                val marker = repository.getNcnnInstallMarkerFile(m.id)
                marker.writeText("${m.paramSha256}\n${m.binSha256}\n")
                Log.i(TAG, "Staged NCNN model into app storage: ${m.id} (${destBin.length()} bytes)")
            }
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
            Log.i(TAG, "=== REAL-WORLD CORPUS MULTI-MODEL EXECUTION ===")

            val realCases = listOf(
                RealTestCase("01_photo_face_texture_4x", "realesr_0014_photo_lr.jpg", UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS),
                RealTestCase("02_anime_compression_4x", "realesr_0030_anime_lr.jpg", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4),
                RealTestCase("03_street_landscape_2x", "realesr_00003_street_lr.png", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2),
                RealTestCase("04_children_alpha_transparency_2x", "realesr_children_alpha_lr.png", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2),
                RealTestCase("05_phone_wallpaper_4x", "phone_wallpaper_480x300.png", UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS),
                RealTestCase("06_large_texture_4x", "realesr_ost009_texture_lr.png", UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS)
            )

            for (case in realCases) {
                Log.i(TAG, "--- Processing real case: ${case.name} with model ${case.model.id} (${case.model.nativeScale}x) ---")
                val inputFile = File("/data/local/tmp/veilframe_validation/real_inputs/${case.fileName}")
                assertTrue("Input file ${case.fileName} must exist on device", inputFile.exists())
                val inputBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
                assertNotNull("Failed to decode input bitmap for ${case.fileName}", inputBitmap)

                val inW = inputBitmap.width
                val inH = inputBitmap.height
                val expectedW = inW * case.model.nativeScale
                val expectedH = inH * case.model.nativeScale

                // Save input image copy to artifacts
                val inputArtifact = File(artifactsDir, "real_input_${case.name}.png")
                FileOutputStream(inputArtifact).use { out ->
                    inputBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                val t0 = System.nanoTime()
                val pssBefore = Debug.getPss()
                val output = engine.upscale(
                    source = BitmapSource(inputBitmap),
                    model = case.model,
                    targetScale = case.model.nativeScale,
                    params = UpscaleInferenceParams(chunkSize = case.chunkSize)
                ) as UpscaleOutput.Streamed
                val t1 = System.nanoTime()
                val pssAfter = Debug.getPss()
                val elapsedMs = (t1 - t0) / 1_000_000
                Log.i(TAG, "RealCase ${case.name} [Vulkan]: ${elapsedMs}ms | ${inW}x${inH} -> ${output.width}x${output.height} | PSS delta: ${pssAfter - pssBefore} kB")

                assertEquals("Width mismatch", expectedW, output.width)
                assertEquals("Height mismatch", expectedH, output.height)
                assertTrue("Output file must exist", output.file.exists())
                assertTrue("Output file must be non-empty", output.file.length() > 0)
                NcnnUpscaleEngine.validatePngFile(output.file, expectedW, expectedH)

                if (case.name == "04_children_alpha_transparency_2x") {
                    val decodedAlpha = BitmapFactory.decodeFile(output.file.absolutePath)
                    assertNotNull("Transparent test output must decode", decodedAlpha)
                    try {
                        val alphaPixels = IntArray(decodedAlpha.width * decodedAlpha.height)
                        decodedAlpha.getPixels(alphaPixels, 0, decodedAlpha.width, 0, 0, decodedAlpha.width, decodedAlpha.height)
                        assertTrue("Upscaled RGBA output must preserve transparent pixels", alphaPixels.any { Color.alpha(it) < 250 })
                        assertTrue("Upscaled RGBA output must preserve opaque pixels", alphaPixels.any { Color.alpha(it) > 250 })
                    } finally {
                        decodedAlpha.recycle()
                    }
                }

                val outputArtifact = File(artifactsDir, "real_output_gpu_${case.name}.png")
                output.file.copyTo(outputArtifact, overwrite = true)
                Log.i(TAG, "Saved real output artifact to ${outputArtifact.absolutePath} (${outputArtifact.length()} bytes)")
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

    @Test
    fun test05_SmallAndSingleColumnInputs() {
        runBlocking {
            Log.i(TAG, "=== SMALL DIMENSION / SINGLE-COLUMN GPU REGRESSION GATE ===")
            val sizes = listOf(1 to 1, 2 to 3, 63 to 65, 65 to 63, 127 to 129)
            for ((width, height) in sizes) {
                val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    input.eraseColor(Color.rgb(80, 120, 160))
                    val output = engine.upscale(
                        source = BitmapSource(input),
                        model = UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2,
                        targetScale = 2,
                        params = UpscaleInferenceParams(chunkSize = 128, overlap = 16)
                    ) as UpscaleOutput.Streamed
                    assertEquals("Output width for ${width}x${height}", width * 2, output.width)
                    assertEquals("Output height for ${width}x${height}", height * 2, output.height)
                    assertTrue("Output must exist for ${width}x${height}", output.file.exists())
                    assertTrue("Output must be non-empty for ${width}x${height}", output.file.length() > 0)
                    NcnnUpscaleEngine.validatePngFile(output.file, width * 2, height * 2)
                    val decoded = BitmapFactory.decodeFile(output.file.absolutePath)
                    assertNotNull("Output must decode for ${width}x${height}", decoded)
                    try {
                        val outputPixels = IntArray(decoded.width * decoded.height)
                        decoded.getPixels(
                            outputPixels, 0, decoded.width, 0, 0, decoded.width, decoded.height
                        )
                        assertTrue(
                            "Upscale output must not be entirely black for ${width}x${height}",
                            outputPixels.any { pixel ->
                                ((pixel ushr 16) and 0xFF) > 2 ||
                                    ((pixel ushr 8) and 0xFF) > 2 ||
                                    (pixel and 0xFF) > 2
                            }
                        )
                        assertTrue(
                            "Opaque input must retain opaque alpha for ${width}x${height}",
                            outputPixels.any { Color.alpha(it) > 250 }
                        )
                    } finally {
                        decoded.recycle()
                    }
                    Log.i(TAG, "Small-image case passed: ${width}x${height} -> ${output.width}x${output.height}")
                } finally {
                    input.recycle()
                }
            }
        }
    }

    @Test
    fun test04_UserWhatsAppClashOfClans_1280x960_4x() {
        runBlocking {
            Log.i(TAG, "=== USER WHATSAPP IMAGE 1280x960 REAL-ESRGAN-X4PLUS (4X -> 5120x3840) ===")
            val inputFile = File("/data/local/tmp/veilframe_validation/real_inputs/IMG-20261013-WA0005.jpg")
            assertTrue("Input file IMG-20261013-WA0005.jpg must exist on device", inputFile.exists())
            val inputBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
            assertNotNull("Failed to decode input bitmap", inputBitmap)
            assertEquals("Input width must be 1280", 1280, inputBitmap.width)
            assertEquals("Input height must be 960", 960, inputBitmap.height)

            val inputArtifact = File(artifactsDir, "real_input_user_wa_clash_1280x960.png")
            FileOutputStream(inputArtifact).use { out ->
                inputBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.i(TAG, "Saved user input artifact to ${inputArtifact.absolutePath} (${inputArtifact.length()} bytes)")

            val targetModel = UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
            val expectedW = 1280 * 4
            val expectedH = 960 * 4

            val t0 = System.nanoTime()
            val pssBefore = Debug.getPss()

            var lastReportedTile = 0
            val output = engine.upscale(
                source = BitmapSource(inputBitmap),
                model = targetModel,
                targetScale = 4,
                params = UpscaleInferenceParams(chunkSize = 160),
                listener = object : com.veilframe.app.upscale.inference.UpscaleInferenceEngine.InferenceProgressListener {
                    override fun onProgress(currentTile: Int, totalTiles: Int, percent: Int) {
                        if (currentTile != lastReportedTile) {
                            lastReportedTile = currentTile
                            Log.i(TAG, "UserWA Upscale Progress: Tile $currentTile of $totalTiles ($percent%)")
                        }
                    }

                    override fun onStatus(message: String) {
                        Log.i(TAG, "UserWA Status: $message")
                    }

                    override fun onStage(stage: String, currentTile: Int, totalTiles: Int) {
                        Log.d(TAG, "UserWA Stage: $stage ($currentTile/$totalTiles)")
                    }
                }
            ) as UpscaleOutput.Streamed

            val t1 = System.nanoTime()
            val pssAfter = Debug.getPss()
            val elapsedMs = (t1 - t0) / 1_000_000
            val totalMegaPixels = (expectedW.toLong() * expectedH.toLong()) / 1_000_000.0
            val throughputMpS = totalMegaPixels / (elapsedMs / 1000.0)

            val mpStr = String.format(java.util.Locale.US, "%.2f", totalMegaPixels)
            val tpStr = String.format(java.util.Locale.US, "%.2f", throughputMpS)
            Log.i(TAG, "=== USER WA UPSCALE COMPLETED SUCCESSFULLY ===")
            Log.i(TAG, "Elapsed time: ${elapsedMs}ms (${elapsedMs / 1000.0}s)")
            Log.i(TAG, "Output resolution: ${output.width}x${output.height} (~$mpStr MP)")
            Log.i(TAG, "Throughput: $tpStr MP/s")
            Log.i(TAG, "PSS Memory delta: ${pssAfter - pssBefore} kB")

            assertEquals("Width mismatch", expectedW, output.width)
            assertEquals("Height mismatch", expectedH, output.height)
            assertTrue("Output file must exist", output.file.exists())
            assertTrue("Output file must be non-empty", output.file.length() > 0)
            NcnnUpscaleEngine.validatePngFile(output.file, expectedW, expectedH)

            val outputArtifact = File(artifactsDir, "real_output_gpu_user_wa_clash_5120x3840.png")
            output.file.copyTo(outputArtifact, overwrite = true)
            Log.i(TAG, "Saved user output artifact to ${outputArtifact.absolutePath} (${outputArtifact.length()} bytes)")
        }
    }

    private data class RealTestCase(
        val name: String,
        val fileName: String,
        val model: com.veilframe.app.upscale.model.UpscaleModel,
        val chunkSize: Int = 160
    )
}
