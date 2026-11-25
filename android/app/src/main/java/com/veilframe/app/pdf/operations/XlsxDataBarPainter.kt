package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.shading.PDShadingType2

/** One clipped vector axial shading per gradient bar, no bitmap or strip approximation. */
internal object XlsxDataBarPainter {
    private fun floats(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
    private fun name(s: String) = COSName.getPDFName(s)
    fun shading(rgb: Int,start: Float,end: Float,y: Float): PDShadingType2 {
        val c = FloatArray(3) { index -> ((rgb ushr (16-index*8)) and 255)/255f }
        // Excel's exact gradient stops are not stored in x14. Render a disclosed
        // base-color to white gradient, oriented from the baseline to the tip.
        val fn = COSDictionary().apply {
            setInt(name("FunctionType"),2)
            setItem(name("Domain"),floats(0f,1f))
            setItem(name("C0"),floats(*c))
            setItem(name("C1"),floats(1f,1f,1f))
            setInt(name("N"),1)
        }
        return PDShadingType2(COSDictionary().apply {
            setInt(name("ShadingType"),2)
            setItem(name("ColorSpace"),name("DeviceRGB"))
            setItem(name("Coords"),floats(start,y,end,y))
            setItem(name("Function"),fn)
        })
    }
    fun draw(stream: PDPageContentStream,bar: XlsxDataBarVisual,x1: Float,x2: Float,y: Float,height: Float) {
        if (bar.gradient) {
            stream.saveGraphicsState()
            try {
                stream.addRect(x1,y,x2-x1,height); stream.clip()
                val forward = bar.end >= bar.start
                stream.shadingFill(shading(bar.rgb,if(forward)x1 else x2,if(forward)x2 else x1,y))
            } finally { stream.restoreGraphicsState() }
        } else {
            stream.setNonStrokingColor(bar.rgb ushr 16 and 255,bar.rgb ushr 8 and 255,bar.rgb and 255)
            stream.addRect(x1,y,x2-x1,height); stream.fill()
        }
        bar.borderRgb?.let { border ->
            stream.setStrokingColor(border ushr 16 and 255,border ushr 8 and 255,border and 255)
            stream.setLineWidth(0.5f)
            stream.addRect(x1,y,x2-x1,height); stream.stroke()
        }
    }
}
