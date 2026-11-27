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

    fun draw(stream: PDPageContentStream,shares: List<Double>,cx: Float,cy: Float,radius: Float,checkpoint: ()->Unit) =
        draw(stream,shares,cx,cy,radius,checkpoint,shares.indices.map { palette[it] },0)

    fun draw(stream: PDPageContentStream,shares: List<Double>,cx: Float,cy: Float,radius: Float,checkpoint: ()->Unit,
        colors: List<Int?>,firstSliceAngle: Int) {
        drawStyled(stream,shares,cx,cy,radius,checkpoint,colors.map { it?.let { rgb -> XlsxCachedChart.Ink(rgb) } },
            List(shares.size) { null },firstSliceAngle)
    }

    fun drawStyled(stream: PDPageContentStream,shares: List<Double>,cx: Float,cy: Float,radius: Float,checkpoint: ()->Unit,
        colors: List<XlsxCachedChart.Ink?>,outlines: List<XlsxCachedChart.Stroke?>,firstSliceAngle: Int) {
        require(radius.isFinite() && radius>0f && colors.size==shares.size && outlines.size==shares.size && firstSliceAngle in 0..360)
        val painter=PdfChartPaint(stream)
        var angle = Math.PI/2-Math.toRadians(firstSliceAngle.toDouble())
        for ((index,share) in shares.withIndex()) {
            checkpoint()
            if (share<=0.0) continue
            val sweep = -2*Math.PI*share
            val start=angle
            angle+=sweep
            val fill=colors[index]
            val outline=outlines[index]
            painter.closed(fill,outline,palette[index]) {
            var cursor=start
            val segments = ceil(abs(sweep)/(Math.PI/2)).toInt().coerceAtLeast(1)
            val delta=sweep/segments
            val x=cx+radius*cos(cursor).toFloat(); val y=cy+radius*sin(cursor).toFloat()
            if(share<1.0-1e-12) { stream.moveTo(cx,cy); stream.lineTo(x,y) }
            else stream.moveTo(x,y)
            repeat(segments) {
                val end=cursor+delta
                val k=(4.0/3.0*tan(delta/4.0)).toFloat()
                val x0=cos(cursor).toFloat(); val y0=sin(cursor).toFloat()
                val x1=cos(end).toFloat(); val y1=sin(end).toFloat()
                stream.curveTo(cx+radius*(x0-k*y0),cy+radius*(y0+k*x0),
                    cx+radius*(x1+k*y1),cy+radius*(y1-k*x1),cx+radius*x1,cy+radius*y1)
                cursor=end
            }
            stream.closePath()
            }
        }
    }
}
