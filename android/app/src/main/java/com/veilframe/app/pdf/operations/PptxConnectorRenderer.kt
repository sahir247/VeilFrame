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
    fun isCurved(root: Node): Boolean {
        val props=properties(root) ?: return false
        return one(props,"prstGeom",drawing)?.attributes?.getNamedItem("prst")?.nodeValue in
            setOf("curvedConnector2","curvedConnector3","curvedConnector4","curvedConnector5")
    }
    data class Resolved(val spec: PptxStrokeStyle.Spec,val color: Int)
    private fun attr(node: Node,key: String)=node.attributes?.getNamedItem(key)?.nodeValue
    private fun fill(line: Node?): Node? {
        if(line==null) return null
        val fills=listOf("noFill","solidFill","gradFill","pattFill","blipFill","grpFill")
            .mapNotNull { one(line,it,drawing) }
        require(fills.size<=1) { "Ambiguous connector line fill" }
        return fills.singleOrNull()
    }
    fun resolve(root: Node,scale: Float,defaultWidth: Float,themeLine: (Int)->Node?,
        theme: (String)->Int?): Resolved? {
        val element=if(root is org.w3c.dom.Document) root.documentElement else root
        val props=properties(root) ?: throw IOException("Missing connector properties")
        val direct=one(props,"ln",drawing)
        val localFill=fill(direct)
        if(localFill?.localName=="noFill") return null
        val ref=one(element,"style",presentation)?.let { one(it,"lnRef",drawing) }
        val index=ref?.let {
            attr(it,"idx")?.toIntOrNull() ?: throw IOException("Invalid connector style index")
        }
        require(index==null || index in 0..999) { "Invalid connector style index" }
        val inherited=if(index!=null && index>0)
            themeLine(index) ?: throw IOException("Missing connector theme line style") else null
        val selected=localFill ?: fill(inherited)
        if(selected?.localName=="noFill" || (selected==null && index==0)) return null
        if(selected?.localName!="solidFill")
            throw IOException("Non-solid or unresolved connector stroke unsupported")
        val spec=PptxStrokeStyle.parse(direct ?: requireNotNull(inherited),scale,defaultWidth,true,inherited)
        val color=readColor(selected) { key ->
            if(key=="phClr") ref?.let { readColor(it,theme) } else theme(key)
        }
        return Resolved(spec,color)
    }

    private fun readColor(fill: Node,theme: (String)->Int?): Int {
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
            union(decorationInk(endSpec,tip,direction,l,width,fit,spec,color))
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
    private fun decorationInk(endSpec: PptxStrokeStyle.End,tip: Float,direction: Float,l: Float,
        width: Float,fit: Float,spec: PptxStrokeStyle.Spec,color: Int): Path {
            if(endSpec.type=="none") return Path()
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
                        val out=Path()
                        if(!pen.getFillPath(path,out)) throw IOException("Cannot expand arrowhead")
                        return out
                    }
                    path.close()
                }
                "oval" -> path.addOval(minOf(tip,back(l)),-w,maxOf(tip,back(l)),w,Path.Direction.CW)
                else -> throw IOException("Unsupported connector decoration")
            }
            return path
    }

    /** Arc-length synchronized bands on a single open curve. Exact source
     * segment for single strokes; bounded sampled normal offsets for compounds. */
    fun drawCurved(canvas: Canvas,path: Path,spec: PptxStrokeStyle.Spec,color: Int) {
        val measure=android.graphics.PathMeasure(path,false)
        val length=measure.length
        require(length.isFinite() && length in 0.001f..20000f && !measure.isClosed)
        require(!measure.nextContour()) { "Multiple curved connector contours unsupported" }
        measure.setPath(path,false)
        val width=spec.width.coerceAtLeast(1f)
        val hl0=if(spec.head.type=="none") 0f else width*multiplier(spec.head.length)
        val tl0=if(spec.tail.type=="none") 0f else width*multiplier(spec.tail.length)
        val fit=if(hl0+tl0>length) length/(hl0+tl0) else 1f
        val hl=hl0*fit;val tl=tl0*fit
        val start=trim(spec.head.type,hl)
        val end=length-trim(spec.tail.type,tl)
        val ink=Path()
        fun union(p: Path) {
            if(p.isEmpty) return
            if(ink.isEmpty) ink.set(p)
            else if(!ink.op(p,Path.Op.UNION)) throw IOException("Cannot combine curved connector ink")
        }
        fun stroke(p: Path,pen: Paint) {
            val out=Path()
            if(!pen.getFillPath(p,out)) throw IOException("Cannot expand curved connector")
            union(out)
        }
        val pos=FloatArray(2);val tan=FloatArray(2)
        fun sample(distance: Float) {
            if(!measure.getPosTan(distance.coerceIn(0f,length),pos,tan) ||
                (pos+tan).any { !it.isFinite() }) throw IOException("Invalid curved connector tangent")
        }
        if(end>start) {
            if(spec.compound=="sng") {
                val shaft=Path()
                if(!measure.getSegment(start,end,shaft,true)) throw IOException("Cannot extract connector curve")
                stroke(shaft,spec.paint(color).apply { strokeWidth=width })
            } else {
                // Dash positions are measured along the source curve and shared
                // by every offset band. Avoid independent offset-length drift.
                val intervals=mutableListOf<Pair<Float,Float>>()
                if(spec.dash.isEmpty()) intervals.add(start to end)
                else {
                    var at=start;var n=0
                    while(at<end) {
                        require(n<8192) { "Curved connector dash budget exceeded" }
                        val next=minOf(end,at+spec.dash[n%spec.dash.size])
                        require(next>at)
                        if(n%2==0) intervals.add(at to next)
                        at=next;n++
                    }
                }
                var samples=0
                for(band in bands(spec.compound)) for((lo,hi) in intervals) {
                    val count=kotlin.math.ceil((hi-lo)/0.5f).toInt().coerceAtLeast(1)
                    samples+=count+1
                    require(samples<=24000) { "Curved offset sample budget exceeded" }
                    val offset=band.center*width
                    val shaft=Path()
                    for(j in 0..count) {
                        sample(lo+(hi-lo)*j/count)
                        val x=pos[0]-tan[1]*offset;val y=pos[1]+tan[0]*offset
                        if(j==0) shaft.moveTo(x,y) else shaft.lineTo(x,y)
                    }
                    stroke(shaft,spec.paint(color).apply { strokeWidth=width*band.fraction;pathEffect=null })
                }
            }
        }
        fun endpoint(e: PptxStrokeStyle.End,distance: Float,direction: Float,size: Float) {
            if(e.type=="none") return
            sample(distance)
            val p=decorationInk(e,0f,direction,size,width,fit,spec,color)
            val transform=android.graphics.Matrix().apply {
                setRotate(Math.toDegrees(atan2(tan[1].toDouble(),tan[0].toDouble())).toFloat())
                postTranslate(pos[0],pos[1])
            }
            p.transform(transform);union(p)
        }
        endpoint(spec.head,0f,1f,hl)
        endpoint(spec.tail,length,-1f,tl)
        canvas.drawPath(ink,Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color;style=Paint.Style.FILL })
    }
}
