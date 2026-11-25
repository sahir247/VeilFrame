package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Shared cached pie geometry for appendix and in-place chart renderers. */
internal object XlsxPieChartPainter {
    val palette = intArrayOf(0x2463A0,0xD15A36,0x36845B,0x8354AA,0xA47715,0x247D82,
        0xAA4167,0x6D7C35,0x5264B4,0x9B653C,0x557D83,0x777777)

    fun shares(values: List<Double?>): List<Double> {
        require(values.size in 1..12 && values.all { it == null || (it.isFinite() && it>=0.0) })
        val max = values.filterNotNull().maxOrNull() ?: 0.0
        require(max>0.0) { "Pie has no positive cached value" }
        val total = values.sumOf { (it ?: 0.0)/max }
        return values.map { (it ?: 0.0)/max/total }
    }

    fun draw(stream: PDPageContentStream,shares: List<Double>,cx: Float,cy: Float,radius: Float,checkpoint: ()->Unit) {
        require(radius.isFinite() && radius>0f)
        var angle = Math.PI/2
        for ((index,share) in shares.withIndex()) {
            checkpoint()
            if (share<=0.0) continue
            val rgb=palette[index]
            stream.setNonStrokingColor(rgb ushr 16 and 255,rgb ushr 8 and 255,rgb and 255)
            val sweep = -2*Math.PI*share
            val segments = ceil(abs(sweep)/(Math.PI/2)).toInt().coerceAtLeast(1)
            val delta=sweep/segments
            stream.moveTo(cx,cy)
            stream.lineTo(cx+radius*cos(angle).toFloat(),cy+radius*sin(angle).toFloat())
            repeat(segments) {
                val end=angle+delta
                val k=(4.0/3.0*tan(delta/4.0)).toFloat()
                val x0=cos(angle).toFloat(); val y0=sin(angle).toFloat()
                val x1=cos(end).toFloat(); val y1=sin(end).toFloat()
                stream.curveTo(cx+radius*(x0-k*y0),cy+radius*(y0+k*x0),
                    cx+radius*(x1+k*y1),cy+radius*(y1-k*x1),cx+radius*x1,cy+radius*y1)
                angle=end
            }
            stream.closePath();stream.fill()
        }
    }
}
