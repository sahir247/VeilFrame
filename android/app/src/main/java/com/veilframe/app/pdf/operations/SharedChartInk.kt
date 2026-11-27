package com.veilframe.app.pdf.operations

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.IOException
import kotlin.math.roundToInt

/** Supported saved chart ink. Distinguish absence, noFill and an opaque solid.
 * Non-opaque paint is refused until the two backends share alpha compositing.
 */
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
    private fun percent(value: String): Double {
        val v=if(value.endsWith("%")) value.dropLast(1).toDoubleOrNull()?.div(100)
            else value.toLongOrNull()?.toDouble()?.div(100000)
        require(v!=null && v.isFinite() && v in 0.0..100.0) { "Invalid chart ink percentage" }
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
        val rgb=doubleArrayOf(((base ushr 16) and 255).toDouble(),((base ushr 8) and 255).toDouble(),(base and 255).toDouble())
        fun changeLightness(transform: (Double)->Double) {
            // DrawingML luminance operations are HSL lightness operations,
            // not per-channel multiplication. Preserve hue and saturation.
            val red=rgb[0]/255; val green=rgb[1]/255; val blue=rgb[2]/255
            val max=maxOf(red,green,blue); val min=minOf(red,green,blue)
            val delta=max-min; val light=(max+min)/2
            val saturation=if(delta==0.0) 0.0 else delta/(1-kotlin.math.abs(2*light-1))
            val hue=if(delta==0.0) 0.0 else when(max) {
                red -> ((green-blue)/delta+6)%6
                green -> (blue-red)/delta+2
                else -> (red-green)/delta+4
            }
            val next=transform(light).coerceIn(0.0,1.0)
            val chroma=(1-kotlin.math.abs(2*next-1))*saturation
            val x=chroma*(1-kotlin.math.abs(hue%2-1))
            val m=next-chroma/2
            val channels=when(hue.toInt()) {
                0 -> doubleArrayOf(chroma,x,0.0)
                1 -> doubleArrayOf(x,chroma,0.0)
                2 -> doubleArrayOf(0.0,chroma,x)
                3 -> doubleArrayOf(0.0,x,chroma)
                4 -> doubleArrayOf(x,0.0,chroma)
                else -> doubleArrayOf(chroma,0.0,x)
            }
            for(i in rgb.indices) rgb[i]=((channels[i]+m)*255).coerceIn(0.0,255.0)
        }
        var alpha=1.0
        for(effect in children(c)) {
            val f=percent(effect.getAttribute("val"))
            require(f in 0.0..1.0 || (effect.name()=="lumMod" && f<=100.0)) { "Invalid chart color transform" }
            when(effect.name()) {
                "tint" -> for(i in rgb.indices) rgb[i]=255-(255-rgb[i])*f
                "shade" -> for(i in rgb.indices) rgb[i]*=f
                "lumMod" -> changeLightness { it*f }
                "lumOff" -> changeLightness { it+f }
                "alpha" -> alpha=f
                "alphaMod" -> alpha*=f
                else -> throw IOException("Unsupported chart color transform: "+effect.name())
            }
        }
        if(alpha==0.0) return XlsxCachedChart.Ink(null)
        if(alpha!=1.0) throw IOException("Translucent chart ink unsupported")
        return XlsxCachedChart.Ink((rgb[0].roundToInt() shl 16) or (rgb[1].roundToInt() shl 8) or rgb[2].roundToInt())
    }

    fun stroke(n: Node?,theme: Map<String,Int>): XlsxCachedChart.Stroke? {
        val ln=one(n,"ln") ?: return null
        require(ln.getAttribute("cmpd") in setOf("","sng") && ln.getAttribute("algn") in setOf("","ctr"))
        require(listOf("headEnd","tailEnd").all { one(ln,it)?.getAttribute("type") in setOf(null,"","none") })
        val cap=ln.getAttribute("cap").ifBlank { "flat" }
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
        } else emptyList()
        require(ratios.all { it.isFinite() && it>0f })
        return XlsxCachedChart.Stroke(fill(ln,theme),width,ratios,cap)
    }

    fun resolved(ink: XlsxCachedChart.Ink?,fallback: Int): Int? =
        if(ink==null) fallback and 0xffffff else ink.rgb

    fun pointFill(series: XlsxCachedChart.Series,index: Int,fallback: Int): Int? =
        resolved(series.pointFills[index] ?: series.fill,fallback)

    fun dashes(stroke: XlsxCachedChart.Stroke?,width: Float): FloatArray =
        stroke?.dashRatios.orEmpty().map { (it*width).coerceAtLeast(0.01f) }.toFloatArray()
}
