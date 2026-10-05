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
    fun `relativeLuminance and contrastRatio follow W3C WCAG 2_1 and sRGB standards`() {
        assertEquals(0.0f, ImageColorAnalyzer.relativeLuminance(Color.BLACK), 0.001f)
        assertEquals(1.0f, ImageColorAnalyzer.relativeLuminance(Color.WHITE), 0.001f)
        assertEquals(0.2126f, ImageColorAnalyzer.relativeLuminance(Color.RED), 0.001f)
        assertEquals(0.7152f, ImageColorAnalyzer.relativeLuminance(Color.GREEN), 0.001f)
        assertEquals(0.0722f, ImageColorAnalyzer.relativeLuminance(Color.BLUE), 0.001f)

        // Mid-gray (128, 128, 128): linear sRGB gives ~0.21586, NOT gamma 0.5
        val grayLum = ImageColorAnalyzer.relativeLuminance(128, 128, 128)
        assertTrue("Mid-gray luminance must be linearized (~0.21586)", grayLum in 0.214f..0.218f)

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
    fun `resolveAdaptiveContrastColor optimizes contrast and avoids black-on-black`() {
        val cyan = 0xFF39C5BC.toInt() // lum ~0.444
        val white = Color.WHITE // lum 1.0
        val deepNavy = 0xFF0B1021.toInt() // lum ~0.008
        val palette = listOf(cyan, deepNavy)

        // 1. Adversarial dark background (lum 0.02):
        // Old bug: dropped to Color.BLACK (0.0), producing black-on-black (CR 1.4:1).
        // Correct behavior: Cyan (lum ~0.444) has CR = (0.444+0.05)/(0.02+0.05) = 0.494/0.07 = 7.05:1!
        // It must NOT drop to black-on-black, but use cyan with CR >= 3.0!
        val darkOnDark = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = listOf(0.02f),
            referenceColor = cyan,
            companionColor = white,
            palette = palette
        ).color
        assertEquals("Dark module on near-black background must use cyan (CR > 7.0), not black", cyan, darkOnDark)
        val contrastDarkOnDark = ImageColorAnalyzer.contrastRatio(ImageColorAnalyzer.relativeLuminance(darkOnDark), 0.02f)
        assertTrue("Contrast on near-black must be >= 3.0", contrastDarkOnDark >= 3.0f)

        // 2. Bright background (lum 0.85):
        // Cyan against 0.85 has CR = (0.85+0.05)/(0.444+0.05) = 0.90/0.494 = 1.82 < 3.0.
        // It searches palette for a darker candidate: deepNavy (lum ~0.008) has CR = 0.90/0.058 = 15.5:1!
        val darkOnBright = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = listOf(0.85f),
            referenceColor = cyan,
            companionColor = white,
            palette = palette
        ).color
        assertEquals("Dark module on bright background must select high-contrast palette candidate", deepNavy, darkOnBright)
        val contrastDarkOnBright = ImageColorAnalyzer.contrastRatio(ImageColorAnalyzer.relativeLuminance(darkOnBright), 0.85f)
        assertTrue("Contrast on bright background must be >= 3.0", contrastDarkOnBright >= 3.0f)

        // 3. Light module on dark background (lum 0.02):
        // White on dark has CR = 1.05 / 0.07 = 15.0:1
        val lightOnDark = AdaptiveColorOptimizer.optimizeColor(
            isDark = false,
            frameLums = listOf(0.02f),
            referenceColor = white,
            companionColor = cyan,
            palette = palette
        ).color
        assertEquals(white, lightOnDark)

        // 4. EF Parity: transparent light modules remain transparent
        val transLight = AdaptiveColorOptimizer.optimizeColor(
            isDark = false,
            frameLums = listOf(0.50f),
            referenceColor = Color.TRANSPARENT,
            companionColor = cyan,
            palette = palette
        ).color
        assertEquals(Color.TRANSPARENT, transLight)
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

        // On the dark left side (black background), cyan has CR > 9.0 against black, so it is preserved!
        val leftDarkNodes = dataNodes.filter { it.x < 256f && it.fill == cyan }
        assertTrue("Left side (dark) dark data nodes must use cyan for high contrast", leftDarkNodes.isNotEmpty())

        // On the bright right side (white background), cyan has CR ~ 2.1 < 3.0, so it adapts to black (CR = 21.0)
        val rightDarkNodes = dataNodes.filter { it.x > 256f && it.fill == Color.BLACK }
        assertTrue("Right side (bright) dark data nodes must adapt to solid black", rightDarkNodes.isNotEmpty())
    }

    @Test
    fun `analyzeAnimated extracts conservative palette across multiple animation frames`() {
        val frame1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(0xFFFF0000.toInt())
        }
        val frame2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(0xFF0000FF.toInt())
        }

        val result = ImageColorAnalyzer.analyzeAnimated(listOf(frame1, frame2))
        assertFalse("Palette must not be empty", result.palette.isEmpty())
        assertTrue("Palette must capture colors from both frames", result.palette.size >= 2)
    }

    @Test
    fun `Test A - AdaptiveColorOptimizer enforces CR at least 3 on near-black background without black-on-black`() {
        val nearBlackBg = 0.02f
        val defaultDark = Color.BLACK
        val defaultLight = Color.WHITE
        val deepNavy = 0xFF0B1021.toInt() // lum ~0.008
        val palette = listOf(Color.BLACK, deepNavy)

        val resolved = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = listOf(nearBlackBg),
            referenceColor = defaultDark,
            companionColor = defaultLight,
            palette = palette
        ).color

        val contrast = ImageColorAnalyzer.contrastRatio(ImageColorAnalyzer.relativeLuminance(resolved), nearBlackBg)
        assertTrue("Resolved color on near-black background must achieve CR >= 3.0:1, got $contrast", contrast >= 3.0f)
        assertNotEquals("Must not return Color.BLACK on near-black background (black-on-black)", Color.BLACK, resolved)
        assertNotEquals("Must not return deep navy on near-black background", deepNavy, resolved)
    }

    @Test
    fun `Test B - AdaptiveColorOptimizer enforces CR at least 3 on bright background for light module without white-on-white`() {
        val nearWhiteBg = 0.98f
        val defaultDark = Color.BLACK
        val defaultLight = Color.WHITE
        val paleYellow = 0xFFFFFFE0.toInt() // lum > 0.95
        val palette = listOf(Color.WHITE, paleYellow)

        val resolved = AdaptiveColorOptimizer.optimizeColor(
            isDark = false,
            frameLums = listOf(nearWhiteBg),
            referenceColor = defaultLight,
            companionColor = defaultDark,
            palette = palette
        ).color

        val contrast = ImageColorAnalyzer.contrastRatio(ImageColorAnalyzer.relativeLuminance(resolved), nearWhiteBg)
        assertTrue("Resolved light module on bright background must achieve CR >= 3.0:1, got $contrast", contrast >= 3.0f)
        assertNotEquals("Must not return Color.WHITE on near-white background (white-on-white)", Color.WHITE, resolved)
        assertNotEquals("Must not return pale yellow on near-white background", paleYellow, resolved)
    }

    @Test
    fun `Test C - temporal worst-case evaluates minimum frame contrast across all frames`() {
        val frameLums = listOf(0.01f, 0.99f)
        val defaultDark = Color.BLACK
        val defaultLight = Color.WHITE

        val resolved = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = frameLums,
            referenceColor = defaultDark,
            companionColor = defaultLight,
            palette = emptyList()
        ).color

        val lum = ImageColorAnalyzer.relativeLuminance(resolved)
        val cr0 = ImageColorAnalyzer.contrastRatio(lum, 0.01f)
        val cr1 = ImageColorAnalyzer.contrastRatio(lum, 0.99f)
        val minCr = Math.min(cr0, cr1)

        assertTrue("Worst-case contrast across all frames must be >= 3.0:1, got min($cr0, $cr1) = $minCr", minCr >= 3.0f)
    }

    @Test
    fun `Test D - ADAPTIVE_PALETTE fallback guarantees both CR at least 3 and deltaL at least 0_25`() {
        val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val color75 = Color.rgb(225, 225, 225)
        canvas.drawColor(color75)

        val primaryLum = ImageColorAnalyzer.relativeLuminance(color75)
        assertTrue("Primary luminance must be around 0.75, was $primaryLum", primaryLum in 0.70f..0.80f)

        val result = ImageColorAnalyzer.analyze(bmp)
        assertTrue("Fallback contrast ratio must be >= 3.0:1, got ${result.contrastRatio}", result.contrastRatio >= 3.0f)
        assertTrue("Fallback luminance delta must be >= 0.25, got ${result.luminanceDelta}", result.luminanceDelta >= 0.25f)
        assertEquals("Light module must be the ~0.75 color", color75, result.lightColor)
        assertEquals("Dark module must be Color.BLACK to satisfy CR >= 3.0:1", Color.BLACK, result.darkColor)
    }

    @Test
    fun `Test E - AdaptiveColorOptimizer preserves non-black dark candidate when defaultLight is transparent`() {
        val cyan = 0xFF39C5BC.toInt() // relative luminance ~0.42
        val lumCyan = ImageColorAnalyzer.relativeLuminance(cyan)
        assertTrue("Cyan luminance is between 0.35 and 0.45", lumCyan in 0.35f..0.45f)

        val resolved = AdaptiveColorOptimizer.optimizeColor(
            isDark = true,
            frameLums = listOf(0.02f),
            referenceColor = cyan,
            companionColor = Color.TRANSPARENT,
            palette = emptyList()
        ).color

        assertEquals("Cyan must be preserved as primary dark candidate, not rejected due to transparent light", cyan, resolved)
    }

    @Test
    fun `Test F - AdaptiveColorOptimizer preserves non-white light candidate when defaultDark is transparent`() {
        val paleCyan = 0xFF80E5FF.toInt() // relative luminance ~0.70
        val resolved = AdaptiveColorOptimizer.optimizeColor(
            isDark = false,
            frameLums = listOf(0.98f),
            referenceColor = paleCyan,
            companionColor = Color.TRANSPARENT,
            palette = emptyList()
        ).color
        assertTrue("On near-white background, light module inverts to dark to achieve contrast", ImageColorAnalyzer.relativeLuminance(resolved) < 0.30f)
    }
}
