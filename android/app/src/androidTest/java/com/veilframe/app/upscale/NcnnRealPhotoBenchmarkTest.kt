package com.veilframe.app.upscale

import android.content.Context
import android.net.Uri
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.upscale.inference.UriRegionSource
import com.veilframe.app.upscale.inference.NcnnNative
import com.veilframe.app.upscale.inference.NcnnUpscaleEngine
import com.veilframe.app.upscale.inference.ResourceLimitExceededException
import com.veilframe.app.upscale.inference.UpscaleInferenceParams
import com.veilframe.app.upscale.inference.UpscaleOutput
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileWriter

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NcnnRealPhotoBenchmarkTest {

    companion object {
        private const val TAG = "VeilFrame.RealBench"
        private const val CSV_HEADER = "model_id,model_name,photo_index,photo_file,input_w,input_h,output_w,output_h,scale,elapsed_ms,pss_delta_kb,tiles_reported,status,backend,input_sha256,output_sha256,thermal_before,thermal_after,pss_before_kb,pss_after_kb,source_mode\n"
        private val RUN_ID = java.util.UUID.randomUUID().toString()

        val REAL_PHOTO_FILES = listOf(
            "01_low_240p_portrait.jpg",
            "02_low_360p_street.jpg",
            "03_low_480p_texture.jpg",
            "04_low_thumb_macro.jpg",
            "05_low_grainy_whatsapp.jpg",
            "06_720p_real_portrait.jpg",
            "07_720p_real_street.jpg",
            "08_720p_real_nature.jpg",
            "09_720p_real_artwork.jpg",
            "10_1080p_real_human.jpg",
            "11_1080p_real_urban.jpg",
            "12_1080p_real_texture.jpg",
            "13_1080p_real_indoor.jpg",
            "14_2k_real_landscape.jpg",
            "15_2k_real_outdoor.jpg",
            "16_2k_real_vehicle.jpg",
            "17_2k_real_night.jpg",
            "18_4k_real_camera_01.jpg",
            "19_4k_real_camera_02.jpg",
            "20_4k_real_camera_03.jpg"
        )
    }

    private lateinit var context: Context
    private lateinit var repository: UpscaleModelRepository
    private lateinit var engine: NcnnUpscaleEngine
    private lateinit var inputDir: File
    private lateinit var outputDir: File
    private lateinit var csvFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = UpscaleModelRepository(context)
        engine = NcnnUpscaleEngine(context, repository)
        inputDir = File("/data/local/tmp/veilframe_validation/real_inputs_20")
        val root = File(checkNotNull(context.getExternalFilesDir(null)), "veilframe_benchmark/$RUN_ID")
        outputDir = File(root, "outputs").apply { check(isDirectory || mkdirs()) }
        csvFile = File(root, "benchmark_results.csv")
        val metadata = File(root, "run_metadata.txt")
        if (!metadata.exists()) metadata.writeText(listOf(
            "run_id=$RUN_ID", "device=${Build.MANUFACTURER} ${Build.MODEL}",
            "fingerprint=${Build.FINGERPRINT}", "abi=${Build.SUPPORTED_ABIS.joinToString()}",
            "sdk=${Build.VERSION.SDK_INT}", "gpu_count=${NcnnNative.getGpuCount()}",
            "gpu0=${if (NcnnNative.getGpuCount() > 0) NcnnNative.getGpuDeviceName(0) else "none"}",
            "base_apk_sha256=${sha256(File(context.applicationInfo.sourceDir))}",
            "timing=end-to-end engine invocation; includes model setup, PNG validation and preview",
            "memory=endpoint process PSS (not peak or GPU allocation)", "chunk_size=160",
            "backend=completed native attempt; AUTO may fall back to CPU",
            "source=URI_REGION_SOURCE; region decoding rather than full source bitmap"
        ).joinToString("\n", postfix = "\n"))

        if (!csvFile.exists()) {
            csvFile.writeText(CSV_HEADER)
        }

        // Stage models from /data/local/tmp/veilframe_validation/models
        val modelsToStage = listOf(
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2,
            UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4,
            UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS
        )
        for (m in modelsToStage) {
            val stagedParam = File("/data/local/tmp/veilframe_validation/models/${m.id}/model.param")
            val stagedBin = File("/data/local/tmp/veilframe_validation/models/${m.id}/model.bin")
            if (stagedParam.exists() && stagedBin.exists()) {
                check(sha256(stagedParam).equals(m.paramSha256, ignoreCase = true)) {
                    "Staged model param hash does not match pinned registry: ${m.id}"
                }
                check(sha256(stagedBin).equals(m.binSha256, ignoreCase = true)) {
                    "Staged model weights hash does not match pinned registry: ${m.id}"
                }
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

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: -1
        else -1

    private fun runBenchmarkForModel(model: UpscaleModel) = runBlocking {
        Log.i(TAG, "=================================================================")
        Log.i(TAG, "STARTING BENCHMARK: Model ${model.id} (${model.nativeScale}x) with 20 REAL photos")
        Log.i(TAG, "Hardware: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}) on Android ${Build.VERSION.RELEASE}")
        Log.i(TAG, "Vulkan GPU: ${if (NcnnNative.getGpuCount() > 0) NcnnNative.getGpuDeviceName(0) else "None"}")
        Log.i(TAG, "=================================================================")

        val fw = FileWriter(csvFile, true)

        try {
            for ((idx, fileName) in REAL_PHOTO_FILES.withIndex()) {
                val inputFile = File(inputDir, fileName)
                assertTrue("Input real photo $fileName must exist", inputFile.exists())

                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(inputFile.absolutePath, boundsOptions)
                val inW = boundsOptions.outWidth
                val inH = boundsOptions.outHeight

                assertTrue("Invalid image bounds for $fileName: ${inW}x${inH}", inW > 0 && inH > 0)
                // Mirror production region decoding; do not allocate the full 4K source
                // before the engine has a chance to enforce its memory contract.
                val source = UriRegionSource(context, Uri.fromFile(inputFile), inW, inH)

                var tilesCount = 0
                val inputHash = sha256(inputFile)
                val thermalBefore = thermalStatus()
                val pssBefore = Debug.getPss()
                val t0 = System.nanoTime()
                var ownedOutput: UpscaleOutput.Streamed? = null

                try {
                    val output = engine.upscale(
                        source = source,
                        model = model,
                        targetScale = model.nativeScale,
                        params = UpscaleInferenceParams(chunkSize = 160),
                        listener = object : com.veilframe.app.upscale.inference.UpscaleInferenceEngine.InferenceProgressListener {
                            override fun onProgress(currentTile: Int, totalTiles: Int, percent: Int) {
                                tilesCount = totalTiles
                            }
                            override fun onStatus(message: String) {}
                        }
                    ) as UpscaleOutput.Streamed
                    ownedOutput = output
                    val backend = checkNotNull(output.inferenceBackend) { "Missing completed inference backend" }

                    val t1 = System.nanoTime()
                    val pssAfter = Debug.getPss()
                    val thermalAfter = thermalStatus()
                    val elapsedMs = (t1 - t0) / 1_000_000
                    val pssDelta = pssAfter - pssBefore

                    assertEquals("Output width mismatch for $fileName", inW * model.nativeScale, output.width)
                    assertEquals("Output height mismatch for $fileName", inH * model.nativeScale, output.height)
                    assertTrue("Output file must exist", output.file.exists())
                    NcnnUpscaleEngine.validatePngFile(output.file, output.width, output.height)

                    // Save output artifact
                    val outDest = File(outputDir, "${model.id}_${fileName.substringBeforeLast('.')}.png")
                    output.file.copyTo(outDest, overwrite = true)

                    Log.i(
                        TAG,
                        "[%02d/20] %s | %s -> %dx%d (%dx%d) in %d ms | Tiles: %d | PSS Delta: %d kB | Size: %d KB"
                            .format(idx + 1, model.id, fileName, inW, inH, output.width, output.height, elapsedMs, tilesCount, pssDelta, outDest.length() / 1024)
                    )

                    fw.write("${model.id},\"${model.name}\",${idx + 1},$fileName,$inW,$inH,${output.width},${output.height},${model.nativeScale},$elapsedMs,$pssDelta,$tilesCount,SUCCESS,$backend,$inputHash,${sha256(outDest)},$thermalBefore,$thermalAfter,$pssBefore,$pssAfter,URI_REGION_SOURCE\n")
                    fw.flush()
                } catch (e: ResourceLimitExceededException) {
                    val t1 = System.nanoTime()
                    val elapsedMs = (t1 - t0) / 1_000_000
                    Log.w(
                        TAG,
                        "[%02d/20] %s | %s (%dx%d) -> SAFELY GUARDED BY RESOURCE ESTIMATOR: %s in %d ms"
                            .format(idx + 1, model.id, fileName, inW, inH, e.message, elapsedMs)
                    )
                    val after = Debug.getPss()
                    fw.write("${model.id},\"${model.name}\",${idx + 1},$fileName,$inW,$inH,0,0,${model.nativeScale},$elapsedMs,${after - pssBefore},0,SAFETY_CAP_EXCEEDED,none,$inputHash,,$thermalBefore,${thermalStatus()},$pssBefore,$after,URI_REGION_SOURCE\n")
                    fw.flush()
                } catch (failure: Throwable) {
                    val after = Debug.getPss()
                    val elapsed = (System.nanoTime() - t0) / 1_000_000
                    fw.write("${model.id},\"${model.name}\",${idx + 1},$fileName,$inW,$inH,0,0,${model.nativeScale},$elapsed,${after - pssBefore},0,FAILED,${ownedOutput?.inferenceBackend ?: "unknown"},$inputHash,,$thermalBefore,${thermalStatus()},$pssBefore,$after,URI_REGION_SOURCE\n")
                    fw.flush()
                    throw failure
                } finally {
                    ownedOutput?.discardUnclaimed()
                    source.close()
                }
            }
        } finally {
            fw.close()
        }
    }

    @Test
    fun test01_Benchmark_AnimeVideoV3_2x_Full20RealPhotos() {
        runBenchmarkForModel(UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2)
    }

    @Test
    fun test02_Benchmark_AnimeVideoV3_4x_Full20RealPhotos() {
        runBenchmarkForModel(UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4)
    }

    @Test
    fun test03_Benchmark_RealESRGAN_x4plus_Full20RealPhotos() {
        runBenchmarkForModel(UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS)
    }
}
