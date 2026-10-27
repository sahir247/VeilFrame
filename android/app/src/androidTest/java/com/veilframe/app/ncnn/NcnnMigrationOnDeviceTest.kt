package com.veilframe.app.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.os.Debug
import com.veilframe.app.cv.segmentation.ncnn.NcnnMattingNative
import com.veilframe.app.cv.segmentation.rembg.CelBackgroundRemover
import com.veilframe.app.cv.segmentation.rembg.MultiPersonMattingEngine
import com.veilframe.app.cv.segmentation.rembg.RembgModel
import com.veilframe.app.cv.segmentation.rembg.RembgModelDownloadManager
import com.veilframe.app.cv.segmentation.rembg.RembgModelRepository
import com.veilframe.app.media.rife.RifeModelManager
import com.veilframe.app.media.rife.RifeNcnnNative
import com.veilframe.app.privacy.PiiNcnnEngine
import com.veilframe.app.upscale.inference.AlgorithmicUpscaler
import com.veilframe.app.upscale.inference.AdaptiveDetailEnhancer
import com.veilframe.app.cv.core.CvRuntime
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter

@RunWith(AndroidJUnit4::class)
class NcnnMigrationOnDeviceTest {

    companion object {
        private const val TAG = "NcnnMigrationTest"
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun loadTestPortrait(): Bitmap {
        val candidates = listOf(
            File("/data/local/tmp/test_portrait.jpg"),
            File(context.cacheDir, "test_portrait.jpg"),
            File(context.getExternalFilesDir(null), "test_portrait.jpg"),
            File("/sdcard/Download/test_portrait.jpg")
        )
        for (f in candidates) {
            try {
                if (f.exists()) {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp != null) {
                        Log.i(TAG, "Loaded test portrait from ${f.absolutePath}: ${bmp.width}x${bmp.height}")
                        return bmp
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not load from ${f.absolutePath}: ${t.message}")
            }
        }
        val bmp = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val bgPaint = android.graphics.Paint().apply { color = Color.rgb(30, 80, 40) }
        canvas.drawRect(0f, 0f, 320f, 320f, bgPaint)
        val skinPaint = android.graphics.Paint().apply { color = Color.rgb(220, 170, 130) }
        canvas.drawCircle(160f, 160f, 80f, skinPaint)
        return bmp
    }

    @Test
    fun test1_NcnnMattingU2NetP_BundledInference() {
        Log.i(TAG, "=== Testing NCNN Matting (U^2-Net-P) ===")

        val initSuccess = NcnnMattingNative.nativeInit(
            assetManager = context.assets,
            paramPath = "models/u2netp/u2netp.ncnn.param",
            binPath = "models/u2netp/u2netp.ncnn.bin",
            modelType = NcnnMattingNative.MODEL_TYPE_U2NETP,
            useGpu = false,
            isAsset = true
        )
        assertTrue("U^2-Net-P nativeInit must succeed from APK assets", initSuccess)

        val inputBitmap = loadTestPortrait()
        assertNotNull("Input bitmap must not be null", inputBitmap)

        val outMask = NcnnMattingNative.nativeProcess(
            srcBitmap = inputBitmap,
            modelType = NcnnMattingNative.MODEL_TYPE_U2NETP,
            useGpu = false
        )
        assertNotNull("nativeProcess must return cutout bitmap", outMask)

        // Verify the cutout has transparency and foreground content
        var transparentCount = 0
        var opaqueCount = 0
        val sampleStep = 10
        for (y in 0 until outMask!!.height step sampleStep) {
            for (x in 0 until outMask.width step sampleStep) {
                val alpha = Color.alpha(outMask.getPixel(x, y))
                if (alpha < 30) transparentCount++
                if (alpha > 200) opaqueCount++
            }
        }
        Log.i(TAG, "U2NETP cutout check: opaque=$opaqueCount, transparent=$transparentCount")
        assertTrue("Cutout must have transparent background pixels", transparentCount > 0)
        assertTrue("Cutout must have opaque foreground pixels", opaqueCount > 0)

        // Save result to app's external files directory
        val outDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val outFile = File(outDir, "u2netp_cutout_result.png")
        FileOutputStream(outFile).use { fos ->
            outMask.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        Log.i(TAG, "Saved cutout result to ${outFile.absolutePath} (${outFile.length()} bytes)")

        assertTrue("isModelLoaded should be true while initialized", NcnnMattingNative.isModelLoaded(NcnnMattingNative.MODEL_TYPE_U2NETP))

        NcnnMattingNative.nativeRelease()
        assertTrue("isModelLoaded should be false after nativeRelease", !NcnnMattingNative.isModelLoaded(NcnnMattingNative.MODEL_TYPE_U2NETP))
    }

    @Test
    fun test1b_NcnnMatting_AspectPreservingLetterbox_NonSquare() {
        Log.i(TAG, "=== Testing Aspect-Preserving Letterboxing on Non-Square Images ===")
        val initSuccess = NcnnMattingNative.nativeInit(
            assetManager = context.assets,
            paramPath = "models/u2netp/u2netp.ncnn.param",
            binPath = "models/u2netp/u2netp.ncnn.bin",
            modelType = NcnnMattingNative.MODEL_TYPE_U2NETP,
            useGpu = false,
            isAsset = true
        )
        assertTrue("U^2-Net-P nativeInit must succeed", initSuccess)

        // 1. Tall portrait (180 x 320 - 9:16 aspect ratio)
        val portrait = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)
        val canvasP = android.graphics.Canvas(portrait)
        canvasP.drawColor(Color.rgb(40, 40, 40))
        val paintP = android.graphics.Paint().apply { color = Color.rgb(220, 160, 120) }
        canvasP.drawCircle(90f, 160f, 60f, paintP)

        val maskP = NcnnMattingNative.nativeProcess(portrait, NcnnMattingNative.MODEL_TYPE_U2NETP, false, letterbox = true)
        assertNotNull("Portrait mask must not be null", maskP)
        assertTrue("Portrait mask width must match source", maskP!!.width == 180)
        assertTrue("Portrait mask height must match source", maskP.height == 320)

        // 2. Wide landscape (320 x 180 - 16:9 aspect ratio)
        val landscape = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvasL = android.graphics.Canvas(landscape)
        canvasL.drawColor(Color.rgb(40, 40, 40))
        val paintL = android.graphics.Paint().apply { color = Color.rgb(220, 160, 120) }
        canvasL.drawCircle(160f, 90f, 60f, paintL)

        val maskL = NcnnMattingNative.nativeProcess(landscape, NcnnMattingNative.MODEL_TYPE_U2NETP, false, letterbox = true)
        assertNotNull("Landscape mask must not be null", maskL)
        assertTrue("Landscape mask width must match source", maskL!!.width == 320)
        assertTrue("Landscape mask height must match source", maskL.height == 180)

        portrait.recycle()
        maskP.recycle()
        landscape.recycle()
        maskL.recycle()
        NcnnMattingNative.nativeRelease()
    }

    @Test
    fun test2_PiiNcnn_UltraFace_BundledInference() {
        Log.i(TAG, "=== Testing PII NCNN (UltraFace + YOLOv8n) ===")

        val inputBitmap = loadTestPortrait()
        assertNotNull(inputBitmap)

        val piiEngine = PiiNcnnEngine(context)
        val detections = piiEngine.detect(inputBitmap, confThreshold = 0.40f, iouThreshold = 0.45f)
        assertNotNull("Detections must not be null", detections)
        Log.i(TAG, "Detected ${detections.size} PII entities")
        for (d in detections) {
            Log.i(TAG, "Entity: ${d.piiClass} conf=${d.score} rect=${d.rect}")
        }
        val hasRealPhoto = listOf(
            File("/data/local/tmp/test_portrait.jpg"),
            File(context.cacheDir, "test_portrait.jpg"),
            File(context.getExternalFilesDir(null), "test_portrait.jpg"),
            File("/sdcard/Download/test_portrait.jpg")
        ).any { it.exists() }
        if (hasRealPhoto) {
            assertTrue("Should detect face/person in portrait photo", detections.isNotEmpty())
        } else {
            Log.w(TAG, "No real portrait file present at test paths; verified PII engine executes without errors.")
        }

        val redacted = PiiNcnnEngine.applySolidRedaction(inputBitmap, detections)
        assertNotNull("Redacted bitmap must not be null", redacted)

        val outDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val redactedFile = File(outDir, "pii_redacted_result.png")
        FileOutputStream(redactedFile).use { fos ->
            redacted.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        Log.i(TAG, "Saved PII redacted result to ${redactedFile.absolutePath} (${redactedFile.length()} bytes)")

        piiEngine.close()
    }

    @Test
    fun test3_RifeNcnn_NativeAvailable() {
        Log.i(TAG, "=== Testing RIFE NCNN Native JNI Binding ===")
        val result = RifeNcnnNative.nativeInit("/data/local/tmp/nonexistent_rife", false)
        Log.i(TAG, "RifeNcnnNative.nativeInit returned $result (expected false for missing files)")
        assertTrue("nativeInit must return false for nonexistent directory", !result)
        RifeNcnnNative.nativeRelease()
    }

    @Test
    fun test4_Rembg_DownloadModNet_And_Inference() = runBlocking {
        Log.i(TAG, "=== Testing Rembg MODNet Download & On-Device Inference ===")
        val repo = RembgModelRepository(context)
        val downloadMgr = RembgModelDownloadManager(context, repo)

        val downloadResult = downloadMgr.downloadModel(RembgModel.MODNET)
        Log.i(TAG, "MODNet download result: $downloadResult")
        assertTrue("MODNet download must succeed", downloadResult.isSuccess)

        val isReady = repo.isModelReady(RembgModel.MODNET)
        assertTrue("MODNet must be ready in repository", isReady)

        val binFile = repo.getModelFile(RembgModel.MODNET)
        val paramFile = repo.getParamFile(RembgModel.MODNET)
        assertTrue("bin file must exist and be > 10MB", binFile.exists() && binFile.length() > 10_000_000L)
        assertTrue("param file must exist", paramFile.exists() && paramFile.length() > 1_000L)

        val initSuccess = NcnnMattingNative.nativeInit(
            assetManager = context.assets,
            paramPath = paramFile.absolutePath,
            binPath = binFile.absolutePath,
            modelType = NcnnMattingNative.MODEL_TYPE_MODNET,
            useGpu = false,
            isAsset = false
        )
        assertTrue("MODNet nativeInit must succeed from downloaded files", initSuccess)

        val inputBitmap = loadTestPortrait()
        val outMask = NcnnMattingNative.nativeProcess(
            srcBitmap = inputBitmap,
            modelType = NcnnMattingNative.MODEL_TYPE_MODNET,
            useGpu = false
        )
        assertNotNull("MODNet nativeProcess must return cutout bitmap", outMask)

        var transparentCount = 0
        var opaqueCount = 0
        val sampleStep = 10
        for (y in 0 until outMask!!.height step sampleStep) {
            for (x in 0 until outMask.width step sampleStep) {
                val alpha = Color.alpha(outMask.getPixel(x, y))
                if (alpha < 30) transparentCount++
                if (alpha > 200) opaqueCount++
            }
        }
        Log.i(TAG, "MODNet cutout check: opaque=$opaqueCount, transparent=$transparentCount")
        assertTrue("MODNet cutout must have transparent pixels", transparentCount > 0)
        assertTrue("MODNet cutout must have opaque pixels", opaqueCount > 0)

        val outDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val outFile = File(outDir, "modnet_cutout_result.png")
        FileOutputStream(outFile).use { fos ->
            outMask.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        Log.i(TAG, "Saved MODNet cutout result to ${outFile.absolutePath} (${outFile.length()} bytes)")
        NcnnMattingNative.nativeRelease()
    }

    @Test
    fun test5_Rife_DownloadModel_And_Interpolate() = runBlocking {
        Log.i(TAG, "=== Testing RIFE v4 Download & On-Device Synthesis ===")
        val rifeMgr = RifeModelManager(context)
        val downloadResult = rifeMgr.ensureModelDownloaded { pct, msg ->
            Log.i(TAG, "RIFE Download progress: $pct% - $msg")
        }
        Log.i(TAG, "RIFE download result: $downloadResult")
        assertTrue("RIFE model download must succeed", downloadResult.isSuccess)
        assertTrue("RIFE model must be ready", rifeMgr.isModelReady())

        val initSuccess = RifeNcnnNative.nativeInit(rifeMgr.modelDir.absolutePath, false)
        assertTrue("RifeNcnnNative.nativeInit must succeed with downloaded model directory", initSuccess)

        val frame0 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas0 = android.graphics.Canvas(frame0)
        canvas0.drawColor(Color.BLUE)

        val frame1 = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas1 = android.graphics.Canvas(frame1)
        canvas1.drawColor(Color.RED)

        val interpolated = RifeNcnnNative.nativeInterpolate(frame0, frame1, 0.5f)
        assertNotNull("RifeNcnnNative.nativeInterpolate must return interpolated bitmap", interpolated)
        Log.i(TAG, "RIFE synthesized frame dimensions: ${interpolated!!.width}x${interpolated.height}")

        val outDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val outFile = File(outDir, "rife_interpolated_frame.png")
        FileOutputStream(outFile).use { fos ->
            interpolated.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        Log.i(TAG, "Saved RIFE synthesized frame to ${outFile.absolutePath} (${outFile.length()} bytes)")
        RifeNcnnNative.nativeRelease()
    }

    @Test
    fun test6_Rembg_ComprehensiveBenchmark_RealPhotos() {
        runBlocking {
            Log.i(TAG, "==========================================================================")
            Log.i(TAG, "=== REAL-LIFE BENCHMARK: 5 Real Photos x 2 Models (U2-Net-P & MODNet) ===")
            Log.i(TAG, "==========================================================================")

            val outDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "bench_output")
            outDir.mkdirs()
            val sdcardOutDir = File("/sdcard/Download/bench/output")

        data class BenchmarkPhoto(
            val category: String,
            val filename: String
        )

        val photos = listOf(
            BenchmarkPhoto("Human Group", "1_group_human.jpg"),
            BenchmarkPhoto("Portrait", "2_portrait_human.jpg"),
            BenchmarkPhoto("Normal Photo", "3_normal_photo.jpg"),
            BenchmarkPhoto("Animal", "4_animal_bear.jpg"),
            BenchmarkPhoto("Object", "5_object_chair.jpg")
        )

        data class ModelConfig(
            val modelType: Int,
            val id: String,
            val displayName: String,
            val isBundled: Boolean
        )

        val models = listOf(
            ModelConfig(NcnnMattingNative.MODEL_TYPE_U2NETP, "u2netp", "U^2-Net-P (Bundled)", true),
            ModelConfig(NcnnMattingNative.MODEL_TYPE_MODNET, "modnet", "MODNet (HD Matting)", false)
        )

        val reportJson = JSONObject()
        val resultsArray = JSONArray()

        for (model in models) {
            Log.i(TAG, "--------------------------------------------------")
            Log.i(TAG, "Initializing Model: ${model.displayName} (type=${model.modelType})")
            Log.i(TAG, "--------------------------------------------------")

            val initSuccess = if (model.isBundled) {
                NcnnMattingNative.nativeInit(
                    assetManager = context.assets,
                    paramPath = "models/u2netp/u2netp.ncnn.param",
                    binPath = "models/u2netp/u2netp.ncnn.bin",
                    modelType = model.modelType,
                    useGpu = false,
                    isAsset = true
                )
            } else {
                val pushedBin = File("/data/local/tmp/bench/models/modnet.ncnn.bin")
                val pushedParam = File("/data/local/tmp/bench/models/modnet.ncnn.param")
                val (binFile, paramFile) = if (pushedBin.exists() && pushedParam.exists()) {
                    Pair(pushedBin, pushedParam)
                } else {
                    val repo = RembgModelRepository(context)
                    val downloadMgr = RembgModelDownloadManager(context, repo)
                    downloadMgr.downloadModel(RembgModel.MODNET)
                    Pair(repo.getModelFile(RembgModel.MODNET), repo.getParamFile(RembgModel.MODNET))
                }
                NcnnMattingNative.nativeInit(
                    assetManager = context.assets,
                    paramPath = paramFile.absolutePath,
                    binPath = binFile.absolutePath,
                    modelType = model.modelType,
                    useGpu = false,
                    isAsset = false
                )
            }
            assertTrue("nativeInit must succeed for ${model.displayName}", initSuccess)

            for (photo in photos) {
                val inputCandidates = listOf(
                    File("/data/local/tmp/bench/input/${photo.filename}"),
                    File("/sdcard/Download/bench/input/${photo.filename}"),
                    File(context.cacheDir, photo.filename)
                )
                val photoFile = inputCandidates.firstOrNull { it.exists() }
                assertNotNull("Benchmark photo file ${photo.filename} must exist", photoFile)

                val originalBitmap = BitmapFactory.decodeFile(photoFile!!.absolutePath)
                assertNotNull("Failed to decode photo: ${photo.filename}", originalBitmap)
                val srcBitmap = if (originalBitmap.config == Bitmap.Config.ARGB_8888) {
                    originalBitmap
                } else {
                    originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                }

                val srcW = srcBitmap.width
                val srcH = srcBitmap.height
                val aspectRatioStr = String.format("%.2f:1 (%dx%d)", srcW.toFloat() / srcH, srcW, srcH)
                Log.i(TAG, "\n>>> Evaluating Category: [${photo.category}] Photo: ${photo.filename} - Res: $aspectRatioStr on ${model.displayName}")

                // Run Legacy (Baseline)
                System.gc()
                Thread.sleep(50)
                val memInfoLegacy = Debug.MemoryInfo()
                Debug.getMemoryInfo(memInfoLegacy)
                val nativeHeapBeforeLegacy = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)

                val t0Legacy = System.nanoTime()
                val legacyMask = NcnnMattingNative.nativeProcessLegacy(srcBitmap, model.modelType, false)
                val tLegacyMs = (System.nanoTime() - t0Legacy) / 1_000_000.0
                assertNotNull("Legacy mask must not be null", legacyMask)

                Debug.getMemoryInfo(memInfoLegacy)
                val nativeHeapAfterLegacy = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
                val pssLegacyMb = memInfoLegacy.totalPss / 1024.0

                // Create legacy cutout
                val legacyCutout = CelBackgroundRemover.applyFullResolutionColors(srcBitmap, legacyMask!!)
                val legacyFilename = "legacy_${model.id}_${photo.category.replace(' ', '_').lowercase()}.png"
                val legacyOutFile = File(outDir, legacyFilename)
                FileOutputStream(legacyOutFile).use { fos ->
                    legacyCutout.compress(Bitmap.CompressFormat.PNG, 95, fos)
                }
                val legacySdcardFile = File(sdcardOutDir, legacyFilename)
                runCatching {
                    sdcardOutDir.mkdirs()
                    FileOutputStream(legacySdcardFile).use { fos ->
                        legacyCutout.compress(Bitmap.CompressFormat.PNG, 95, fos)
                    }
                }
                legacyMask.recycle()
                legacyCutout.recycle()

                // Run Patched (New Code) - Cold run
                System.gc()
                Thread.sleep(50)
                val memInfoPatched = Debug.MemoryInfo()
                Debug.getMemoryInfo(memInfoPatched)
                val nativeHeapBeforePatched = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)

                val t0Patched = System.nanoTime()
                val patchedMask = MultiPersonMattingEngine.process(context, srcBitmap, model.modelType, false)
                val tPatchedColdMs = (System.nanoTime() - t0Patched) / 1_000_000.0
                assertNotNull("Patched mask must not be null", patchedMask)

                Debug.getMemoryInfo(memInfoPatched)
                val nativeHeapAfterPatched = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
                val pssPatchedMb = memInfoPatched.totalPss / 1024.0

                // Create patched cutout
                val patchedCutout = CelBackgroundRemover.applyFullResolutionColors(srcBitmap, patchedMask!!)
                val patchedFilename = "patched_${model.id}_${photo.category.replace(' ', '_').lowercase()}.png"
                val patchedOutFile = File(outDir, patchedFilename)
                FileOutputStream(patchedOutFile).use { fos ->
                    patchedCutout.compress(Bitmap.CompressFormat.PNG, 95, fos)
                }
                val patchedSdcardFile = File(sdcardOutDir, patchedFilename)
                runCatching {
                    sdcardOutDir.mkdirs()
                    FileOutputStream(patchedSdcardFile).use { fos ->
                        patchedCutout.compress(Bitmap.CompressFormat.PNG, 95, fos)
                    }
                }
                patchedMask.recycle()
                patchedCutout.recycle()

                // Memory Leak & Warm Latency Monitoring: 4 additional consecutive warm runs
                val warmLatencies = mutableListOf<Double>()
                val heapAcrossRuns = mutableListOf<Double>()
                heapAcrossRuns.add(nativeHeapAfterPatched)

                for (runIdx in 2..5) {
                    val tWarm0 = System.nanoTime()
                    val warmMask = MultiPersonMattingEngine.process(context, srcBitmap, model.modelType, false)
                    val tWarmMs = (System.nanoTime() - tWarm0) / 1_000_000.0
                    warmLatencies.add(tWarmMs)
                    val heapMb = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
                    heapAcrossRuns.add(heapMb)
                    warmMask?.recycle()
                }

                val avgWarmLatencyMs = warmLatencies.average()
                val heapLeakDeltaMb = heapAcrossRuns.last() - heapAcrossRuns.first()

                Log.i(TAG, "   [LEGACY]  Latency: ${String.format("%.1f", tLegacyMs)} ms | Native Heap: ${String.format("%.2f", nativeHeapAfterLegacy)} MB | Total PSS: ${String.format("%.2f", pssLegacyMb)} MB")
                Log.i(TAG, "   [PATCHED] Latency Cold: ${String.format("%.1f", tPatchedColdMs)} ms | Warm Avg: ${String.format("%.1f", avgWarmLatencyMs)} ms | Native Heap: ${String.format("%.2f", nativeHeapAfterPatched)} MB | Total PSS: ${String.format("%.2f", pssPatchedMb)} MB")
                Log.i(TAG, "   [STABILITY] Leak Delta (Run 5 - Run 1): ${String.format("%.3f", heapLeakDeltaMb)} MB (5 runs heap: ${heapAcrossRuns.map { String.format("%.2f", it) }})")

                val photoStat = JSONObject().apply {
                    put("category", photo.category)
                    put("filename", photo.filename)
                    put("model", model.displayName)
                    put("model_id", model.id)
                    put("resolution", "${srcW}x${srcH}")
                    put("aspect_ratio", aspectRatioStr)
                    put("legacy_latency_ms", tLegacyMs)
                    put("legacy_heap_mb", nativeHeapAfterLegacy)
                    put("legacy_pss_mb", pssLegacyMb)
                    put("legacy_file", legacyFilename)
                    put("patched_latency_cold_ms", tPatchedColdMs)
                    put("patched_latency_warm_avg_ms", avgWarmLatencyMs)
                    put("patched_heap_mb", nativeHeapAfterPatched)
                    put("patched_pss_mb", pssPatchedMb)
                    put("patched_file", patchedFilename)
                    put("warm_latencies_ms", JSONArray(warmLatencies))
                    put("heap_across_runs_mb", JSONArray(heapAcrossRuns))
                    put("heap_leak_delta_mb", heapLeakDeltaMb)
                }
                resultsArray.put(photoStat)

                if (srcBitmap !== originalBitmap) {
                    srcBitmap.recycle()
                }
                originalBitmap.recycle()
            }

            NcnnMattingNative.nativeRelease()
        }

        reportJson.put("device", "Pixel_9a (Android API 37, ARM64-v8a)")
        reportJson.put("results", resultsArray)

        val reportFile = File(outDir, "benchmark_report.json")
        FileWriter(reportFile).use { writer ->
            writer.write(reportJson.toString(2))
        }
        runCatching {
            sdcardOutDir.mkdirs()
            val sdcardReportFile = File(sdcardOutDir, "benchmark_report.json")
            FileWriter(sdcardReportFile).use { writer ->
                writer.write(reportJson.toString(2))
            }
        }
        Log.i(TAG, "Saved full benchmark JSON to ${reportFile.absolutePath} (${reportFile.length()} bytes)")
        Log.i(TAG, "==========================================================================")
        }
    }

    private fun computeLaplacianVariance(bitmap: Bitmap): Double {
        if (CvRuntime.isNativeAvailable) {
            try {
                val mat = Mat()
                org.opencv.android.Utils.bitmapToMat(bitmap, mat)
                val gray = Mat()
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                val laplacian = Mat()
                Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
                val mean = MatOfDouble()
                val stddev = MatOfDouble()
                Core.meanStdDev(laplacian, mean, stddev)
                val std = stddev.toArray().getOrNull(0) ?: 0.0
                mat.release()
                gray.release()
                laplacian.release()
                return std * std
            } catch (_: Throwable) {}
        }

        // Direct pixel-based Laplacian variance calculation (zero dependency fallback)
        val w = bitmap.width
        val h = bitmap.height
        val step = maxOf(1, minOf(w, h) / 300) // sample grid for performance on huge bitmaps
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in step until (h - step) step step) {
            val yOffset = y * w
            val yPrev = (y - step) * w
            val yNext = (y + step) * w
            for (x in step until (w - step) step step) {
                fun lum(p: Int): Double {
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    return 0.299 * r + 0.587 * g + 0.114 * b
                }
                val c = lum(pixels[yOffset + x])
                val left = lum(pixels[yOffset + x - step])
                val right = lum(pixels[yOffset + x + step])
                val up = lum(pixels[yPrev + x])
                val down = lum(pixels[yNext + x])

                val lap = 4.0 * c - left - right - up - down
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count) - (mean * mean)
    }

    @Test
    fun test7_Upscale_ComprehensiveBenchmark_RealPhotos() {
        Log.i(TAG, "==========================================================================")
        Log.i(TAG, "=== REAL-LIFE BENCHMARK: Image Upscaling & Adaptive Detail Enhancement ===")
        Log.i(TAG, "==========================================================================")

        val outDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "upscale_output")
        outDir.mkdirs()
        val sdcardOutDir = File("/sdcard/Download/bench/output")
        sdcardOutDir.mkdirs()

        val photoFilenames = listOf(
            "1_group_human.jpg",
            "2_portrait_human.jpg",
            "3_normal_photo.jpg",
            "4_animal_bear.jpg",
            "5_object_chair.jpg"
        )

        val upscaleReportJson = JSONObject()
        val upscaleResults = JSONArray()

        for (filename in photoFilenames) {
            val inputCandidates = listOf(
                File("/data/local/tmp/bench/input/$filename"),
                File("/sdcard/Download/bench/input/$filename"),
                File(context.cacheDir, filename)
            )
            val photoFile = inputCandidates.firstOrNull { it.exists() }
            if (photoFile == null) {
                Log.w(TAG, "Photo $filename not found, skipping upscale test")
                continue
            }

            val originalBitmap = BitmapFactory.decodeFile(photoFile.absolutePath) ?: continue
            val srcW = originalBitmap.width
            val srcH = originalBitmap.height
            val targetW = srcW * 2
            val targetH = srcH * 2

            Log.i(TAG, "\n>>> Benchmarking Upscale on [$filename] (${srcW}x${srcH} -> 2x ${targetW}x${targetH})")

            // 0. Compute baseline original sharpness
            val originalSharpness = computeLaplacianVariance(originalBitmap)

            // 1. Baseline Bicubic 2x
            System.gc()
            val t0Bicubic = System.nanoTime()
            val bicubicBitmap = AlgorithmicUpscaler.scaleBicubic(originalBitmap, targetW, targetH)
            val tBicubicMs = (System.nanoTime() - t0Bicubic) / 1_000_000.0
            val bicubicSharpness = computeLaplacianVariance(bicubicBitmap)

            // 2. OpenCV SIMD Lanczos-4 2x
            System.gc()
            val t0Lanczos = System.nanoTime()
            val lanczosBitmap = AlgorithmicUpscaler.scaleLanczos(originalBitmap, targetW, targetH)
            val tLanczosMs = (System.nanoTime() - t0Lanczos) / 1_000_000.0
            val lanczosSharpness = computeLaplacianVariance(lanczosBitmap)

            // 3. Lanczos-4 + Adaptive Detail Enhancer (strength = 0.35f)
            System.gc()
            val t0Enhance = System.nanoTime()
            val enhancedBitmap = AdaptiveDetailEnhancer.enhance(lanczosBitmap, 0.35f)
            val tEnhanceMs = (System.nanoTime() - t0Enhance) / 1_000_000.0
            val enhancedSharpness = computeLaplacianVariance(enhancedBitmap)

            val baseName = filename.substringBeforeLast('.')
            val bicubicFile = File(sdcardOutDir, "upscale_bicubic_${baseName}.png")
            val lanczosFile = File(sdcardOutDir, "upscale_lanczos_${baseName}.png")
            val enhancedFile = File(sdcardOutDir, "upscale_enhanced_${baseName}.png")

            FileOutputStream(bicubicFile).use { bicubicBitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
            FileOutputStream(lanczosFile).use { lanczosBitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
            FileOutputStream(enhancedFile).use { enhancedBitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }

            Log.i(TAG, "   [ORIGINAL] Sharpness: ${String.format("%.1f", originalSharpness)}")
            Log.i(TAG, "   [BICUBIC]  Latency: ${String.format("%.1f", tBicubicMs)} ms | Sharpness: ${String.format("%.1f", bicubicSharpness)}")
            Log.i(TAG, "   [LANCZOS4] Latency: ${String.format("%.1f", tLanczosMs)} ms | Sharpness: ${String.format("%.1f", lanczosSharpness)}")
            Log.i(TAG, "   [ENHANCED] Latency: ${String.format("%.1f", tLanczosMs + tEnhanceMs)} ms (Enhance: ${String.format("%.1f", tEnhanceMs)} ms) | Sharpness: ${String.format("%.1f", enhancedSharpness)}")
            Log.i(TAG, "   [GAIN]     Sharpness gain over Bicubic: +${String.format("%.1f", (enhancedSharpness / maxOf(1.0, bicubicSharpness) - 1.0) * 100)}%")

            val stat = JSONObject().apply {
                put("filename", filename)
                put("src_resolution", "${srcW}x${srcH}")
                put("dst_resolution", "${targetW}x${targetH}")
                put("original_sharpness", originalSharpness)
                put("bicubic_latency_ms", tBicubicMs)
                put("bicubic_sharpness", bicubicSharpness)
                put("lanczos_latency_ms", tLanczosMs)
                put("lanczos_sharpness", lanczosSharpness)
                put("enhance_latency_ms", tEnhanceMs)
                put("total_enhanced_latency_ms", tLanczosMs + tEnhanceMs)
                put("enhanced_sharpness", enhancedSharpness)
                put("sharpness_gain_pct", (enhancedSharpness / maxOf(1.0, bicubicSharpness) - 1.0) * 100)
            }
            upscaleResults.put(stat)

            bicubicBitmap.recycle()
            if (enhancedBitmap !== lanczosBitmap) {
                enhancedBitmap.recycle()
            }
            lanczosBitmap.recycle()
            originalBitmap.recycle()
        }

        upscaleReportJson.put("device", "Pixel_9a (Android API 37, ARM64-v8a)")
        upscaleReportJson.put("results", upscaleResults)

        val reportFile = File(sdcardOutDir, "upscale_report.json")
        FileWriter(reportFile).use { it.write(upscaleReportJson.toString(2)) }
        Log.i(TAG, "Saved upscale benchmark JSON to ${reportFile.absolutePath}")
        Log.i(TAG, "==========================================================================")
    }
}

