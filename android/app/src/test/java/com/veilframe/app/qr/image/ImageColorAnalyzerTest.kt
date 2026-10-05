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

    @Test
    fun `resolveAdaptiveContrastColor adapts based on local background luminance`() {
        val cyan = 0xFF39C5BC.toInt() // lum ~0.653
        val white = Color.WHITE // lum 1.0

        // Bright background (lum 0.85): cyan is darker than 0.85 - 0.15 = 0.70 -> keeps cyan
        val darkOnBright = ImageColorAnalyzer.resolveAdaptiveContrastColor(
            isDark = true,
            localLum = 0.85f,
            defaultDark = cyan,
            defaultLight = white
        )
        assertEquals(cyan, darkOnBright)

        // Dark background (lum 0.20): cyan (0.653) is NOT darker than 0.20 - 0.15 = 0.05 -> drops to BLACK
        val darkOnDark = ImageColorAnalyzer.resolveAdaptiveContrastColor(
            isDark = true,
            localLum = 0.20f,
            defaultDark = cyan,
            defaultLight = white
        )
        assertEquals(Color.BLACK, darkOnDark)

        // Light module on dark background (lum 0.20): white (1.0) is lighter than 0.20 + 0.15 = 0.35 -> keeps white
        val lightOnDark = ImageColorAnalyzer.resolveAdaptiveContrastColor(
            isDark = false,
            localLum = 0.20f,
            defaultDark = cyan,
            defaultLight = white
        )
        assertEquals(white, lightOnDark)
    }

    @Test
    fun `ADAPTIVE_CONTRAST protects structural patterns (finders, timing, alignment)`() {
        val splitBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(splitBmp)
        canvas.drawColor(Color.BLACK)

        val matrix = QrMatrix("https://veilframe.app/adaptive-contrast-test", ErrorCorrectionLevel.M)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            imageColorStrategy = ImageColorStrategy.ADAPTIVE_CONTRAST,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(splitBmp)),
            positionDarkColor = 0xFF123456.toInt(),
            positionLightColor = 0xFFABCDEF.toInt(),
            timingDarkColor = 0xFF234567.toInt(),
            timingLightColor = 0xFFBCDEFA.toInt(),
            alignDarkColor = 0xFF345678.toInt(),
            alignLightColor = 0xFFCDEFAB.toInt(),
            dataColorDark = 0xFF39C5BC.toInt(),
            dataColorLight = Color.WHITE
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val ir = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)

        // 1. Verify finders used position colors (not adaptive data colors)
        val finderNodes = ir.rootNodes.filterIsInstance<RectNode>().filter {
            it.fill == 0xFF123456.toInt() || it.fill == 0xFFABCDEF.toInt()
        }
        assertTrue("Finder nodes must use protected position colors", finderNodes.isNotEmpty())

        // 2. Verify timing used timing colors
        val timingNodes = ir.rootNodes.filterIsInstance<RectNode>().filter {
            it.fill == 0xFF234567.toInt() || it.fill == 0xFFBCDEFA.toInt()
        }
        assertTrue("Timing nodes must use protected timing colors", timingNodes.isNotEmpty())

        // 3. Verify alignment used alignment colors (if present)
        val alignNodes = ir.rootNodes.filterIsInstance<RectNode>().filter {
            it.fill == 0xFF345678.toInt() || it.fill == 0xFFCDEFAB.toInt()
        }
        assertTrue("Alignment nodes must use protected alignment colors", alignNodes.isNotEmpty())
    }

    @Test
    fun `ADAPTIVE_CONTRAST switches data module colors across split-tone background`() {
        // Left half is black (0..49), right half is white (50..99)
        val splitBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(splitBmp)
        val blackPaint = Paint().apply { color = Color.BLACK }
        val whitePaint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, 50f, 100f, blackPaint)
        canvas.drawRect(50f, 0f, 100f, 100f, whitePaint)

        val matrix = QrMatrix("https://veilframe.app/split-tone-test", ErrorCorrectionLevel.M)
        val cyan = 0xFF39C5BC.toInt()
        val design = QrDesign(
            style = QrStyle.IMAGE,
            imageColorStrategy = ImageColorStrategy.ADAPTIVE_CONTRAST,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(splitBmp)),
            imageDataScale = 0.35f,
            dataColorDark = cyan,
            dataColorLight = Color.WHITE
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val ir = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)

        val expectedDataSize = 0.35f * geometry.moduleSize
        val dataNodes = ir.rootNodes.filterIsInstance<RectNode>().filter {
            Math.abs(it.width - expectedDataSize) < 0.05f
        }

        // Data nodes in the right half (white background) with dark fill should be cyan
        val rightDarkNodes = dataNodes.filter { it.x > 256f && it.fill == cyan }
        assertTrue("Right side (bright) dark data nodes must use cyan", rightDarkNodes.isNotEmpty())

        // Data nodes in the left half (black background) with dark fill should drop to solid black
        val leftDarkNodes = dataNodes.filter { it.x < 256f && it.fill == Color.BLACK }
        assertTrue("Left side (dark) dark data nodes must adapt to solid black", leftDarkNodes.isNotEmpty())
    }
}
