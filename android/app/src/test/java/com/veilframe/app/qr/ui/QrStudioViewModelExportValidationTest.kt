package com.veilframe.app.qr.ui

import android.app.Application
import android.graphics.Bitmap
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.renderer.ConnectedOrganicRenderer
import com.veilframe.app.qr.renderer.RandomRectangleRenderer
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying that QrStudioViewModel does not silently export fallback URLs
 * like "https://example.com" when the user-provided content is blank, and properly sets
 * user-facing validation error messages.
 */
class QrStudioViewModelExportValidationTest {

    @Test
    fun testExportRejectedWhenContentIsBlank() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        // Ensure content starts blank or is set to whitespace
        vm.updateContent("   ")

        // 1. Save to Gallery
        vm.saveToGallery()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 2. Save SVG
        vm.saveSvg()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 3. Save GIF
        vm.saveGif()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 4. Save Video
        vm.saveVideo()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 5. Save Animated SVG
        vm.saveAnimatedSvg()
        assertEquals("Content is required to export QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)

        // 6. Share
        vm.share()
        assertEquals("Content is required to share QR code", vm.state.value.saveResult)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.isExporting)
    }

    @Test
    fun testBlankContentDoesNotGeneratePreviewOrExampleCom() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateContent("   ")

        // Must not silently generate a preview for example.com
        assertNull(vm.state.value.bitmap)
        assertNull(vm.state.value.matrix)
        assertNull(vm.state.value.scanabilityReport)
        assertFalse(vm.state.value.isRenderingPreview)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun testAutoRepairRefusesBlankContent() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateContent("")
        vm.autoRepair()

        // Auto repair must not run or set repairNotice on blank content
        assertNull(vm.state.value.repairNotice)
        assertNull(vm.state.value.bitmap)
    }

    @Test
    fun testSeparatePreviewAndExportLoadingStates() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        assertFalse(vm.state.value.isRenderingPreview)
        assertFalse(vm.state.value.isExporting)
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun testFormStateAndStyleStateUpdates() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        // Preset and Form Hydration
        vm.updateActivePreset(999)
        assertEquals(999, vm.state.value.activePresetId)

        vm.updateWifiForm("TestSSID", "TestPass", 2, true)
        assertEquals("TestSSID", vm.state.value.wifiSsid)
        assertEquals("TestPass", vm.state.value.wifiPassword)
        assertEquals(2, vm.state.value.wifiSecurityPos)
        assertTrue(vm.state.value.wifiHidden)

        vm.updateVcardForm("Partha", "Maitra", "9876543210", "partha@example.com", "VeilFrame")
        assertEquals("Partha", vm.state.value.vcardFirst)
        assertEquals("Maitra", vm.state.value.vcardLast)
        assertEquals("9876543210", vm.state.value.vcardPhone)
        assertEquals("partha@example.com", vm.state.value.vcardEmail)
        assertEquals("VeilFrame", vm.state.value.vcardOrg)

        vm.updateEmailForm("hello@example.com", "Hi", "Message")
        assertEquals("hello@example.com", vm.state.value.emailRecipient)
        assertEquals("Hi", vm.state.value.emailSubject)
        assertEquals("Message", vm.state.value.emailBody)

        vm.updateSmsForm("123456", "Hello")
        assertEquals("123456", vm.state.value.smsPhone)
        assertEquals("Hello", vm.state.value.smsBody)

        vm.updateUpiForm("merchant@upi", "250.00")
        assertEquals("merchant@upi", vm.state.value.upiVpa)
        assertEquals("250.00", vm.state.value.upiAmount)

        // Contextual Style Options
        vm.updateD25Depth(1.75f)
        assertEquals(1.75f, vm.state.value.d25Depth, 0.001f)

        vm.updateLineDirection(com.veilframe.app.qr.model.LineDirection.CROSS)
        assertEquals(com.veilframe.app.qr.model.LineDirection.CROSS, vm.state.value.lineDirection)

        vm.updateLineThickness(0.85f)
        assertEquals(0.85f, vm.state.value.lineThickness, 0.001f)

        vm.updateDsjSizes(0.45f, 0.45f)
        assertEquals(0.45f, vm.state.value.dsjLineSize, 0.001f)

        val initialSeed = vm.state.value.randomRectSeed
        vm.randomizeRandomRectSeed()
        assertNotEquals(initialSeed, vm.state.value.randomRectSeed)
    }

    @Test
    fun testRepairNoticeCanBeCleared() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.clearRepairNotice()
        assertNull(vm.state.value.repairNotice)
    }

    @Test
    fun testScannerPipelinePauseAndResume() {
        val scanner = com.veilframe.app.qr.scanner.QrScanner {}
        assertFalse(scanner.isAnalysisPaused)

        scanner.pauseAnalysis()
        assertTrue(scanner.isAnalysisPaused)

        scanner.resumeAnalysis()
        assertFalse(scanner.isAnalysisPaused)
        scanner.close()
    }

    @Test
    fun testIndependentDsjSizes() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateDsjLineSize(0.42f)
        assertEquals(0.42f, vm.state.value.dsjLineSize, 0.001f)
        // xSize should remain at default 0.7f
        assertEquals(0.70f, vm.state.value.dsjXSize, 0.001f)

        vm.updateDsjXSize(0.88f)
        assertEquals(0.42f, vm.state.value.dsjLineSize, 0.001f)
        assertEquals(0.88f, vm.state.value.dsjXSize, 0.001f)

        vm.updateDsjColors(0x112233, 0x445566, 0x778899)
        assertEquals(0x112233, vm.state.value.dsjHorizontalColor)
        assertEquals(0x445566, vm.state.value.dsjVerticalColor)
        assertEquals(0x778899, vm.state.value.dsjXColor)
    }

    @Test
    fun testMultiDimensionalD25Style() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateD25Depth(1.4f)
        vm.updateD25PositionDepth(0.8f)
        vm.updateD25Angle(30f)
        vm.updateD25Colors(0x10000000, 0x20000000)

        assertEquals(1.4f, vm.state.value.d25Depth, 0.001f)
        assertEquals(0.8f, vm.state.value.d25PositionDepth, 0.001f)
        assertEquals(30f, vm.state.value.d25Angle, 0.001f)
        assertEquals(0x10000000, vm.state.value.d25LeftColor)
        assertEquals(0x20000000, vm.state.value.d25RightColor)
    }

    @Test
    fun testLineVariantAndTopology() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateLineVariant(LineVariant.CIRCUIT)
        assertEquals(LineVariant.CIRCUIT, vm.state.value.lineVariant)

        vm.updateLineLengthFraction(0.75f)
        assertEquals(0.75f, vm.state.value.lineLengthFraction, 0.001f)

        vm.updateLineTopology(accentRings = true, circuitBridges = true)
        assertTrue(vm.state.value.lineAccentRings)
        assertTrue(vm.state.value.lineCircuitBridges)
    }

    @Test
    fun testBubbleFunctionAndJitterUpdaters() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateBubbleCluster(ambient = false, density = 0.35f)
        assertFalse(vm.state.value.bubbleAmbient)
        assertEquals(0.35f, vm.state.value.bubbleDensity, 0.001f)

        vm.updateRandomJitter(scale = 0.4f, offset = 0.2f, color = 0.05f)
        assertEquals(0.4f, vm.state.value.randomJitterScale, 0.001f)
        assertEquals(0.2f, vm.state.value.randomJitterOffset, 0.001f)
        assertEquals(0.05f, vm.state.value.randomJitterColor, 0.001f)

        vm.updateVeilFunction(type = VeilFunctionType.CIRCLE, dataStyle = VeilFunctionDataStyle.RECTANGLE)
        assertEquals(VeilFunctionType.CIRCLE, vm.state.value.veilFunctionType)
        assertEquals(VeilFunctionDataStyle.RECTANGLE, vm.state.value.veilFunctionDataStyle)

        vm.updateParamFunction(type = FunctionType.SPIRAL)
        assertEquals(FunctionType.SPIRAL, vm.state.value.paramFunctionType)

        vm.updateConnectedLineThickness(0.33f)
        assertEquals(0.33f, vm.state.value.connectedLineThickness, 0.001f)

        vm.updateGradient(start = 0xFF0000, end = 0x00FF00, type = GradientType.LINEAR)
        assertEquals(0xFF0000, vm.state.value.gradientStart)
        assertEquals(0x00FF00, vm.state.value.gradientEnd)
        assertEquals(GradientType.LINEAR, vm.state.value.gradientType)

        val insets = DirectionalInsets(left = 2, top = 3, right = 4, bottom = 5)
        vm.updateDirectionalQuietZone(insets)
        assertEquals(insets, vm.state.value.directionalQuietZone)
    }

    @Test
    fun testSingleFlightExportSerialization() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        // Multiple rapid export attempts on blank content are rejected safely
        vm.saveToGallery()
        vm.saveSvg()
        vm.saveGif()
        vm.saveVideo()
        vm.saveAnimatedSvg()
        vm.share()

        assertFalse(vm.state.value.isExporting)
        assertEquals("Content is required to share QR code", vm.state.value.saveResult)
    }

    @Test
    fun testExportButtonEligibilitySemantics() {
        // Contract: canExport = content.isNotBlank() && !isRenderingPreview && !isExporting
        // Does NOT depend on state.bitmap != null
        val stateBlank = QrStudioViewModel.UiState(content = "", isRenderingPreview = false, isExporting = false, bitmap = null)
        val canExportBlank = stateBlank.content.isNotBlank() && !stateBlank.isRenderingPreview && !stateBlank.isExporting
        assertFalse(canExportBlank)

        val stateReadyNoBitmapYet = QrStudioViewModel.UiState(content = "https://veilframe.app", isRenderingPreview = false, isExporting = false, bitmap = null)
        val canExportReady = stateReadyNoBitmapYet.content.isNotBlank() && !stateReadyNoBitmapYet.isRenderingPreview && !stateReadyNoBitmapYet.isExporting
        assertTrue(canExportReady)

        val stateBusyRendering = QrStudioViewModel.UiState(content = "https://veilframe.app", isRenderingPreview = true, isExporting = false, bitmap = null)
        val canExportBusyRendering = stateBusyRendering.content.isNotBlank() && !stateBusyRendering.isRenderingPreview && !stateBusyRendering.isExporting
        assertFalse(canExportBusyRendering)

        val stateBusyExporting = QrStudioViewModel.UiState(content = "https://veilframe.app", isRenderingPreview = false, isExporting = true, bitmap = null)
        val canExportBusyExporting = stateBusyExporting.content.isNotBlank() && !stateBusyExporting.isRenderingPreview && !stateBusyExporting.isExporting
        assertFalse(canExportBusyExporting)
    }

    @Test
    fun testBuildDesignFromStatePopulatesAllStyleParameters() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        vm.updateContent("https://veilframe.app")
        vm.updateD25Depth(1.8f)
        vm.updateD25PositionDepth(0.6f)
        vm.updateD25Angle(35f)
        vm.updateD25Colors(0x111111, 0x222222)
        vm.updateLineDirection(LineDirection.CROSS)
        vm.updateLineThickness(0.7f)
        vm.updateLineVariant(LineVariant.CIRCUIT)
        vm.updateLineLengthFraction(0.85f)
        vm.updateLineTopology(accentRings = true, circuitBridges = true)
        vm.updateDsjLineSize(0.45f)
        vm.updateDsjXSize(0.95f)
        vm.updateDsjColors(0x123456, 0x654321, 0xABCDEF)
        vm.updateRandomJitter(scale = 0.35f, offset = 0.22f, color = 0.08f)
        vm.updateBubbleCluster(ambient = false, density = 0.28f)
        vm.updateVeilFunction(type = VeilFunctionType.CIRCLE, dataStyle = VeilFunctionDataStyle.RECTANGLE)
        vm.updateParamFunction(type = FunctionType.NOISE)
        vm.updateConnectedLineThickness(0.38f)
        vm.updateGradient(start = 0xFF0000, end = 0x0000FF, type = GradientType.LINEAR)
        val insets = DirectionalInsets(left = 1, top = 2, right = 3, bottom = 4)
        vm.updateDirectionalQuietZone(insets)

        val design = vm.buildDesignFromState(vm.state.value, isPreview = false)

        // Verify DepthStyle
        assertEquals(1.8f, design.depthStyle.depth, 0.001f)
        assertEquals(0.6f, design.depthStyle.positionDepth, 0.001f)
        assertEquals(35f, design.depthStyle.angleDegrees, 0.001f)
        assertEquals(0x111111, design.depthStyle.leftColor)
        assertEquals(0x222222, design.depthStyle.rightColor)

        // Verify LineStyle
        assertEquals(LineDirection.CROSS, design.lineStyle.direction)
        assertEquals(0.7f, design.lineStyle.thicknessFraction, 0.001f)
        assertEquals(0.85f, design.lineStyle.lengthFraction, 0.001f)
        assertEquals(LineVariant.CIRCUIT, design.lineStyle.variant)
        assertTrue(design.lineStyle.accentRingsEnabled)
        assertTrue(design.lineStyle.circuitBridgesEnabled)

        // Verify VeilDsjStyle
        assertEquals(0.45f, design.veilDsjStyle.lineSize, 0.001f)
        assertEquals(0.95f, design.veilDsjStyle.xSize, 0.001f)
        assertEquals(0x123456, design.veilDsjStyle.horizontalLineColor)
        assertEquals(0x654321, design.veilDsjStyle.verticalLineColor)
        assertEquals(0xABCDEF, design.veilDsjStyle.xColor)

        // Verify JitterStyle
        assertEquals(0.35f, design.jitterStyle.scaleJitter, 0.001f)
        assertEquals(0.22f, design.jitterStyle.offsetJitter, 0.001f)
        assertEquals(0.08f, design.jitterStyle.colorJitter, 0.001f)

        // Verify BubbleClusterStyle
        assertFalse(design.clusterStyle.ambientBubbles)
        assertEquals(0.28f, design.clusterStyle.ambientDensity, 0.001f)

        // Verify VeilFunctionStyle
        assertEquals(VeilFunctionType.CIRCLE, design.veilFunctionStyle.functionType)
        assertEquals(VeilFunctionDataStyle.RECTANGLE, design.veilFunctionStyle.dataStyle)

        // Verify FunctionStyle
        assertEquals(FunctionType.NOISE, design.functionStyle.type)

        // Verify CompositePrimitiveStyle
        assertEquals(0.38f, design.compositeStyle.lineThickness, 0.001f)

        // Verify Palette & DirectionalQuietZone
        assertEquals(0xFF0000, design.palette.gradientStart)
        assertEquals(0x0000FF, design.palette.gradientEnd)
        assertEquals(GradientType.LINEAR, design.palette.gradientType)
        assertEquals(insets, design.directionalQuietZone)
    }

    @Test
    fun testRandomRectangleRendererBehavioralScaleJitter() {
        val matrix = QrMatrix("https://veilframe.app/jitter-test", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, quietZoneModules = 4)
        val renderer = RandomRectangleRenderer()

        val designScale01 = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 99999L),
            jitterStyle = RandomJitterStyle(seed = 99999L, scaleJitter = 0.1f, offsetJitter = 0.0f)
        )
        val designScale05 = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 99999L),
            jitterStyle = RandomJitterStyle(seed = 99999L, scaleJitter = 0.5f, offsetJitter = 0.0f)
        )

        val ir01 = renderer.generateGeometry(matrix, designScale01, geometry)
        val ir05 = renderer.generateGeometry(matrix, designScale05, geometry)

        val widths01 = ir01.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1).map { it.width }
        val widths05 = ir05.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1).map { it.width }

        assertNotEquals("Different scaleJitter values must produce different rect widths", widths01, widths05)

        val svg01 = SvgExporter.generateSvg(matrix, designScale01)
        val svg05 = SvgExporter.generateSvg(matrix, designScale05)
        assertNotEquals("SVG output must differ when jitter scale is modified", svg01, svg05)
    }

    @Test
    fun testRandomRectangleRendererBehavioralOffsetJitter() {
        val matrix = QrMatrix("https://veilframe.app/offset-test", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, 512, 512, quietZoneModules = 4)
        val renderer = RandomRectangleRenderer()

        val designOffset0 = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 88888L),
            jitterStyle = RandomJitterStyle(seed = 88888L, scaleJitter = 0.25f, offsetJitter = 0.0f)
        )
        val designOffset03 = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 88888L),
            jitterStyle = RandomJitterStyle(seed = 88888L, scaleJitter = 0.25f, offsetJitter = 0.3f)
        )

        val ir0 = renderer.generateGeometry(matrix, designOffset0, geometry)
        val ir03 = renderer.generateGeometry(matrix, designOffset03, geometry)

        val positions0 = ir0.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1).map { Pair(it.x, it.y) }
        val positions03 = ir03.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1).map { Pair(it.x, it.y) }

        assertNotEquals("Different offsetJitter values must produce different module positions", positions0, positions03)

        val svg0 = SvgExporter.generateSvg(matrix, designOffset0)
        val svg03 = SvgExporter.generateSvg(matrix, designOffset03)
        assertNotEquals("SVG output must differ when jitter offset is modified", svg0, svg03)
    }

    private fun createTestBitmap(): Bitmap {
        val bmp: Bitmap? = try {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            null
        }
        return bmp ?: allocateBitmapReflectively()
    }

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    @Test
    fun testImageStyleSourcePhotoRequirementPredicates() {
        val app = Application()
        val vm = QrStudioViewModel(app)

        // Case A: IMAGE style, no source photo, no background -> banner visible
        val stateNoPhotos = QrStudioViewModel.UiState(
            style = QrStyle.IMAGE,
            sourceImage = null,
            backgroundImage = null
        )
        assertTrue(
            "Source photo required when sourceImage is null",
            vm.isSourcePhotoRequired(stateNoPhotos.style, stateNoPhotos.sourceImage)
        )

        val actualBackgroundBitmap = createTestBitmap()
        assertNotNull(actualBackgroundBitmap)

        // Case B: IMAGE style, no source photo, even when an actual background Bitmap is present -> banner must STILL be visible!
        // (Ensures presence of canvas backdrop Bitmap never masks a missing source photo needed for finder cutout masks)
        val stateBgConfigured = QrStudioViewModel.UiState(
            style = QrStyle.IMAGE,
            sourceImage = null,
            backgroundImage = actualBackgroundBitmap,
            backgroundImageAlpha = 0.80f
        )
        assertTrue(
            "Banner must remain visible when sourceImage is null even if actual background Bitmap is configured",
            vm.isSourcePhotoRequired(stateBgConfigured.style, stateBgConfigured.sourceImage)
        )

        // Case C: IMAGE style with an actual source photo Bitmap provided -> banner hidden
        val actualSourceBitmap = createTestBitmap()
        val stateSourceProvided = QrStudioViewModel.UiState(
            style = QrStyle.IMAGE,
            sourceImage = actualSourceBitmap,
            backgroundImage = actualBackgroundBitmap
        )
        assertFalse(
            "Banner must be hidden when sourceImage Bitmap is provided",
            vm.isSourcePhotoRequired(stateSourceProvided.style, stateSourceProvided.sourceImage)
        )

        // Case D: Non-IMAGE style (e.g. BASIC, LINE, D25) with no source photo -> banner hidden
        assertFalse(
            "Basic style does not require source photo",
            vm.isSourcePhotoRequired(QrStyle.BASIC, null)
        )
        assertFalse(
            "Line style does not require source photo",
            vm.isSourcePhotoRequired(QrStyle.LINE, null)
        )
        assertFalse(
            "D25 style does not require source photo",
            vm.isSourcePhotoRequired(QrStyle.D25, null)
        )
    }

    @Test
    fun testRandomRectangleRendererExactBehavioralBounds() {
        val matrix = QrMatrix("https://veilframe.app/bounds-test", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, (matrix.size + 8) * 16, (matrix.size + 8) * 16, quietZoneModules = 4)
        val moduleSize = geometry.moduleSize
        val renderer = RandomRectangleRenderer()

        // 1. scaleJitter = 0 -> every tempRand = 1.05
        val designScale0 = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 12345L),
            jitterStyle = RandomJitterStyle(seed = 12345L, scaleJitter = 0.0f, offsetJitter = 0.0f)
        )
        val ir0 = renderer.generateGeometry(matrix, designScale0, geometry)
        // Decouple node identification: group by module coordinates (x, y) shared by concentric dual rects
        val darkRects0 = ir0.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1)
        val darkGroups0 = darkRects0.groupBy { Pair(it.x, it.y) }
        assertTrue(darkGroups0.isNotEmpty())
        for ((_, nodes) in darkGroups0) {
            assertEquals("Each dark module must emit exactly inner + outer rectangles", 2, nodes.size)
        }
        val innerNodes0 = darkGroups0.values.map { it.minByOrNull { node -> node.width }!! }
        val outerNodes0 = darkGroups0.values.map { it.maxByOrNull { node -> node.width }!! }

        assertTrue(innerNodes0.isNotEmpty())
        for (node in innerNodes0) {
            val tempRand = (node.width / moduleSize).toFloat()
            assertEquals("scaleJitter = 0 must produce exact 1.05 scale", 1.05f, tempRand, 0.001f)
        }
        for (node in outerNodes0) {
            val outerScale = (node.width / moduleSize).toFloat()
            assertEquals("scaleJitter = 0 must produce exact 1.20 outer scale (1.05 + 0.15)", 1.20f, outerScale, 0.001f)
        }

        // 2. scaleJitter = 0.1 -> tempRand in [0.95, 1.15]
        val designScale01 = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 12345L),
            jitterStyle = RandomJitterStyle(seed = 12345L, scaleJitter = 0.1f, offsetJitter = 0.0f)
        )
        val ir01 = renderer.generateGeometry(matrix, designScale01, geometry)
        val darkRects01 = ir01.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1)
        val darkGroups01 = darkRects01.groupBy { Pair(it.x, it.y) }
        for ((_, nodes) in darkGroups01) {
            assertEquals("Each dark module must emit exactly inner + outer rectangles", 2, nodes.size)
        }
        val innerNodes01 = darkGroups01.values.map { it.minByOrNull { node -> node.width }!! }
        for (node in innerNodes01) {
            val tempRand = (node.width / moduleSize).toFloat()
            assertTrue("tempRand ($tempRand) must be >= 0.949 for scaleJitter 0.1", tempRand >= 0.949f)
            assertTrue("tempRand ($tempRand) must be <= 1.151 for scaleJitter 0.1", tempRand <= 1.151f)
        }

        // 3. scaleJitter = 0.5 -> tempRand in [0.55, 1.55]
        val designScale05 = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 12345L),
            jitterStyle = RandomJitterStyle(seed = 12345L, scaleJitter = 0.5f, offsetJitter = 0.0f)
        )
        val ir05 = renderer.generateGeometry(matrix, designScale05, geometry)
        val darkRects05 = ir05.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1)
        val darkGroups05 = darkRects05.groupBy { Pair(it.x, it.y) }
        for ((_, nodes) in darkGroups05) {
            assertEquals("Each dark module must emit exactly inner + outer rectangles", 2, nodes.size)
        }
        val innerNodes05 = darkGroups05.values.map { it.minByOrNull { node -> node.width }!! }
        for (node in innerNodes05) {
            val tempRand = (node.width / moduleSize).toFloat()
            assertTrue("tempRand ($tempRand) must be >= 0.549 for scaleJitter 0.5", tempRand >= 0.549f)
            assertTrue("tempRand ($tempRand) must be <= 1.551 for scaleJitter 0.5", tempRand <= 1.551f)
        }

        // 4. offsetJitter bounds: abs(dx) <= offsetJitter * moduleSize, abs(dy) <= offsetJitter * moduleSize
        val offsetJitterValue = 0.3f
        val designOffset03 = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 12345L),
            jitterStyle = RandomJitterStyle(seed = 12345L, scaleJitter = 0.0f, offsetJitter = offsetJitterValue)
        )
        val irOffset03 = renderer.generateGeometry(matrix, designOffset03, geometry)
        val nodesOffset0 = ir0.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1)
        val nodesOffset03 = irOffset03.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1)

        val maxAllowedDisplacement = (offsetJitterValue * moduleSize).toFloat() + 0.001f
        var nonZeroDisplacementCount = 0

        for (i in nodesOffset0.indices) {
            val dx = kotlin.math.abs(nodesOffset03[i].x - nodesOffset0[i].x)
            val dy = kotlin.math.abs(nodesOffset03[i].y - nodesOffset0[i].y)

            assertTrue("dx ($dx) must be <= offsetJitter * moduleSize ($maxAllowedDisplacement)", dx <= maxAllowedDisplacement)
            assertTrue("dy ($dy) must be <= offsetJitter * moduleSize ($maxAllowedDisplacement)", dy <= maxAllowedDisplacement)

            if (dx > 0.0001f || dy > 0.0001f) {
                nonZeroDisplacementCount++
            }
        }
        assertTrue("At least some nodes must exhibit non-zero displacement under offset jitter", nonZeroDisplacementCount > 0)
    }

    @Test
    fun testRandomRectangleJitterControlsAreIndependent() {
        val matrix = QrMatrix("https://veilframe.app/independent-jitter", ErrorCorrectionLevel.M)
        val geometry = QrGeometry(matrix.size, (matrix.size + 8) * 16, (matrix.size + 8) * 16, quietZoneModules = 4)
        val renderer = RandomRectangleRenderer()

        val baseDesign = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 42L),
            jitterStyle = RandomJitterStyle(seed = 42L, scaleJitter = 0.0f, offsetJitter = 0.2f, colorJitter = 0.15f)
        )
        val modifiedScaleDesign = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 42L),
            jitterStyle = RandomJitterStyle(seed = 42L, scaleJitter = 0.3f, offsetJitter = 0.2f, colorJitter = 0.15f)
        )
        val modifiedColorDesign = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 42L),
            jitterStyle = RandomJitterStyle(seed = 42L, scaleJitter = 0.0f, offsetJitter = 0.2f, colorJitter = 0.40f)
        )
        val modifiedOffsetDesign = QrDesign(
            style = QrStyle.RANDOM_RECTANGLE,
            effects = EffectStyle(seed = 42L),
            jitterStyle = RandomJitterStyle(seed = 42L, scaleJitter = 0.0f, offsetJitter = 0.45f, colorJitter = 0.15f)
        )

        val irBase = renderer.generateGeometry(matrix, baseDesign, geometry)
        val irScale = renderer.generateGeometry(matrix, modifiedScaleDesign, geometry)
        val irColor = renderer.generateGeometry(matrix, modifiedColorDesign, geometry)
        val irOffset = renderer.generateGeometry(matrix, modifiedOffsetDesign, geometry)

        val groupsBase = irBase.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1).groupBy { Pair(it.x, it.y) }
        val groupsScale = irScale.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1).groupBy { Pair(it.x, it.y) }
        val groupsColor = irColor.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1).groupBy { Pair(it.x, it.y) }
        val groupsOffset = irOffset.rootNodes.filterIsInstance<com.veilframe.app.qr.geometry.RectNode>().drop(1).groupBy { Pair(it.x, it.y) }

        // Invariant 1: Every dark module always has exactly 2 rectangles (inner + outer) across all configurations
        for ((_, nodes) in groupsBase) assertEquals(2, nodes.size)
        for ((_, nodes) in groupsScale) assertEquals(2, nodes.size)
        for ((_, nodes) in groupsColor) assertEquals(2, nodes.size)
        for ((_, nodes) in groupsOffset) assertEquals(2, nodes.size)

        val innerColorsBase = groupsBase.values.map { g -> g.minByOrNull { it.width }!!.fill }
        val innerColorsScale = groupsScale.values.map { g -> g.minByOrNull { it.width }!!.fill }
        val innerColorsOffset = groupsOffset.values.map { g -> g.minByOrNull { it.width }!!.fill }

        // Invariant 2: Changing scale or offset jitter does NOT change module colors
        assertEquals("Changing scale jitter must NOT change module colors", innerColorsBase, innerColorsScale)
        assertEquals("Changing offset jitter must NOT change module colors", innerColorsBase, innerColorsOffset)

        // Invariant 3: Changing scale or color jitter does NOT change module center positions
        val centersBase = groupsBase.values.map { g ->
            val inner = g.minByOrNull { it.width }!!
            Pair((inner.x + inner.width / 2f).roundToInt(), (inner.y + inner.height / 2f).roundToInt())
        }
        val centersScale = groupsScale.values.map { g ->
            val inner = g.minByOrNull { it.width }!!
            Pair((inner.x + inner.width / 2f).roundToInt(), (inner.y + inner.height / 2f).roundToInt())
        }
        val centersColor = groupsColor.values.map { g ->
            val inner = g.minByOrNull { it.width }!!
            Pair((inner.x + inner.width / 2f).roundToInt(), (inner.y + inner.height / 2f).roundToInt())
        }
        assertEquals("Changing scale jitter must NOT change module center positions", centersBase, centersScale)
        assertEquals("Changing color jitter must NOT change module center positions", centersBase, centersColor)

        // Invariant 4: Changing color or offset jitter does NOT change module widths
        val widthsBase = groupsBase.values.map { g -> g.minByOrNull { it.width }!!.width }
        val widthsColor = groupsColor.values.map { g -> g.minByOrNull { it.width }!!.width }
        val widthsOffset = groupsOffset.values.map { g -> g.minByOrNull { it.width }!!.width }
        assertEquals("Changing color jitter must NOT change module widths", widthsBase, widthsColor)
        assertEquals("Changing offset jitter must NOT change module widths", widthsBase, widthsOffset)

        // Verify the modified controls themselves DO exhibit the expected modifications
        val widthsScale = groupsScale.values.map { g -> g.minByOrNull { it.width }!!.width }
        assertNotEquals("Changing scale jitter must change module widths", widthsBase, widthsScale)

        val innerColorsColor = groupsColor.values.map { g -> g.minByOrNull { it.width }!!.fill }
        assertNotEquals("Changing color jitter must change module colors", innerColorsBase, innerColorsColor)

        val centersOffset = groupsOffset.values.map { g ->
            val inner = g.minByOrNull { it.width }!!
            Pair((inner.x + inner.width / 2f).roundToInt(), (inner.y + inner.height / 2f).roundToInt())
        }
        assertNotEquals("Changing offset jitter must change module center positions", centersBase, centersOffset)
    }

    @Test
    fun testConnectedOrganicRendererContinuousMonotonicThickness() {
        val thicknesses = listOf(0.00f, 0.05f, 0.06f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.33333334f, 0.35f, 0.50f, 1.00f)
        val fractions = thicknesses.map { ConnectedOrganicRenderer.calculateStrokeFraction(it) }

        // Verify continuous, non-decreasing mapping with upper saturation at 1.0
        for (i in 0 until fractions.size - 1) {
            assertTrue(
                "Stroke fraction at thickness ${thicknesses[i + 1]} (${fractions[i + 1]}) must be >= thickness ${thicknesses[i]} (${fractions[i]})",
                fractions[i + 1] >= fractions[i]
            )
        }

        // Verify lower bound clamping removes zero discontinuity (0.00 and 0.05 both yield 0.15f)
        assertEquals(0.15f, ConnectedOrganicRenderer.calculateStrokeFraction(0.00f), 0.001f)
        assertEquals(0.15f, ConnectedOrganicRenderer.calculateStrokeFraction(0.05f), 0.001f)

        // Verify intermediate linear scaling points
        assertEquals(0.30f, ConnectedOrganicRenderer.calculateStrokeFraction(0.10f), 0.001f)
        assertEquals(0.45f, ConnectedOrganicRenderer.calculateStrokeFraction(0.15f), 0.001f)
        assertEquals(0.60f, ConnectedOrganicRenderer.calculateStrokeFraction(0.20f), 0.001f)
        assertEquals(0.75f, ConnectedOrganicRenderer.calculateStrokeFraction(0.25f), 0.001f)
        assertEquals(0.90f, ConnectedOrganicRenderer.calculateStrokeFraction(0.30f), 0.001f)

        // Verify explicit upper saturation at 1.0 (capped at one module width)
        assertEquals(1.00f, ConnectedOrganicRenderer.calculateStrokeFraction(0.33333334f), 0.001f)
        assertEquals(1.00f, ConnectedOrganicRenderer.calculateStrokeFraction(0.35f), 0.001f)
        assertEquals(1.00f, ConnectedOrganicRenderer.calculateStrokeFraction(0.50f), 0.001f)
        assertEquals(1.00f, ConnectedOrganicRenderer.calculateStrokeFraction(1.00f), 0.001f)
    }
}
