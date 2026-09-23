package com.veilframe.app.qr

import android.graphics.Color
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.veilframe.app.qr.exporter.SvgExporter
import com.veilframe.app.qr.model.*
import com.veilframe.app.qr.model.ModuleShape
import org.junit.Assert.*
import org.junit.Test

class SvgExporterTest {

    @Test
    fun testSvgExporterGeneratesValidSvgWithGradients() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)
        val design = QrDesign(
            palette = PaletteStyle(
                foreground = Color.BLACK,
                background = Color.WHITE,
                gradientStart = 0xFF0000FF.toInt(), // Blue
                gradientEnd = 0xFFFF0000.toInt(),   // Red
                gradientType = GradientType.LINEAR
            ),
            moduleStyle = ModuleStyle(shape = ModuleShape.PILL)
        )

        val svg = SvgExporter.generateSvg(matrix, design)

        assertTrue("SVG must start with XML declaration", svg.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue("SVG must contain <svg> root element", svg.contains("<svg xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue("SVG must define linearGradient", svg.contains("<linearGradient id=\"qrGrad\""))
        assertTrue("SVG must use gradient fill for data modules", svg.contains("fill=\"url(#qrGrad)\""))
        assertTrue("SVG must close with </svg>", svg.endsWith("</svg>"))
    }

    @Test
    fun testSvgExporterPerZoneColoring() {
        val matrix = QrMatrix("https://veilframe.app", ErrorCorrectionLevel.M)
        val timingColor = 0xFF10B981.toInt() // Green
        val alignmentColor = 0xFFF59E0B.toInt() // Amber
        val timingHex = String.format("#%06X", 0xFFFFFF and timingColor)
        val alignmentHex = String.format("#%06X", 0xFFFFFF and alignmentColor)

        val design = QrDesign(
            timingColor = timingColor,
            alignmentColor = alignmentColor
        )

        val svg = SvgExporter.generateSvg(matrix, design)

        assertTrue("SVG must contain timing module color override", svg.contains("fill=\"$timingHex\""))
    }
}
