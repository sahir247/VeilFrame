package com.veilframe.app.cv.qr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.qr.GenerationMode
import com.veilframe.app.qr.QrGenerator
import com.veilframe.app.qr.QrRenderResult
import com.veilframe.app.qr.QrStyle
import com.veilframe.app.qr.model.DepthStyle
import com.veilframe.app.qr.model.EyeStyle
import com.veilframe.app.qr.model.FinderStyle
import com.veilframe.app.qr.model.ImageScaleMode
import com.veilframe.app.qr.model.ImageSource
import com.veilframe.app.qr.model.ImageSourceStyle
import com.veilframe.app.qr.model.LineDirection
import com.veilframe.app.qr.model.LineStyle
import com.veilframe.app.qr.model.LineVariant
import com.veilframe.app.qr.model.QrDesign
import com.veilframe.app.qr.model.ResampleDitherMode
import com.veilframe.app.qr.model.ResampleStyle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented round-trip tests: the WeChatQRCode engine that users scan with
 * must decode what the QR pipeline renders, and the reliability gate must be
 * runnable end-to-end on device.
 *
 * These tests intentionally use the SAME engine instance path as production
 * ([WeChatQrEngine.install]) — validator and scanner share it by design.
 */
@RunWith(AndroidJUnit4::class)
class WeChatQrRoundTripTest {

    private val payload = "https://veilframe.local/cv-gate"

    private fun renderQr(payload: String, size: Int = 512): Bitmap {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    @Test
    fun wechatQrCodeDecodesBasicRaster() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WeChatQrEngine.install(context)
        assertTrue("WeChatQRCode MUST be available for runtime verification (initError=${engine.initError})", engine.isAvailable)

        val mat = BitmapBridge.toMat(renderQr(payload))
        try {
            val detections = engine.decode(mat)
            assertTrue("WeChatQRCode decoded nothing", detections.isNotEmpty())
            assertEquals(payload, detections.first().rawValue)
            assertEquals(QrDecodeSource.WECHAT, detections.first().source)
        } finally {
            mat.release()
        }
    }

    @Test
    fun wechatDecodesEncodedPngAndJpegArtifacts() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WeChatQrEngine.install(context)
        assertTrue("WeChatQRCode must be available", engine.isAvailable)
        val bitmap = renderQr(payload)
        try {
            for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
                val bytes = ByteArrayOutputStream().use { out ->
                    assertTrue("Encoding failed: $format", bitmap.compress(format, 95, out))
                    out.toByteArray()
                }
                val decoded = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try {
                    val mat = BitmapBridge.toMat(decoded)
                    try {
                        assertTrue("Encoded $format lost its payload", engine.decode(mat).any { it.rawValue == payload })
                    } finally { mat.release() }
                } finally { decoded.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun reliabilityGateApprovesBasicRaster() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WeChatQrEngine.install(context)
        assertTrue("WeChatQRCode MUST be available for runtime verification (initError=${engine.initError})", engine.isAvailable)

        val mat = BitmapBridge.toMat(renderQr(payload))
        try {
            val report = QrReliabilityGate.run(
                image = mat,
                expectedPayload = payload,
                probe = QrReliabilityGate.wechatThenMlKitProbe { null },
            )
            assertEquals(
                "gate blocked a basic raster: ${report.reasons}",
                QrReliabilityGate.Level.PASS,
                report.level,
            )
            assertTrue(report.releaseApproved)
            assertEquals("${QrStressMatrix.ALL.size}/${QrStressMatrix.ALL.size}", report.wechatCoverage)
        } finally {
            mat.release()
        }
    }

    @Test
    fun reliabilityGateBlocksCorruptedImage() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WeChatQrEngine.install(context)
        assertTrue("WeChatQRCode MUST be available for runtime verification (initError=${engine.initError})", engine.isAvailable)

        // Pure noise: nothing resembling a QR symbol exists.
        val noise = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        val pixels = IntArray(512 * 512) { random.nextInt() or (0xFF shl 24) }
        noise.setPixels(pixels, 0, 512, 0, 0, 512, 512)

        val mat = BitmapBridge.toMat(noise)
        try {
            val report = QrReliabilityGate.run(
                image = mat,
                expectedPayload = payload,
                probe = QrReliabilityGate.wechatThenMlKitProbe { null },
            )
            assertEquals(QrReliabilityGate.Level.FAIL, report.level)
            assertTrue(!report.releaseApproved)
        } finally {
            mat.release()
        }
    }

    @Test
    fun verifyLineCircuitDefaultD25AndFloydDitherWithRealDecoders() = runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val engine = WeChatQrEngine.install(context)
        assertTrue("WeChatQRCode MUST be available", engine.isAvailable)

        // 1. Line & Circuit default (PLANETS + CIRCUIT + thickness 0.75) must scan cleanly
        val lineDesign = QrDesign(
            style = QrStyle.LINE,
            outputSize = 512,
            quietZoneModules = 2,
            eyeStyle = EyeStyle(style = FinderStyle.PLANETS),
            lineStyle = LineStyle(
                direction = LineDirection.X,
                thicknessFraction = 0.75f,
                variant = LineVariant.CIRCUIT,
                accentRingsEnabled = true,
                circuitBridgesEnabled = true,
                positionStyle = FinderStyle.PLANETS
            )
        )
        val lineRes = QrGenerator.generateWithResultSuspend("https://veilframe.app", lineDesign, GenerationMode.ARTISTIC_ENGINE)
        assertTrue("Line & Circuit default generation must succeed", lineRes is QrRenderResult.Success)
        val lineOk = lineRes as QrRenderResult.Success
        val lineDirectWeChat = lineOk.bitmap?.let { bmp ->
            val m = BitmapBridge.toMat(bmp)
            try { engine.decode(m).firstOrNull()?.rawValue } finally { m.release() }
        }
        assertTrue(
            "Line & Circuit default must verify cleanly (decoder=${lineOk.report.decodeResult.decoderId}, directWeChat=$lineDirectWeChat): ${lineOk.report.warnings}",
            lineOk.report.isScanReady
        )
        assertEquals(
            "Line & Circuit default should decode directly via WeChatQRCode or WeChat/MLKit verifier",
            "WeChatQRCode",
            lineOk.report.decodeResult.decoderId
        )

        // 2. Broken high-depth opaque D25 must FAIL verification (zero false positives!)
        val brokenD25 = QrDesign(
            style = QrStyle.D25,
            outputSize = 512,
            depthStyle = DepthStyle(
                depth = 1.6f,
                positionDepth = 1.6f,
                leftColor = 0xCC000000.toInt(),
                rightColor = 0xEE000000.toInt()
            )
        )
        val brokenD25Res = QrGenerator.generateWithResultSuspend(payload, brokenD25, GenerationMode.ARTISTIC_ENGINE)
        assertTrue(brokenD25Res is QrRenderResult.Success)
        val brokenReport = (brokenD25Res as QrRenderResult.Success).report
        assertFalse("Opaque crushed D25 must NOT false-positive as verified", brokenReport.isScanReady)

        // 3. Scannable D25 defaults (depth=0.40, posDepth=0.30, light side faces)
        val scannableD25 = QrDesign(
            style = QrStyle.D25,
            outputSize = 512,
            depthStyle = DepthStyle(
                depth = 0.40f,
                positionDepth = 0.30f,
                leftColor = 0x22000000,
                rightColor = 0x42000000.toInt()
            )
        )
        val d25Res = QrGenerator.generateWithResultSuspend("https://veilframe.app", scannableD25, GenerationMode.ARTISTIC_ENGINE)
        assertTrue(d25Res is QrRenderResult.Success)
        val d25Ok = d25Res as QrRenderResult.Success
        val d25Report = d25Ok.report
        assertTrue("Scannable D25 must genuinely decode on device (decoder=${d25Report.decodeResult.decoderId}): ${d25Report.warnings}", d25Report.isScanReady)

        // Also test raw direct WeChatQRCode (no retry padding) on D25 with depth=0.40 & quietZone=1
        val d25CameraFriendly = scannableD25.copy(quietZoneModules = 1, explicitQuietZone = 1)
        val d25CamRes = QrGenerator.generateWithResultSuspend("https://veilframe.app", d25CameraFriendly, GenerationMode.ARTISTIC_ENGINE)
        assertTrue(d25CamRes is QrRenderResult.Success && (d25CamRes as QrRenderResult.Success).report.isScanReady)

        // 4. Floyd-Steinberg Dither QR on short text ("Hi") with Version 6 grid
        val portrait = android.graphics.Bitmap.createBitmap(128, 128, android.graphics.Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = android.graphics.Paint().apply { isAntiAlias = true }
            paint.color = android.graphics.Color.DKGRAY
            canvas.drawCircle(64f, 54f, 36f, paint)
            paint.color = android.graphics.Color.BLACK
            canvas.drawCircle(52f, 46f, 5f, paint)
            canvas.drawCircle(76f, 46f, 5f, paint)
            canvas.drawArc(android.graphics.RectF(48f, 48f, 80f, 76f), 20f, 140f, false, paint)
        }
        val ditherDesign = QrDesign(
            style = QrStyle.IMAGE_RESAMPLE,
            outputSize = 512,
            quietZoneModules = 1,
            imageSource = ImageSourceStyle(
                source = ImageSource.Memory(portrait),
                scaleMode = ImageScaleMode.ASPECT_FILL
            ),
            resampleStyle = ResampleStyle(
                ditherMode = ResampleDitherMode.FLOYD_STEINBERG,
                minVersion = 6
            )
        )
        val ditherRes = QrGenerator.generateWithResultSuspend("Hi", ditherDesign, GenerationMode.ARTISTIC_ENGINE)
        assertTrue(ditherRes is QrRenderResult.Success)
        val ditherOk = ditherRes as QrRenderResult.Success
        android.util.Log.i("WeChatQrRoundTrip", "Floyd Dither V6 verified=${ditherOk.report.isScanReady}, decoder=${ditherOk.report.decodeResult}, warnings=${ditherOk.report.warnings}")
        assertTrue("Floyd-Steinberg V6 Dither QR on short text 'Hi' must decode: ${ditherOk.report.warnings}", ditherOk.report.isScanReady)
        assertFalse("Floyd-Steinberg Dither QR must not emit false Low luminance separation: ${ditherOk.report.warnings}", ditherOk.report.warnings.any { it.contains("Low luminance separation") })

        // 5. Real device SVG export & strict WeChatQR / ML Kit raster validation for Line & Circuit + Dither + Bubble
        val lineColorDesign = QrDesign(
            style = QrStyle.LINE,
            outputSize = 512,
            quietZoneModules = 1,
            eyeStyle = EyeStyle(
                style = FinderStyle.PLANETS,
                outerColor = android.graphics.Color.RED,
                innerColor = android.graphics.Color.BLUE
            ),
            lineStyle = LineStyle(
                variant = LineVariant.CIRCUIT,
                positionStyle = FinderStyle.PLANETS,
                positionColor = android.graphics.Color.RED
            )
        )
        val lineMatrix = QrGenerator.generateMatrix("https://veilframe.app/svg-line", lineColorDesign, GenerationMode.ARTISTIC_ENGINE)
        val lineSvg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(lineMatrix, lineColorDesign)
        val lineSvgErr = com.veilframe.app.qr.exporter.QrExporter.validateSvgScanability(lineSvg, lineColorDesign, lineMatrix, "https://veilframe.app/svg-line")
        assertNull("Line & Circuit SVG with distinct outer/inner finder colors must pass strict export validation, got: $lineSvgErr", lineSvgErr)

        val bubbleDesign = QrDesign(
            style = QrStyle.BUBBLE,
            outputSize = 512,
            quietZoneModules = 1,
            eyeStyle = EyeStyle(style = FinderStyle.CIRCLE)
        )
        val blackBubbleDesign = QrDesign(
            style = QrStyle.BUBBLE,
            outputSize = 512,
            quietZoneModules = 1,
            eyeStyle = EyeStyle(style = FinderStyle.CIRCLE),
            clusterStyle = com.veilframe.app.qr.model.BubbleClusterStyle(
                dataColor = Color.BLACK,
                dataCenterColor = Color.WHITE,
                positionColor = Color.BLACK
            )
        )
        val bubbleMatrix = QrGenerator.generateMatrix("https://veilframe.app/svg-bubble", bubbleDesign, GenerationMode.ARTISTIC_ENGINE)
        val blackSvg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(bubbleMatrix, blackBubbleDesign)
        val blackSvgErr = com.veilframe.app.qr.exporter.QrExporter.validateSvgScanability(blackSvg, blackBubbleDesign, bubbleMatrix, "https://veilframe.app/svg-bubble")
        assertNull("Black Bubble Cluster SVG must pass strict export validation, got: $blackSvgErr", blackSvgErr)

        val bubbleSvg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(bubbleMatrix, bubbleDesign)
        val bubbleSvgErr = com.veilframe.app.qr.exporter.QrExporter.validateSvgScanability(bubbleSvg, bubbleDesign, bubbleMatrix, "https://veilframe.app/svg-bubble")
        assertNull("Default Bubble Cluster SVG must pass strict export validation, got: $bubbleSvgErr", bubbleSvgErr)

        val ditherMatrix = QrGenerator.generateMatrix("Hi", ditherDesign, GenerationMode.ARTISTIC_ENGINE)
        val ditherSvg = com.veilframe.app.qr.exporter.SvgExporter.generateSvg(ditherMatrix, ditherDesign)
        val ditherSvgErr = com.veilframe.app.qr.exporter.QrExporter.validateSvgScanability(ditherSvg, ditherDesign, ditherMatrix, "Hi")
        assertNull("Floyd-Steinberg Dither SVG must pass strict export validation, got: $ditherSvgErr", ditherSvgErr)

        portrait.recycle()
    }
}
