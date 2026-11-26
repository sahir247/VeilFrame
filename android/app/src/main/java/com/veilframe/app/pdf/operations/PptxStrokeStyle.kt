package com.veilframe.app.pdf.operations

import android.graphics.DashPathEffect
import android.graphics.Paint
import org.w3c.dom.Node
import java.io.IOException

/** Shared explicit DrawingML stroke attributes for shapes and table borders.
 * Uses POI's empirically determined PowerPoint dash ratios, not Java2D paints.
 */
internal object PptxStrokeStyle {
    data class Spec(val width: Float,val cap: String="flat",val join: String="miter",
        val miter: Float=4f,val dash: List<Float> = emptyList()) {
        fun paint(rgb: Int)=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=rgb; style=Paint.Style.STROKE; strokeWidth=width
            strokeCap=when(cap) { "rnd" -> Paint.Cap.ROUND; "sq" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
            strokeJoin=when(join) { "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
            strokeMiter=miter
            if(dash.isNotEmpty()) pathEffect=DashPathEffect(dash.toFloatArray(),0f)
        }
    }
    private val a=setOf("http://schemas.openxmlformats.org/drawingml/2006/main","http://purl.oclc.org/ooxml/drawingml/main")
    private val p=setOf("http://schemas.openxmlformats.org/presentationml/2006/main","http://purl.oclc.org/ooxml/presentationml/main")
    private fun children(n: Node): List<Node> {
        val result=mutableListOf<Node>(); var c=n.firstChild
        while(c!=null) {
            if(c.nodeType==Node.ELEMENT_NODE) {
                require(result.size<1024) { "Outline element budget exceeded" }
                result.add(c)
            }
            c=c.nextSibling
        }
        return result
    }
    private fun one(n: Node,name: String,ns: Set<String> = a): Node? {
        val found=children(n).filter { it.localName==name && it.namespaceURI in ns }
        require(found.size<=1) { "Ambiguous outline: "+name }
        return found.singleOrNull()
    }
    private fun attr(n: Node,key: String)=n.attributes?.getNamedItem(key)?.nodeValue
    private fun percentage(raw: String?): Float {
        val number=raw?.let {
            if(it.endsWith("%")) it.dropLast(1).toDoubleOrNull()?.div(100)
            else it.toLongOrNull()?.toDouble()?.div(100000)
        } ?: throw IOException("Invalid outline percentage")
        require(number.isFinite() && number in 0.0..100.0)
        return number.toFloat()
    }
    fun fromShape(root: Node,scale: Float,defaultWidth: Float): Spec {
        val element=if(root.nodeType==Node.DOCUMENT_NODE) children(root).singleOrNull() ?: return Spec(defaultWidth) else root
        val line=one(element,"spPr",p)?.let { one(it,"ln") } ?: return Spec(defaultWidth)
        return parse(line,scale,defaultWidth)
    }
    fun parse(line: Node,scale: Float,defaultWidth: Float): Spec {
        require(scale.isFinite() && scale>0f && defaultWidth.isFinite() && defaultWidth>=0f)
        val width=attr(line,"w")?.let {
            val emu=it.toLongOrNull() ?: throw IOException("Invalid outline width")
            require(emu in 0..20116800) { "Outline width exceeds budget" }
            emu/12700f*scale
        } ?: defaultWidth
        require(width.isFinite() && width in 0f..8192f)
        val cap=attr(line,"cap") ?: "flat" // Office default, per MS-OI29500
        require(cap in setOf("flat","rnd","sq")) { "Unsupported outline cap" }
        require(attr(line,"cmpd") in setOf(null,"sng") && attr(line,"algn") in setOf(null,"ctr")) {
            "Compound or inset outline unsupported"
        }
        for(end in listOf("headEnd","tailEnd")) {
            val e=one(line,end)
            if(e!=null && attr(e,"type") !in setOf(null,"none"))
                throw IOException("Arrowhead outline geometry unsupported")
        }
        val joins=children(line).filter { it.namespaceURI in a && it.localName in setOf("miter","round","bevel") }
        require(joins.size<=1) { "Ambiguous outline join" }
        val join=joins.singleOrNull()?.localName ?: "miter"
        val miter=joins.singleOrNull()?.let { if(join=="miter") attr(it,"lim")?.let(::percentage) else null } ?: 4f
        require(miter>=1f) { "Invalid outline miter limit" }
        val preset=one(line,"prstDash")
        val custom=one(line,"custDash")
        require(preset==null || custom==null) { "Ambiguous outline dashing" }
        val ratios=if(custom!=null) {
            val stops=children(custom)
            require(stops.size in 1..64 && stops.all { it.localName=="ds" && it.namespaceURI in a })
            stops.flatMap { listOf(percentage(attr(it,"d")),percentage(attr(it,"sp"))) }
                .also { require(it.all { v -> v>0f }) { "Zero-length custom dash unsupported" } }
        } else if(preset!=null) {
            val name=when(attr(preset,"val")) {
                "solid" -> "SOLID"; "dot" -> "DOT"; "dash" -> "DASH"; "lgDash" -> "LG_DASH"
                "dashDot" -> "DASH_DOT"; "lgDashDot" -> "LG_DASH_DOT"; "lgDashDotDot" -> "LG_DASH_DOT_DOT"
                "sysDash" -> "SYS_DASH"; "sysDot" -> "SYS_DOT"; "sysDashDot" -> "SYS_DASH_DOT"
                "sysDashDotDot" -> "SYS_DASH_DOT_DOT"
                else -> throw IOException("Unknown outline dash pattern")
            }
            org.apache.poi.sl.usermodel.StrokeStyle.LineDash.valueOf(name).pattern?.map { it.toFloat() }.orEmpty()
        } else emptyList()
        val dash=ratios.map { it*width.coerceAtLeast(1f) }
        require(dash.all { it.isFinite() && it in 0.001f..819200f }) { "Outline dash exceeds budget" }
        return Spec(width,cap,join,miter,dash)
    }
}
