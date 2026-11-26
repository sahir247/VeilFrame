package com.veilframe.app.pdf.operations

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import org.w3c.dom.Node
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.hypot

/** Straight DrawingML connectors: preserve the source direction before applying
 * the enclosing shape/group transform. Render an ink union, never erase gaps
 * with the background (which would destroy underlying content).
 */
internal object PptxConnectorRenderer {
    private val drawing=setOf("http://schemas.openxmlformats.org/drawingml/2006/main",
        "http://purl.oclc.org/ooxml/drawingml/main")
    private val presentation=setOf("http://schemas.openxmlformats.org/presentationml/2006/main",
        "http://purl.oclc.org/ooxml/presentationml/main")
    private fun one(node: Node,name: String,ns: Set<String>): Node? {
        var found: Node?=null
        var child=node.firstChild
        while(child!=null) {
            if(child.localName==name && child.namespaceURI in ns) {
                require(found==null) { "Ambiguous connector property" }
                found=child
            }
            child=child.nextSibling
        }
        return found
    }
    private fun properties(root: Node): Node? {
        val element=if(root is org.w3c.dom.Document) root.documentElement else root
        return one(element,"spPr",presentation)
    }
    fun isStraight(root: Node): Boolean {
        val props=properties(root) ?: return false
        val preset=one(props,"prstGeom",drawing) ?: return false
        return preset.attributes?.getNamedItem("prst")?.nodeValue in setOf("line","straightConnector1")
    }
    fun explicitlyInvisible(root: Node): Boolean {
        val props=properties(root) ?: return false
        val line=one(props,"ln",drawing) ?: return false
        return one(line,"noFill",drawing)!=null
    }

    fun explicitColor(root: Node,theme: (String)->Int?): Int {
        val props=properties(root) ?: throw IOException("Missing connector properties")
        val line=one(props,"ln",drawing) ?: throw IOException("Inherited connector stroke unsupported")
        val fill=one(line,"solidFill",drawing) ?: throw IOException("Non-solid or inherited connector stroke unsupported")
        val colors=mutableListOf<Node>()
        var child=fill.firstChild
        while(child!=null) {
            if(child.nodeType==Node.ELEMENT_NODE) colors.add(child)
            child=child.nextSibling
        }
        val c=colors.singleOrNull() ?: throw IOException("Invalid connector stroke color")
        val value=c.attributes?.getNamedItem("val")?.nodeValue ?: throw IOException("Missing connector color")
        val rgb=when {
            c.namespaceURI !in drawing -> throw IOException("Invalid connector color namespace")
            c.localName=="srgbClr" && value.matches(Regex("[0-9A-Fa-f]{6}")) ->
                value.toInt(16) or 0xff000000.toInt()
            c.localName=="schemeClr" -> theme(value) ?: throw IOException("Unresolved connector theme color")
            else -> throw IOException("Unsupported connector stroke color")
        }
        var effect=c.firstChild
        var alpha: Int?=null
        while(effect!=null) {
            if(effect.nodeType==Node.ELEMENT_NODE) {
                if(effect.namespaceURI !in drawing || effect.localName!="alpha" || alpha!=null)
                    throw IOException("Unsupported connector color transform")
                val raw=effect.attributes?.getNamedItem("val")?.nodeValue
                    ?: throw IOException("Missing connector alpha")
                val fraction=if(raw.endsWith("%")) raw.dropLast(1).toDoubleOrNull()?.div(100)
                    else raw.toLongOrNull()?.toDouble()?.div(100000)
                require(fraction!=null && fraction.isFinite() && fraction in 0.0..1.0) { "Invalid connector alpha" }
                alpha=kotlin.math.round(fraction*255).toInt()
            }
            effect=effect.nextSibling
        }
        return (rgb and 0xffffff) or ((alpha ?: (rgb ushr 24)) shl 24)
    }

    data class Band(val center: Float,val fraction: Float)
    // Fractions of the total stroke envelope; asymmetry is directed
    // consistently along the source path. Ratios are approximations.
    internal fun bands(compound: String): List<Band> = when(compound) {
        "sng" -> listOf(Band(0f,1f))
        "dbl" -> listOf(Band(-1f/3,1f/3),Band(1f/3,1f/3))
        "thickThin" -> listOf(Band(-0.2f,0.6f),Band(0.4f,0.2f))
        "thinThick" -> listOf(Band(-0.4f,0.2f),Band(0.2f,0.6f))
        "tri" -> listOf(Band(-0.4f,0.2f),Band(0f,0.2f),Band(0.4f,0.2f))
        else -> throw IOException("Unknown connector compound stroke")
    }
    internal fun multiplier(size: String): Float = when(size) {
        "sm" -> 2f
        "med" -> 3f
        "lg" -> 5f
        else -> throw IOException("Unknown connector endpoint size")
    }
    internal fun trim(type: String,length: Float): Float = when(type) {
        "none","arrow" -> 0f
        "stealth" -> length*0.65f
        "triangle","diamond","oval" -> length
        else -> throw IOException("Unknown connector endpoint")
    }

    fun draw(canvas: Canvas,x1: Float,y1: Float,x2: Float,y2: Float,
        spec: PptxStrokeStyle.Spec,color: Int) {
        val dx=x2-x1; val dy=y2-y1
        val length=hypot(dx,dy)
        require(listOf(x1,y1,x2,y2,length).all { it.isFinite() } && length>0f) {
            "Invalid connector endpoints"
        }
        val width=spec.width.coerceAtLeast(1f)
        val headLength=if(spec.head.type=="none") 0f else width*multiplier(spec.head.length)
        val tailLength=if(spec.tail.type=="none") 0f else width*multiplier(spec.tail.length)
        // Short connectors must not reverse the shaft or invert a decoration.
        val fit=if(headLength+tailLength>length) length/(headLength+tailLength) else 1f
        val hl=headLength*fit; val tl=tailLength*fit
        val start=trim(spec.head.type,hl)
        val end=length-trim(spec.tail.type,tl)
        val ink=Path()
        fun union(path: Path) {
            if(path.isEmpty) return
            if(ink.isEmpty) ink.set(path)
            else if(!ink.op(path,Path.Op.UNION)) throw IOException("Cannot combine connector ink")
        }
        fun stroked(path: Path,paint: Paint) {
            val outline=Path()
            if(!paint.getFillPath(path,outline)) throw IOException("Cannot outline connector stroke")
            union(outline)
        }
        if(end>start) for(band in bands(spec.compound)) {
            val y=band.center*width
            val shaft=Path().apply { moveTo(start,y); lineTo(end,y) }
            val pen=spec.paint(color).apply { strokeWidth=width*band.fraction }
            stroked(shaft,pen)
        }
        fun decoration(endSpec: PptxStrokeStyle.End,tip: Float,direction: Float,l: Float) {
            if(endSpec.type=="none") return
            val w=width*multiplier(endSpec.width)*fit/2
            fun back(d: Float)=tip+direction*d
            val path=Path()
            when(endSpec.type) {
                "triangle","stealth","diamond","arrow" -> {
                    if(endSpec.type=="diamond") {
                        path.moveTo(tip,0f); path.lineTo(back(l/2),-w)
                        path.lineTo(back(l),0f); path.lineTo(back(l/2),w)
                    } else {
                        path.moveTo(back(l),-w); path.lineTo(tip,0f); path.lineTo(back(l),w)
                        if(endSpec.type=="stealth") path.lineTo(back(l*0.65f),0f)
                    }
                    if(endSpec.type=="arrow") {
                        val pen=spec.paint(color).apply { strokeWidth=width; pathEffect=null }
                        stroked(path,pen)
                        return
                    }
                    path.close()
                }
                "oval" -> path.addOval(minOf(tip,back(l)),-w,maxOf(tip,back(l)),w,Path.Direction.CW)
                else -> throw IOException("Unsupported connector decoration")
            }
            union(path)
        }
        decoration(spec.head,0f,1f,hl)
        decoration(spec.tail,length,-1f,tl)
        val save=canvas.save()
        try {
            canvas.translate(x1,y1)
            canvas.rotate(Math.toDegrees(atan2(dy.toDouble(),dx.toDouble())).toFloat())
            canvas.drawPath(ink,Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color; style=Paint.Style.FILL })
        } finally { canvas.restoreToCount(save) }
    }
}
