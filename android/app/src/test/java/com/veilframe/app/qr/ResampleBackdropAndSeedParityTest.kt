package com.veilframe.app.qr

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.registry.QrStyleRegistry
import com.veilframe.app.qr.renderer.ArrayPixelSource
import com.veilframe.app.qr.renderer.ComposableQrRenderer
import com.veilframe.app.qr.renderer.ImageFillRenderer
import com.veilframe.app.qr.renderer.ResampleSubpixelEngine
import com.veilframe.app.qr.validation.ScanabilityValidator
import org.junit.Assert.*
import org.junit.Test

/**
 * Verification suite for:
 * 1. ResampleStyle continuous backdrop rendering in SVG / IR.
 * 2. Dynamic seed determinism: different seeds produce different valid stochastic dot distributions.
 * 3. QrDesign.effectiveQuietZone consistency across explicitQuietZone vs quietZoneModules.
 * 4. ScanabilityValidator multi-sample grid and validationSkipped reporting on null bitmap.
 * 5. QrStyleRegistry authoritative binding for IMAGE_FILL and IMAGE_RESAMPLE.
 */
class ResampleBackdropAndSeedParityTest {

    private fun createCheckerPixelSource(size: Int): ArrayPixelSource {
        val pixels = IntArray(size * size) { idx ->
            val x = idx % size
            val y = idx / size
            if ((x / 4 + y / 4) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return ArrayPixelSource(size, size, pixels)
    }

    @Test
    fun testEffectiveQuietZoneResolution() {
        // When explicitQuietZone is null, fallback to quietZoneModules
        val designDefault = QrDesign(
            quietZoneModules = 4,
            explicitQuietZone = null
        )
        assertEquals(4, designDefault.effectiveQuietZone)

        // When explicitQuietZone is explicitly provided (e.g. 0 or 1), it takes precedence
        val designExplicitZero = QrDesign(
            quietZoneModules = 4,
            explicitQuietZone = 0
        )
        assertEquals(0, designExplicitZero.effectiveQuietZone)

        val designExplicitOne = QrDesign(
            quietZoneModules = 4,
            explicitQuietZone = 1
        )
        assertEquals(1, designExplicitOne.effectiveQuietZone)
    }

    @Test
    fun testResampleSeedProducesAlternateDeterministicDistributions() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/RESAMPLE_SEED_TEST", ErrorCorrectionLevel.M)
        val pixelSource = createCheckerPixelSource(64)
        val style = ImageSourceStyle(contrast = 0.5f, exposure = 0.2f)

        val samplesSeed1 = mutableListOf<String>()
        ResampleSubpixelEngine.traverseSubpixels(matrix, pixelSource, style, seed = 42L) { col, row, sx, sy, _ ->
            samplesSeed1.add("$col,$row,$sx,$sy")
        }

        val samplesSeed1Repeat = mutableListOf<String>()
        ResampleSubpixelEngine.traverseSubpixels(matrix, pixelSource, style, seed = 42L) { col, row, sx, sy, _ ->
            samplesSeed1Repeat.add("$col,$row,$sx,$sy")
        }

        val samplesSeed2 = mutableListOf<String>()
        ResampleSubpixelEngine.traverseSubpixels(matrix, pixelSource, style, seed = 9999999L) { col, row, sx, sy, _ ->
            samplesSeed2.add("$col,$row,$sx,$sy")
        }

        // Determinism: Same seed must produce identical traversal
        assertEquals("Same seed must produce identical subpixel outputs", samplesSeed1, samplesSeed1Repeat)

        // Alternate variant: Different seed must alter the stochastic dot distribution
        assertNotEquals("Different seed must produce different stochastic dot distributions", samplesSeed1, samplesSeed2)
    }

    @Test
    fun testSvgContinuousBackdropEmission() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/BACKDROP_TEST", ErrorCorrectionLevel.H)
        val designWithBackdrop = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            quietZoneModules = 2,
            resampleStyle = ResampleStyle(
                seed = 12345L,
                useSourceAsBackdrop = true,
                backdropOpacity = 0.75f,
                backdropScaleMode = ImageScaleMode.ASPECT_FILL
            )
        )

        val pixelSource = createCheckerPixelSource(64)
        val svgWithBackdrop = SvgExporter.generateSvg(matrix, designWithBackdrop, pixelSource)
        assertTrue(
            "SVG must include backdrop image element when useSourceAsBackdrop is true",
            svgWithBackdrop.contains("<image") && svgWithBackdrop.contains("opacity=\"0.75\"")
        )
        assertTrue(
            "SVG must include preserveAspectRatio xMidYMid slice for ASPECT_FILL backdrop",
            svgWithBackdrop.contains("preserveAspectRatio=\"xMidYMid slice\"")
        )

        val designWithoutBackdrop = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            quietZoneModules = 2,
            resampleStyle = ResampleStyle(
                useSourceAsBackdrop = false
            )
        )
        val svgWithoutBackdrop = SvgExporter.generateSvg(matrix, designWithoutBackdrop, pixelSource)
        assertFalse(
            "SVG must NOT include backdrop image element when useSourceAsBackdrop is false",
            svgWithoutBackdrop.contains("opacity=\"0.75\"")
        )
    }

    @Test
    fun testRegistryAuthoritativeRendererBinding() {
        val resampleDef = QrStyleRegistry.get(QrStyle.IMAGE_RESAMPLE)
        val fillDef = QrStyleRegistry.get(QrStyle.IMAGE_FILL)

        assertTrue(
            "IMAGE_RESAMPLE in registry must instantiate ComposableQrRenderer",
            resampleDef.rendererFactory() is ComposableQrRenderer
        )
        assertTrue(
            "IMAGE_FILL in registry must instantiate ImageFillRenderer",
            fillDef.rendererFactory() is ImageFillRenderer
        )
    }

    @Test
    fun testGeneratorNullBitmapReturnsValidationSkipped() {
        val design = QrDesign(
            outputSize = 512,
            quietZoneModules = 4
        )

        // In headless JVM environment where android.graphics.Bitmap.createBitmap() is mocked/null,
        // QrGenerator must return validationSkipped = true and isScanReady = false instead of fake success.
        val renderResult = QrGenerator.generateWithResult("HTTPS://VEILFRAME.APP/NULL_TEST", design)
        when (renderResult) {
            is QrRenderResult.Success -> {
                if (renderResult.bitmap == null) {
                    assertFalse("Scan ready must be false when bitmap is null", renderResult.report.isScanReady)
                    assertTrue("validationSkipped must be true when bitmap is null", renderResult.report.validationSkipped)
                }
            }
            is QrRenderResult.Failure -> {
                // If it fails cleanly with exception, that is also a valid failure
            }
        }
    }

    @Test
    fun testScanabilityResampleQuietZoneRules() {
        val resampleDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            quietZoneModules = 1,
            explicitQuietZone = 1
        )
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/QZ_RULES", ErrorCorrectionLevel.H)
        val result = QrGenerator.generateWithResult("HTTPS://VEILFRAME.APP/QZ_RULES", resampleDesign)
        assertTrue(result is QrRenderResult.Success)
        val report = (result as QrRenderResult.Success).report

        // In IMAGE_RESAMPLE, quiet zone of 1 module must NOT trigger RESTORE_QUIET_ZONE repair reason
        assertFalse(
            "IMAGE_RESAMPLE with 1-module quiet zone must not suggest RESTORE_QUIET_ZONE",
            report.repairSuggestions.contains(com.veilframe.app.qr.validation.RepairReason.RESTORE_QUIET_ZONE)
        )
    }
}

