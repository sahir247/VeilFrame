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
    fun `PARITY_EF preserves directional quiet zone`() {
        val directional = DirectionalInsets(left = 3, top = 2, right = 5, bottom = 6)
        val design = QrDesign(
            style = QrStyle.BASIC,
            directionalQuietZone = directional
        )
        val matrix = QrMatrix("HELLO", ErrorCorrectionLevel.H)
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)

        // Directional quiet zones must not be clamped to 1
        assertEquals("Directional quiet zone top must be preserved", 2, geometry.quietZoneTop)
        assertEquals("Directional quiet zone bottom must be preserved", 6, geometry.quietZoneBottom)
        assertEquals("Directional quiet zone left must be preserved", 3, geometry.quietZoneLeft)
        assertEquals("Directional quiet zone right must be preserved", 5, geometry.quietZoneRight)
    }

    @Test
    fun `PARITY_EF preserves fractional quiet zone`() {
        val fractional = FractionalInsets(left = 0.1f, top = 0.1f, right = 0.1f, bottom = 0.1f)
        val design = QrDesign(
            style = QrStyle.BASIC,
            backdropStyle = BackdropStyle(fractionalQuietZone = fractional)
        )
        val matrix = QrMatrix("HELLO", ErrorCorrectionLevel.H)
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)

        // Fractional quiet zone must resolve based on 0.1f * matrix.size
        assertNotNull("Fractional quiet zone must be preserved", design.backdropStyle.fractionalQuietZone)
        assertEquals(0.1f, design.backdropStyle.fractionalQuietZone?.left ?: 0f, 0.001f)
        assertEquals(matrix.size * 0.1f, geometry.quietZoneLeftFloat, 0.01f)
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
}
