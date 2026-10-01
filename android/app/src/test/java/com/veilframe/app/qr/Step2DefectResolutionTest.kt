package com.veilframe.app.qr

import android.graphics.Bitmap
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.BackdropStyle
import com.veilframe.app.qr.model.DirectionalInsets
import com.veilframe.app.qr.model.FractionalInsets
import com.veilframe.app.qr.model.ImageSource
import com.veilframe.app.qr.model.ImageSourceStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrFrame
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verification test suite for the three audit defect resolutions:
 * 1. Animated SVG double-render bug eliminated.
 * 2. Fractional quiet-zone matrix-size assumption fixed and preserved across IR-backed SVG styles.
 * 3. APNG per-frame variable duration timeline preserved via ffconcat.
 */
class Step2DefectResolutionTest {

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    // =========================================================================
    // 1. ANIMATED SVG DOUBLE-RENDER DEFECT ELIMINATION
    // =========================================================================

    @Test
    fun testAnimatedSvgGenerationNoDoubleRender() {
        val bmp = allocateBitmapReflectively()
        val frames = listOf(QrFrame(bmp, 200), QrFrame(bmp, 300))
        val matrix = QrMatrix("https://veilframe.app/no-double-render", ErrorCorrectionLevel.M)

        // Native animated IMAGE style
        val imageDesign = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(
                source = ImageSource.Animated(frames.map { it.bitmap }, frames.map { it.durationMs })
            )
        )

        val imageSvg = AnimatedQrGenerator.generateAnimatedSvg(matrix, imageDesign)

        // 1. Must contain exactly one root <svg> element (no nested <svg> elements)
        val svgTagMatches = Regex("<svg[\\s>]").findAll(imageSvg).count()
        assertEquals("Animated SVG must have exactly one <svg> root element", 1, svgTagMatches)

        // 2. Generic multi-frame animated SVG (e.g. BASIC / DOTS style)
        val dotsDesign = QrDesign(
            style = QrStyle.BASIC,
            imageSource = ImageSourceStyle(
                source = ImageSource.Animated(frames.map { it.bitmap }, frames.map { it.durationMs })
            )
        )

        val multiFrameSvg = AnimatedQrGenerator.generateAnimatedSvg(matrix, dotsDesign)
        val multiSvgMatches = Regex("<svg[\\s>]").findAll(multiFrameSvg).count()
        assertEquals("Multi-frame animated SVG must have exactly one root <svg>", 1, multiSvgMatches)
        assertTrue("Multi-frame animated SVG must define frame 0", multiFrameSvg.contains("<g id=\"qr_frame_0\">"))
        val secondSvgIndex = multiFrameSvg.indexOf("<svg", startIndex = multiFrameSvg.indexOf("<svg") + 4)
        assertEquals("Frames must not contain nested <svg> tags", -1, secondSvgIndex)
    }

    // =========================================================================
    // 2. FRACTIONAL QUIET-ZONE RESOLUTION AGAINST ACTUAL MATRIX SIZE
    // =========================================================================

    @Test
    fun testFractionalQuietZoneResolvesAgainstActualMatrixSize() {
        val design = QrDesign(
            backdropStyle = BackdropStyle(
                fractionalQuietZone = FractionalInsets(0.10f, 0.10f, 0.10f, 0.10f)
            )
        )

        // V1 matrix (21 modules): 21 * 10% = 2.1
        val v1Qz = design.resolveQuietZone(21)
        assertEquals(2.1f, v1Qz.left, 0.001f)
        assertEquals(2.1f, v1Qz.top, 0.001f)
        assertEquals(2, v1Qz.maxMarginInt)

        // V5 matrix (37 modules): 37 * 10% = 3.7
        val v5Qz = design.resolveQuietZone(37)
        assertEquals(3.7f, v5Qz.left, 0.001f)
        assertEquals(3.7f, v5Qz.top, 0.001f)
        assertEquals(4, v5Qz.maxMarginInt)

        // V10 matrix (57 modules): 57 * 10% = 5.7
        val v10Qz = design.resolveQuietZone(57)
        assertEquals(5.7f, v10Qz.left, 0.001f)
        assertEquals(5.7f, v10Qz.top, 0.001f)
        assertEquals(6, v10Qz.maxMarginInt)

        // Parameterized convenience helpers on QrDesign
        assertEquals(3.7f, design.effectiveQuietZoneLeftFloat(37), 0.001f)
        assertEquals(4, design.effectiveQuietZone(37))
    }

    // =========================================================================
    // 3. FRACTIONAL QUIET-ZONE PRESERVATION IN IR-BACKED SVG STYLES
    // =========================================================================

    @Test
    fun testFractionalQuietZonePreservedInIrBackedSvgStyles() {
        // High version matrix (37 modules, V5)
        val payload = "https://veilframe.app/fractional-qz-preservation-v5-matrix-payload-with-adequate-length"
        val matrix = QrMatrix(payload, ErrorCorrectionLevel.H)
        val n = matrix.size
        assertTrue("Matrix size must be greater than 21", n > 21)

        val fracDesign = QrDesign(
            backdropStyle = BackdropStyle(
                fractionalQuietZone = FractionalInsets(0.10f, 0.10f, 0.10f, 0.10f)
            )
        )

        val resolvedQz = QrGeometry.resolveQuietZone(fracDesign, n)
        val expectedTotal = n + resolvedQz.left + resolvedQz.right
        val expectedCoordStr = SvgExporter.formatCoord(expectedTotal.toDouble())
        assertTrue("Expected dimension must have fractional component", expectedTotal % 1f != 0f)

        // 1. Test DSJ style (IR-backed)
        val dsjSvg = SvgExporter.generateSvg(matrix, fracDesign.copy(style = QrStyle.DSJ))
        assertTrue("DSJ SVG must preserve exact fractional viewBox: $expectedCoordStr", dsjSvg.contains("viewBox=\"0 0 $expectedCoordStr $expectedCoordStr\""))

        // 2. Test BUBBLE style (IR-backed)
        val bubbleSvg = SvgExporter.generateSvg(matrix, fracDesign.copy(style = QrStyle.BUBBLE))
        assertTrue("BUBBLE SVG must preserve exact fractional viewBox: $expectedCoordStr", bubbleSvg.contains("viewBox=\"0 0 $expectedCoordStr $expectedCoordStr\""))

        // 3. Test LINE style (IR-backed)
        val lineSvg = SvgExporter.generateSvg(matrix, fracDesign.copy(style = QrStyle.LINE))
        assertTrue("LINE SVG must preserve exact fractional viewBox: $expectedCoordStr", lineSvg.contains("viewBox=\"0 0 $expectedCoordStr $expectedCoordStr\""))

        // 4. Test RANDOM_RECTANGLE style (IR-backed)
        val randSvg = SvgExporter.generateSvg(matrix, fracDesign.copy(style = QrStyle.RANDOM_RECTANGLE))
        assertTrue("RANDOM_RECTANGLE SVG must preserve exact fractional viewBox: $expectedCoordStr", randSvg.contains("viewBox=\"0 0 $expectedCoordStr $expectedCoordStr\""))

        // 5. Test IMAGE_RESAMPLE style (IR-backed)
        val resampleSvg = SvgExporter.generateSvg(matrix, fracDesign.copy(style = QrStyle.IMAGE_RESAMPLE))
        assertTrue("IMAGE_RESAMPLE SVG must preserve exact fractional viewBox: $expectedCoordStr", resampleSvg.contains("viewBox=\"0 0 $expectedCoordStr $expectedCoordStr\""))
    }

    // =========================================================================
    // 4. APNG VARIABLE FRAME DURATIONS VIA CONCAT DEMUXER
    // =========================================================================

    @Test
    fun testApngCommandBuilderPreservesVariableFrameDurations() {
        val bmp = allocateBitmapReflectively()
        val frame0 = QrFrame(bmp, 100)
        val frame1 = QrFrame(bmp, 900)
        val frames = listOf(frame0, frame1)

        val tempDir = File.createTempFile("apng_test_dir_", "").apply {
            delete()
            mkdirs()
        }
        val outputFile = File(tempDir, "output.png")

        try {
            val cmd = AnimatedQrGenerator.buildApngFfmpegCommand(
                renderedFrames = frames,
                tempDir = tempDir,
                outputFile = outputFile,
                fps = 15,
                loops = 3
            )

            // Must use ffconcat demuxer with input.txt
            assertTrue("APNG command must use concat demuxer for variable timing", cmd.contains("-f concat"))
            assertTrue("APNG command must use -safe 0", cmd.contains("-safe 0"))
            assertTrue("APNG command must reference input.txt", cmd.contains("input.txt"))
            assertTrue("APNG command must output apng format", cmd.contains("-f apng"))
            assertTrue("APNG command must set loops", cmd.contains("-plays 3"))

            // Verify input.txt concat script contents
            val concatFile = File(tempDir, "input.txt")
            assertTrue("Concat script input.txt must exist", concatFile.exists())
            val script = concatFile.readText()
            assertTrue("Concat script must specify version 1.0", script.contains("ffconcat version 1.0"))
            assertTrue("Concat script must contain frame 0 duration 0.1000", script.contains("duration 0.1000"))
            assertTrue("Concat script must contain frame 1 duration 0.9000", script.contains("duration 0.9000"))
            assertTrue("Concat script must reference frame_0000.png", script.contains("file 'frame_0000.png'"))
            assertTrue("Concat script must reference frame_0001.png", script.contains("file 'frame_0001.png'"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testApngCommandBuilderDerivesEffectiveFpsForUniformDurations() {
        val bmp = allocateBitmapReflectively()
        // 500ms duration per frame = 2 fps
        val frame0 = QrFrame(bmp, 500)
        val frame1 = QrFrame(bmp, 500)
        val frames = listOf(frame0, frame1)

        val tempDir = File.createTempFile("apng_uniform_dir_", "").apply {
            delete()
            mkdirs()
        }
        val outputFile = File(tempDir, "output.png")

        try {
            val cmd = AnimatedQrGenerator.buildApngFfmpegCommand(
                renderedFrames = frames,
                tempDir = tempDir,
                outputFile = outputFile,
                fps = 15, // default
                loops = 0
            )

            // Uniform timing derives effectiveFps = 2 from 500ms instead of blind 15
            assertTrue("APNG command must derive effective framerate 2 from 500ms", cmd.contains("-framerate 2"))
            assertTrue("APNG command must use apng muxer", cmd.contains("-f apng"))
            assertTrue("APNG command must specify loops", cmd.contains("-plays 0"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
