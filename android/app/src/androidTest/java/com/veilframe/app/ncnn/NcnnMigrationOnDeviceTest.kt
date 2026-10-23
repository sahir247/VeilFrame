package com.veilframe.app.ncnn

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.cv.segmentation.ncnn.NcnnMattingNative
import com.veilframe.app.cv.segmentation.rembg.RembgModel
import com.veilframe.app.cv.segmentation.rembg.RembgModelDownloadManager
import com.veilframe.app.cv.segmentation.rembg.RembgModelRepository
import com.veilframe.app.media.rife.RifeModelManager
import com.veilframe.app.media.rife.RifeNcnnNative
import com.veilframe.app.privacy.PiiNcnnEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

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
        assertTrue("Should detect face/person in portrait photo", detections.isNotEmpty())

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
}
