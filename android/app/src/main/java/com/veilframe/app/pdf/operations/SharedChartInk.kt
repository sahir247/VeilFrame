package com.veilframe.app.pdf.operations

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import kotlin.math.roundToInt

/** Supported saved solid chart ink, with independent fill and stroke opacity. */
internal object SharedChartInk {
    private fun children(n: Node?): List<Element> = buildList {
        var c=n?.firstChild
        while(c!=null) {
            if(c is Element) add(c)
            c=c.nextSibling
        }
    }
    private fun Node.name()=nodeName.substringAfterLast(':')
    private fun one(n: Node?,name: String): Element? {
        val list=children(n).filter { it.name()==name }
        require(list.size<=1) { "Ambiguous chart ink: "+name }
        return list.singleOrNull()
    }
    private fun percent(value: String, signed: Boolean=false): Double {
        val v=if(value.endsWith("%")) value.dropLast(1).toDoubleOrNull()?.div(100)
            else value.toLongOrNull()?.toDouble()?.div(100000)
        require(v!=null && v.isFinite() && v in (if(signed) -100.0 else 0.0)..100.0) { "Invalid chart ink percentage" }
        return v
    }
    fun fill(n: Node?,theme: Map<String,Int>): XlsxCachedChart.Ink? {
        val all=children(n).filter { it.name() in setOf("solidFill","noFill","gradFill","pattFill","blipFill","grpFill") }
        require(all.size<=1) { "Ambiguous chart fill" }
        val f=all.singleOrNull() ?: return null
        if(f.name()=="noFill") return XlsxCachedChart.Ink(null)
        if(f.name()!="solidFill") throw IOException("Unsupported chart fill")
        val c=children(f).singleOrNull() ?: throw IOException("Missing chart solid color")
        val base=when(c.name()) {
            "srgbClr" -> c.getAttribute("val").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toInt(16)
            "schemeClr" -> theme[c.getAttribute("val")]
            "sysClr" -> c.getAttribute("lastClr").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toInt(16)
            else -> null
        } ?: throw IOException("Unresolved chart ink")
        var rgb=doubleArrayOf(((base ushr 16) and 255)/255.0,((base ushr 8) and 255)/255.0,(base and 255)/255.0)
        var alpha=1.0
        for(effect in children(c)) {
            val kind=effect.name()
            val f=percent(effect.getAttribute("val"),kind=="alphaOff")
            require(when(kind) {
                "alphaOff" -> f in -1.0..1.0
                "lumMod","alphaMod" -> f in 0.0..100.0
                else -> f in 0.0..1.0
            }) { "Invalid chart color transform" }
            when(effect.name()) {
                "tint" -> for(i in rgb.indices) rgb[i]=1-(1-rgb[i])*f
                "shade" -> for(i in rgb.indices) rgb[i]*=f
                "lumMod" -> rgb=DrawingMlColorMath.lightness(rgb) { it*f }
                "lumOff" -> rgb=DrawingMlColorMath.lightness(rgb) { it+f }
                "alpha" -> alpha=f
                "alphaMod" -> alpha*=f
                "alphaOff" -> alpha+=f
                else -> throw IOException("Unsupported chart color transform: "+effect.name())
            }
            alpha=alpha.coerceIn(0.0,1.0)
        }
        if(alpha==0.0) return XlsxCachedChart.Ink(null)
        return XlsxCachedChart.Ink(((rgb[0]*255).roundToInt() shl 16) or ((rgb[1]*255).roundToInt() shl 8) or (rgb[2]*255).roundToInt(),
            (alpha*255).roundToInt())
    }

    fun stroke(n: Node?,theme: Map<String,Int>,inherited: XlsxCachedChart.Stroke?=null): XlsxCachedChart.Stroke? {
        val ln=one(n,"ln") ?: return null
        require(ln.getAttribute("cmpd") in setOf("","sng") && ln.getAttribute("algn") in setOf("","ctr"))
        require(listOf("headEnd","tailEnd").all { one(ln,it)?.getAttribute("type") in setOf(null,"","none") })
        val cap=ln.getAttribute("cap").ifBlank { inherited?.cap ?: "flat" }
        require(cap in setOf("flat","rnd","sq"))
        val width=ln.getAttribute("w").takeIf { it.isNotEmpty() }?.let {
            val v=it.toLongOrNull() ?: throw IOException("Invalid chart stroke width")
            require(v in 1..914400) { "Chart stroke exceeds width budget" }
            (v/12700f)
        }
        val preset=one(ln,"prstDash"); val custom=one(ln,"custDash")
        require(preset==null || custom==null)
        val ratios=if(custom!=null) {
            val stops=children(custom)
            require(stops.size in 1..64 && stops.all { it.name()=="ds" })
            stops.flatMap { listOf(percent(it.getAttribute("d")).toFloat(),percent(it.getAttribute("sp")).toFloat()) }
        } else if(preset!=null) {
            val key=when(preset.getAttribute("val")) {
                "solid" -> "SOLID"; "dot" -> "DOT"; "dash" -> "DASH"; "lgDash" -> "LG_DASH"
                "dashDot" -> "DASH_DOT"; "lgDashDot" -> "LG_DASH_DOT"; "lgDashDotDot" -> "LG_DASH_DOT_DOT"
                "sysDash" -> "SYS_DASH"; "sysDot" -> "SYS_DOT"; "sysDashDot" -> "SYS_DASH_DOT"
                "sysDashDotDot" -> "SYS_DASH_DOT_DOT"
                else -> throw IOException("Unsupported chart stroke dash")
            }
            org.apache.poi.sl.usermodel.StrokeStyle.LineDash.valueOf(key).pattern?.map { it.toFloat() }.orEmpty()
        } else inherited?.dashRatios.orEmpty()
        require(ratios.all { it.isFinite() && it>0f })
        val joins=children(ln).filter { it.name() in setOf("round","bevel","miter") }
        require(joins.size<=1) { "Ambiguous chart line join" }
        val join=joins.singleOrNull()?.name() ?: inherited?.join ?: "miter"
        val limit=if(joins.isEmpty()) inherited?.miterLimit ?: 4f else joins.single().getAttribute("lim")
            .takeIf { it.isNotBlank() }?.let { percent(it).toFloat() } ?: 4f
        require(limit.isFinite() && limit in 1f..100f) { "Invalid chart miter limit" }
        return XlsxCachedChart.Stroke(fill(ln,theme) ?: inherited?.ink,width ?: inherited?.widthPt,ratios,cap,join,limit)
    }

    fun resolveInk(ink: XlsxCachedChart.Ink?,fallback: Int): XlsxCachedChart.Ink? =
        (ink ?: XlsxCachedChart.Ink(fallback and 0xffffff)).takeIf { it.rgb!=null && it.alpha>0 }

    // RGB compatibility accessor for cache tests and legacy callers.
    fun resolved(ink: XlsxCachedChart.Ink?,fallback: Int): Int? = resolveInk(ink,fallback)?.rgb
    fun argb(ink: XlsxCachedChart.Ink): Int = (ink.alpha shl 24) or requireNotNull(ink.rgb)

    fun pointInk(series: XlsxCachedChart.Series,index: Int,fallback: Int): XlsxCachedChart.Ink? =
        resolveInk(series.pointFills[index] ?: series.fill,fallback)
    fun pointFill(series: XlsxCachedChart.Series,index: Int,fallback: Int): Int? =
        pointInk(series,index,fallback)?.rgb
    fun pointStroke(series: XlsxCachedChart.Series,index: Int): XlsxCachedChart.Stroke? =
        series.pointStrokes[index] ?: series.stroke
    fun outlineInk(stroke: XlsxCachedChart.Stroke?,fallback: Int): XlsxCachedChart.Ink? =
        stroke?.let { resolveInk(it.ink,fallback) }

    fun dashes(stroke: XlsxCachedChart.Stroke?,width: Float): FloatArray =
        stroke?.dashRatios.orEmpty().map { (it*width).coerceAtLeast(0.01f) }.toFloatArray()
}
