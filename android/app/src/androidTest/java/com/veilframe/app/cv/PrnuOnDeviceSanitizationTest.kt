package com.veilframe.app.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.prnu.PrnuSanitizerEngine
import com.veilframe.app.cv.prnu.ncnn.VeilNetNanoNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream
import java.util.Random

@RunWith(AndroidJUnit4::class)
class PrnuOnDeviceSanitizationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        CvRuntime.initialize()
    }

    @Test
    fun testVeilNetNativeLoadAndPurifyOnDevice() {
        val loaded = VeilNetNanoNative.ensureInitialized(context, useGpu = false)
        assertTrue("VeilNet-Nano model must load successfully from APK assets", loaded)
        assertTrue("nativeIsLoaded must report true", VeilNetNanoNative.nativeIsLoaded())

        // Create a 512x512 test bitmap with realistic color gradients and noise
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        for (i in 0 until 512 step 8) {
            paint.color = Color.rgb((i / 2) % 256, (255 - i / 2) % 256, 128)
            canvas.drawRect(0f, i.toFloat(), 512f, (i + 8).toFloat(), paint)
        }

        val purified = VeilNetNanoNative.purify(
            context = context,
            src = bmp,
            useGpu = false,
            coreSize = 480,
            halo = 16
        )

        assertNotNull("Purified bitmap must not be null", purified)
        assertEquals(bmp.width, purified.width)
        assertEquals(bmp.height, purified.height)

        VeilNetNanoNative.release()
    }

    @Test
    fun testPrnuSanitizerPipelineOnDevice() {
        val testDir = File(context.cacheDir, "prnu_device_test").apply { mkdirs() }
        val inputFile = File(testDir, "test_input.jpg")
        val outputFile = File(testDir, "test_output.jpg")

        // Create a synthetic camera capture with fine noise
        val bmp = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val rng = Random(42)
        val pixels = IntArray(1024 * 1024)
        for (y in 0 until 1024) {
            for (x in 0 until 1024) {
                val base = (y * 255 / 1024)
                val noise = rng.nextInt(15) - 7
                val c = (base + noise).coerceIn(0, 255)
                pixels[y * 1024 + x] = Color.rgb(c, c, c)
            }
        }
        bmp.setPixels(pixels, 0, 1024, 0, 0, 1024, 1024)
        FileOutputStream(inputFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
        }

        val milestones = mutableListOf<String>()
        val success = PrnuSanitizerEngine.sanitizeImageFile(
            context = context,
            inputFile = inputFile,
            outputFile = outputFile,
            mode = PrnuSanitizerEngine.DefenseMode.STEALTH_HYBRID,
            onMilestone = { milestones.add(it) }
        )

        assertTrue("Sanitization on physical device must succeed", success)
        assertTrue("Output file must exist and have non-zero length", outputFile.exists() && outputFile.length() > 0)
        assertTrue("Must complete Stage 1 VeilNet", milestones.any { it.contains("VeilNet-Nano") })
        assertTrue("Must complete Stage 2 Coarse Remap", milestones.any { it.contains("Stage 2") })
        assertTrue("Must complete Stage 3 Clean IJG", milestones.any { it.contains("Stage 3") })

        // Test On-Device Self-Audit Loop
        val origMat = Mat()
        val sanMat = Mat()
        val origBmp = android.graphics.BitmapFactory.decodeFile(inputFile.absolutePath)
        val sanBmp = android.graphics.BitmapFactory.decodeFile(outputFile.absolutePath)
        Utils.bitmapToMat(origBmp, origMat)
        Utils.bitmapToMat(sanBmp, sanMat)

        val audit = PrnuSanitizerEngine.auditSanitization(origMat, sanMat, maxDimension = 512)
        android.util.Log.i("PrnuTest", "Baseline sPCE: ${audit.baselineSpce}, Post sPCE: ${audit.postSpce}, MaxStrip: ${audit.post6StripMaxSpce}, Secure: ${audit.verifiedSecure}")
        assertTrue("Post sPCE must be substantially reduced compared to baseline", kotlin.math.abs(audit.postSpce) < kotlin.math.abs(audit.baselineSpce) * 0.1 || kotlin.math.abs(audit.postSpce) < 25.0)
        assertTrue("6-strip max sPCE must be suppressed compared to baseline", audit.post6StripMaxSpce < 35.0 || audit.post6StripMaxSpce < kotlin.math.abs(audit.baselineSpce) * 0.15)

        origMat.release()
        sanMat.release()
        bmp.recycle()
        origBmp.recycle()
        sanBmp.recycle()
        testDir.deleteRecursively()
    }
}
