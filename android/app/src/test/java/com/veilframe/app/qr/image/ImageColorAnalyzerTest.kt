package com.veilframe.app.qr.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.geometry.ImageGeometryBuilder
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.ui.QrStudioViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ImageColorAnalyzerTest {

    @Test
    fun `analyze handles null and recycled bitmaps gracefully`() {
        val nullResult = ImageColorAnalyzer.analyze(null)
        assertEquals(Color.BLACK, nullResult.darkColor)
        assertEquals(Color.WHITE, nullResult.lightColor)
        assertTrue(nullResult.palette.isEmpty())
        assertEquals(21.0f, nullResult.contrastRatio, 0.01f)
        assertEquals(1.0f, nullResult.luminanceDelta, 0.01f)

        val recycled = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        recycled.recycle()
        val recycledResult = ImageColorAnalyzer.analyze(recycled)
        assertEquals(Color.BLACK, recycledResult.darkColor)
        assertEquals(Color.WHITE, recycledResult.lightColor)
        assertTrue(recycledResult.palette.isEmpty())
    }

    @Test
    fun `relativeLuminance and contrastRatio follow WCAG standards`() {
        assertEquals(0.0f, ImageColorAnalyzer.relativeLuminance(Color.BLACK), 0.001f)
        assertEquals(1.0f, ImageColorAnalyzer.relativeLuminance(Color.WHITE), 0.001f)
        assertEquals(21.0f, ImageColorAnalyzer.contrastRatio(Color.BLACK, Color.WHITE), 0.01f)
        assertEquals(1.0f, ImageColorAnalyzer.contrastRatio(Color.RED, Color.RED), 0.01f)
    }

    @Test
    fun `filters transparent pixels and extracts distinct colors`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Background is transparent
        canvas.drawColor(Color.TRANSPARENT)
        // Draw a solid square of blue
        val paint = Paint().apply { color = 0xFF0038F8.toInt() }
        canvas.drawRect(20f, 20f, 80f, 80f, paint)

        val result = ImageColorAnalyzer.analyze(bmp)
        assertFalse(result.palette.isEmpty())
        val top = result.palette.first()
        assertEquals(0x00, Color.red(top))
        assertTrue("Green channel within tolerance", Math.abs(Color.green(top) - 0x38) <= 15)
        assertTrue("Blue channel within tolerance", Math.abs(Color.blue(top) - 0xF8) <= 15)
    }

    @Test
    fun `intra-palette contrast selects both dark and light from image when contrast is adequate`() {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Draw half Navy (#000080) and half Gold (#FFD700)
        val navyPaint = Paint().apply { color = 0xFF000080.toInt() }
        val goldPaint = Paint().apply { color = 0xFFFFD700.toInt() }
        canvas.drawRect(0f, 0f, 50f, 100f, navyPaint)
        canvas.drawRect(50f, 0f, 100f, 100f, goldPaint)

        val result = ImageColorAnalyzer.analyze(bmp)
        assertTrue("Contrast ratio must be >= 3.0", result.contrastRatio >= 3.0f)
        assertTrue("Luminance delta must be >= 0.25", result.luminanceDelta >= 0.25f)
        assertTrue("Both colors must be in palette", result.palette.contains(result.darkColor) || result.palette.contains(result.lightColor))
    }

    @Test
    fun `miku reference image extracts cyan and pairs with high contrast light module`() {
        val stream = javaClass.classLoader?.getResourceAsStream("miku_reference.png")
            ?: javaClass.getResourceAsStream("/miku_reference.png")
        assertNotNull("miku_reference.png must exist in test resources", stream)
        val mikuBmp = BitmapFactory.decodeStream(stream)
        assertNotNull(mikuBmp)

        val result = ImageColorAnalyzer.analyze(mikuBmp)
        assertFalse("Palette must not be empty", result.palette.isEmpty())

        // Top prominent color should be Miku cyan (#39C5BC)
        val top = result.palette.first()
        val r = Color.red(top)
        val g = Color.green(top)
        val b = Color.blue(top)
        assertTrue("Red must be around 0x39", r in 0x20..0x55)
        assertTrue("Green must be around 0xC5", g in 0xB0..0xD5)
        assertTrue("Blue must be around 0xBC", b in 0xA5..0xD0)

        // Dark color should be Miku cyan, and light color should be Color.WHITE (separation >= 0.25)
        assertEquals(top, result.darkColor)
        assertEquals(Color.WHITE, result.lightColor)
        assertTrue("Luminance delta must be >= 0.25", result.luminanceDelta >= 0.25f)
    }

    @Test
    fun `ImageGeometryBuilder uses adaptive colors when imageColorStrategy is ADAPTIVE_PALETTE`() {
        val stream = javaClass.classLoader?.getResourceAsStream("miku_reference.png")
            ?: javaClass.getResourceAsStream("/miku_reference.png")
        val mikuBmp = BitmapFactory.decodeStream(stream)

        val matrix = QrMatrix("https://veilframe.app/adaptive-test", ErrorCorrectionLevel.M)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            imageColorStrategy = ImageColorStrategy.ADAPTIVE_PALETTE,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(mikuBmp)),
            imageDataScale = 0.35f,
            dataColorDark = Color.BLACK, // Fixed fallback that should be overridden by adaptive
            dataColorLight = Color.DKGRAY
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val ir = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)

        val expectedDataSize = 0.35f * geometry.moduleSize
        val dataNodes = ir.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedDataSize) < 0.05f && Math.abs(it.height - expectedDataSize) < 0.05f
        }
        assertFalse("Data nodes must be present", dataNodes.isEmpty())

        // Verify that dark modules are rendered with Miku cyan, NOT the fixed Color.BLACK
        val darkNodes = dataNodes.filter { it.fill != Color.WHITE }
        assertFalse("Dark data nodes must be present", darkNodes.isEmpty())
        val firstDarkFill = darkNodes.first().fill ?: 0
        assertNotEquals("Must not use fixed Color.BLACK", Color.BLACK, firstDarkFill)
        val r = Color.red(firstDarkFill)
        val g = Color.green(firstDarkFill)
        val b = Color.blue(firstDarkFill)
        assertTrue("Must be Miku cyan", r in 0x20..0x55 && g in 0xB0..0xD5 && b in 0xA5..0xD0)

        // Verify that light modules are rendered with Color.WHITE
        val lightNodes = dataNodes.filter { it.fill == Color.WHITE }
        assertFalse("White light modules must be present", lightNodes.isEmpty())
    }

    @Test
    fun `ViewModel updateImageColorStrategy ADAPTIVE_PALETTE updates imageDataDarkColor and imageDataLightColor`() {
        val stream = javaClass.classLoader?.getResourceAsStream("miku_reference.png")
            ?: javaClass.getResourceAsStream("/miku_reference.png")
        val mikuBmp = BitmapFactory.decodeStream(stream)

        val vm = QrStudioViewModel(Application())
        vm.updateSourceImage(mikuBmp)
        vm.updateImageColorStrategy(ImageColorStrategy.ADAPTIVE_PALETTE)

        assertEquals(ImageColorStrategy.ADAPTIVE_PALETTE, vm.state.value.imageColorStrategy)
        val darkColor = vm.state.value.imageDataDarkColor
        val lightColor = vm.state.value.imageDataLightColor

        assertEquals(Color.WHITE, lightColor)
        val r = Color.red(darkColor)
        val g = Color.green(darkColor)
        val b = Color.blue(darkColor)
        assertTrue("ViewModel dark color must be Miku cyan", r in 0x20..0x55 && g in 0xB0..0xD5 && b in 0xA5..0xD0)
    }
}
