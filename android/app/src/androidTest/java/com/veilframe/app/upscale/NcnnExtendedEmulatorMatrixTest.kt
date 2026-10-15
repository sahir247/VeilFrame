package com.veilframe.app.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.upscale.inference.AlgorithmicUpscaler
import com.veilframe.app.upscale.inference.BitmapSource
import com.veilframe.app.upscale.inference.NcnnUpscaleEngine
import com.veilframe.app.upscale.inference.StreamingPngWriter
import com.veilframe.app.upscale.inference.UpscaleInferenceParams
import com.veilframe.app.upscale.inference.UpscaleOutput
import com.veilframe.app.upscale.model.ModelType
import com.veilframe.app.upscale.model.UpscaleModel
import com.veilframe.app.upscale.model.UpscaleModelRegistry
import com.veilframe.app.upscale.model.UpscaleModelRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NcnnExtendedEmulatorMatrixTest {

    companion object {
        private const val TAG = "VeilFrame.MatrixTest"
    }

    private lateinit var context: Context
    private lateinit var repository: UpscaleModelRepository
    private lateinit var engine: NcnnUpscaleEngine
    private lateinit var artifactsDir: File

    data class MatrixTestCase(
        val id: String,
        val description: String,
        val model: UpscaleModel,
        val targetScale: Int,
        val generator: (Context) -> Bitmap,
        val chunkSize: Int = 128
    )

    data class TestResultSummary(
        val caseId: String,
        val modelName: String,
        val inDims: String,
        val outDims: String,
        val elapsedMs: Long,
        val throughputMpS: Double,
        val pssDeltaKb: Long,
        val outputBytes: Long
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = UpscaleModelRepository(context)
        engine = NcnnUpscaleEngine(context, repository)
        artifactsDir = File(context.getExternalFilesDir(null), "matrix_artifacts").apply { mkdirs() }

        // Clean cache directory
        File(context.cacheDir, "upscale_streaming").listFiles()?.forEach { it.delete() }

        // Stage all 3 NCNN models into app-internal storage
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
                Log.i(TAG, "Staged NCNN model: ${m.id} (${destBin.length()} bytes)")
            }
        }
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "upscale_streaming").listFiles()?.forEach { it.delete() }
        System.gc()
        Log.i(TAG, "Post-case memory cleanup: PSS=${Debug.getPss()} kB")
    }

    private fun runMatrixCase(testCase: MatrixTestCase) = runBlocking {
        Log.i(TAG, "=================================================================")
        Log.i(TAG, "RUNNING MATRIX CASE: ${testCase.id} | ${testCase.description}")
        Log.i(TAG, "Model: ${testCase.model.id} (${testCase.model.name}), TargetScale: ${testCase.targetScale}x")
        Log.i(TAG, "=================================================================")

        val inputBitmap = testCase.generator(context)
        assertNotNull("Generator returned null bitmap for ${testCase.id}", inputBitmap)
        val inW = inputBitmap.width
        val inH = inputBitmap.height
        val expectedW = inW * testCase.targetScale
        val expectedH = inH * testCase.targetScale

        // Persist input artifact
        val inArtifact = File(artifactsDir, "matrix_in_${testCase.id}.png")
        FileOutputStream(inArtifact).use { out ->
            inputBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val pssBefore = Debug.getPss()
        val t0 = System.nanoTime()

        val outArtifact = File(artifactsDir, "matrix_out_${testCase.id}.png")

        if (testCase.model.type == ModelType.ALGORITHMIC) {
            // Algorithmic processing (Lanczos, Bicubic, Nearest)
            val scaledBitmap = when (testCase.model.id) {
                "lanczos-3" -> AlgorithmicUpscaler.scaleLanczos3(inputBitmap, expectedW, expectedH)
                "bicubic" -> AlgorithmicUpscaler.scaleBicubic(inputBitmap, expectedW, expectedH)
                "nearest" -> AlgorithmicUpscaler.scaleNearest(inputBitmap, expectedW, expectedH)
                else -> throw IllegalArgumentException("Unknown algorithmic model ${testCase.model.id}")
            }
            FileOutputStream(outArtifact).use { out ->
                scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            scaledBitmap.recycle()
        } else {
            // AI NCNN processing
            val output = engine.upscale(
                source = BitmapSource(inputBitmap),
                model = testCase.model,
                targetScale = testCase.targetScale,
                params = UpscaleInferenceParams(chunkSize = testCase.chunkSize, overlap = 16)
            ) as UpscaleOutput.Streamed

            assertEquals("Width mismatch for ${testCase.id}", expectedW, output.width)
            assertEquals("Height mismatch for ${testCase.id}", expectedH, output.height)
            assertTrue("Output file must exist", output.file.exists())
            assertTrue("Output file must not be empty", output.file.length() > 0)
            NcnnUpscaleEngine.validatePngFile(output.file, expectedW, expectedH)

            output.file.copyTo(outArtifact, overwrite = true)
        }

        val t1 = System.nanoTime()
        val pssAfter = Debug.getPss()
        val elapsedMs = (t1 - t0) / 1_000_000
        val totalMp = (expectedW.toLong() * expectedH.toLong()) / 1_000_000.0
        val throughput = if (elapsedMs > 0) totalMp / (elapsedMs / 1000.0) else 0.0
        val pssDelta = pssAfter - pssBefore

        assertTrue("Output artifact must exist", outArtifact.exists() && outArtifact.length() > 0)

        Log.i(
            TAG,
            "SUCCESS ${testCase.id}: ${inW}x${inH} -> ${expectedW}x${expectedH} | " +
                "${elapsedMs}ms | ${String.format(Locale.US, "%.3f", throughput)} MP/s | " +
                "PSS delta: ${pssDelta} kB | File: ${outArtifact.length()} bytes"
        )
    }

    // Helper image generators
    private fun loadDiskImage(fileName: String): Bitmap {
        val file = File("/data/local/tmp/veilframe_validation/real_inputs/$fileName")
        assertTrue("Required input file $fileName missing at ${file.absolutePath}", file.exists())
        return BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalStateException("Failed to decode $fileName")
    }

    private fun createSyntheticCanvas(w: Int, h: Int, draw: (Canvas, Paint) -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        draw(canvas, paint)
        return bmp
    }

    // =========================================================================
    // 18 EXTENSIVE TEST CASES COVERING ALL MODELS AND DIVERSE IMAGE TYPES/SIZES
    // =========================================================================

    @Test
    fun test01_MicroIcon_32x32_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("01_micro_icon_32x32", "Micro app icon (32x32)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            createSyntheticCanvas(32, 32) { c, p ->
                c.drawColor(Color.rgb(20, 30, 40))
                p.color = Color.CYAN
                c.drawCircle(16f, 16f, 12f, p)
                p.color = Color.WHITE
                p.textSize = 12f
                c.drawText("VF", 8f, 20f, p)
            }
        }))
    }

    @Test
    fun test02_BadgeUi_64x64_AnimeV3_4x() {
        runMatrixCase(MatrixTestCase("02_badge_ui_64x64", "UI status badge with text & border (64x64)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4, 4, {
            createSyntheticCanvas(64, 64) { c, p ->
                c.drawColor(Color.rgb(15, 15, 25))
                p.color = Color.rgb(255, 100, 50)
                c.drawRect(8f, 8f, 56f, 56f, p)
                p.color = Color.YELLOW
                c.drawCircle(32f, 32f, 16f, p)
            }
        }))
    }

    @Test
    fun test03_PrimeOdd_127x127_RealEsrganX4Plus() {
        runMatrixCase(MatrixTestCase("03_prime_odd_127x127", "Prime odd non-power-of-two (127x127)", UpscaleModelRegistry.NCNN_REAL_ESRGAN_X4PLUS, 4, {
            createSyntheticCanvas(127, 127) { c, p ->
                c.drawColor(Color.DKGRAY)
                p.color = Color.GREEN
                p.strokeWidth = 3f
                c.drawLine(0f, 0f, 127f, 127f, p)
                c.drawLine(0f, 127f, 127f, 0f, p)
                p.color = Color.MAGENTA
                c.drawCircle(63f, 63f, 30f, p)
            }
        }, chunkSize = 64))
    }

    @Test
    fun test04_PixelArtSprite_128x128_Nearest_2x() {
        runMatrixCase(MatrixTestCase("04_pixel_sprite_128x128", "Retro 8-bit game sprite (128x128)", UpscaleModelRegistry.NEAREST, 2, {
            createSyntheticCanvas(128, 128) { c, p ->
                c.drawColor(Color.BLACK)
                p.color = Color.RED
                for (x in 16 until 112 step 16) {
                    for (y in 16 until 112 step 16) {
                        if ((x + y) % 32 == 0) c.drawRect(x.toFloat(), y.toFloat(), (x + 16).toFloat(), (y + 16).toFloat(), p)
                    }
                }
            }
        }))
    }

    @Test
    fun test05_AnimePortrait_180x240_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("05_anime_portrait_180x240", "Miku anime face crop (180x240)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            val src = loadDiskImage("miku_reference.png")
            Bitmap.createBitmap(src, 200, 150, 180, 240)
        }))
    }

    @Test
    fun test06_WwfVectorLogo_200x200_AnimeV3_4x() {
        runMatrixCase(MatrixTestCase("06_wwf_logo_200x200", "WWF panda vector logo high contrast (200x200)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4, 4, {
            val src = loadDiskImage("WWF.png")
            Bitmap.createScaledBitmap(src, 200, 200, true)
        }))
    }

    @Test
    fun test07_CharacterFine_240x240_AnimeV3_4x() {
        runMatrixCase(MatrixTestCase("07_character_fine_240x240", "Detailed character illustration crop (240x240)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4, 4, {
            val src = loadDiskImage("miku_reference.png")
            Bitmap.createBitmap(src, 100, 100, 240, 240)
        }))
    }

    @Test
    fun test08_PhotoFace_320x240_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("08_photo_face_320x240", "Real photograph human face & skin pores (320x240)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            loadDiskImage("realesr_0014_photo_lr.jpg")
        }))
    }

    @Test
    fun test09_AnimeWidescreen_384x216_AnimeV3_4x() {
        runMatrixCase(MatrixTestCase("09_anime_widescreen_384x216", "16:9 widescreen anime frame (384x216)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4, 4, {
            loadDiskImage("realesr_0030_anime_lr.jpg")
        }))
    }

    @Test
    fun test10_StreetCity_400x300_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("10_street_city_400x300", "Urban street architecture & signage (400x300)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            loadDiskImage("realesr_00003_street_lr.png")
        }))
    }

    @Test
    fun test11_WallpaperArt_480x300_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("11_wallpaper_art_480x300", "Digital artwork phone wallpaper (480x300)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            loadDiskImage("phone_wallpaper_480x300.png")
        }))
    }

    @Test
    fun test12_ChildrenAlphaRgba_480x360_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("12_children_alpha_480x360", "4-channel RGBA transparent cutouts (480x360)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            loadDiskImage("realesr_children_alpha_lr.png")
        }))
    }

    @Test
    fun test13_TypographyDoc_512x384_Lanczos_2x() {
        runMatrixCase(MatrixTestCase("13_typography_doc_512x384", "Fine typographic document with small serifs (512x384)", UpscaleModelRegistry.LANCZOS, 2, {
            createSyntheticCanvas(512, 384) { c, p ->
                c.drawColor(Color.WHITE)
                p.color = Color.BLACK
                p.textSize = 14f
                for (row in 0 until 18) {
                    c.drawText("Line $row: PrivacyVideoCleaner VeilFrame Super-Resolution Engine Verification Matrix", 20f, (30 + row * 19).toFloat(), p)
                }
            }
        }))
    }

    @Test
    fun test14_NatureMacro_600x450_Bicubic_2x() {
        runMatrixCase(MatrixTestCase("14_nature_macro_600x450", "Macro gradient organic botanical (600x450)", UpscaleModelRegistry.BICUBIC, 2, {
            createSyntheticCanvas(600, 450) { c, p ->
                for (x in 0 until 600 step 4) {
                    for (y in 0 until 450 step 4) {
                        val r = (x * 255 / 600)
                        val g = (y * 255 / 450)
                        val b = ((x + y) * 255 / 1050)
                        p.color = Color.rgb(r, g, b)
                        c.drawRect(x.toFloat(), y.toFloat(), (x + 4).toFloat(), (y + 4).toFloat(), p)
                    }
                }
            }
        }))
    }

    @Test
    fun test15_PanoramaWide_640x160_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("15_panorama_wide_640x160", "Extreme 4:1 panoramic horizontal aspect ratio (640x160)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            createSyntheticCanvas(640, 160) { c, p ->
                c.drawColor(Color.rgb(10, 20, 60))
                p.color = Color.YELLOW
                c.drawCircle(80f, 80f, 40f, p)
                p.color = Color.CYAN
                p.strokeWidth = 4f
                c.drawLine(0f, 140f, 640f, 140f, p)
            }
        }))
    }

    @Test
    fun test16_BannerTall_200x800_AnimeV3_4x() {
        runMatrixCase(MatrixTestCase("16_banner_tall_200x800", "Extreme 1:4 vertical banner aspect ratio (200x800)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X4, 4, {
            createSyntheticCanvas(200, 800) { c, p ->
                c.drawColor(Color.rgb(40, 10, 30))
                p.color = Color.MAGENTA
                p.strokeWidth = 6f
                c.drawLine(100f, 0f, 100f, 800f, p)
                p.color = Color.WHITE
                p.textSize = 24f
                c.drawText("VERTICAL", 35f, 400f, p)
            }
        }))
    }

    @Test
    fun test17_DenseBrickTexture_680x450_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("17_dense_brick_texture_680x450", "High-frequency brickwork stone texture (680x450)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            loadDiskImage("realesr_ost009_texture_lr.png")
        }))
    }

    @Test
    fun test18_UserWhatsAppClash_1280x960_AnimeV3_2x() {
        runMatrixCase(MatrixTestCase("18_user_wa_clash_1280x960", "User real WhatsApp game screenshot (1280x960 -> 2560x1920)", UpscaleModelRegistry.NCNN_ANIMEVIDEO_V3_X2, 2, {
            loadDiskImage("user_wa_clash_1280x960.png")
        }))
    }
}
