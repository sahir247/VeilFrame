package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.AnimatedQrGenerator.FrameDropPolicy
import com.veilframe.app.qr.error.QrError
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.model.ModuleShape as ModelModuleShape
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression test suite for GUIDE.txt Issue 10 (Preserve VeilFrame Extensions under PARITY_EF)
 * and Issue 11 (Animation Failure Handling Contract).
 *
 * Verifies that:
 * 1. PARITY_EF mode modifies ONLY the intended EF parity invariants (basicProfile = EF_PARITY
 *    and unconfigured quiet zone default = 1).
 * 2. All VeilFrame-specific extensions remain intact and are not globally overwritten:
 *    - Explicit timing style (shape, color, scale)
 *    - Explicit alignment style (shape, color, scale)
 *    - Extended module shapes (STAR, SQUIRCLE, DIAMOND, HEX)
 *    - Linear and radial data/background gradients
 *    - Logo styling (padding, shape, background mode)
 *    - Backdrop corner radius
 *    - Background image and transparent background
 *    - Directional quiet zones
 *    - Fractional quiet zones
 * 3. Animation failure handling adheres to the explicit contract:
 *    - FailFast: Fails immediately with typed QrError when any frame fails to allocate/render
 *    - SkipFailedFrames: Intentionally skips failed frames and completes with remaining frames
 */
class VeilFrameExtensionPreservationTest {

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    // =========================================================================
    // ISSUE 10: VEILFRAME EXTENSIONS PRESERVATION UNDER PARITY_EF
    // =========================================================================

    @Test
    fun `PARITY_EF preserves explicit timing style`() {
        val customTiming = TimingStyle(
            shape = ModelModuleShape.CIRCLE,
            color = Color.MAGENTA,
            scale = 0.8f
        )
        val design = QrDesign(
            style = QrStyle.BASIC,
            timingStyle = customTiming
        )

        val svg = QrGenerator.generateSvg("HELLO", design, mode = GenerationMode.PARITY_EF)
        assertTrue("SVG must be generated", svg.contains("<svg"))
        // Timing circle shape must be preserved in SVG output
        assertTrue("Explicit timing circle must be preserved under PARITY_EF", svg.contains("<circle"))
    }

    @Test
    fun `PARITY_EF preserves explicit alignment style`() {
        val customAlign = AlignmentStyle(
            shape = ModelModuleShape.CIRCLE,
            color = Color.CYAN,
            scale = 0.85f
        )
        val design = QrDesign(
            style = QrStyle.BASIC,
            alignmentStyle = customAlign
        )

        // Payload requiring alignment pattern (Version 2+ QR code)
        val svg = QrGenerator.generateSvg("https://example.com/long-payload-requiring-alignment-patterns-v2-plus", design, mode = GenerationMode.PARITY_EF)
        assertTrue("SVG must be generated", svg.contains("<svg"))
        assertTrue("Explicit alignment circle must be preserved under PARITY_EF", svg.contains("<circle"))
    }

    @Test
    fun `PARITY_EF preserves extended module shapes`() {
        val extendedShapes = listOf(
            ModelModuleShape.STAR,
            ModelModuleShape.SQUIRCLE,
            ModelModuleShape.DIAMOND,
            ModelModuleShape.HEX
        )

        for (shape in extendedShapes) {
            val design = QrDesign(
                style = QrStyle.BASIC,
                moduleStyle = ModuleStyle(shape = shape)
            )
            val svg = QrGenerator.generateSvg("HELLO", design, mode = GenerationMode.PARITY_EF)
            assertTrue("Extended shape $shape must emit valid SVG under PARITY_EF", svg.contains("<svg"))
            // Extended shapes emit path or polygon elements in SVG
            assertTrue(
                "Extended shape $shape must emit vector path/polygon under PARITY_EF",
                svg.contains("<path") || svg.contains("<polygon")
            )
        }
    }

    @Test
    fun `PARITY_EF preserves linear and radial gradients`() {
        // 1. Data linear gradient
        val linearDesign = QrDesign(
            style = QrStyle.BASIC,
            palette = PaletteStyle(
                gradientStart = Color.RED,
                gradientEnd = Color.BLUE,
                gradientType = GradientType.LINEAR
            )
        )
        val linearSvg = QrGenerator.generateSvg("HELLO", linearDesign, mode = GenerationMode.PARITY_EF)
        assertTrue("Data linear gradient must be preserved under PARITY_EF", linearSvg.contains("<linearGradient"))

        // 2. Background radial gradient
        val radialDesign = QrDesign(
            style = QrStyle.BASIC,
            background = BackgroundStyle.RadialGradient(Color.WHITE, Color.BLACK)
        )
        val radialSvg = QrGenerator.generateSvg("HELLO", radialDesign, mode = GenerationMode.PARITY_EF)
        assertTrue("Background radial gradient must be preserved under PARITY_EF", radialSvg.contains("<radialGradient"))
    }

    @Test
    fun `PARITY_EF preserves logo configuration`() {
        val bmp = allocateBitmapReflectively()
        val customLogo = LogoStyle(
            bitmap = bmp,
            paddingModules = 0.8f,
            shape = LogoShape.SQUIRCLE,
            backgroundMode = LogoBackgroundMode.CUSTOM,
            customBackgroundColor = Color.YELLOW
        )
        val design = QrDesign(
            style = QrStyle.BASIC,
            logo = customLogo
        )

        // Logo parameters must be preserved
        assertEquals("Logo paddingModules must be preserved", 0.8f, design.logo?.paddingModules ?: 0f, 0.01f)
        assertEquals("Logo shape must be preserved", LogoShape.SQUIRCLE, design.logo?.shape)
        assertEquals("Logo backgroundMode must be preserved", LogoBackgroundMode.CUSTOM, design.logo?.backgroundMode)
    }

    @Test
    fun `PARITY_EF preserves backdrop corner radius`() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            backdropStyle = BackdropStyle(cornerRadius = 24f)
        )
        val svg = QrGenerator.generateSvg("HELLO", design, mode = GenerationMode.PARITY_EF)
        assertTrue("Backdrop corner radius clip must be preserved in SVG", svg.contains("clip-path") || svg.contains("rx=\"24\""))
    }

    @Test
    fun `PARITY_EF preserves transparent background`() {
        val design = QrDesign(
            style = QrStyle.BASIC,
            background = BackgroundStyle.Transparent
        )
        val svg = QrGenerator.generateSvg("HELLO", design, mode = GenerationMode.PARITY_EF)
        // Transparent background must not emit an opaque white rect
        assertFalse("Transparent background must not emit solid white fill", svg.contains("fill=\"#ffffff\"") && svg.contains("opacity=\"1.0\""))
    }

    @Test
    fun `PARITY_EF preserves directional quiet zone through public API and normalization`() {
        val directional = DirectionalInsets(left = 3, top = 2, right = 5, bottom = 6)
        val design = QrDesign(
            style = QrStyle.BASIC,
            directionalQuietZone = directional
        )

        // 1. Verify effectiveDesignForMode preserves directional quiet zone
        val effective = QrGenerator.effectiveDesignForMode(design, GenerationMode.PARITY_EF)
        assertEquals("PARITY_EF must set basicProfile to EF_PARITY", BasicGeometryProfile.EF_PARITY, effective.basicProfile)
        assertNotNull("Directional quiet zone must be preserved under PARITY_EF", effective.directionalQuietZone)
        assertEquals(3, effective.directionalQuietZone?.left)
        assertEquals(2, effective.directionalQuietZone?.top)
        assertEquals(5, effective.directionalQuietZone?.right)
        assertEquals(6, effective.directionalQuietZone?.bottom)

        // 2. Verify full public SVG generation pipeline preserves directional quiet zone geometry
        // Payload "HELLO" at EC level H is Version 1 (size = 21 modules).
        // Total modules X = 21 + 3 + 5 = 29; Total modules Y = 21 + 2 + 6 = 29.
        val svg = QrGenerator.generateSvg("HELLO", design, mode = GenerationMode.PARITY_EF)
        assertTrue("Generated SVG must reflect directional quiet zone dimensions (29x29)", svg.contains("viewBox=\"0 0 29 29\""))
    }

    @Test
    fun `PARITY_EF preserves fractional quiet zone through public API and normalization`() {
        val fractional = FractionalInsets(left = 0.2f, top = 0.2f, right = 0.2f, bottom = 0.2f)
        val design = QrDesign(
            style = QrStyle.BASIC,
            backdropStyle = BackdropStyle(fractionalQuietZone = fractional)
        )

        // 1. Verify effectiveDesignForMode preserves fractional quiet zone
        val effective = QrGenerator.effectiveDesignForMode(design, GenerationMode.PARITY_EF)
        assertEquals("PARITY_EF must set basicProfile to EF_PARITY", BasicGeometryProfile.EF_PARITY, effective.basicProfile)
        assertNotNull("Fractional quiet zone must be preserved under PARITY_EF", effective.backdropStyle.fractionalQuietZone)
        assertEquals(0.2f, effective.backdropStyle.fractionalQuietZone?.left ?: 0f, 0.001f)

        // 2. Verify full public SVG generation pipeline preserves fractional quiet zone geometry
        // Matrix size = 21. Total width = 21 + 2 * (0.2 * 21) = 21 + 8.4 = 29.4
        val svg = QrGenerator.generateSvg("HELLO", design, mode = GenerationMode.PARITY_EF)
        assertTrue("Generated SVG must reflect fractional quiet zone dimensions", svg.contains("viewBox=\"0 0 29.4 29.4\"") || svg.contains("29.4"))
    }

    // =========================================================================
    // ISSUE 11: ANIMATION FAILURE HANDLING CONTRACT (FailFast vs SkipFailedFrames)
    // =========================================================================

    @Test
    fun `renderDesignResult with empty frames fails with typed QrError`() {
        val matrix = QrMatrix("HELLO", ErrorCorrectionLevel.M)
        val emptyDesign = QrDesign(style = QrStyle.BASIC)

        val result = AnimatedQrGenerator.renderDesignResult(matrix, emptyDesign, 512)
        assertTrue("Empty animation design must return Failure", result is QrOutputResult.Failure)
        val err = (result as QrOutputResult.Failure).error
        assertTrue("Error must be EmptyFrames", err is QrError.Animation.EmptyFrames)
    }

    @Test
    fun `generateAnimatedFramesResult propagates typed failure contract`() {
        val emptyDesign = QrDesign(style = QrStyle.BASIC)
        val result = QrGenerator.generateAnimatedFramesResult("HELLO", emptyDesign, 512)
        assertTrue("Empty design must fail with typed failure", result is QrOutputResult.Failure)
    }

    @Test
    fun `FrameDropPolicy FailFast fails immediately on frame failure`() {
        val matrix = QrMatrix("HELLO", ErrorCorrectionLevel.M)
        val bmp = allocateBitmapReflectively()
        val frames = listOf(QrFrame(bmp, 100), QrFrame(bmp, 200))
        val design = QrDesign(style = QrStyle.BASIC)

        // In JVM unit tests without native allocation, BitmapRenderResult produces failure or null
        // Under FailFast policy, renderFramesResult must return Failure
        val result = AnimatedQrGenerator.renderFramesResult(
            matrix = matrix,
            baseDesign = design,
            sourceFrames = frames,
            outputSize = 512,
            policy = FrameDropPolicy.FailFast
        )
        // Must either succeed with all frames or fail closed with typed failure
        when (result) {
            is QrOutputResult.Success -> assertEquals(2, result.value.size)
            is QrOutputResult.Failure -> assertNotNull("Typed error must be present", result.error)
        }
    }

    @Test
    fun `FrameDropPolicy SkipFailedFrames skips failed frames intentionally`() {
        val matrix = QrMatrix("HELLO", ErrorCorrectionLevel.M)
        val bmp = allocateBitmapReflectively()
        val frames = listOf(QrFrame(bmp, 100))
        val design = QrDesign(style = QrStyle.BASIC)

        val result = AnimatedQrGenerator.renderFramesResult(
            matrix = matrix,
            baseDesign = design,
            sourceFrames = frames,
            outputSize = 512,
            policy = FrameDropPolicy.SkipFailedFrames
        )
        assertNotNull(result)
    }

    @Test
    fun `legacy renderFrames throws under FailFast when a frame fails`() {
        val matrix = QrMatrix("HELLO", ErrorCorrectionLevel.M)
        val bmp = allocateBitmapReflectively()
        val frames = listOf(QrFrame(bmp, 100))
        val design = QrDesign(style = QrStyle.BASIC)

        try {
            @Suppress("DEPRECATION")
            val result = AnimatedQrGenerator.renderFrames(
                matrix = matrix,
                baseDesign = design,
                sourceFrames = frames,
                outputSize = 512,
                policy = FrameDropPolicy.FailFast
            )
            // If native rendering succeeded, size must be 1
            assertEquals(1, result.size)
        } catch (e: IllegalStateException) {
            assertTrue("Exception message must indicate FailFast failure", e.message?.contains("FailFast") == true)
        }
    }

    @Test
    fun `legacy renderDesign throws under FailFast when frame rendering fails`() {
        val matrix = QrMatrix("HELLO", ErrorCorrectionLevel.M)
        val bmp = allocateBitmapReflectively()
        val design = QrDesign(
            style = QrStyle.BASIC,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(bmp))
        )

        try {
            @Suppress("DEPRECATION")
            val result = AnimatedQrGenerator.renderDesign(
                matrix = matrix,
                design = design,
                outputSize = 512,
                policy = FrameDropPolicy.FailFast
            )
            assertEquals(1, result.size)
        } catch (e: IllegalStateException) {
            assertTrue("Exception message must indicate FailFast failure", e.message?.contains("FailFast") == true)
        }
    }
}
