package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream

/** Vector-only DrawingML marker subset; caller owns the paint color.
 * Image markers, custom strokes/fills and theme-default resolution are separate.
 */
internal object XlsxChartMarker {
    val supportedSymbols = setOf("none", "circle", "square", "diamond", "triangle", "dash", "dot", "plus", "x", "star")

    fun draw(stream: PDPageContentStream, symbol: String?, sizePt: Float?,
        x: Float, y: Float, defaultSize: Float,
        fill: XlsxCachedChart.Ink?=null,outline: XlsxCachedChart.Stroke?=null,
        fallbackRgb: Int?=null,painter: PdfChartPaint=PdfChartPaint(stream)) {
        if (symbol == "none") return
        fun path() {
        val size = sizePt ?: defaultSize
        require(size.isFinite() && size in 2f..72f) { "Invalid saved chart marker size" }
        val half = size / 2f
        val thickness = size / 5f
        fun plus() {
            stream.addRect(x - half, y - thickness / 2f, size, thickness)
            stream.addRect(x - thickness / 2f, y - half, thickness, size)
        }
        // Fill same-winding diagonal quadrilaterals within the saved marker
        // rectangle instead of mutating the series stroke width.
        fun cross() {
            stream.moveTo(x-half, y-half+thickness)
            stream.lineTo(x-half+thickness, y-half)
            stream.lineTo(x+half, y+half-thickness)
            stream.lineTo(x+half-thickness, y+half)
            stream.closePath()
            stream.moveTo(x-half, y+half-thickness)
            stream.lineTo(x+half-thickness, y-half)
            stream.lineTo(x+half, y-half+thickness)
            stream.lineTo(x-half+thickness, y+half)
            stream.closePath()
        }
        when (symbol ?: "square") {
            "dash" -> stream.addRect(x-half, y-thickness/2f, size, thickness)
            "dot" -> stream.addRect(x-size/4f, y-thickness/2f, size/2f, thickness)
            "plus" -> plus()
            "x" -> cross()
            // Excel's star marker is an asterisk rather than a five-point star.
            "star" -> { plus(); cross() }
            "square" -> stream.addRect(x-half,y-half,size,size)
            "diamond" -> {
                stream.moveTo(x,y+half)
                stream.lineTo(x+half,y)
                stream.lineTo(x,y-half)
                stream.lineTo(x-half,y)
                stream.closePath()
            }
            "triangle" -> {
                stream.moveTo(x,y+half)
                stream.lineTo(x+half,y-half)
                stream.lineTo(x-half,y-half)
                stream.closePath()
            }
            "circle" -> {
                val c=0.55228475f*half
                stream.moveTo(x+half,y)
                stream.curveTo(x+half,y+c,x+c,y+half,x,y+half)
                stream.curveTo(x-c,y+half,x-half,y+c,x-half,y)
                stream.curveTo(x-half,y-c,x-c,y-half,x,y-half)
                stream.curveTo(x+c,y-half,x+half,y-c,x+half,y)
                stream.closePath()
            }
            else -> throw IllegalArgumentException("Unsupported chart marker symbol")
        }
        }
        if(fallbackRgb==null) { path(); stream.fill() }
        else painter.closed(SharedChartInk.resolveInk(fill,fallbackRgb),outline,fallbackRgb,::path)
    }
}
