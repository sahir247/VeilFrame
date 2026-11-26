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
        fallbackRgb: Int?=null) {
        if (symbol == "none") return
        if(fallbackRgb!=null && SharedChartInk.resolved(fill,fallbackRgb)==null &&
            (outline==null || SharedChartInk.resolved(outline.ink,fallbackRgb)==null)) return
        stream.saveGraphicsState()
        try {
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
        val fillRgb=fallbackRgb?.let { SharedChartInk.resolved(fill,it) }
        val outlineRgb=if(outline==null) null else fallbackRgb?.let { SharedChartInk.resolved(outline.ink,it) }
        if(fillRgb!=null) stream.setNonStrokingColor(fillRgb ushr 16 and 255,fillRgb ushr 8 and 255,fillRgb and 255)
        if(outlineRgb!=null) {
            stream.setStrokingColor(outlineRgb ushr 16 and 255,outlineRgb ushr 8 and 255,outlineRgb and 255)
            val width=outline?.widthPt ?: 1f
            stream.setLineWidth(width)
            stream.setLineCapStyle(when(outline?.cap) { "rnd" -> 1; "sq" -> 2; else -> 0 })
            stream.setLineDashPattern(SharedChartInk.dashes(outline,width),0f)
            if(fillRgb!=null) stream.fillAndStroke() else stream.stroke()
        } else stream.fill()
        } finally { stream.restoreGraphicsState() }
    }
}
