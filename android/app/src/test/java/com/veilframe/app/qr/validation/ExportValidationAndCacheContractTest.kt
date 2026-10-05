package com.veilframe.app.qr.validation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.veilframe.app.qr.GenerationMode
import com.veilframe.app.qr.QrGenerator
import com.veilframe.app.qr.QrRenderResult
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.geometry.AdaptiveCacheKey
import com.veilframe.app.qr.image.ImageSourceLoader
import com.veilframe.app.qr.image.LuminanceDistribution
import com.veilframe.app.qr.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression and verification test suite for export validation, multi-frame adaptive cache safety,
 * and context-aware image source materialization.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ExportValidationAndCacheContractTest {

    private val payload = "https://veilframe.app/export-verify"
    private lateinit var app: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        app = Application()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeDist(p10: Float, p90: Float): LuminanceDistribution {
        return LuminanceDistribution(
            min = p10,
            p10 = p10,
            p25 = p10,
            median = (p10 + p90) / 2f,
            p75 = p90,
            p90 = p90,
            max = p90,
            mean = (p10 + p90) / 2f,
            sampleCount = 100
        )
    }

    @Test
    fun `Multi-frame adaptive cache key prevents collision across distinct temporal dynamics`() {
        // Sequence A: frame 1 dark, frame 2 bright
        // Average p90 = (0.2 + 0.8) / 2 = 0.5; Average p10 = (0.1 + 0.7) / 2 = 0.4
        val distA1 = makeDist(0.10f, 0.20f)
        val distA2 = makeDist(0.70f, 0.80f)
        val distributionsA: List<LuminanceDistribution> = listOf(distA1, distA2)

        // Sequence B: frame 1 medium, frame 2 medium
        // Average p90 = (0.5 + 0.5) / 2 = 0.5; Average p10 = (0.4 + 0.4) / 2 = 0.4
        val distB1 = makeDist(0.40f, 0.50f)
        val distB2 = makeDist(0.40f, 0.50f)
        val distributionsB: List<LuminanceDistribution> = listOf(distB1, distB2)

        // If scalar averaging were used:
        val avgP90A = ((distributionsA[0].p90 + distributionsA[1].p90) / 2f * 50f).toInt()
        val avgP10A = ((distributionsA[0].p10 + distributionsA[1].p10) / 2f * 50f).toInt()
        val avgP90B = ((distributionsB[0].p90 + distributionsB[1].p90) / 2f * 50f).toInt()
        val avgP10B = ((distributionsB[0].p10 + distributionsB[1].p10) / 2f * 50f).toInt()
        assertEquals("Scalar averages would collide", avgP90A to avgP10A, avgP90B to avgP10B)

        // With AdaptiveCacheKey.Multi exact temporal buckets:
        val keyA = AdaptiveCacheKey.Multi(
            IntArray(distributionsA.size) { i ->
                val qP90 = (distributionsA[i].p90 * 50f).toInt().coerceIn(0, 50)
                val qP10 = (distributionsA[i].p10 * 50f).toInt().coerceIn(0, 50)
                (qP90 shl 8) or (qP10 and 0xFF)
            }
        )
        val keyB = AdaptiveCacheKey.Multi(
            IntArray(distributionsB.size) { i ->
                val qP90 = (distributionsB[i].p90 * 50f).toInt().coerceIn(0, 50)
                val qP10 = (distributionsB[i].p10 * 50f).toInt().coerceIn(0, 50)
                (qP90 shl 8) or (qP10 and 0xFF)
            }
        )

        assertNotEquals("Vector signatures must not collide", keyA, keyB)
        assertNotEquals("Hash codes must differ", keyA.hashCode(), keyB.hashCode())

        // Identical sequence reproduces exact same key
        val keyAClone = AdaptiveCacheKey.Multi(
            IntArray(distributionsA.size) { i ->
                val qP90 = (distributionsA[i].p90 * 50f).toInt().coerceIn(0, 50)
                val qP10 = (distributionsA[i].p10 * 50f).toInt().coerceIn(0, 50)
                (qP90 shl 8) or (qP10 and 0xFF)
            }
        )
        assertEquals(keyA, keyAClone)
        assertEquals(keyA.hashCode(), keyAClone.hashCode())
    }

    @Test
    fun `Full-resolution export with PARITY_EF passes strict validation without false rejection`() = runBlocking {
        // Simulates QrStudioViewModel export workflow
        val exportDesign = QrDesign(
            style = QrStyle.BASIC,
            outputSize = 1024
        )

        val result = QrGenerator.generateStrictWithResult(
            context = app,
            content = payload,
            design = exportDesign,
            mode = GenerationMode.PARITY_EF
        )

        assertTrue("Export render result must succeed", result is QrRenderResult.Success)
        val success = result as QrRenderResult.Success
        assertNotNull(success.bitmap)
        assertEquals(1024, success.bitmap!!.width)
        assertEquals(1024, success.bitmap!!.height)

        assertTrue(
            "Export report must be scan-ready (warnings: ${success.report.warnings})",
            success.report.isScanReady
        )
        assertTrue("Export report must be strictly compliant", success.report.isStrictlyCompliant)
    }

    @Test
    fun `Full-resolution export with positive explicit quiet zone passes strict validation`() = runBlocking {
        val exportDesign = QrDesign(
            style = QrStyle.BASIC,
            outputSize = 1024,
            quietZoneModules = 2,
            explicitQuietZone = 2
        )

        val result = QrGenerator.generateStrictWithResult(
            context = app,
            content = payload,
            design = exportDesign,
            mode = GenerationMode.PARITY_EF
        )

        assertTrue(result is QrRenderResult.Success)
        val success = result as QrRenderResult.Success
        assertTrue(
            "Explicit quiet zone 2 modules must be scan ready: ${success.report.warnings}",
            success.report.isScanReady
        )
        assertTrue(success.report.isStrictlyCompliant)
    }

    @Test
    fun `Full-resolution export with artistic style and 1-module margin passes strict validation`() = runBlocking {
        val artBmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.LTGRAY)
        }
        val exportDesign = QrDesign(
            style = QrStyle.IMAGE,
            outputSize = 1024,
            quietZoneModules = 1,
            explicitQuietZone = 1,
            imageSource = ImageSourceStyle(
                source = ImageSource.Memory(artBmp)
            ),
            dataColorDark = Color.BLACK,
            dataColorLight = Color.WHITE
        )

        val result = QrGenerator.generateStrictWithResult(
            context = app,
            content = payload,
            design = exportDesign,
            mode = GenerationMode.ARTISTIC_ENGINE
        )

        assertTrue(result is QrRenderResult.Success)
        val success = result as QrRenderResult.Success
        assertTrue(
            "Artistic 1-module export must pass scan-readiness: ${success.report.warnings}",
            success.report.isScanReady
        )
        assertTrue(success.report.isStrictlyCompliant)
    }

    @Test
    fun `ImageSourceLoader materializeDesign safely leaves unresolvable source fail-closed`() {
        val unresolvableDesign = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(source = ImageSource.Uri("content://invalid/missing/file.png"))
        )

        val materialized = ImageSourceLoader.materializeDesign(app, unresolvableDesign)
        assertEquals(
            "Unresolvable source must remain intact",
            unresolvableDesign.imageSource.source,
            materialized.imageSource.source
        )
        assertNull("Unresolvable bitmap must remain null", materialized.imageSource.bitmap)
    }

    @Test
    fun `ImageSourceLoader calculateInSampleSize computes power-of-two downsampling correctly`() {
        val options = android.graphics.BitmapFactory.Options().apply {
            outWidth = 4000
            outHeight = 3000
        }
        val sampleSize = ImageSourceLoader.calculateInSampleSize(options, 1024, 1024)
        assertEquals(2, sampleSize) // half width 2000, half height 1500 >= 1024
    }
}
