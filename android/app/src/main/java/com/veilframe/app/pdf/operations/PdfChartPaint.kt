package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState

/** Per-content-stream paint state cache. Scoped graphics state prevents
 * translucent marks from leaking opacity, dash or color to unrelated content. */
internal class PdfChartPaint(private val stream: PDPageContentStream) {
    private val states=mutableMapOf<Pair<Int,Int>,PDExtendedGraphicsState>()
    private fun alpha(fill: Int,stroke: Int) {
        if(fill==255 && stroke==255) return
        val state=states.getOrPut(fill to stroke) {
            PDExtendedGraphicsState().apply {
                setNonStrokingAlphaConstant(fill/255f)
                setStrokingAlphaConstant(stroke/255f)
            }
        }
        stream.setGraphicsStateParameters(state)
    }
    private fun fillColor(ink: XlsxCachedChart.Ink) {
        val rgb=requireNotNull(ink.rgb)
        stream.setNonStrokingColor(rgb ushr 16 and 255,rgb ushr 8 and 255,rgb and 255)
    }
    private fun strokeColor(ink: XlsxCachedChart.Ink,spec: XlsxCachedChart.Stroke?,width: Float) {
        val rgb=requireNotNull(ink.rgb)
        stream.setStrokingColor(rgb ushr 16 and 255,rgb ushr 8 and 255,rgb and 255)
        stream.setLineWidth(width)
        stream.setLineCapStyle(when(spec?.cap) { "rnd" -> 1; "sq" -> 2; else -> 0 })
        stream.setLineJoinStyle(when(spec?.join) { "round" -> 1; "bevel" -> 2; else -> 0 })
        stream.setMiterLimit(spec?.miterLimit ?: 4f)
        stream.setLineDashPattern(SharedChartInk.dashes(spec,width),0f)
    }
    fun closed(fill: XlsxCachedChart.Ink?,outline: XlsxCachedChart.Stroke?,fallback: Int,path: ()->Unit) {
        val border=SharedChartInk.outlineInk(outline,fallback)
        if(fill==null && border==null) return
        stream.saveGraphicsState()
        try {
            alpha(fill?.alpha ?: 255,border?.alpha ?: 255)
            fill?.let(::fillColor)
            border?.let { strokeColor(it,outline,outline?.widthPt ?: 1f) }
            path()
            when {
                fill!=null && border!=null -> stream.fillAndStroke()
                fill!=null -> stream.fill()
                else -> stream.stroke()
            }
        } finally { stream.restoreGraphicsState() }
    }
    fun rect(x: Float,y: Float,w: Float,h: Float,fill: XlsxCachedChart.Ink?,
        outline: XlsxCachedChart.Stroke?,fallback: Int) {
        if(w<=0f || h<=0f) return
        closed(fill,outline,fallback) { stream.addRect(x,y,w,h) }
    }
    /** Paint all contiguous subpaths in one operation; alpha is not applied
     * again at every vertex and dash phase is continuous within each subpath. */
    fun polyline(points: List<Pair<Float,Float>?>,series: XlsxCachedChart.Series,fallback: Int,defaultWidth: Float) {
        val ink=SharedChartInk.resolveInk(series.stroke?.ink,fallback) ?: return
        if(points.zipWithNext().none { (a,b) -> a!=null && b!=null }) return
        stream.saveGraphicsState()
        try {
            alpha(255,ink.alpha)
            strokeColor(ink,series.stroke,series.stroke?.widthPt ?: series.strokeWidthPt ?: defaultWidth)
            var previous: Pair<Float,Float>?=null
            for(point in points) {
                if(point!=null) {
                    if(previous==null) stream.moveTo(point.first,point.second)
                    else stream.lineTo(point.first,point.second)
                }
                previous=point
            }
            stream.stroke()
        } finally { stream.restoreGraphicsState() }
    }
}
