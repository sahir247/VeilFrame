package com.veilframe.app.pdf.operations

import android.graphics.DashPathEffect
import android.graphics.Paint
import org.w3c.dom.Node
import java.io.IOException

/** Shared explicit DrawingML stroke attributes for shapes and table borders.
 * Uses POI's empirically determined PowerPoint dash ratios, not Java2D paints.
 */
internal object PptxStrokeStyle {
    data class End(val type: String="none",val width: String="med",val length: String="med")
    data class Spec(val width: Float,val cap: String="flat",val join: String="miter",
        val miter: Float=4f,val dash: List<Float> = emptyList(),val compound: String="sng",
        val head: End=End(),val tail: End=End(),val alignment: String="ctr") {
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
    fun fromShape(root: Node,scale: Float,defaultWidth: Float,straightConnector: Boolean=false): Spec {
        val element=if(root.nodeType==Node.DOCUMENT_NODE) children(root).singleOrNull() ?: return Spec(defaultWidth) else root
        val line=one(element,"spPr",p)?.let { one(it,"ln") } ?: return Spec(defaultWidth)
        return parse(line,scale,defaultWidth,straightConnector,closedOutline=!straightConnector)
    }
    fun parse(line: Node,scale: Float,defaultWidth: Float,straightConnector: Boolean=false,inherited: Node?=null,closedOutline: Boolean=false): Spec {
        fun attribute(key: String): String? = attr(line,key) ?: inherited?.let { attr(it,key) }
        require(scale.isFinite() && scale>0f && defaultWidth.isFinite() && defaultWidth>=0f)
        val width=attribute("w")?.let {
            val emu=it.toLongOrNull() ?: throw IOException("Invalid outline width")
            require(emu in 0..20116800) { "Outline width exceeds budget" }
            emu/12700f*scale
        } ?: defaultWidth
        require(width.isFinite() && width in 0f..8192f)
        val cap=attribute("cap") ?: "flat" // Office default, per MS-OI29500
        require(cap in setOf("flat","rnd","sq")) { "Unsupported outline cap" }
        val compound=attribute("cmpd") ?: "sng"
        require(compound in setOf("sng","dbl","thickThin","thinThick","tri")) { "Unknown compound outline" }
        val alignment=attribute("algn") ?: "ctr"
        require(alignment in setOf("ctr","in") && (alignment=="ctr" || closedOutline)) { "Inset open outline unsupported" }
        fun endpoint(name: String): End {
            val local=one(line,name)
            val base=inherited?.let { one(it,name) }
            if(local==null && base==null) return End()
            fun endAttr(key: String)=local?.let { attr(it,key) } ?: base?.let { attr(it,key) }
            val result=End(endAttr("type") ?: "none",endAttr("w") ?: "med",endAttr("len") ?: "med")
            require(result.type in setOf("none","triangle","stealth","diamond","oval","arrow") &&
                result.width in setOf("sm","med","lg") && result.length in setOf("sm","med","lg")) {
                "Unknown outline endpoint"
            }
            return result
        }
        val head=endpoint("headEnd")
        val tail=endpoint("tailEnd")
        if(!straightConnector && ((!closedOutline && compound!="sng") || head.type!="none" || tail.type!="none"))
            throw IOException("Decorated/compound non-straight outline unsupported")
        fun joinsOf(n: Node)=children(n).filter { it.namespaceURI in a && it.localName in setOf("miter","round","bevel") }
        val joins=joinsOf(line).ifEmpty { inherited?.let(::joinsOf).orEmpty() }
        require(joins.size<=1) { "Ambiguous outline join" }
        val join=joins.singleOrNull()?.localName ?: "miter"
        val miter=joins.singleOrNull()?.let { if(join=="miter") attr(it,"lim")?.let(::percentage) else null } ?: 4f
        require(miter>=1f) { "Invalid outline miter limit" }
        val localPreset=one(line,"prstDash")
        val localCustom=one(line,"custDash")
        val preset=if(localPreset==null && localCustom==null) inherited?.let { one(it,"prstDash") } else localPreset
        val custom=if(localPreset==null && localCustom==null) inherited?.let { one(it,"custDash") } else localCustom
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
        if(closedOutline && compound!="sng" && dash.isNotEmpty()) throw IOException("Dashed compound closed outline unsupported")
        return Spec(width,cap,join,miter,dash,compound,head,tail,alignment)
    }
}
