package com.veilframe.app.pdf.operations

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import java.io.IOException
import kotlin.math.abs

/** Construct closed stroke ink from nested offset envelopes; no background
 * erasure and only one compositing operation, even for translucent compound ink.
 * Asymmetric band placement is defined outer-to-inner. */
internal object PptxClosedOutline {
    internal data class Interval(val low: Float,val high: Float)
    internal fun intervals(spec: PptxStrokeStyle.Spec): List<Interval> {
        val shift=if(spec.alignment=="in") spec.width/2 else 0f
        return PptxConnectorRenderer.bands(spec.compound).map {
            Interval((it.center-it.fraction/2)*spec.width+shift,(it.center+it.fraction/2)*spec.width+shift)
        }
    }
    fun draw(canvas: Canvas,path: Path,spec: PptxStrokeStyle.Spec,color: Int) {
        if(spec.width<=0f) return
        if(spec.compound=="sng" && spec.alignment=="ctr") {
            canvas.drawPath(path,spec.paint(color));return
        }
        val measure=PathMeasure(path,false)
        if(!measure.isClosed || measure.length<=0f || measure.nextContour())
            throw IOException("Inset/compound outline requires a single closed contour")
        if(spec.compound!="sng" && spec.dash.isNotEmpty())
            throw IOException("Dashed compound closed outline unsupported")
        fun combine(a: Path,b: Path,op: Path.Op): Path {
            val result=Path()
            if(!result.op(a,b,op)) throw IOException("Cannot resolve closed outline ink")
            return result
        }
        fun envelope(distance: Float): Path {
            if(distance<=0f) return Path()
            val out=Path()
            val pen=spec.paint(color).apply { strokeWidth=distance*2 }
            if(!pen.getFillPath(path,out)) throw IOException("Cannot expand closed outline")
            return out
        }
        fun side(a: Float,b: Float,inside: Boolean): Path {
            val outer=envelope(b)
            val ring=if(a>0f) combine(outer,envelope(a),Path.Op.DIFFERENCE) else outer
            return combine(ring,path,if(inside) Path.Op.INTERSECT else Path.Op.DIFFERENCE)
        }
        var ink=Path()
        for(band in intervals(spec)) {
            if(band.low<0) ink=combine(ink,side(abs(minOf(0f,band.high)),abs(band.low),false),Path.Op.UNION)
            if(band.high>0) ink=combine(ink,side(maxOf(0f,band.low),band.high,true),Path.Op.UNION)
        }
        canvas.drawPath(ink,Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color;style=Paint.Style.FILL })
    }
}
