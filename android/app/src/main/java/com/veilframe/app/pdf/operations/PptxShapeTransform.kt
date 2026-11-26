package com.veilframe.app.pdf.operations

import org.w3c.dom.Node
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.hypot

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
        val determinant: Double get() = a*d-b*c
        fun inverse(): Affine {
            val det = determinant
            require(det.isFinite() && abs(det)>1e-12) { "Degenerate slide text transform" }
            return Affine(d/det,-b/det,-c/det,a/det,(c*ty-d*tx)/det,(b*tx-a*ty)/det)
        }
        fun pixelAffine(sx: Double,sy: Double): Affine {
            require(sx.isFinite() && sy.isFinite() && sx>0 && sy>0)
            return Affine(a,b*sy/sx,c*sx/sy,d,tx*sx,ty*sy)
        }
        fun pixels(sx: Double,sy: Double): FloatArray {
            val p = pixelAffine(sx,sy)
            val values = doubleArrayOf(p.a,p.c,p.tx,p.b,p.d,p.ty,0.0,0.0,1.0)
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
        val shapeProperties = properties(root,"spPr")
        val node = if(shapeProperties!=null) one(shapeProperties,"xfrm",drawing) else properties(root,"xfrm")
        if(node==null) return null
        val bounds = rect(node,"off","ext")
        return Transform(bounds,orientation(node,bounds))
    }
    data class TextBody(val upright: Boolean,val direction: String?,val rotation: Double? = null)
    private fun bodyAttribute(root: Node,key: String): String? =
        (properties(root,"txBody") ?: one(root,"txBody",drawing))?.let { one(it,"bodyPr",drawing) }?.let { attr(it,key) }

    internal fun resolveTextBody(resolve: (String)->String?): TextBody {
        val upright = when(resolve("upright")) {
            null,"0","false" -> false
            "1","true" -> true
            else -> throw IOException("Invalid upright text flag")
        }
        val rotation = resolve("rot")?.let {
            (it.toLongOrNull() ?: throw IOException("Invalid slide text rotation"))/60000.0
        }
        return TextBody(upright,resolve("vert"),rotation)
    }
    fun textBody(root: Node): TextBody = resolveTextBody { bodyAttribute(root,it) }

    /** Resolve each property separately: a local empty bodyPr does not erase inheritance.
     * POI uses slide -> matching layout placeholder -> master placeholder precedence.
     */
    fun textBody(shape: org.apache.poi.xslf.usermodel.XSLFShape): TextBody = resolveTextBody { key ->
        var found: String? = null
        shape.fetchShapeProperty(object: org.apache.poi.xslf.model.PropertyFetcher<String>() {
            override fun fetch(candidate: org.apache.poi.xslf.usermodel.XSLFShape): Boolean {
                val value = bodyAttribute(candidate.xmlObject.domNode,key) ?: return false
                found = value
                return true
            }
        })
        found
    }
    data class TextFrame(val bounds: Rect,val correction: Affine,val stacked: Boolean = false,
        val columnsRtl: Boolean = false,val nativeVertical: Boolean = false)

    /**
     * Shape canvas already has its pixel affine. Return a relative correction
     * and a centered layout rectangle; glyphs must not be mirror images.
     * 90/270 flow swaps the layout axes, not individual characters.
     */
    fun textFrame(shape: Affine,bounds: Rect,body: TextBody,rotation: Double,
        inheritedDirection: String? = null): TextFrame {
        require(rotation.isFinite()) { "Invalid slide text rotation" }
        val cx = bounds.x+bounds.width/2
        val cy = bounds.y+bounds.height/2
        var desired = shape
        if (desired.determinant<0) desired = desired*around(bounds,0.0,true,false)
        var frame = bounds
        fun swapped(r: Rect) = Rect(r.x+(r.width-r.height)/2,r.y+(r.height-r.width)/2,r.height,r.width)
        if (body.upright) {
            val xLength = hypot(desired.a,desired.b)
            val yLength = hypot(desired.c,desired.d)
            require(xLength>1e-8 && yLength>1e-8) { "Degenerate upright text transform" }
            val angle = atan2(desired.b,desired.a)
            val (mx,my) = desired.point(cx,cy)
            val quarterTurn = abs(sin(angle))>abs(cos(angle))
            if (quarterTurn) frame=swapped(frame)
            // Fit an axis-aligned, undistorted text rectangle inside the mapped
            // parallelogram. Inverse-map all four corners by absolute extents.
            val inverse = desired.inverse()
            val scaleX = if(quarterTurn) yLength else xLength
            val scaleY = if(quarterTurn) xLength else yLength
            val halfX = frame.width*scaleX/2
            val halfY = frame.height*scaleY/2
            val reachX = abs(inverse.a)*halfX+abs(inverse.c)*halfY
            val reachY = abs(inverse.b)*halfX+abs(inverse.d)*halfY
            val fit = minOf(1.0,bounds.width/(2*reachX),bounds.height/(2*reachY))
            require(fit.isFinite() && fit>0.001) { "Upright text rectangle is degenerate" }
            desired = translate(mx,my)*scale(scaleX*fit,scaleY*fit)*translate(-cx,-cy)
        } else {
            desired = desired*around(bounds,rotation%360.0,false,false)
        }
        val direction = body.direction ?: inheritedDirection ?: "horz"
        val stacked = direction in setOf("wordArtVert","wordArtVertRtl","STACKED")
        val nativeVertical = direction in setOf("eaVert","mongolianVert","VERTICAL_EAST_ASIAN","VERTICAL_MONGOLIAN")
        val flow = when(direction) {
            "horz","HORIZONTAL","wordArtVert","wordArtVertRtl","STACKED",
            "eaVert","mongolianVert","VERTICAL_EAST_ASIAN","VERTICAL_MONGOLIAN" -> 0.0
            "vert","VERTICAL" -> 90.0
            "vert270","VERTICAL_270" -> -90.0
            else -> throw OfficeTextEncodingException("Native vertical-script slide text is unsupported")
        }
        if (flow!=0.0) {
            desired = desired*around(bounds,flow,false,false)
            frame=swapped(frame)
        }
        return TextFrame(frame,shape.inverse()*desired,stacked,
            direction in setOf("wordArtVertRtl","eaVert","VERTICAL_EAST_ASIAN"),nativeVertical)
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
