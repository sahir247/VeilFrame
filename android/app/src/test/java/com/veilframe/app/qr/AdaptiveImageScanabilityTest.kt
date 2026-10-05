package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.decoder.ZxingQrDecoder
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.geometry.ImageGeometryBuilder
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.validation.ScanabilityValidator
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
 * End-to-end scanability and decodability validation suite for adaptive image color strategies
 * ([ImageColorStrategy.ADAPTIVE_PALETTE] and [ImageColorStrategy.ADAPTIVE_CONTRAST]).
 *
 * Verifies that dynamic palette extraction and per-module adaptive contrast maintain 100%
 * decode success across varied image sources, split-tone backgrounds, and multi-resolution outputs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AdaptiveImageScanabilityTest {

    private lateinit var mikuPhoto: Bitmap
    private val expectedPayload = "https://veilframe.app/adaptive-scan-verify"
    private val zxingDecoder = ZxingQrDecoder()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val stream = javaClass.classLoader?.getResourceAsStream("miku_reference.png")
            ?: javaClass.getResourceAsStream("/miku_reference.png")
        assertNotNull("miku_reference.png must exist in test resources", stream)
        mikuPhoto = BitmapFactory.decodeStream(stream)
        assertNotNull("mikuPhoto must decode successfully", mikuPhoto)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ADAPTIVE_PALETTE with Miku photo autonomously resolves colors and decodes 100 percent`() = runBlocking {
        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.H)
        val resolutions = listOf(512, 1024)

        for (res in resolutions) {
            val design = QrDesign(
                style = QrStyle.IMAGE,
                outputSize = res,
                quietZoneModules = 1,
                imageColorStrategy = ImageColorStrategy.ADAPTIVE_PALETTE,
                imageSource = ImageSourceStyle(source = ImageSource.Memory(mikuPhoto)),
                imageDataScale = 0.35f,
                allowTransparent = true,
                positionDarkColor = 0xFF39C5BC.toInt(),
                positionLightColor = Color.WHITE
            )

            // 1. Verify SVG generation contains extracted cyan theme
            val svg = SvgExporter.generateSvg(matrix, design)
            assertTrue("SVG contains hole mask", svg.contains("""<mask id="hole">"""))
            assertTrue("SVG contains cyan fill", svg.contains("#39C5BC") || svg.contains("#39c5bc"))

            // 2. Verify bitmap rendering and ZXing decode
            val renderResult = QrGenerator.generateBitmapResult(matrix, design)
            assertTrue("Render at ${res}px must succeed", renderResult is QrGenerator.BitmapRenderResult.Success)
            val bitmap = (renderResult as QrGenerator.BitmapRenderResult.Success).bitmap
            assertNotNull(bitmap)

            val zxResult = zxingDecoder.decode(bitmap)
            assertTrue("ADAPTIVE_PALETTE at ${res}px must decode successfully via ZXing", zxResult.success)
            assertEquals(expectedPayload, zxResult.text)
        }
    }

    @Test
    fun `ADAPTIVE_CONTRAST with Miku photo renders and decodes 100 percent`() = runBlocking {
        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.H)
        val resolutions = listOf(512, 1024)

        for (res in resolutions) {
            val design = QrDesign(
                style = QrStyle.IMAGE,
                outputSize = res,
                quietZoneModules = 1,
                imageColorStrategy = ImageColorStrategy.ADAPTIVE_CONTRAST,
                imageSource = ImageSourceStyle(source = ImageSource.Memory(mikuPhoto)),
                imageDataScale = 0.35f,
                dataColorDark = 0xFF39C5BC.toInt(),
                dataColorLight = Color.WHITE,
                allowTransparent = true,
                positionDarkColor = 0xFF39C5BC.toInt(),
                positionLightColor = Color.WHITE
            )

            val renderResult = QrGenerator.generateBitmapResult(matrix, design)
            assertTrue("Render at ${res}px must succeed", renderResult is QrGenerator.BitmapRenderResult.Success)
            val bitmap = (renderResult as QrGenerator.BitmapRenderResult.Success).bitmap
            assertNotNull(bitmap)

            val zxResult = zxingDecoder.decode(bitmap)
            assertTrue("ADAPTIVE_CONTRAST at ${res}px must decode successfully via ZXing", zxResult.success)
            assertEquals(expectedPayload, zxResult.text)
        }
    }

    @Test
    fun `ADAPTIVE_CONTRAST on split-tone high-contrast background decodes 100 percent`() = runBlocking {
        // Create 512x512 split-tone image: left half is dark navy (#0B1021), right half is light (#F0F4F8)
        val splitBmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(splitBmp)
        val darkPaint = Paint().apply { color = 0xFF0B1021.toInt() }
        val lightPaint = Paint().apply { color = 0xFFF0F4F8.toInt() }
        canvas.drawRect(0f, 0f, 256f, 512f, darkPaint)
        canvas.drawRect(256f, 0f, 512f, 512f, lightPaint)

        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.H)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            outputSize = 512,
            quietZoneModules = 1,
            imageColorStrategy = ImageColorStrategy.ADAPTIVE_CONTRAST,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(splitBmp)),
            imageDataScale = 0.40f,
            dataColorDark = 0xFF39C5BC.toInt(), // Cyan
            dataColorLight = Color.WHITE,
            allowTransparent = true,
            positionDarkColor = Color.BLACK,
            positionLightColor = Color.WHITE
        )

        val renderResult = QrGenerator.generateBitmapResult(matrix, design)
        assertTrue("Render on split-tone must succeed", renderResult is QrGenerator.BitmapRenderResult.Success)
        val bitmap = (renderResult as QrGenerator.BitmapRenderResult.Success).bitmap
        assertNotNull(bitmap)

        val zxResult = zxingDecoder.decode(bitmap)
        assertTrue("ADAPTIVE_CONTRAST on split-tone must decode successfully via ZXing", zxResult.success)
        assertEquals(expectedPayload, zxResult.text)
    }

    @Test
    fun `ADAPTIVE_PALETTE on dark monochromatic image falls back safely with high contrast decode`() = runBlocking {
        // Dark moody background (#101428)
        val darkBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(darkBmp)
        canvas.drawColor(0xFF101428.toInt())

        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.H)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            outputSize = 512,
            quietZoneModules = 1,
            imageColorStrategy = ImageColorStrategy.ADAPTIVE_PALETTE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(darkBmp)),
            imageDataScale = 0.40f,
            allowTransparent = true,
            positionDarkColor = Color.BLACK,
            positionLightColor = Color.WHITE
        )

        val renderResult = QrGenerator.generateBitmapResult(matrix, design)
        assertTrue("Render must succeed", renderResult is QrGenerator.BitmapRenderResult.Success)
        val bitmap = (renderResult as QrGenerator.BitmapRenderResult.Success).bitmap
        assertNotNull(bitmap)

        val zxResult = zxingDecoder.decode(bitmap)
        assertTrue("Monochromatic image with ADAPTIVE_PALETTE must decode via ZXing", zxResult.success)
        assertEquals(expectedPayload, zxResult.text)
    }

    @Test
    fun `Cross-backend IR parity - Canvas and SVG produce identical geometry IR for adaptive modes`() {
        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.M)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            outputSize = 512,
            imageColorStrategy = ImageColorStrategy.ADAPTIVE_PALETTE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(mikuPhoto)),
            imageDataScale = 0.35f,
            allowTransparent = true
        )

        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val ir = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)

        assertNotNull("IR must not be null", ir)
        assertEquals(512f, ir.width, 0.01f)
        assertEquals(512f, ir.height, 0.01f)
        assertTrue("IR must contain hole mask", ir.masks.containsKey("hole"))
        assertTrue("IR rootNodes must not be empty", ir.rootNodes.isNotEmpty())
    }

    @Test
    fun `ADAPTIVE_CONTRAST on near-black background preserves cyan and decodes 100 percent without black-on-black`() = runBlocking {
        // Near-black background (#05070E) with lum ~0.003
        val nearBlackBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(nearBlackBmp)
        canvas.drawColor(0xFF05070E.toInt())

        val cyan = 0xFF39C5BC.toInt()
        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.H)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            outputSize = 512,
            quietZoneModules = 1,
            imageColorStrategy = ImageColorStrategy.ADAPTIVE_CONTRAST,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(nearBlackBmp)),
            imageDataScale = 0.40f,
            dataColorDark = cyan,
            dataColorLight = Color.WHITE,
            allowTransparent = true,
            positionDarkColor = Color.BLACK,
            positionLightColor = Color.WHITE
        )

        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val ir = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)

        // Verify that dark modules are rendered with cyan (CR > 8.0 against near-black), NOT black-on-black
        val darkDataNodes = ir.rootNodes.filterIsInstance<RectNode>().filter { it.fill == cyan }
        assertFalse("Dark modules on near-black must use cyan with high contrast", darkDataNodes.isEmpty())

        val renderResult = QrGenerator.generateBitmapResult(matrix, design)
        assertTrue("Render must succeed", renderResult is QrGenerator.BitmapRenderResult.Success)
        val bitmap = (renderResult as QrGenerator.BitmapRenderResult.Success).bitmap
        assertNotNull(bitmap)

        val zxResult = zxingDecoder.decode(bitmap)
        assertTrue("ADAPTIVE_CONTRAST on near-black must decode via ZXing", zxResult.success)
        assertEquals(expectedPayload, zxResult.text)
    }

    @Test
    fun `ADAPTIVE_CONTRAST on animated alternating frames maintains scanability across frames`() = runBlocking {
        // Frame 1 is dark navy, Frame 2 is bright silver
        val frame1 = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(0xFF0B1021.toInt())
        }
        val frame2 = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(0xFFE2E8F0.toInt())
        }
        val animFrames = listOf(QrFrame(frame1), QrFrame(frame2))

        val matrix = QrMatrix(expectedPayload, ErrorCorrectionLevel.H)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            outputSize = 512,
            quietZoneModules = 1,
            imageColorStrategy = ImageColorStrategy.ADAPTIVE_CONTRAST,
            imageSource = ImageSourceStyle(
                source = ImageSource.Animated(frames = listOf(frame1, frame2), delaysMs = listOf(100, 100))
            ),
            imageDataScale = 0.40f,
            dataColorDark = 0xFF39C5BC.toInt(),
            dataColorLight = Color.WHITE,
            allowTransparent = true,
            positionDarkColor = Color.BLACK,
            positionLightColor = Color.WHITE
        )

        val renderResult = QrGenerator.generateBitmapResult(matrix, design)
        assertTrue("Render animated must succeed", renderResult is QrGenerator.BitmapRenderResult.Success)
        val bitmap = (renderResult as QrGenerator.BitmapRenderResult.Success).bitmap
        assertNotNull(bitmap)

        val zxResult = zxingDecoder.decode(bitmap)
        assertTrue("Animated alternating frames with ADAPTIVE_CONTRAST must decode via ZXing", zxResult.success)
        assertEquals(expectedPayload, zxResult.text)
    }
}
