package com.veilframe.app.qr

import android.graphics.Color
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.model.ModuleShape
import com.veilframe.app.qr.renderer.BubbleClusterEngine
import com.veilframe.app.qr.renderer.ClusterPrimitive
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Empirical scanability and architectural tests for:
 * 1. [BubbleClusterEngine] topological multi-module clustering and deterministic ambient bubbles.
 * 2. [ImageSourceStyle] lightweight serializable domain state decoupled from [BackgroundLayer].
 * 3. [SvgExporter] `<mask id="qrDataMask">` generation respecting [ImageMaskScope].
 * 4. Empirical ZXing decode test across payloads (URL, UPI, vCard) and EC levels with ambient bubbles.
 */
class BubbleClusterAndImageMaskedTest {

    @Test
    fun testTopologicalClusteringDecomposition() {
        val matrix = QrMatrix("https://veilframe.app/privacy-cleaner", ErrorCorrectionLevel.M)
        val design = QrDesign(
            moduleStyle = ModuleStyle(shape = ModuleShape.BUBBLE_CLUSTER),
            clusterStyle = BubbleClusterStyle(seed = 42L, ambientBubbles = true, ambientDensity = 0.20f)
        )

        val clusters = BubbleClusterEngine.computeClusters(matrix, design)
        assertTrue("Clusters list must not be empty", clusters.isNotEmpty())

        val solidSingletons = clusters.filter { it.isSolid }
        val strokedMacroOrPairs = clusters.filter { !it.isSolid && !it.isAmbient }
        val ambientBubbles = clusters.filter { it.isAmbient }

        assertTrue("Must contain solid singletons", solidSingletons.isNotEmpty())
        assertTrue("Must contain multi-module macro clusters or pairs", strokedMacroOrPairs.isNotEmpty())
        assertTrue("Must contain ambient micro-bubbles", ambientBubbles.isNotEmpty())

        // Verify protected functional zones are never touched by any cluster
        val n = matrix.size
        for (cluster in clusters) {
            val col = cluster.cx.toInt()
            val row = cluster.cy.toInt()
            if (col in 0 until n && row in 0 until n) {
                // Must never be in Finder 7x7 zones or timing track (col 6 / row 6)
                assertFalse("Cluster at ($col, $row) must not be in protected finder pattern", matrix.functionMask.isFinder(col, row))
                assertFalse("Cluster at ($col, $row) must not be in protected separator", matrix.functionMask.isSeparator(col, row))
            }
        }
    }

    @Test
    fun testDeterministicAmbientBubbleGeneration() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP", ErrorCorrectionLevel.H)
        val designA = QrDesign(
            moduleStyle = ModuleStyle(shape = ModuleShape.BUBBLE_CLUSTER),
            clusterStyle = BubbleClusterStyle(seed = 12345L, ambientBubbles = true)
        )
        val designB = QrDesign(
            moduleStyle = ModuleStyle(shape = ModuleShape.BUBBLE_CLUSTER),
            clusterStyle = BubbleClusterStyle(seed = 12345L, ambientBubbles = true)
        )
        val designDiff = QrDesign(
            moduleStyle = ModuleStyle(shape = ModuleShape.BUBBLE_CLUSTER),
            clusterStyle = BubbleClusterStyle(seed = 99999L, ambientBubbles = true)
        )

        val clustersA = BubbleClusterEngine.computeClusters(matrix, designA)
        val clustersB = BubbleClusterEngine.computeClusters(matrix, designB)
        val clustersDiff = BubbleClusterEngine.computeClusters(matrix, designDiff)

        assertEquals("Same seed must produce identical cluster count", clustersA.size, clustersB.size)
        for (i in clustersA.indices) {
            val a = clustersA[i]
            val b = clustersB[i]
            assertEquals("Cluster $i cx must match", a.cx, b.cx, 0.0001f)
            assertEquals("Cluster $i cy must match", a.cy, b.cy, 0.0001f)
            assertEquals("Cluster $i radius must match", a.radius, b.radius, 0.0001f)
            assertEquals("Cluster $i isAmbient must match", a.isAmbient, b.isAmbient)
            assertEquals("Cluster $i isSolid must match", a.isSolid, b.isSolid)
        }

        // Different seed should produce variation in primitives
        assertNotEquals(
            "Different seed must produce distinct cluster configuration",
            clustersA.map { it.cx to it.cy },
            clustersDiff.map { it.cx to it.cy }
        )
    }

    @Test
    fun testEmpiricalScanabilityWithRealZxingDecode() {
        val testPayloads = listOf(
            "https://veilframe.app/scan-me",
            "upi://pay?pa=partha.pay@okhdfcbank&am=100.00&cu=INR",
            "BEGIN:VCARD\nVERSION:3.0\nN:VeilFrame\nTEL:9876543210\nEND:VCARD"
        )

        val ecLevels = listOf(
            ErrorCorrectionLevel.M,
            ErrorCorrectionLevel.Q,
            ErrorCorrectionLevel.H
        )

        val reader = MultiFormatReader()

        for (payload in testPayloads) {
            for (ec in ecLevels) {
                val matrix = QrMatrix(payload, ec)
                val design = QrDesign(
                    moduleStyle = ModuleStyle(shape = ModuleShape.BUBBLE_CLUSTER),
                    clusterStyle = BubbleClusterStyle(
                        seed = 42L,
                        ambientBubbles = true,
                        ambientDensity = 0.15f,
                        ambientMaxRadius = 0.20f
                    )
                )

                // Software rasterize QR code including finders, timing, data clusters, and ambient bubbles
                val scale = 8 // 8 pixels per module
                val qz = 4
                val n = matrix.size
                val totalModules = n + (2 * qz)
                val totalPx = totalModules * scale
                val pixels = IntArray(totalPx * totalPx) { 0xFFFFFFFF.toInt() } // White background

                // 1. Draw All Functional Modules (Finders, Separators, Format Info, Timing, Alignment)
                for (c in 0 until n) {
                    for (r in 0 until n) {
                        if (matrix.roleAt(c, r) != QrModuleRole.DATA) {
                            if (matrix.isDark(c, r)) {
                                fillModule(pixels, totalPx, c + qz, r + qz, scale, 0xFF000000.toInt())
                            }
                        }
                    }
                }

                // 2. Draw Bubble Clusters & Ambient Bubbles from BubbleClusterEngine
                val clusters = BubbleClusterEngine.computeClusters(matrix, design)
                for (cluster in clusters) {
                    val pixelCx = (cluster.cx + qz) * scale
                    val pixelCy = (cluster.cy + qz) * scale
                    val pixelR = cluster.radius * scale

                    if (cluster.isSolid) {
                        drawFilledCircle(pixels, totalPx, pixelCx, pixelCy, pixelR, 0xFF000000.toInt())
                    } else if (cluster.isAmbient) {
                        // Ambient ring
                        val strokeW = cluster.strokeWidthRatio * scale
                        drawStrokedCircle(pixels, totalPx, pixelCx, pixelCy, pixelR, strokeW, 0xFF000000.toInt())
                    } else {
                        // Macro cluster ring
                        val strokeW = cluster.strokeWidthRatio * scale
                        drawStrokedCircle(pixels, totalPx, pixelCx, pixelCy, pixelR, strokeW, 0xFF000000.toInt())
                        if (cluster.hasInnerDot && cluster.innerRadius > 0f) {
                            val innerR = cluster.innerRadius * scale
                            drawFilledCircle(pixels, totalPx, pixelCx, pixelCy, innerR, 0xFF000000.toInt())
                        }
                    }
                }

                // Decode with ZXing
                val luminanceSource = RGBLuminanceSource(totalPx, totalPx, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(luminanceSource))
                val result = reader.decode(binaryBitmap)

                assertNotNull("Scanability verification failed for payload: $payload, ec: $ec", result)
                assertEquals("Decoded text must match payload", payload, result.text)
            }
        }
    }

    @Test
    fun testImageMaskedSvgOutputStructure() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)
        val design = QrDesign(
            moduleStyle = ModuleStyle(fill = ModuleFill.IMAGE_MASKED),
            imageSource = ImageSourceStyle(
                source = ImageSource.Uri("content://media/external/images/123"),
                scope = ImageMaskScope.DATA_ONLY,
                opacity = 0.9f
            )
        )

        val totalSize = matrix.size + 2 * design.quietZoneModules
        val svg = SvgExporter.generateSvg(matrix, design)
        assertTrue("SVG must contain mask definition", svg.contains("<mask id=\"qrDataMask\">"))
        assertTrue("SVG must have black mask base", svg.contains("<rect width=\"$totalSize\" height=\"$totalSize\" fill=\"black\" />"))
        assertTrue("SVG must have white module stencil elements in mask", svg.contains("fill=\"white\""))
    }

    @Test
    fun testImageSourceDecoupledFromBackdropLayer() {
        // Verify ImageSourceStyle handles Uri without Bitmap pointer
        val imageSource = ImageSourceStyle(
            source = ImageSource.Uri("content://media/external/images/photo_456.jpg"),
            scaleMode = ImageScaleMode.CENTER_CROP,
            scope = ImageMaskScope.DATA_ONLY,
            opacity = 0.85f,
            maskColor = Color.DKGRAY,
            maskAlpha = 0.2f
        )

        val backdrop = BackgroundLayer(
            enabled = true,
            color = Color.WHITE,
            opacity = 0.5f
        )

        val design = QrDesign(
            imageSource = imageSource,
            backgroundLayer = backdrop
        )

        assertNull("ImageSource.Uri must not force a bitmap pointer in state", design.imageSource.bitmap)
        assertEquals(ImageScaleMode.CENTER_CROP, design.imageSource.scaleMode)
        assertEquals(ImageMaskScope.DATA_ONLY, design.imageSource.scope)
        assertEquals(0.85f, design.imageSource.opacity, 0.001f)
        assertEquals(0.5f, design.backgroundLayer.opacity, 0.001f)
    }

    // Helper functions for software rasterization in empirical decode tests
    private fun fillModule(pixels: IntArray, width: Int, col: Int, row: Int, scale: Int, color: Int) {
        val startX = col * scale
        val startY = row * scale
        for (y in startY until (startY + scale)) {
            for (x in startX until (startX + scale)) {
                if (x in 0 until width && y in 0 until width) {
                    pixels[y * width + x] = color
                }
            }
        }
    }

    private fun drawFilledCircle(pixels: IntArray, width: Int, cx: Float, cy: Float, r: Float, color: Int) {
        val minX = (cx - r).toInt().coerceAtLeast(0)
        val maxX = (cx + r).toInt().coerceAtMost(width - 1)
        val minY = (cy - r).toInt().coerceAtLeast(0)
        val maxY = (cy + r).toInt().coerceAtMost(width - 1)
        val rSq = r * r

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= rSq) {
                    pixels[y * width + x] = color
                }
            }
        }
    }

    private fun drawStrokedCircle(pixels: IntArray, width: Int, cx: Float, cy: Float, r: Float, strokeW: Float, color: Int) {
        val outerR = r + (strokeW / 2f)
        val innerR = (r - (strokeW / 2f)).coerceAtLeast(0f)
        val outerRSq = outerR * outerR
        val innerRSq = innerR * innerR

        val minX = (cx - outerR).toInt().coerceAtLeast(0)
        val maxX = (cx + outerR).toInt().coerceAtMost(width - 1)
        val minY = (cy - outerR).toInt().coerceAtLeast(0)
        val maxY = (cy + outerR).toInt().coerceAtMost(width - 1)

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x - cx
                val dy = y - cy
                val distSq = dx * dx + dy * dy
                if (distSq in innerRSq..outerRSq) {
                    pixels[y * width + x] = color
                }
            }
        }
    }
}
