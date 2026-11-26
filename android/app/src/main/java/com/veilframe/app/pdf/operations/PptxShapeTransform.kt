package com.veilframe.app.pdf.operations

import org.w3c.dom.Node
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin

/** DrawingML coordinates remain in EMUs until the complete group transform is composed. */
internal object PptxShapeTransform {
    private val drawing = setOf("http://schemas.openxmlformats.org/drawingml/2006/main",
        "http://purl.oclc.org/ooxml/drawingml/main")
    private val presentation = setOf("http://schemas.openxmlformats.org/presentationml/2006/main",
        "http://purl.oclc.org/ooxml/presentationml/main")
    data class Rect(val x: Double,val y: Double,val width: Double,val height: Double) {
        init {
            require(listOf(x,y,width,height).all { it.isFinite() && kotlin.math.abs(it)<=1e10 } &&
                width>0 && height>0) { "Invalid slide shape rectangle" }
        }
    }
    /** Column-vector matrix: this * inner applies inner first. */
    data class Affine(val a: Double=1.0,val b: Double=0.0,val c: Double=0.0,
        val d: Double=1.0,val tx: Double=0.0,val ty: Double=0.0) {
        operator fun times(inner: Affine) = Affine(
            a*inner.a+c*inner.b,b*inner.a+d*inner.b,
            a*inner.c+c*inner.d,b*inner.c+d*inner.d,
            a*inner.tx+c*inner.ty+tx,b*inner.tx+d*inner.ty+ty)
        fun point(x: Double,y: Double) = (a*x+c*y+tx) to (b*x+d*y+ty)
        fun pixels(sx: Double,sy: Double): FloatArray {
            require(sx.isFinite() && sy.isFinite() && sx>0 && sy>0)
            val values = doubleArrayOf(a,c*sx/sy,tx*sx,b*sy/sx,d,ty*sy,0.0,0.0,1.0)
            require(values.all { it.isFinite() && kotlin.math.abs(it)<=1e8 }) {
                "Slide shape transform exceeds rendering bounds"
            }
            return FloatArray(values.size) { values[it].toFloat() }
        }
    }
    data class Transform(val bounds: Rect,val matrix: Affine)

    private fun translate(x: Double,y: Double) = Affine(tx=x,ty=y)
    private fun scale(x: Double,y: Double) = Affine(a=x,d=y)
    private fun around(rect: Rect,angle: Double,flipH: Boolean,flipV: Boolean): Affine {
        val cx = rect.x+rect.width/2; val cy = rect.y+rect.height/2
        val radians = Math.toRadians(angle)
        val rotation = Affine(cos(radians),sin(radians),-sin(radians),cos(radians))
        return translate(cx,cy)*rotation*scale(if(flipH) -1.0 else 1.0,if(flipV) -1.0 else 1.0)*translate(-cx,-cy)
    }
    private fun children(node: Node): Sequence<Node> = sequence {
        var child = node.firstChild
        while(child!=null) { yield(child); child=child.nextSibling }
    }
    private fun one(root: Node,name: String,namespaces: Set<String>): Node? {
        val matches = children(root).filter { it.localName==name && it.namespaceURI in namespaces }.toList()
        if(matches.size>1) throw IOException("Ambiguous slide transform element: $name")
        return matches.singleOrNull()
    }
    private fun attr(node: Node,key: String) = node.attributes?.getNamedItem(key)?.nodeValue
    private fun number(node: Node,key: String): Double =
        attr(node,key)?.toLongOrNull()?.toDouble() ?: throw IOException("Invalid slide transform coordinate: $key")
    private fun flag(node: Node,key: String): Boolean = when(attr(node,key)) {
        null,"0","false" -> false
        "1","true" -> true
        else -> throw IOException("Invalid slide transform flag: $key")
    }
    private fun rect(node: Node,offset: String,extent: String): Rect {
        val off = one(node,offset,drawing) ?: throw IOException("Missing slide transform offset")
        val ext = one(node,extent,drawing) ?: throw IOException("Missing slide transform extent")
        return Rect(number(off,"x"),number(off,"y"),number(ext,"cx"),number(ext,"cy"))
    }
    private fun properties(root: Node,name: String): Node? {
        val element = if(root.nodeType==Node.DOCUMENT_NODE)
            children(root).firstOrNull { it.nodeType==Node.ELEMENT_NODE } ?: return null else root
        return one(element,name,presentation)
    }
    private fun orientation(node: Node,bounds: Rect): Affine {
        val angle = attr(node,"rot")?.let {
            (it.toLongOrNull() ?: throw IOException("Invalid slide rotation"))/60000.0
        } ?: 0.0
        return around(bounds,angle%360.0,flag(node,"flipH"),flag(node,"flipV"))
    }
    fun shape(root: Node): Transform? {
        val properties = properties(root,"spPr") ?: return null
        val node = one(properties,"xfrm",drawing) ?: return null
        val bounds = rect(node,"off","ext")
        return Transform(bounds,orientation(node,bounds))
    }
    fun group(root: Node): Affine {
        val properties = properties(root,"grpSpPr") ?: throw IOException("Missing slide group properties")
        val node = one(properties,"xfrm",drawing) ?: throw IOException("Missing slide group transform")
        val outer = rect(node,"off","ext")
        val inner = rect(node,"chOff","chExt")
        return orientation(node,outer)*translate(outer.x,outer.y)*
            scale(outer.width/inner.width,outer.height/inner.height)*translate(-inner.x,-inner.y)
    }
}
