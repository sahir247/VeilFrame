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
import com.veilframe.app.qr.geometry.ImageNode
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
    fun `EF Image style reference configuration builds valid Geometry IR`() {
        val content = "https://veilframe.app/ef-image-parity"
        val matrix = QrMatrix(content, ErrorCorrectionLevel.H)
        val photo = createTestPhoto(600, 600)

        val design = QrDesign.efImage(
            photo = photo,
            darkColor = 0xFF39C5BC.toInt(), // EF cyan from reference image
            lightColor = Color.TRANSPARENT,
            dataScale = 0.35f,
            allowTransparent = true,
            finderColor = 0xFF39C5BC.toInt(),
            finderBackingColor = Color.WHITE
        )

        assertEquals("Style must be IMAGE", QrStyle.IMAGE, design.style)
        assertEquals("Data scale must be 0.35", 0.35f, design.imageDataScale ?: 0f, 0.001f)
        assertEquals("Light module color must be transparent", Color.TRANSPARENT, design.dataColorLight)
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

        val design = QrDesign.efImage(
            photo = photo,
            darkColor = 0xFF39C5BC.toInt(),
            lightColor = Color.TRANSPARENT,
            dataScale = 0.35f,
            allowTransparent = true
        )

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

        val design = QrDesign.efImage(
            photo = photo,
            darkColor = 0xFF39C5BC.toInt(),
            lightColor = Color.TRANSPARENT,
            dataScale = 0.35f,
            allowTransparent = true
        )

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
}
