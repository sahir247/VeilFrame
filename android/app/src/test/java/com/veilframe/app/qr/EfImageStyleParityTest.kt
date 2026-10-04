package com.veilframe.app.qr

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.geometry.AnimatedImageNode
import com.veilframe.app.qr.geometry.CircleNode
import com.veilframe.app.qr.geometry.ImageGeometryBuilder
import com.veilframe.app.qr.geometry.ImageNode
import com.veilframe.app.qr.geometry.IrSvgRenderer
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.renderer.ImageRenderer
import com.veilframe.app.qr.ui.QrStudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Parity and regression test suite for EFQRCode 7.0.3 EFStyleImage semantics:
 * 1. Embedded photo in matrix area masked out of the 3 finder 8x8 regions (#hole mask).
 * 2. Scaled data modules (e.g. 0.35 dot scale) centered inside each module cell.
 * 3. Transparent light modules (alpha = 0) allowing underlying artwork to show through.
 * 4. Solid finder patterns with 8x8 white backing and cyan core/ring.
 * 5. Default timing & alignment shapes in UiState set to SQUARE for EF parity.
 * 6. RGBA / Alpha color picker support and ViewModel light transparency toggle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class EfImageStyleParityTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createTestPhoto(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Fill with pastel artwork colors (resembling anime / portrait background)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (180 + (x * 75 / width)).coerceIn(0, 255)
                val g = (200 + (y * 55 / height)).coerceIn(0, 255)
                val b = (220 + ((x + y) * 35 / (width + height))).coerceIn(0, 255)
                bitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return bitmap
    }

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            val hints = mapOf(
                com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                com.google.zxing.DecodeHintType.TRY_HARDER to true
            )
            setHints(hints)
        }
        return try {
            reader.decodeWithState(binaryBitmap).text
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun `EF Image style standard defaults matches EFQRCode library configuration`() {
        val photo = createTestPhoto(400, 400)
        val design = QrDesign.efImage(photo = photo)

        assertEquals("Style must be IMAGE", QrStyle.IMAGE, design.style)
        assertEquals("Standard EF default data scale must be 1.0", 1.0f, design.imageDataScale ?: 0f, 0.001f)
        assertEquals("Standard EF default dark color must be black", Color.BLACK, design.dataColorDark)
        assertEquals("Standard EF default light color must be white", Color.WHITE, design.dataColorLight)
        assertEquals(255, Color.alpha(design.dataColorLight))
        assertFalse("Standard EF default allowTransparent must be false", design.allowTransparent)
        assertEquals("Standard EF default finder dark must be black", Color.BLACK, design.positionDarkColor)
        assertEquals("Standard EF default finder backing must be white", Color.WHITE, design.positionLightColor)
    }

    @Test
    fun `EF Image style reference preset configuration builds valid Geometry IR`() {
        val content = "https://veilframe.app/ef-image-parity"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val photo = createTestPhoto(600, 600)

        val design = QrDesign.efImagePresetReference(photo = photo)

        assertEquals("Style must be IMAGE", QrStyle.IMAGE, design.style)
        assertEquals("Reference preset data scale must be 0.35", 0.35f, design.imageDataScale ?: 0f, 0.001f)
        assertEquals("Reference preset dark color must be cyan #39C5BC", 0xFF39C5BC.toInt(), design.dataColorDark)
        assertEquals("Reference preset light module color must be transparent", Color.TRANSPARENT, design.dataColorLight)
        assertEquals(0, Color.alpha(design.dataColorLight))
        assertTrue("allowTransparent must be true", design.allowTransparent)

        val geometry = QrGeometry.fromDesign(matrix.size, 600, 600, design)
        val renderer = ImageRenderer()
        val ir = renderer.generateGeometry(matrix, design, geometry)

        // 1. Verify defs contains #hole mask for finder cutouts
        val defsStr = ir.defs.joinToString("\n")
        assertTrue("Defs must contain hole mask", defsStr.contains("""mask id="hole""""))

        // 2. Verify rootNodes contains ImageNode with maskId "hole"
        val imageNode = ir.rootNodes.filterIsInstance<ImageNode>().firstOrNull { it.maskId == "hole" }
        assertNotNull("ImageNode with hole mask must be present", imageNode)

        // 3. Verify data module nodes: only dark scaled modules exist (no light transparent rects)
        val rectNodes = ir.rootNodes.filterIsInstance<RectNode>()
        val dataModuleNodes = rectNodes.filter { it.width < geometry.moduleSize * 0.9f }
        assertTrue("Scaled data modules must be emitted", dataModuleNodes.isNotEmpty())
        for (node in dataModuleNodes) {
            assertEquals("Data module must have cyan fill", 0xFF39C5BC.toInt(), node.fill)
            assertEquals("Data module size must match scale * moduleSize", 0.35f * geometry.moduleSize, node.width, 0.05f)
        }

        // 4. Verify finder backing rects: 3 8x8 white backing rects
        val finderBackings = rectNodes.filter { it.width == 8 * geometry.moduleSize && it.height == 8 * geometry.moduleSize }
        assertEquals("Must contain exactly 3 8x8 finder backing rects", 3, finderBackings.size)
        for (backing in finderBackings) {
            assertEquals("Finder backing must be white", Color.WHITE, backing.fill)
        }
    }

    @Test
    fun `EF Image style Canvas bitmap reveals photo beneath transparent light modules`() {
        val content = "https://veilframe.app/transparent-photo"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val photo = createTestPhoto(600, 600)

        val design = QrDesign.efImagePresetReference(photo = photo)

        val result = QrGenerator.generateBitmapResult(matrix, design)
        assertTrue("Bitmap generation must succeed", result is QrGenerator.BitmapRenderResult.Success)
        val bitmap = (result as QrGenerator.BitmapRenderResult.Success).bitmap
        assertEquals(600, bitmap.width)
        assertEquals(600, bitmap.height)

        // Check a light data module position: it must NOT be opaque white (Color.WHITE = -1)
        // Instead, it must retain the underlying photo pixel values!
        val geometry = QrGeometry.fromDesign(matrix.size, 600, 600, design)
        var testedLightModule = false
        for (col in 8 until matrix.size - 8) {
            for (row in 8 until matrix.size - 8) {
                if (matrix.roleAt(col, row) == QrModuleRole.DATA && !matrix.isDark(col, row)) {
                    val px = (geometry.offsetX + (col + 0.5f) * geometry.moduleSize).toInt()
                    val py = (geometry.offsetY + (row + 0.5f) * geometry.moduleSize).toInt()
                    val actualColor = bitmap.getPixel(px, py)
                    assertNotEquals("Light module must NOT be solid white", Color.WHITE, actualColor)
                    val expectedPhotoPixel = photo.getPixel(
                        ((col + 0.5f) * photo.width / matrix.size).toInt().coerceIn(0, photo.width - 1),
                        ((row + 0.5f) * photo.height / matrix.size).toInt().coerceIn(0, photo.height - 1)
                    )
                    val rDiff = Math.abs(Color.red(actualColor) - Color.red(expectedPhotoPixel))
                    val gDiff = Math.abs(Color.green(actualColor) - Color.green(expectedPhotoPixel))
                    val bDiff = Math.abs(Color.blue(actualColor) - Color.blue(expectedPhotoPixel))
                    assertTrue("Underlying photo must show through light module", rDiff < 40 && gDiff < 40 && bDiff < 40)
                    testedLightModule = true
                    break
                }
            }
            if (testedLightModule) break
        }
        assertTrue("At least one light module must have been checked", testedLightModule)
    }

    @Test
    fun `EF Image style SVG export omits transparent light modules and scales dark modules`() {
        val content = "https://veilframe.app/svg-image-parity"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val photo = createTestPhoto(400, 400)

        val design = QrDesign.efImagePresetReference(photo = photo)

        val svg = SvgExporter.generateSvg(matrix, design)
        assertTrue("SVG must start with XML declaration", svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue("SVG must contain #hole mask", svg.contains("""<mask id="hole">"""))
        assertTrue("SVG must apply #hole mask to image layer", svg.contains("""mask="url(#hole)""""))

        // Scaled dark modules with 0.35 size
        assertTrue("SVG must contain 0.35 scaled data modules", svg.contains("""width="0.35"""") || svg.contains("""width="0.350"""") || svg.contains("""width="0.3499"""))

        // Dark color #39C5BC
        assertTrue("SVG must contain cyan fill", svg.contains("#39C5BC") || svg.contains("#39c5bc"))

        // In the top layer (data modules on top of image), transparent light modules must not be emitted
        // (colorAlphaInt == 0 check skips them)
        assertFalse("SVG must not contain transparent rectangles with opacity 0.00", svg.contains("""opacity="0.00""""))
    }

    @Test
    fun `EF Image style SVG finder geometry matches EF canonical 8x8 backing 3x3 inner and 6x6 stroked ring with TL BL TR order`() {
        val content = "https://veilframe.app/svg-finder-forensic-parity"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val n = matrix.size
        val photo = createTestPhoto(400, 400)
        val qz = 1 // default EF quiet zone

        val design = QrDesign.efImagePresetReference(photo = photo, quietZoneModules = qz)
        val svg = SvgExporter.generateSvg(matrix, design)

        // Split SVG into defs and body (outside defs) to ensure hole-mask rects are not confused with finders
        val defsEnd = svg.indexOf("</defs>")
        assertTrue("SVG must contain defs closing tag", defsEnd > 0)
        val bodySvg = svg.substring(defsEnd)

        // 1. Extract all rect elements outside defs
        val rectRegex = Regex("""<rect\s+([^>]+)/>""")
        val bodyRects = rectRegex.findAll(bodySvg).map { it.value }.toList()

        // 2. Forensically verify exactly 3 8x8 finder backing rects with exact coordinates
        val backing8x8 = bodyRects.filter { it.contains("""width="8"""") && it.contains("""height="8"""") }
        assertEquals("Must contain exactly 3 8x8 finder backing rectangles", 3, backing8x8.size)

        val blY = n - 7
        val trX = n - 7
        // TL backing: x="1" y="1" (with qz=1)
        assertTrue("1st backing must be TL at x=\"$qz\" y=\"$qz\"", backing8x8[0].contains("""x="$qz"""") && backing8x8[0].contains("""y="$qz""""))
        // BL backing: x="1" y="${blY}"
        assertTrue("2nd backing must be BL at x=\"$qz\" y=\"$blY\"", backing8x8[1].contains("""x="$qz"""") && backing8x8[1].contains("""y="$blY""""))
        // TR backing: x="${trX}" y="1"
        assertTrue("3rd backing must be TR at x=\"$trX\" y=\"$qz\"", backing8x8[2].contains("""x="$trX"""") && backing8x8[2].contains("""y="$qz""""))

        // 3. Forensically verify exactly 3 3x3 inner center rects with exact coordinates
        val center3x3 = bodyRects.filter { it.contains("""width="3"""") && it.contains("""height="3"""") }
        assertEquals("Must contain exactly 3 3x3 finder center rectangles", 3, center3x3.size)

        val tlCenterCoord = qz + 2
        val blCenterY = n - 4
        val trCenterX = n - 4
        // TL center: x="${tlCenterCoord}" y="${tlCenterCoord}"
        assertTrue("1st center must be TL at x=\"$tlCenterCoord\" y=\"$tlCenterCoord\"", center3x3[0].contains("""x="$tlCenterCoord"""") && center3x3[0].contains("""y="$tlCenterCoord""""))
        // BL center: x="${tlCenterCoord}" y="${blCenterY}"
        assertTrue("2nd center must be BL at x=\"$tlCenterCoord\" y=\"$blCenterY\"", center3x3[1].contains("""x="$tlCenterCoord"""") && center3x3[1].contains("""y="$blCenterY""""))
        // TR center: x="${trCenterX}" y="${tlCenterCoord}"
        assertTrue("3rd center must be TR at x=\"$trCenterX\" y=\"$tlCenterCoord\"", center3x3[2].contains("""x="$trCenterX"""") && center3x3[2].contains("""y="$tlCenterCoord""""))

        // 4. Forensically verify exactly 3 6x6 stroked outer ring rects with exact coordinates
        val ring6x6 = bodyRects.filter { it.contains("""width="6"""") && it.contains("""height="6"""") && it.contains("""fill="none"""") && it.contains("""stroke-width="1"""") }
        assertEquals("Must contain exactly 3 6x6 stroked outer ring rectangles", 3, ring6x6.size)

        val tlRingCoord = SvgExporter.formatCoord(qz + 0.5)
        val blRingY = SvgExporter.formatCoord(n - 5.5)
        val trRingX = SvgExporter.formatCoord(n - 5.5)
        // TL ring: x="${tlRingCoord}" y="${tlRingCoord}"
        assertTrue("1st ring must be TL at x=\"$tlRingCoord\" y=\"$tlRingCoord\"", ring6x6[0].contains("""x="$tlRingCoord"""") && ring6x6[0].contains("""y="$tlRingCoord""""))
        // BL ring: x="${tlRingCoord}" y="${blRingY}"
        assertTrue("2nd ring must be BL at x=\"$tlRingCoord\" y=\"$blRingY\"", ring6x6[1].contains("""x="$tlRingCoord"""") && ring6x6[1].contains("""y="$blRingY""""))
        // TR ring: x="${trRingX}" y="${tlRingCoord}"
        assertTrue("3rd ring must be TR at x=\"$trRingX\" y=\"$tlRingCoord\"", ring6x6[2].contains("""x="$trRingX"""") && ring6x6[2].contains("""y="$tlRingCoord""""))

        // 5. Forensically verify element sequence & grouping in body SVG:
        // TL (bg -> center -> ring) -> BL (bg -> center -> ring) -> TR (bg -> center -> ring)
        val idxTlBg = bodySvg.indexOf(backing8x8[0])
        val idxTlCenter = bodySvg.indexOf(center3x3[0])
        val idxTlRing = bodySvg.indexOf(ring6x6[0])

        val idxBlBg = bodySvg.indexOf(backing8x8[1])
        val idxBlCenter = bodySvg.indexOf(center3x3[1])
        val idxBlRing = bodySvg.indexOf(ring6x6[1])

        val idxTrBg = bodySvg.indexOf(backing8x8[2])
        val idxTrCenter = bodySvg.indexOf(center3x3[2])
        val idxTrRing = bodySvg.indexOf(ring6x6[2])

        assertTrue("TL backing must precede TL center", idxTlBg < idxTlCenter)
        assertTrue("TL center must precede TL ring", idxTlCenter < idxTlRing)
        assertTrue("TL ring must precede BL backing", idxTlRing < idxBlBg)
        assertTrue("BL backing must precede BL center", idxBlBg < idxBlCenter)
        assertTrue("BL center must precede BL ring", idxBlCenter < idxBlRing)
        assertTrue("BL ring must precede TR backing", idxBlRing < idxTrBg)
        assertTrue("TR backing must precede TR center", idxTrBg < idxTrCenter)
        assertTrue("TR center must precede TR ring", idxTrCenter < idxTrRing)

        // 6. Must NOT emit generic concentric 7x7 outer or 5x5 background cutout rects
        assertFalse("SVG must not contain generic 7x7 outer filled rect", bodySvg.contains("""width="7" height="7""""))
        assertFalse("SVG must not contain generic 5x5 background cutout rect", bodySvg.contains("""width="5" height="5""""))
    }

    @Test
    fun `EF Image style with high contrast photo decodes via ZXing`() {
        val content = "https://veilframe.app/scan-success"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        // High-key light background photo (e.g. White/light pastel anime background)
        // provides strong contrast against dark cyan / dark modules
        val lightPhoto = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        for (y in 0 until 600) {
            for (x in 0 until 600) {
                lightPhoto.setPixel(x, y, Color.rgb(240, 245, 250))
            }
        }

        val design = QrDesign.efImage(
            photo = lightPhoto,
            darkColor = Color.BLACK,
            lightColor = Color.TRANSPARENT,
            dataScale = 0.65f, // Scaled for scanability verification
            allowTransparent = true,
            quietZoneModules = 4
        )

        val result = QrGenerator.generateBitmapResult(matrix, design)
        assertTrue("Bitmap render must succeed", result is QrGenerator.BitmapRenderResult.Success)
        val bitmap = (result as QrGenerator.BitmapRenderResult.Success).bitmap

        val decoded = decodeBitmap(bitmap)
        assertNotNull("ZXing must successfully decode the EF Image style QR code", decoded)
        assertEquals(content, decoded)
    }

    @Test
    fun `UiState defaults timingShape and alignShape to SQUARE for EF parity`() {
        val state = QrStudioViewModel.UiState()
        assertEquals(
            "Default timingShape must be SQUARE for EF parity",
            ModuleShape.SQUARE,
            state.timingShape
        )
        assertEquals(
            "Default alignShape must be SQUARE for EF parity",
            ModuleShape.SQUARE,
            state.alignShape
        )
    }

    @Test
    fun `ViewModel updateImageDataLightTransparent toggles transparency state correctly`() {
        val vm = QrStudioViewModel(Application())

        // Default state: opaque white light modules
        assertEquals(Color.WHITE, vm.state.value.imageDataLightColor)

        // Toggle to transparent
        vm.updateImageDataLightTransparent(true)
        assertEquals(Color.TRANSPARENT, vm.state.value.imageDataLightColor)
        assertEquals(0, Color.alpha(vm.state.value.imageDataLightColor))
        assertTrue(vm.state.value.imageAllowTransparent)

        // Verify built design propagates transparent light color and allowTransparent
        val design = vm.buildDesignFromState(vm.state.value.copy(style = QrStyle.IMAGE))
        assertEquals(Color.TRANSPARENT, design.dataColorLight)
        assertEquals(0, Color.alpha(design.dataColorLight))
        assertTrue(design.allowTransparent)

        // Toggle back to opaque white
        vm.updateImageDataLightTransparent(false)
        assertEquals(Color.WHITE, vm.state.value.imageDataLightColor)
        assertEquals(255, Color.alpha(vm.state.value.imageDataLightColor))
    }

    @Test
    fun `ViewModel applyImageReferencePreset applies reference sample configuration`() {
        val vm = QrStudioViewModel(Application())

        vm.applyImageReferencePreset()

        val state = vm.state.value
        assertEquals("Data module scale must be 35%", 0.35f, state.imageDataScale, 0.001f)
        assertEquals("Dark color must be cyan #39C5BC", 0xFF39C5BC.toInt(), state.imageDataDarkColor)
        assertEquals("Light color must be transparent", Color.TRANSPARENT, state.imageDataLightColor)
        assertTrue("allowTransparent must be true", state.imageAllowTransparent)
        assertEquals("Position dark color must be cyan #39C5BC", 0xFF39C5BC.toInt(), state.imagePositionDarkColor)
        assertEquals("Position light color must be white", Color.WHITE, state.imagePositionLightColor)
    }

    @Test
    fun `ViewModel applyImageStandardEfDefaults applies EF library defaults`() {
        val vm = QrStudioViewModel(Application())

        // First apply preset, then restore EF defaults
        vm.applyImageReferencePreset()
        vm.applyImageStandardEfDefaults()

        val state = vm.state.value
        assertEquals("Data module scale must be 1.0", 1.0f, state.imageDataScale, 0.001f)
        assertEquals("Dark color must be black", Color.BLACK, state.imageDataDarkColor)
        assertEquals("Light color must be white", Color.WHITE, state.imageDataLightColor)
        assertFalse("allowTransparent must be false", state.imageAllowTransparent)
        assertEquals("Position dark color must be black", Color.BLACK, state.imagePositionDarkColor)
        assertEquals("Position light color must be white", Color.WHITE, state.imagePositionLightColor)
    }

    @Test
    fun `Canvas IR finder geometry matches EF canonical 8x8 backing 3x3 inner and 6x6 stroked ring with TL BL TR order`() {
        val content = "https://veilframe.app/canvas-ir-finder-parity"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val n = matrix.size
        val photo = createTestPhoto(400, 400)
        val qz = 1

        val design = QrDesign.efImagePresetReference(photo = photo, quietZoneModules = qz)
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val ir = ImageRenderer().generateGeometry(matrix, design, geometry)

        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        // Filter all finder rect nodes: 8x8 backings, 3x3 centers, and 6x6 rings
        val rectNodes = ir.rootNodes.filterIsInstance<RectNode>()
        val finderNodes = rectNodes.filter {
            it.width == 8 * mSize || it.width == 3 * mSize || it.width == 6 * mSize
        }
        assertEquals("Must contain exactly 9 finder rect nodes (3 per finder)", 9, finderNodes.size)

        // 1. Verify TL finder (backing -> center -> ring)
        assertEquals("Node 0 must be TL 8x8 backing", 8 * mSize, finderNodes[0].width, 0.01f)
        assertEquals(ox, finderNodes[0].x, 0.01f)
        assertEquals(oy, finderNodes[0].y, 0.01f)

        assertEquals("Node 1 must be TL 3x3 center", 3 * mSize, finderNodes[1].width, 0.01f)
        assertEquals(ox + 2 * mSize, finderNodes[1].x, 0.01f)
        assertEquals(oy + 2 * mSize, finderNodes[1].y, 0.01f)

        assertEquals("Node 2 must be TL 6x6 ring", 6 * mSize, finderNodes[2].width, 0.01f)
        assertEquals(ox + 0.5f * mSize, finderNodes[2].x, 0.01f)
        assertEquals(oy + 0.5f * mSize, finderNodes[2].y, 0.01f)

        // 2. Verify BL finder (backing -> center -> ring)
        assertEquals("Node 3 must be BL 8x8 backing", 8 * mSize, finderNodes[3].width, 0.01f)
        assertEquals(ox, finderNodes[3].x, 0.01f)
        assertEquals(oy + (n - 8) * mSize, finderNodes[3].y, 0.01f)

        assertEquals("Node 4 must be BL 3x3 center", 3 * mSize, finderNodes[4].width, 0.01f)
        assertEquals(ox + 2 * mSize, finderNodes[4].x, 0.01f)
        assertEquals(oy + (n - 5) * mSize, finderNodes[4].y, 0.01f)

        assertEquals("Node 5 must be BL 6x6 ring", 6 * mSize, finderNodes[5].width, 0.01f)
        assertEquals(ox + 0.5f * mSize, finderNodes[5].x, 0.01f)
        assertEquals(oy + (n - 6.5f) * mSize, finderNodes[5].y, 0.01f)

        // 3. Verify TR finder (backing -> center -> ring)
        assertEquals("Node 6 must be TR 8x8 backing", 8 * mSize, finderNodes[6].width, 0.01f)
        assertEquals(ox + (n - 8) * mSize, finderNodes[6].x, 0.01f)
        assertEquals(oy, finderNodes[6].y, 0.01f)

        assertEquals("Node 7 must be TR 3x3 center", 3 * mSize, finderNodes[7].width, 0.01f)
        assertEquals(ox + (n - 5) * mSize, finderNodes[7].x, 0.01f)
        assertEquals(oy + 2 * mSize, finderNodes[7].y, 0.01f)

        assertEquals("Node 8 must be TR 6x6 ring", 6 * mSize, finderNodes[8].width, 0.01f)
        assertEquals(ox + (n - 6.5f) * mSize, finderNodes[8].x, 0.01f)
        assertEquals(oy + 0.5f * mSize, finderNodes[8].y, 0.01f)
    }

    @Test
    fun `EF Image style without image suppresses prepass hole mask and image nodes across IR and SVG`() {
        val content = "https://veilframe.app/no-image-test"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        // allowTransparent is true, but NO static image and NO animated frames
        val design = QrDesign(
            style = QrStyle.IMAGE,
            allowTransparent = true,
            imageDataScale = 0.35f,
            imageSource = ImageSourceStyle(source = null)
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)

        // 1. Verify Geometry IR
        val ir = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)
        assertFalse("IR defs must not contain hole mask when hasImage is false", ir.defs.any { it.contains("""mask id="hole"""") })
        assertFalse("IR masks map must not contain hole mask when hasImage is false", ir.masks.containsKey("hole"))
        assertFalse("IR rootNodes must not contain ImageNode when hasImage is false", ir.rootNodes.any { it is ImageNode || it is AnimatedImageNode })

        // Prepass full-size 1x1 data modules must NOT be present when hasImage is false
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY
        val prepassDataNodes = ir.rootNodes.filterIsInstance<RectNode>().filter { node ->
            Math.abs(node.width - mSize) < 0.001f && node.stroke == null && run {
                val col = Math.round((node.x - ox) / mSize)
                val row = Math.round((node.y - oy) / mSize)
                if (col in 0 until matrix.size && row in 0 until matrix.size) {
                    val type = matrix.typeAt(col, row)
                    type != ModuleType.TIMING && type != ModuleType.ALIGN_CENTER && type != ModuleType.ALIGN_OTHER &&
                            type != ModuleType.POS_CENTER && type != ModuleType.POS_OTHER
                } else false
            }
        }
        assertTrue("No prepass full-size data nodes when hasImage is false", prepassDataNodes.isEmpty())

        // 2. Verify Direct SVG Exporter
        val svg = SvgExporter.generateSvg(matrix, design)
        assertFalse("SVG must not contain #hole mask when hasImage is false", svg.contains("""<mask id="hole">"""))
        assertFalse("SVG must not reference mask=\"url(#hole)\" when hasImage is false", svg.contains("""mask="url(#hole)""""))
        assertFalse("SVG must not contain <image elements when hasImage is false", svg.contains("<image"))
    }

    @Test
    fun `EF Image style scaled module finder stroke width matches Canvas and SVG`() {
        val content = "https://veilframe.app/scaled-stroke-test"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val mSize = 10f
        val posSize = 0.8f

        // Test standard rectangular ring
        val squareDesign = QrDesign(
            style = QrStyle.IMAGE,
            positionSize = posSize,
            eyeStyle = EyeStyle(style = FinderStyle.CLASSIC)
        )
        val squareGeom = QrGeometry(
            matrixSize = matrix.size,
            outputWidth = (matrix.size * mSize).toInt(),
            outputHeight = (matrix.size * mSize).toInt(),
            quietZoneModules = 0
        )
        val squareIr = ImageGeometryBuilder.generateGeometry(matrix, squareDesign, squareGeom)
        val squareRing = squareIr.rootNodes.filterIsInstance<RectNode>().first { it.width == 6 * mSize && it.stroke != null }
        val expectedSw = 1.0f * posSize * mSize
        assertEquals("Rect ring strokeWidth must scale by mSize", expectedSw, squareRing.strokeWidth ?: 0f, 0.01f)
        val expectedSwStr = SvgExporter.formatCoord(expectedSw.toDouble())
        assertEquals("Rect ring strokeWidthString must match scaled stroke", expectedSwStr, squareRing.strokeWidthString)

        val squareSvg = IrSvgRenderer.render(squareIr)
        assertTrue("SVG must emit stroke-width=\"$expectedSwStr\"", squareSvg.contains("""stroke-width="$expectedSwStr""""))

        // Test CIRCLE finder ring
        val circleDesign = QrDesign(
            style = QrStyle.IMAGE,
            positionSize = posSize,
            eyeStyle = EyeStyle(style = FinderStyle.CIRCLE)
        )
        val circleIr = ImageGeometryBuilder.generateGeometry(matrix, circleDesign, squareGeom)
        val circleRing = circleIr.rootNodes.filterIsInstance<CircleNode>().first { it.radius == 3.0f * mSize && it.stroke != null }
        assertEquals("Circle ring strokeWidth must scale by mSize", expectedSw, circleRing.strokeWidth ?: 0f, 0.01f)
        assertEquals("Circle ring strokeWidthString must match scaled stroke", expectedSwStr, circleRing.strokeWidthString)

        val circleSvg = IrSvgRenderer.render(circleIr)
        assertTrue("Circle SVG must emit stroke-width=\"$expectedSwStr\"", circleSvg.contains("""stroke-width="$expectedSwStr""""))
    }

    @Test
    fun `EF Image style animated frames generate AnimatedImageNode with hole mask`() {
        val content = "https://veilframe.app/animated-test"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val frame1 = createTestPhoto(200, 200)
        val frame2 = createTestPhoto(200, 200)
        val design = QrDesign(
            style = QrStyle.IMAGE,
            imageSource = ImageSourceStyle(
                source = ImageSource.Animated(
                    frames = listOf(frame1, frame2),
                    delaysMs = listOf(150, 150)
                )
            )
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val ir = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)
        assertTrue("IR defs must contain hole mask", ir.defs.any { it.contains("""mask id="hole"""") })
        assertTrue("IR masks map must contain hole mask", ir.masks.containsKey("hole"))
        val animNode = ir.rootNodes.filterIsInstance<AnimatedImageNode>().firstOrNull()
        assertNotNull("AnimatedImageNode must be emitted", animNode)
        assertEquals("AnimatedImageNode must reference hole mask", "hole", animNode!!.maskId)
        assertEquals(2, animNode.frames.size)
    }

    @Test
    fun `EF Image style implements single column-major traversal and unifies Canvas and SVG on canonical IR`() {
        val content = "https://veilframe.app/traversal-test"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val n = matrix.size
        val design = QrDesign(
            style = QrStyle.IMAGE,
            imageDataScale = 0.5f,
            imageSource = ImageSourceStyle(source = null)
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512, 512, design)
        val mSize = geometry.moduleSize
        val ox = geometry.offsetX
        val oy = geometry.offsetY

        val ir = ImageGeometryBuilder.generateGeometry(matrix, design, geometry)

        // 1. In EF's single x-major / y-minor traversal:
        // TR Finder center is at col = n - 4, row = 3.
        // Therefore, ALL modules from col 0 up to n - 5 must appear in ir.rootNodes BEFORE TR finder!
        val trFinderBacking = ir.rootNodes.filterIsInstance<RectNode>().firstOrNull {
            Math.abs(it.width - 8 * mSize) < 0.01f && Math.abs(it.x - (ox + (n - 8) * mSize)) < 0.01f && Math.abs(it.y - oy) < 0.01f
        }
        val trIdx = ir.rootNodes.indexOf(trFinderBacking!!)

        // Ensure nodes prior to TR finder include modules from earlier columns (col < n - 4)
        val nodesBeforeTr = ir.rootNodes.subList(0, trIdx)
        val earlyColNodes = nodesBeforeTr.filterIsInstance<RectNode>().filter {
            val col = Math.round((it.x - ox) / mSize)
            col in 0 until (n - 4)
        }
        assertTrue("Earlier column modules must precede TR finder in single column-major traversal", earlyColNodes.isNotEmpty())

        // 2. Canonical IR unification:
        // SvgExporter must generate SVG identical to IrSvgRenderer.render(ImageGeometryBuilder.generateGeometry)
        val directSvg = SvgExporter.generateSvg(matrix, design, geometry = geometry)
        val irSvg = IrSvgRenderer.render(ir)
        assertEquals("SvgExporter and IrSvgRenderer must produce identical SVG for Image style", irSvg, directSvg)
    }
}
