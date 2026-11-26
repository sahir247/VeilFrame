package com.veilframe.app.pdf.operations

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.w3c.dom.Node
import java.io.IOException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** Bounded DrawingML custom paths. Coordinates are local to each path, not slide EMUs. */
internal object PptxCustomGeometry {
    data class Command(val kind: String,val args: List<Double>)
    data class Subpath(val width: Double,val height: Double,val fill: Boolean,val stroke: Boolean,val commands: List<Command>,val fillMode: String=if(fill) "norm" else "none")
    private val ns=setOf("http://schemas.openxmlformats.org/drawingml/2006/main","http://purl.oclc.org/ooxml/drawingml/main")
    private val pres=setOf("http://schemas.openxmlformats.org/presentationml/2006/main","http://purl.oclc.org/ooxml/presentationml/main")
    private fun children(n: Node): List<Node> {
        val result=mutableListOf<Node>()
        var c=n.firstChild
        while(c!=null) {
            if(c.nodeType==Node.ELEMENT_NODE) {
                if(result.size>=16384) throw IOException("Slide custom geometry node budget exceeded")
                result.add(c)
            }
            c=c.nextSibling
        }
        return result
    }
    private fun one(n: Node,key: String,namespaces: Set<String> = ns): Node? {
        val found=children(n).filter { it.localName==key && it.namespaceURI in namespaces }
        if(found.size>1) throw IOException("Ambiguous slide custom geometry: "+key)
        return found.singleOrNull()
    }
    private fun attr(n: Node,key: String)=n.attributes?.getNamedItem(key)?.nodeValue
    private fun number(n: Node,key: String,guide: ((String)->Double)? = null): Double {
        val raw=attr(n,key) ?: throw IOException("Missing custom path coordinate: "+key)
        val v=raw.toLongOrNull()?.toDouble() ?: guide?.invoke(raw)
            ?: throw IOException("Invalid custom path coordinate: "+key)
        if(!v.isFinite() || abs(v)>1e10) throw IOException("Custom shape coordinate exceeds budget")
        return v
    }
    private fun flag(n: Node,key: String): Boolean = when(attr(n,key)) {
        null,"1","true" -> true
        "0","false" -> false
        else -> throw IOException("Invalid custom path flag")
    }
    // Read the Apache POI resource as XML data, never instantiate its Java2D path objects.
    // DOM is shared read-only and accesses are serialized by parsePreset.
    private val presets: Node by lazy {
        val stream=PptxCustomGeometry::class.java.getResourceAsStream(
            "/org/apache/poi/sl/draw/geom/presetShapeDefinitions.xml")
            ?: throw IOException("Bundled DrawingML preset definitions are unavailable")
        val xml=stream.use { OpenXmlDocumentParser.XmlReadBudget(4L*1024*1024,4L*1024*1024).read(it) }
        val factory=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware=true
            isValidating=false
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver { _,_ -> throw org.xml.sax.SAXException("External preset entities forbidden") }
        }.parse(org.xml.sax.InputSource(java.io.StringReader(xml))).documentElement
    }
    private fun definitions(node: Node,name: String): List<Pair<String,String>> {
        val list=one(node,name) ?: return emptyList()
        val entries=children(list)
        if(entries.size>1024) throw IOException("DrawingML guide list exceeds budget")
        return entries.map { entry ->
            if(entry.namespaceURI !in ns || entry.localName!="gd") throw IOException("Invalid geometry guide list")
            val key=attr(entry,"name") ?: throw IOException("Unnamed geometry guide")
            val formula=attr(entry,"fmla") ?: throw IOException("Missing geometry guide formula")
            key to formula
        }
    }
    private data class Geometry(val node: Node,val guides: List<Pair<String,String>>)
    private fun presetGeometry(preset: Node): Geometry? {
        val name=attr(preset,"prst") ?: throw IOException("Missing slide preset name")
        if(name=="rect") return null // ordinary rectangular shapes and picture fills
        if(!name.matches(Regex("[A-Za-z][A-Za-z0-9]{0,63}"))) throw IOException("Invalid preset name")
        val geometry=children(presets).filter { it.localName==name }.singleOrNull()
            ?: throw IOException("Unknown DrawingML preset: "+name)
        val defaults=definitions(geometry,"avLst")
        val supplied=definitions(preset,"avLst")
        val overrides=supplied.toMap()
        require(overrides.size==supplied.size && overrides.keys.all { key -> defaults.any { it.first==key } }) {
            "Invalid preset adjustment values"
        }
        val guides=defaults.map { (key,value) -> key to (overrides[key] ?: value) }+definitions(geometry,"gdLst")
        return Geometry(geometry,guides)
    }

    private data class Resolved(val geometry: Geometry,val width: Double?,val height: Double?)
    private fun resolveGeometry(root: Node,fallbackWidth: Double?,fallbackHeight: Double?,
        includePresets: Boolean): Resolved? {
        val element=if(root.nodeType==Node.DOCUMENT_NODE) children(root).singleOrNull() ?: return null else root
        val sp=one(element,"spPr",pres) ?: return null
        val extent=one(sp,"xfrm")?.let { one(it,"ext") }
        val width=extent?.let { number(it,"cx") } ?: fallbackWidth
        val height=extent?.let { number(it,"cy") } ?: fallbackHeight
        val geom=one(sp,"custGeom")
        if(geom!=null) {
            val guides=definitions(geom,"avLst")+definitions(geom,"gdLst")
            return Resolved(Geometry(geom,guides),width,height)
        }
        if(includePresets) return one(sp,"prstGeom")?.let { presetGeometry(it) }?.let { Resolved(it,width,height) }
        return null
    }

    @Synchronized
    fun parse(root: Node,fallbackWidth: Double?=null,fallbackHeight: Double?=null,
        includePresets: Boolean=false): List<Subpath>? {
        val resolved=resolveGeometry(root,fallbackWidth,fallbackHeight,includePresets) ?: return null
        return parsePaths(resolved.geometry.node,resolved.width,resolved.height,resolved.geometry.guides)
    }

    /** Shape text rectangle is evaluated in the shape coordinate space, NOT
     * the arbitrary w/h of an individual path. Keep this independent of paint.
     */
    data class TextBounds(val left: Double,val top: Double,val right: Double,val bottom: Double) {
        fun mapped(x: Float,y: Float,w: Float,h: Float): FloatArray =
            floatArrayOf(x+(left*w).toFloat(),y+(top*h).toFloat(),
                ((right-left)*w).toFloat(),((bottom-top)*h).toFloat())
    }
    @Synchronized
    fun textBounds(root: Node,width: Double,height: Double): TextBounds? {
        val resolved=resolveGeometry(root,width,height,true) ?: return null
        val rect=one(resolved.geometry.node,"rect") ?: return null
        val w=resolved.width ?: width
        val h=resolved.height ?: height
        val guides=DrawingMlGuides(w,h,resolved.geometry.guides)
        val l=number(rect,"l",guides::value)/w
        val t=number(rect,"t",guides::value)/h
        val r=number(rect,"r",guides::value)/w
        val b=number(rect,"b",guides::value)/h
        if(listOf(l,t,r,b).any { !it.isFinite() || abs(it)>16 } || r<=l || b<=t)
            throw OfficeTextEncodingException("Shape has no valid text rectangle")
        return TextBounds(l,t,r,b)
    }

    private fun parsePaths(geom: Node,shapeWidth: Double?,shapeHeight: Double?,definitions: List<Pair<String,String>>): List<Subpath> {
        val paths=one(geom,"pathLst") ?: throw IOException("Missing shape path list")
        val nodes=children(paths)
        if(nodes.size !in 1..64) throw IOException("Custom path count exceeds budget")
        var commandCount=0
        return nodes.map { node ->
            if(node.namespaceURI !in ns || node.localName!="path") throw IOException("Unexpected custom geometry element")
            val width=if(attr(node,"w")!=null) number(node,"w") else
                shapeWidth ?: throw IOException("Missing shape path width")
            val height=if(attr(node,"h")!=null) number(node,"h") else
                shapeHeight ?: throw IOException("Missing shape path height")
            if(width<=0 || height<=0) throw IOException("Invalid custom path coordinate space")
            val guides=DrawingMlGuides(width,height,definitions)
            val resolve=guides::value
            val fillMode=attr(node,"fill") ?: "norm"
            if(fillMode !in setOf("none","norm","lighten","lightenLess","darken","darkenLess"))
                throw IOException("Unsupported custom path fill effect")
            val fill=fillMode!="none"
            val stroke=flag(node,"stroke")
            var started=false
            var hasSegment=false
            val commands=children(node).map { command ->
                if(++commandCount>8192 || command.namespaceURI !in ns)
                    throw IOException("Custom path command budget or namespace exceeded")
                val kind=command.localName
                val pointCount=when(kind) {
                    "moveTo","lnTo" -> 1
                    "quadBezTo" -> 2
                    "cubicBezTo" -> 3
                    "close","arcTo" -> 0
                    else -> throw IOException("Unsupported custom shape path command: "+kind)
                }
                val pts=children(command)
                if(pts.size!=pointCount || pts.any { it.namespaceURI !in ns || it.localName!="pt" })
                    throw IOException("Invalid custom path point count")
                if(kind!="moveTo" && !started) throw IOException("Custom path must start at an explicit point")
                if(kind=="moveTo") started=true
                else if(kind!="close") hasSegment=true
                val args=if(kind=="arcTo") listOf(number(command,"wR",resolve),number(command,"hR",resolve),number(command,"stAng",resolve),number(command,"swAng",resolve))
                    else pts.flatMap { listOf(number(it,"x",resolve),number(it,"y",resolve)) }
                if(kind=="arcTo" && (args[0]<=0 || args[1]<=0 || abs(args[3])>21600000))
                    throw IOException("Invalid custom ellipse arc")
                Command(kind,args)
            }
            if(!hasSegment) throw IOException("Empty custom shape path")
            Subpath(width,height,fill,stroke,commands,fillMode)
        }
    }

    // DrawingML angles describe rays from the ellipse center, not Android's
    // ellipse parameter angles. Convert before producing an axis-aligned arc.
    internal fun ellipseAngle(rx: Double,ry: Double,degrees: Double): Double {
        val rad=degrees*PI/180
        return atan2(rx*sin(rad),ry*cos(rad))*180/PI
    }
    /** Relative solid face shading. The 20/40 percent strengths are an
     * interoperability convention; independent Office raster parity is not assumed.
     * Keep alpha and the caller's Paint unchanged across overlapping faces.
     */
    internal fun shadeColor(argb: Int,mode: String): Int {
        val amount=when(mode) {
            "norm" -> return argb
            "lightenLess","darkenLess" -> 20
            "lighten","darken" -> 40
            else -> throw IOException("Unsupported solid path shading")
        }
        val target=if(mode.startsWith("lighten")) 255 else 0
        fun channel(shift: Int): Int {
            val original=(argb ushr shift) and 255
            return (original*(100-amount)+target*amount+50)/100
        }
        return (argb and 0xff000000.toInt()) or (channel(16) shl 16) or
            (channel(8) shl 8) or channel(0)
    }

    fun draw(canvas: Canvas,paths: List<Subpath>,bounds: RectF,paint: Paint) {
        require(bounds.width()>0 && bounds.height()>0)
        for(spec in paths) {
            if(paint.style==Paint.Style.FILL && !spec.fill || paint.style==Paint.Style.STROKE && !spec.stroke) continue
            val sx=bounds.width()/spec.width
            val sy=bounds.height()/spec.height
            fun x(v: Double): Float = (bounds.left+v*sx).also {
                require(it.isFinite() && abs(it)<=1e7)
            }.toFloat()
            fun y(v: Double): Float = (bounds.top+v*sy).also {
                require(it.isFinite() && abs(it)<=1e7)
            }.toFloat()
            val path=Path()
            var cx=0.0; var cy=0.0; var startX=0.0; var startY=0.0
            for(command in spec.commands) {
                val p=command.args
                when(command.kind) {
                    "moveTo" -> { cx=p[0]; cy=p[1]; startX=cx; startY=cy; path.moveTo(x(cx),y(cy)) }
                    "lnTo" -> { cx=p[0]; cy=p[1]; path.lineTo(x(cx),y(cy)) }
                    "quadBezTo" -> { cx=p[2]; cy=p[3]; path.quadTo(x(p[0]),y(p[1]),x(cx),y(cy)) }
                    "cubicBezTo" -> { cx=p[4]; cy=p[5]; path.cubicTo(x(p[0]),y(p[1]),x(p[2]),y(p[3]),x(cx),y(cy)) }
                    "close" -> { path.close(); cx=startX; cy=startY }
                    "arcTo" -> {
                        val rx=p[0]; val ry=p[1]
                        val start=p[2]/60000
                        val sweep=p[3]/60000
                        val angle=ellipseAngle(rx,ry,start)
                        val ar=angle*PI/180
                        val ox=cx-rx*cos(ar); val oy=cy-ry*sin(ar)
                        val box=RectF(x(ox-rx),y(oy-ry),x(ox+rx),y(oy+ry))
                        var previous=angle
                        // <=90-degree ray steps avoid ambiguous full-circle or branch-cut sweeps.
                        val pieces=kotlin.math.ceil(abs(sweep)/90).toInt().coerceAtLeast(1)
                        for(i in 1..pieces) {
                            val next=ellipseAngle(rx,ry,start+sweep*i/pieces)
                            var delta=next-previous
                            if(sweep>0 && delta<0) delta+=360
                            if(sweep<0 && delta>0) delta-=360
                            if(sweep!=0.0) path.arcTo(box,previous.toFloat(),delta.toFloat(),false)
                            previous=next
                        }
                        val end=previous*PI/180
                        cx=ox+rx*cos(end); cy=oy+ry*sin(end)
                    }
                }
            }
            val facePaint=if(paint.style==Paint.Style.FILL && spec.fillMode!="norm") {
                if(paint.shader!=null) throw IOException("Non-solid shaded path fill unsupported")
                Paint(paint).apply { color=shadeColor(paint.color,spec.fillMode) }
            } else paint
            canvas.drawPath(path,facePaint)
        }
    }
}
