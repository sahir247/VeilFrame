package com.veilframe.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.geometry.GroupNode
import com.veilframe.app.qr.geometry.ImageNode
import com.veilframe.app.qr.geometry.IrCanvasRenderer
import com.veilframe.app.qr.geometry.PathNode
import com.veilframe.app.qr.geometry.QrGeometryIr
import com.veilframe.app.qr.geometry.QrGeometryNode
import com.veilframe.app.qr.geometry.QrMaskDefinition
import com.veilframe.app.qr.geometry.RectNode
import com.veilframe.app.qr.geometry.ResampleGeometryBuilder
import com.veilframe.app.qr.geometry.VeilIconPipeline
import com.veilframe.app.qr.model.BackdropStyle
import com.veilframe.app.qr.model.ImageSource
import com.veilframe.app.qr.model.ImageSourceStyle
import com.veilframe.app.qr.model.LogoShape
import com.veilframe.app.qr.model.LogoStyle
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.QrGeometry
import com.veilframe.app.qr.model.QrMatrix
import com.veilframe.app.qr.model.ResampleStyle
import com.veilframe.app.qr.renderer.ArrayPixelSource
import com.veilframe.app.qr.renderer.ImageFillRenderer
import com.veilframe.app.qr.renderer.ImageRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification test suite for Step 2: Backend-neutral masks and IR Canvas parity.
 *
 * Covers:
 * 1. Squircle logo border PathNode has valid androidPath and stroke configuration for Canvas.
 * 2. Circle and squircle logo ImageNodes have clipPath and trigger canvas.clipPath.
 * 3. ImageNode.maskId resolution via QrGeometryIr.masks (clipPath and clipOutRects) on Canvas.
 * 4. GroupNode mask and clip parity on Canvas.
 * 5. ImageFillRenderer stencil mask and GroupNode.clipPath parity.
 * 6. ImageRenderer hole mask with 3 finder cutouts parity.
 * 7. ResampleGeometryBuilder backdrop corner radius clip path parity.
 * 8. SvgExporter applyBackdropToIr rounded-corners clip path parity.
 */
class Step2IrCanvasMaskingParityTest {

    private fun allocateBitmapReflectively(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return method.invoke(unsafe, Bitmap::class.java) as Bitmap
    }

    private fun makeRectF(l: Float, t: Float, r: Float, b: Float): RectF = RectF(l, t, r, b).apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    data class RecordedRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    class TrackingCanvas : Canvas() {
        val clipPathCalls = mutableListOf<Path>()
        val clipOutRectCalls = mutableListOf<RecordedRect>()
        val drawBitmapCalls = mutableListOf<Bitmap>()
        val drawPathCalls = mutableListOf<Path>()
        val drawRectCalls = mutableListOf<RecordedRect>()
        var saveCalls = 0
        var restoreCalls = 0

        override fun clipPath(path: Path): Boolean {
            clipPathCalls.add(path)
            return true
        }

        override fun clipOutRect(rect: RectF): Boolean {
            clipOutRectCalls.add(RecordedRect(rect.left, rect.top, rect.right, rect.bottom))
            return true
        }

        override fun clipOutRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
            clipOutRectCalls.add(RecordedRect(left, top, right, bottom))
            return true
        }

        override fun save(): Int {
            saveCalls++
            return saveCalls
        }

        override fun restore() {
            restoreCalls++
        }

        override fun restoreToCount(saveCount: Int) {
            restoreCalls++
        }

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: RectF, paint: Paint?) {
            drawBitmapCalls.add(bitmap)
        }

        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            drawBitmapCalls.add(bitmap)
        }

        override fun drawPath(path: Path, paint: Paint) {
            drawPathCalls.add(path)
        }

        override fun drawRect(rect: RectF, paint: Paint) {
            drawRectCalls.add(RecordedRect(rect.left, rect.top, rect.right, rect.bottom))
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            drawRectCalls.add(RecordedRect(left, top, right, bottom))
        }
    }

    @Test
    fun testSquircleBorderPathNodeHasValidAndroidPathAndStroke() {
        val bmp = allocateBitmapReflectively()
        val design = QrDesign(
            style = QrStyle.BASIC,
            logo = LogoStyle(
                bitmap = bmp,
                shape = LogoShape.SQUIRCLE,
                borderWidth = 5f,
                borderColor = Color.BLUE
            )
        )
        val nodes = mutableListOf<QrGeometryNode>()
        val defs = mutableListOf<String>()
        val masks = mutableMapOf<String, QrMaskDefinition>()

        VeilIconPipeline.appendIconNodes(
            nodes = nodes,
            defs = defs,
            design = design,
            ox = 20f,
            oy = 20f,
            qrPixelSize = 400f,
            masks = masks
        )

        // Find the border PathNode
        val borderNode = nodes.filterIsInstance<PathNode>().firstOrNull()
        assertNotNull("Squircle logo must produce a border PathNode", borderNode)
        borderNode!!

        assertNotNull("Squircle border PathNode must have a non-null androidPath for Canvas rendering", borderNode.androidPath)
        assertEquals("Squircle border PathNode must have canvasStrokeWidth matching borderWidth", 5f, borderNode.canvasStrokeWidth, 0.001f)

        // Render through TrackingCanvas and verify drawPath is executed
        val ir = QrGeometryIr(width = 512f, height = 512f, rootNodes = listOf(borderNode), masks = masks)
        val canvas = TrackingCanvas()
        IrCanvasRenderer.render(ir, canvas)

        assertTrue("Canvas must execute drawPath for squircle border", canvas.drawPathCalls.contains(borderNode.androidPath))
    }

    @Test
    fun testCircleAndSquircleLogoClipPathOnImageNodeAndCanvasParity() {
        val bmp = allocateBitmapReflectively()

        for (shape in listOf(LogoShape.CIRCLE, LogoShape.SQUIRCLE)) {
            val design = QrDesign(
                style = QrStyle.BASIC,
                logo = LogoStyle(
                    bitmap = bmp,
                    shape = shape,
                    borderWidth = 2f,
                    borderColor = Color.RED
                )
            )
            val nodes = mutableListOf<QrGeometryNode>()
            val defs = mutableListOf<String>()
            val masks = mutableMapOf<String, QrMaskDefinition>()

            VeilIconPipeline.appendIconNodes(
                nodes = nodes,
                defs = defs,
                design = design,
                ox = 30f,
                oy = 30f,
                qrPixelSize = 300f,
                masks = masks
            )

            val imageNode = nodes.filterIsInstance<ImageNode>().firstOrNull()
            assertNotNull("Logo must produce an ImageNode for shape $shape", imageNode)
            imageNode!!

            assertNotNull("ImageNode for shape $shape must have a non-null clipPath", imageNode.clipPath)
            assertNotNull("ImageNode for shape $shape must have a maskId", imageNode.maskId)
            assertTrue("masks map must contain the logo maskId", masks.containsKey(imageNode.maskId))
            assertSame("masks[maskId].clipPath must match imageNode.clipPath", imageNode.clipPath, masks[imageNode.maskId]?.clipPath)

            // Render through TrackingCanvas and verify clipPath is executed before drawBitmap
            val ir = QrGeometryIr(width = 512f, height = 512f, rootNodes = listOf(imageNode), masks = masks)
            val canvas = TrackingCanvas()
            IrCanvasRenderer.render(ir, canvas)

            assertTrue("Canvas must receive clipPath for shape $shape", canvas.clipPathCalls.contains(imageNode.clipPath))
            assertTrue("Canvas must draw the logo bitmap", canvas.drawBitmapCalls.contains(bmp))
            assertTrue("Canvas must use save/restore around masked image", canvas.saveCalls >= 1 && canvas.restoreCalls >= 1)
        }
    }

    @Test
    fun testImageNodeMaskIdResolvedViaQrGeometryIrMasks() {
        val bmp = allocateBitmapReflectively()
        val customPath = Path()
        val customCutout = makeRectF(50f, 50f, 100f, 100f)
        val maskDef = QrMaskDefinition(
            id = "testMask",
            clipPath = customPath,
            clipOutRects = listOf(customCutout)
        )

        val imageNode = ImageNode(
            x = 0f,
            y = 0f,
            width = 200f,
            height = 200f,
            bitmap = bmp,
            base64Data = "",
            maskId = "testMask",
            clipPath = null // deliberately null to test maskId resolution via ir.masks
        )

        val ir = QrGeometryIr(
            width = 200f,
            height = 200f,
            rootNodes = listOf(imageNode),
            masks = mapOf("testMask" to maskDef)
        )

        val canvas = TrackingCanvas()
        IrCanvasRenderer.render(ir, canvas)

        assertTrue("Canvas must resolve and apply clipPath from ir.masks[maskId]", canvas.clipPathCalls.contains(customPath))
        val expectedCutout = RecordedRect(50f, 50f, 100f, 100f)
        assertTrue("Canvas must resolve and apply clipOutRect from ir.masks[maskId]", canvas.clipOutRectCalls.contains(expectedCutout))
        assertTrue("Canvas must draw the bitmap after clipping", canvas.drawBitmapCalls.contains(bmp))
        assertTrue("Canvas must properly save and restore state", canvas.saveCalls >= 1 && canvas.restoreCalls >= 1)
    }

    @Test
    fun testGroupNodeMaskAndClipParityOnCanvas() {
        val groupPath = Path()
        val clipOut1 = makeRectF(10f, 10f, 30f, 30f)
        val clipOut2 = makeRectF(40f, 40f, 60f, 60f)
        val childRect1 = RectNode(x = 0f, y = 0f, width = 100f, height = 100f, fill = Color.BLACK)
        val childRect2 = RectNode(x = 100f, y = 100f, width = 100f, height = 100f, fill = Color.WHITE)

        val groupNode = GroupNode(
            children = listOf(childRect1, childRect2),
            clipPath = groupPath,
            clipOutRects = listOf(clipOut1, clipOut2)
        )

        val ir = QrGeometryIr(width = 200f, height = 200f, rootNodes = listOf(groupNode))
        val canvas = TrackingCanvas()
        IrCanvasRenderer.render(ir, canvas)

        assertTrue("Canvas must apply GroupNode clipPath", canvas.clipPathCalls.contains(groupPath))
        val expected1 = RecordedRect(10f, 10f, 30f, 30f)
        val expected2 = RecordedRect(40f, 40f, 60f, 60f)
        assertTrue("Canvas must apply GroupNode clipOut1", canvas.clipOutRectCalls.contains(expected1))
        assertTrue("Canvas must apply GroupNode clipOut2", canvas.clipOutRectCalls.contains(expected2))
        assertEquals("Both child rects must be drawn", 2, canvas.drawRectCalls.size)
        assertTrue("GroupNode must be wrapped in save/restore", canvas.saveCalls >= 1 && canvas.restoreCalls >= 1)
    }

    @Test
    fun testImageFillRendererProducesHoleStencilAndGroupNodeClip() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/TEST_FILL", ErrorCorrectionLevel.M)
        val design = QrDesign(
            style = QrStyle.IMAGE_FILL,
            quietZoneModules = 2
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512f, 512f, design)

        val ir = ImageFillRenderer().generateGeometry(matrix, design, geometry)

        assertTrue("ImageFillRenderer must register 'hole' in ir.masks", ir.masks.containsKey("hole"))
        val maskDef = ir.masks["hole"]
        assertNotNull("Hole mask definition must exist", maskDef)
        assertNotNull("Hole mask must define a clipPath stencil covering dark modules", maskDef?.clipPath)

        val groupNode = ir.rootNodes.filterIsInstance<GroupNode>().firstOrNull { it.maskId == "hole" }
        assertNotNull("ImageFillRenderer must produce a GroupNode with maskId 'hole'", groupNode)
        groupNode!!

        assertNotNull("GroupNode must have clipPath set to stencil path", groupNode.clipPath)
        assertSame("GroupNode.clipPath must match ir.masks['hole'].clipPath", maskDef?.clipPath, groupNode.clipPath)

        // Render through TrackingCanvas
        val canvas = TrackingCanvas()
        IrCanvasRenderer.render(ir, canvas)

        assertTrue("Canvas must apply the dark module stencil clipPath", canvas.clipPathCalls.contains(groupNode.clipPath))
    }

    @Test
    fun testImageRendererProducesHoleFinderCutouts() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/TEST_IMAGE", ErrorCorrectionLevel.H)
        val bmp = allocateBitmapReflectively()
        val design = QrDesign(
            style = QrStyle.IMAGE,
            quietZoneModules = 4,
            imageSource = ImageSourceStyle(source = ImageSource.Memory(bmp))
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512f, 512f, design)

        val ir = ImageRenderer().generateGeometry(matrix, design, geometry)

        assertTrue("ImageRenderer must register 'hole' mask in ir.masks", ir.masks.containsKey("hole"))
        val holeMask = ir.masks["hole"]
        assertNotNull("Hole mask must exist", holeMask)
        assertEquals("Hole mask must define exactly 3 finder clipOutRects (TL, TR, BL)", 3, holeMask?.clipOutRects?.size)

        // Render through TrackingCanvas
        val canvas = TrackingCanvas()
        IrCanvasRenderer.render(ir, canvas)

        // The 3 finder rects from ir.masks["hole"] must be applied via canvas.clipOutRect
        assertEquals("Canvas must execute clipOutRect exactly 3 times for finders", 3, canvas.clipOutRectCalls.size)
        for (finderRect in holeMask!!.clipOutRects) {
            val rec = RecordedRect(finderRect.left, finderRect.top, finderRect.right, finderRect.bottom)
            assertTrue("Canvas must execute clipOutRect for finder: $rec", canvas.clipOutRectCalls.contains(rec))
        }
    }

    @Test
    fun testResampleGeometryBackdropClipParity() {
        val matrix = QrMatrix("HTTPS://VEILFRAME.APP/RESAMPLE_CLIP", ErrorCorrectionLevel.Q)
        val bmp = allocateBitmapReflectively()
        val design = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            resampleStyle = ResampleStyle(
                backdropCornerRadius = 18f,
                backdropBitmap = bmp
            ),
            backdropStyle = BackdropStyle(
                cornerRadius = 18f,
                image = bmp
            )
        )
        val geometry = QrGeometry.fromDesign(matrix.size, 512f, 512f, design)
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        val pixelSource = ArrayPixelSource(64, 64, pixels)

        val ir = ResampleGeometryBuilder.generateGeometry(matrix, design, geometry, pixelSource)

        assertTrue("ResampleGeometryBuilder must register 'backdropClip' in ir.masks", ir.masks.containsKey("backdropClip"))
        val backdropMask = ir.masks["backdropClip"]
        assertNotNull("backdropClip mask must have a non-null clipPath", backdropMask?.clipPath)

        val backdropImage = ir.rootNodes.filterIsInstance<ImageNode>().firstOrNull { it.maskId == "backdropClip" || it.clipPathId == "backdropClip" }
        assertNotNull("Resample backdrop ImageNode must have maskId/clipPathId 'backdropClip'", backdropImage)
        backdropImage!!
        assertNotNull("Resample backdrop ImageNode must have clipPath set", backdropImage.clipPath)

        // Render through TrackingCanvas
        val canvas = TrackingCanvas()
        IrCanvasRenderer.render(ir, canvas)

        assertTrue("Canvas must execute clipPath for backdrop with corner radius", canvas.clipPathCalls.contains(backdropImage.clipPath))
    }

    @Test
    fun testSvgExporterApplyBackdropToIrCornerClipParity() {
        val rawIr = QrGeometryIr(
            width = 512f,
            height = 512f,
            rootNodes = listOf(RectNode(x = 10f, y = 10f, width = 100f, height = 100f, fill = Color.BLACK))
        )
        val design = QrDesign(
            style = QrStyle.BASIC,
            backdropStyle = BackdropStyle(cornerRadius = 24f)
        )

        val resultIr = SvgExporter.applyBackdropToIr(rawIr, design, 512f, 512f)

        assertTrue("applyBackdropToIr must register 'rounded-corners' in ir.masks", resultIr.masks.containsKey("rounded-corners"))
        val cornerMask = resultIr.masks["rounded-corners"]
        assertNotNull("rounded-corners mask must define a clipPath", cornerMask?.clipPath)

        val rootGroup = resultIr.rootNodes.firstOrNull() as? GroupNode
        assertNotNull("applyBackdropToIr must wrap rootNodes in GroupNode when cornerRadius > 0", rootGroup)
        rootGroup!!
        assertEquals("GroupNode must have clipPathId 'rounded-corners'", "rounded-corners", rootGroup.clipPathId)
        assertNotNull("GroupNode must have clipPath populated", rootGroup.clipPath)

        // Render through TrackingCanvas
        val canvas = TrackingCanvas()
        IrCanvasRenderer.render(resultIr, canvas)

        assertTrue("Canvas must execute clipPath for outer rounded corners", canvas.clipPathCalls.contains(rootGroup.clipPath))
    }
}
