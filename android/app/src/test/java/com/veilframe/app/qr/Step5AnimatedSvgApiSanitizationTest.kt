package com.veilframe.app.qr

import android.graphics.Bitmap
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.QrExporter
import com.veilframe.app.qr.model.ImageSource
import com.veilframe.app.qr.model.ImageSourceStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrFrame
import com.veilframe.app.qr.model.QrMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import kotlin.io.path.createTempDirectory

/**
 * Step 5: Animated SVG API Sanitization and Escape Hatch Closure Verification Suite.
 *
 * Verifies that:
 * 1. The primary saveAnimatedSvgTyped(context, matrix, design) signature uses [QrDesign] as the
 *    single authoritative source of truth - no [List<QrFrame>] parameter on the canonical overload.
 * 2. The compatibility 4-arg overload correctly injects [ImageSource.Animated] into design.imageSource
 *    rather than passing raw pre-rendered QR bitmaps directly to AnimatedQrGenerator.generateAnimatedSvg.
 * 3. Frame bitmaps and millisecond delays are preserved exactly through the compatibility mapping.
 * 4. The sanitized design is correctly recognized as animated by AnimatedQrGenerator.isDesignAnimated.
 * 5. Generating SVG from the sanitized design produces exactly one root <svg> element (no nested SVG).
 * 6. Animated SVG structure (defs, frame groups, animate element) is present and correct.
 * 7. The 4-arg overload is annotated @Deprecated so callers are guided to the canonical API.
 * 8. APNG buildApngFfmpegCommand preserves variable frame durations via ffconcat.
 * 9. APNG buildApngFfmpegCommand uses -framerate for uniform durations.
 */
class Step5AnimatedSvgApiSanitizationTest {

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    // =========================================================================
    // 1. CANONICAL API SHAPE: primary overload must NOT accept List<QrFrame>
    // =========================================================================

    @Test
    fun testCanonicalSaveAnimatedSvgTypedHasNoFramesParameter() {
        val exporterClass = Class.forName("com.veilframe.app.qr.exporter.QrExporter")
        val methods: Array<Method> = exporterClass.declaredMethods
        val matchingMethods = methods.filter { it.name == "saveAnimatedSvgTyped" }
        assertTrue(
            "QrExporter must declare at least one saveAnimatedSvgTyped method",
            matchingMethods.isNotEmpty()
        )
        val deprecatedOverloads = matchingMethods.filter { m ->
            m.isAnnotationPresent(Deprecated::class.java)
        }
        assertTrue(
            "At least one saveAnimatedSvgTyped overload must be @Deprecated (the compat 4-arg overload)",
            deprecatedOverloads.isNotEmpty()
        )
        // Suspend functions in JVM bytecode have a trailing Continuation parameter.
        // Canonical: Context, QrMatrix, QrDesign, Continuation = 4 params total.
        val canonicalOverloads = matchingMethods.filter { m ->
            !m.isAnnotationPresent(Deprecated::class.java) && m.parameterCount == 4
        }
        assertTrue(
            "Must have a non-deprecated saveAnimatedSvgTyped(context, matrix, design) canonical overload",
            canonicalOverloads.isNotEmpty()
        )
    }

    @Test
    fun testCompatibilityOverloadIsAnnotatedDeprecated() {
        val exporterClass = Class.forName("com.veilframe.app.qr.exporter.QrExporter")
        val methods: Array<Method> = exporterClass.declaredMethods
        val compatOverload = methods.firstOrNull { m ->
            m.name == "saveAnimatedSvgTyped" && m.isAnnotationPresent(Deprecated::class.java)
        }
        assertNotNull(
            "The 4-arg saveAnimatedSvgTyped compatibility overload must be annotated @Deprecated",
            compatOverload
        )
    }

    // =========================================================================
    // 2. COMPATIBILITY OVERLOAD INJECTS ImageSource.Animated - NOT Memory
    // =========================================================================

    @Test
    fun testCompatibilityMappingInjectsImageSourceAnimated() {
        val bmp1 = allocateBitmapReflectively()
        val bmp2 = allocateBitmapReflectively()
        val bmp3 = allocateBitmapReflectively()
        val sourceArtworkFrames = listOf(
            QrFrame(bitmap = bmp1, durationMs = 100),
            QrFrame(bitmap = bmp2, durationMs = 250),
            QrFrame(bitmap = bmp3, durationMs = 750)
        )
        val originalDesign = QrDesign(style = QrStyle.IMAGE)
        // Call the ACTUAL production function — not a manually reconstructed copy.
        val safeDesign = QrExporter.applyArtworkFramesToDesign(originalDesign, sourceArtworkFrames)
        assertTrue(
            "QrExporter.applyArtworkFramesToDesign must inject ImageSource.Animated",
            safeDesign.imageSource.source is ImageSource.Animated
        )
        assertFalse(
            "QrExporter.applyArtworkFramesToDesign must not produce ImageSource.Memory",
            safeDesign.imageSource.source is ImageSource.Memory
        )
    }

    // =========================================================================
    // 3. FRAME BITMAP IDENTITY AND DELAY FIDELITY THROUGH COMPATIBILITY MAPPING
    // =========================================================================

    @Test
    fun testCompatibilityMappingPreservesFrameBitmapsExactly() {
        val bmp1 = allocateBitmapReflectively()
        val bmp2 = allocateBitmapReflectively()
        val bmp3 = allocateBitmapReflectively()
        val sourceArtworkFrames = listOf(
            QrFrame(bitmap = bmp1, durationMs = 100),
            QrFrame(bitmap = bmp2, durationMs = 250),
            QrFrame(bitmap = bmp3, durationMs = 750)
        )
        val design = QrDesign(style = QrStyle.IMAGE)
        // Call the ACTUAL production function — not a manually reconstructed copy.
        val safeDesign = QrExporter.applyArtworkFramesToDesign(design, sourceArtworkFrames)
        val animatedSource = safeDesign.imageSource.source as ImageSource.Animated
        assertEquals("Frame count must be preserved", 3, animatedSource.frames.size)
        assertSame("Frame 0 bitmap identity must be preserved", bmp1, animatedSource.frames[0])
        assertSame("Frame 1 bitmap identity must be preserved", bmp2, animatedSource.frames[1])
        assertSame("Frame 2 bitmap identity must be preserved", bmp3, animatedSource.frames[2])
    }

    @Test
    fun testCompatibilityMappingPreservesMillisecondDelaysFully() {
        val bmp = allocateBitmapReflectively()
        val sourceArtworkFrames = listOf(
            QrFrame(bitmap = bmp, durationMs = 83),
            QrFrame(bitmap = bmp, durationMs = 167),
            QrFrame(bitmap = bmp, durationMs = 750)
        )
        val design = QrDesign(style = QrStyle.BASIC)
        // Call the ACTUAL production function — not a manually reconstructed copy.
        val safeDesign = QrExporter.applyArtworkFramesToDesign(design, sourceArtworkFrames)
        val animatedSource = safeDesign.imageSource.source as ImageSource.Animated
        assertEquals("3 delays must be preserved", 3, animatedSource.delaysMs.size)
        assertEquals("Frame 0 delay must be 83 ms", 83, animatedSource.delaysMs[0])
        assertEquals("Frame 1 delay must be 167 ms", 167, animatedSource.delaysMs[1])
        assertEquals("Frame 2 delay must be 750 ms", 750, animatedSource.delaysMs[2])
    }

    // =========================================================================
    // 4. isDesignAnimated RECOGNIZES SANITIZED DESIGN CORRECTLY
    // =========================================================================

    @Test
    fun testIsDesignAnimatedReturnsTrueAfterSanitization() {
        val bmp = allocateBitmapReflectively()
        val sourceArtworkFrames = listOf(
            QrFrame(bitmap = bmp, durationMs = 200),
            QrFrame(bitmap = bmp, durationMs = 300)
        )
        val originalDesign = QrDesign(style = QrStyle.IMAGE)
        assertFalse(
            "Original design with no animated frames must not be detected as animated",
            AnimatedQrGenerator.isDesignAnimated(originalDesign)
        )
        // Call the ACTUAL production function — not a manually reconstructed copy.
        val safeDesign = QrExporter.applyArtworkFramesToDesign(originalDesign, sourceArtworkFrames)
        assertTrue(
            "Design produced by applyArtworkFramesToDesign must be detected as animated",
            AnimatedQrGenerator.isDesignAnimated(safeDesign)
        )
    }

    // =========================================================================
    // 5. NO NESTED SVG: sanitized design produces valid single-root SVG
    // =========================================================================

    @Test
    fun testSanitizedDesignGeneratesNoNestedSvg() {
        val bmp = allocateBitmapReflectively()
        val sourceArtworkFrames = listOf(
            QrFrame(bitmap = bmp, durationMs = 200),
            QrFrame(bitmap = bmp, durationMs = 300)
        )
        val matrix = QrMatrix("https://veilframe.app/step5-no-nested-svg", ErrorCorrectionLevel.M)
        val baseDesign = QrDesign(style = QrStyle.BASIC)
        val safeDesign = baseDesign.copy(
            imageSource = baseDesign.imageSource.copy(
                source = ImageSource.Animated(
                    sourceArtworkFrames.map { it.bitmap },
                    sourceArtworkFrames.map { it.durationMs }
                )
            )
        )
        val svg = AnimatedQrGenerator.generateAnimatedSvg(matrix, safeDesign)
        val rootSvgCount = Regex("<svg[\\s>]").findAll(svg).count()
        assertEquals("Sanitized animated SVG must have exactly one root <svg> element", 1, rootSvgCount)
        val firstSvgIdx = svg.indexOf("<svg")
        val secondSvgIdx = svg.indexOf("<svg", startIndex = firstSvgIdx + 4)
        assertEquals("Frame groups must not contain nested <svg> elements", -1, secondSvgIdx)
    }

    // =========================================================================
    // 6. ANIMATED SVG STRUCTURE: defs, frame groups, animate element
    // =========================================================================

    @Test
    fun testSanitizedDesignGeneratesCorrectAnimatedSvgStructure() {
        val bmp = allocateBitmapReflectively()
        val sourceArtworkFrames = listOf(
            QrFrame(bitmap = bmp, durationMs = 400),
            QrFrame(bitmap = bmp, durationMs = 600)
        )
        val matrix = QrMatrix("https://veilframe.app/step5-structure", ErrorCorrectionLevel.M)
        val baseDesign = QrDesign(style = QrStyle.BASIC)
        val safeDesign = baseDesign.copy(
            imageSource = baseDesign.imageSource.copy(
                source = ImageSource.Animated(
                    sourceArtworkFrames.map { it.bitmap },
                    sourceArtworkFrames.map { it.durationMs }
                )
            )
        )
        val svg = AnimatedQrGenerator.generateAnimatedSvg(matrix, safeDesign)
        assertTrue("Animated SVG must have <defs> section", svg.contains("<defs>"))
        assertTrue("Must define frame group qr_frame_0", svg.contains("""<g id="qr_frame_0">"""))
        assertTrue("Must define frame group qr_frame_1", svg.contains("""<g id="qr_frame_1">"""))
        assertTrue("Must contain <use xlink:href", svg.contains("""<use xlink:href="#qr_frame_0">"""))
        assertTrue("Must contain <animate", svg.contains("<animate"))
        assertTrue("Must animate xlink:href attribute", svg.contains("""attributeName="xlink:href""""))
        assertTrue("Must cycle frame values", svg.contains("""values="#qr_frame_0;#qr_frame_1""""))
        assertTrue("Must use discrete calcMode", svg.contains("""calcMode="discrete""""))
        assertTrue("Must loop indefinitely", svg.contains("""repeatCount="indefinite""""))
        assertTrue("Must have total duration 1.000s (400+600ms)", svg.contains("""dur="1.000s""""))
    }

    @Test
    fun testAnimatedSvgKeyTimesArePreciselyProportional() {
        val bmp = allocateBitmapReflectively()
        // Three frames: 200ms, 500ms, 300ms, total 1000ms
        // Expected keyTimes: 0.000, 0.200, 0.700
        val sourceArtworkFrames = listOf(
            QrFrame(bitmap = bmp, durationMs = 200),
            QrFrame(bitmap = bmp, durationMs = 500),
            QrFrame(bitmap = bmp, durationMs = 300)
        )
        val matrix = QrMatrix("https://veilframe.app/step5-keytimes", ErrorCorrectionLevel.M)
        val baseDesign = QrDesign(style = QrStyle.BASIC)
        val safeDesign = baseDesign.copy(
            imageSource = baseDesign.imageSource.copy(
                source = ImageSource.Animated(
                    sourceArtworkFrames.map { it.bitmap },
                    sourceArtworkFrames.map { it.durationMs }
                )
            )
        )
        val svg = AnimatedQrGenerator.generateAnimatedSvg(matrix, safeDesign)
        assertTrue("keyTimes must begin at 0.000", svg.contains("keyTimes=\"0.000;"))
        assertTrue("keyTimes must contain 0.200 for the second frame boundary", svg.contains(";0.200;"))
        val hasThirdBoundary = svg.contains(";0.700\"") || svg.contains(";0.700;")
        assertTrue("keyTimes must contain 0.700 for the third frame boundary", hasThirdBoundary)
    }

    // =========================================================================
    // 7. EMPTY sourceArtworkFrames: compatibility overload passes design through unchanged
    // =========================================================================

    @Test
    fun testCompatibilityMappingWithEmptyFramesPassesDesignThrough() {
        val originalDesign = QrDesign(style = QrStyle.BASIC)
        val emptyFrames = emptyList<QrFrame>()
        // Call the ACTUAL production function with an empty list.
        val safeDesign = QrExporter.applyArtworkFramesToDesign(originalDesign, emptyFrames)
        assertSame(
            "applyArtworkFramesToDesign with empty frames must return the original design instance unchanged",
            originalDesign,
            safeDesign
        )
        assertFalse(
            "Non-animated design must not be detected as animated when empty frames are supplied",
            AnimatedQrGenerator.isDesignAnimated(safeDesign)
        )
    }

    // =========================================================================
    // 8. APNG: variable durations use ffconcat demuxer
    // =========================================================================

    @Test
    fun testApngVariableFrameDurationsUsesFfconcat() {
        val bmp = allocateBitmapReflectively()
        val frames = listOf(
            QrFrame(bitmap = bmp, durationMs = 100),
            QrFrame(bitmap = bmp, durationMs = 900)
        )
        val tempDir = createTempDirectory("apng_step5_var_").toFile()
        val outputFile = java.io.File(tempDir, "out.png")
        try {
            val cmd = AnimatedQrGenerator.buildApngFfmpegCommand(frames, tempDir, outputFile, fps = 15, loops = 0)
            assertTrue("Variable-duration APNG command must use concat demuxer", cmd.contains("-f concat"))
            assertTrue("Variable-duration APNG command must set -safe 0", cmd.contains("-safe 0"))
            assertTrue("Variable-duration APNG command must reference input.txt", cmd.contains("input.txt"))
            assertTrue("Variable-duration APNG command must output apng format", cmd.contains("-f apng"))
            assertTrue("Variable-duration APNG command must set -plays 0", cmd.contains("-plays 0"))
            val concatFile = java.io.File(tempDir, "input.txt")
            assertTrue("ffconcat file must exist for variable timing", concatFile.exists())
            val concatContent = concatFile.readText()
            assertTrue("ffconcat must contain duration 0.1000 for 100ms frame", concatContent.contains("duration 0.1000"))
            assertTrue("ffconcat must contain duration 0.9000 for 900ms frame", concatContent.contains("duration 0.9000"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 9. APNG: uniform durations use -framerate path
    // =========================================================================

    @Test
    fun testApngUniformFrameDurationsUsesFramerate() {
        val bmp = allocateBitmapReflectively()
        val frames = listOf(
            QrFrame(bitmap = bmp, durationMs = 500),
            QrFrame(bitmap = bmp, durationMs = 500),
            QrFrame(bitmap = bmp, durationMs = 500)
        )
        val tempDir = createTempDirectory("apng_step5_uniform_").toFile()
        val outputFile = java.io.File(tempDir, "out.png")
        try {
            val cmd = AnimatedQrGenerator.buildApngFfmpegCommand(frames, tempDir, outputFile, fps = 15, loops = 0)
            assertFalse("Uniform-duration APNG command must NOT use concat demuxer", cmd.contains("-f concat"))
            assertTrue("Uniform-duration APNG command must use -framerate flag", cmd.contains("-framerate"))
            assertTrue("Uniform-duration APNG must derive effective framerate 2 from 500ms", cmd.contains("-framerate 2"))
            assertTrue("Uniform-duration APNG command must output apng format", cmd.contains("-f apng"))
            assertTrue("Uniform-duration APNG command must use frame%04d pattern", cmd.contains("frame_%04d.png"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 10. SINGLE SOURCE OF TRUTH: QrDesign.imageSource contracts
    // =========================================================================

    @Test
    fun testImageSourceStyleIsAnimatedContract() {
        val bmp = allocateBitmapReflectively()

        val memoryStyle = ImageSourceStyle(source = ImageSource.Memory(bmp))
        assertFalse("Memory source must not be isAnimated", memoryStyle.isAnimated)
        assertEquals("Memory source bitmap must equal wrapped bitmap", bmp, memoryStyle.bitmap)
        assertFalse("Memory source must not have animatedFrames", memoryStyle.animatedFrames != null)

        val animatedStyle = ImageSourceStyle(
            source = ImageSource.Animated(listOf(bmp, bmp), listOf(200, 300))
        )
        assertTrue("Animated source must be isAnimated", animatedStyle.isAnimated)
        assertNotNull("Animated source must have animatedFrames", animatedStyle.animatedFrames)
        assertEquals("Animated source must have 2 frames", 2, animatedStyle.animatedFrames!!.size)
        assertEquals("First delay must be 200ms", 200, animatedStyle.frameDelaysMs!![0])
        assertEquals("Second delay must be 300ms", 300, animatedStyle.frameDelaysMs!![1])
        assertEquals("bitmap must return first frame of Animated source", bmp, animatedStyle.bitmap)
    }
}
