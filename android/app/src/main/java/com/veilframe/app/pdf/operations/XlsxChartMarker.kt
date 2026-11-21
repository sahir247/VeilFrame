package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream

/** Vector-only DrawingML marker subset; caller owns the paint color. */
internal object XlsxChartMarker {
    fun draw(stream: PDPageContentStream, symbol: String?, sizePt: Float?,
        x: Float, y: Float, defaultSize: Float) {
        if (symbol == "none") return
        val size = sizePt ?: defaultSize
        require(size.isFinite() && size in 2f..72f) { "Invalid saved chart marker size" }
        val half = size / 2f
        when (symbol ?: "square") {
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
        stream.fill()
    }
}
