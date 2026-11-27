package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException
import kotlin.math.abs

/** Generate only bounded, self-contained standard markup appearances.
 * Never replace an existing (even malformed) appearance or execute actions.
 */
internal object PdfAnnotationAppearance {
    private fun key(s: String)=COSName.getPDFName(s)
    private const val MAX_COORD=1_000_000f
    private const val MAX_NUMBERS=16_384
    private val quads=setOf("Highlight","Underline","StrikeOut","Squiggly")
    private val supported=quads+setOf("Ink","Square","Circle","Line","Polygon","PolyLine","FreeText","Text")
    private fun reject(message: String): Nothing=throw IOException(message)
    private fun number(value: Any?,description: String): Float {
        val n=(value as? COSNumber)?.floatValue() ?: reject("Invalid annotation "+description)
        if(!n.isFinite() || abs(n)>MAX_COORD) reject("Out-of-range annotation "+description)
        return n
    }
    private fun array(dict: COSDictionary,name: String): COSArray? {
        if(!dict.containsKey(key(name))) return null
        return dict.getDictionaryObject(key(name)) as? COSArray ?: reject("Invalid annotation "+name)
    }
    private fun numbers(a: COSArray,min: Int,max: Int,step: Int=1): List<Float> {
        if(a.size() !in min..max || a.size()%step!=0) reject("Invalid annotation coordinate array length")
        return (0 until a.size()).map { number(a.getObject(it),"array coordinate") }
    }
    private fun color(dict: COSDictionary,name: String): Boolean {
        val a=array(dict,name) ?: return false
        if(a.size() !in setOf(0,1,3,4)) reject("Invalid annotation color component count")
        return numbers(a,0,4).also {
            if(it.any { v -> v !in 0f..1f }) reject("Invalid annotation color component")
        }.isNotEmpty()
    }
    private fun scalar(dict: COSDictionary,name: String,default: Float,min: Float,max: Float): Float {
        if(!dict.containsKey(key(name))) return default
        return number(dict.getDictionaryObject(key(name)),name).also {
            if(it !in min..max) reject("Invalid annotation "+name)
        }
    }
    private fun bounds(rect: PDRectangle?): PDRectangle {
        if(rect==null || listOf(rect.lowerLeftX,rect.lowerLeftY,rect.upperRightX,rect.upperRightY)
                .any { !it.isFinite() || abs(it)>MAX_COORD } || rect.width<=0 || rect.height<=0)
            reject("Invalid annotation rectangle")
        return rect
    }
    private fun validate(annotation: PDAnnotation) {
        val d=annotation.cosObject
        val subtype=annotation.subtype
        if(subtype !in supported) reject("Unsupported annotation without appearance: "+subtype)
        val rect=bounds(annotation.rectangle)
        if(d.containsKey(key("Path")) || d.containsKey(key("Measure")))
            reject("Unsupported annotation curved path or measurement")
        val effect=if(d.containsKey(key("BE"))) d.getDictionaryObject(key("BE")) as? COSDictionary
            ?: reject("Invalid annotation border effect") else null
        if(effect!=null) {
            if(subtype !in setOf("Square","Circle") || effect.getNameAsString(key("S"),"S")!="C")
                reject("Unsupported annotation border effect")
            val intensity=scalar(effect,"I",0f,0f,2f)
            if(intensity<=0f || rect.width>2000f || rect.height>2000f)
                reject("Cloudy annotation exceeds synthesis budget")
        }
        scalar(d,"CA",1f,0f,1f).also { if(it==0f) reject("Zero-opacity annotation synthesis unsupported") }
        val stroke=color(d,"C")
        val fill=color(d,"IC")
        val bs=if(d.containsKey(key("BS"))) d.getDictionaryObject(key("BS")) as? COSDictionary
            ?: reject("Invalid annotation border style") else null
        val border=array(d,"Border")
        if(border!=null) {
            if(border.size() !in 3..4) reject("Invalid annotation border")
            for(i in 0..2) if(number(border.getObject(i),"border") !in 0f..1000f) reject("Invalid annotation border width")
            if(border.size()==4) {
                val dash=border.getObject(3) as? COSArray ?: reject("Invalid annotation dash")
                if(numbers(dash,1,64).any { it<=0 || it>1000 }) reject("Invalid annotation dash")
            }
        }
        val width=if(bs!=null) scalar(bs,"W",1f,0f,1000f) else if(border!=null) number(border.getObject(2),"border width") else 1f
        if(effect!=null && (width<0.1f || width>32f || !stroke || border?.size()==4 ||
                bs?.getNameAsString(key("S"),"S")=="D"))
            reject("Unsupported cloudy annotation stroke")
        if(bs!=null) {
            if(bs.getNameAsString(key("S"),"S") !in setOf("S","D")) reject("Unsupported annotation border style")
            array(bs,"D")?.let { if(numbers(it,1,64).any { n -> n<=0 || n>1000 }) reject("Invalid annotation dash") }
        }
        if(subtype=="FreeText") {
            if(listOf("RC","DS","CL","RD","DR","Rotate","LE").any { d.containsKey(key(it)) } ||
                d.getNameAsString(key("IT")) !in setOf(null,"FreeText") ||
                bs?.getNameAsString(key("S"),"S")=="D" || border?.size()==4)
                reject("Unsupported rich, rotated, callout or dashed FreeText annotation")
            if(width*4>=rect.width || width*4>=rect.height) reject("FreeText border exceeds rectangle")
        } else if(subtype=="Text") {
            if(!d.containsKey(key("F")) || d.getBoolean(key("Open"),false))
                reject("Open or view-dependent note icon cannot be flattened")
            if(d.getNameAsString(key("Name"),"Note") !in setOf("Note","Comment","Key","Help","NewParagraph",
                    "Paragraph","Insert","Circle","Cross","Star","Check","RightArrow","RightPointer","UpArrow","UpLeftArrow","CrossHairs"))
                reject("Unsupported note icon")
        } else if(subtype in quads) {
            if(!stroke) reject("Missing text markup color")
            val points=numbers(array(d,"QuadPoints") ?: reject("Missing annotation QuadPoints"),8,MAX_NUMBERS,8)
            for(q in points.chunked(8)) {
                // PDFBox expects the Acrobat Z-order: top-left/top-right,
                // bottom-left/bottom-right. Reject non-parallel or collapsed edges.
                val dx=q[2]-q[0]; val dy=q[3]-q[1]
                val ex=q[6]-q[4]; val ey=q[7]-q[5]
                val cross=dx*(q[5]-q[1])-dy*(q[4]-q[0])
                if(abs(cross)<0.001f || abs(dx-ex)>0.1f || abs(dy-ey)>0.1f)
                    reject("Unsupported or degenerate annotation quadrilateral")
            }
        } else if(subtype=="Square" || subtype=="Circle") {
            if(!fill && (!stroke || width<=0)) reject("Annotation has no visible paint")
            val rd=array(d,"RD")?.let { numbers(it,4,4) }
            if(rd!=null && (rd.any { it<0 } || rd[0]+rd[2]>=rect.width || rd[1]+rd[3]>=rect.height))
                reject("Invalid annotation rectangle differences")
            if(stroke && (width>=rect.width || width>=rect.height)) reject("Annotation border exceeds rectangle")
        } else {
            if(!stroke || width<=0) reject("Missing annotation stroke")
            when(subtype) {
                "Ink" -> {
                    val paths=array(d,"InkList") ?: reject("Missing InkList")
                    if(paths.size() !in 1..512) reject("Invalid annotation ink path count")
                    var count=0
                    for(i in 0 until paths.size()) {
                        val points=numbers(paths.getObject(i) as? COSArray ?: reject("Invalid ink path"),4,MAX_NUMBERS,2)
                        count+=points.size
                        if(count>MAX_NUMBERS || points.chunked(2).distinct().size<2) reject("Invalid annotation ink geometry")
                    }
                }
                "Line" -> {
                    val points=numbers(array(d,"L") ?: reject("Missing line endpoints"),4,4)
                    if(points.take(2)==points.drop(2)) reject("Degenerate annotation line")
                    // Captions require fonts and layout; leader lines are a
                    // separate geometry contract.
                    if(d.getBoolean(key("Cap"),false) || listOf("LL","LLE","LLO").any { scalar(d,it,0f,-1000f,1000f)!=0f })
                        reject("Unsupported annotation line caption or leader")
                }
                else -> {
                    val points=numbers(array(d,"Vertices") ?: reject("Missing annotation vertices"),if(subtype=="Polygon") 6 else 4,MAX_NUMBERS,2)
                    if(points.chunked(2).distinct().size<(if(subtype=="Polygon") 3 else 2)) reject("Degenerate annotation vertices")
                }
            }
            val endings=array(d,"LE")
            if(endings!=null && (endings.size()!=2 || (0..1).any {
                    (endings.getObject(it) as? COSName)?.name !in setOf("None","Square","Circle","Diamond","OpenArrow","ClosedArrow","Butt","ROpenArrow","RClosedArrow","Slash")
                })) reject("Unsupported annotation line ending")
        }
    }
    fun resolve(document: PDDocument,annotation: PDAnnotation): PDAppearanceStream {
        annotation.normalAppearanceStream?.let { return it }
        val d=annotation.cosObject
        if(d.containsKey(key("AP"))) reject("Invalid existing annotation appearance; cannot regenerate")
        validate(annotation)
        // The pinned PDFBox handlers generate native vector appearances and
        // may expand /Rect to enclose ink. They log some I/O errors internally.
        if(annotation.subtype=="FreeText") PdfPlainFreeTextAppearance.generate(document,annotation)
        else annotation.constructAppearances(document)
        if(annotation.isNoZoom || annotation.isNoRotate) reject("Generated appearance has view-dependent geometry")
        val appearance=annotation.normalAppearanceStream ?: reject("Annotation appearance generation failed")
        bounds(annotation.rectangle)
        bounds(appearance.bBox)
        verifyGeneratedInk(appearance)
        return appearance
    }

    /** A Do alone is not evidence of visible ink: PDFBox highlights use
     * nested transparency forms, and their handlers can log I/O failures.
     * Only inspect the fresh, internally generated resource graph. */
    private fun verifyGeneratedInk(appearance: PDAppearanceStream) {
        val visited=java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<com.tom_roush.pdfbox.cos.COSStream,Boolean>())
        var totalTokens=0
        fun verify(form: com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject,depth: Int): Boolean {
            if(depth>8 || visited.size>=32 || !visited.add(form.cosObject))
                reject("Invalid generated annotation form graph")
            val parser=PDFStreamParser(form)
            parser.parse()
            val tokens=parser.tokens
            totalTokens+=tokens.size
            if(totalTokens>200_000) reject("Generated annotation appearance exceeds token budget")
            var paints=false
            for((index,token) in tokens.withIndex()) {
                if(token !is Operator) continue
                when(token.name) {
                    "S","s","f","F","f*","B","B*","b","b*" -> paints=true
                    "Tj" -> if((tokens.getOrNull(index-1) as? com.tom_roush.pdfbox.cos.COSString)?.bytes?.isNotEmpty()==true) paints=true
                    "Do" -> {
                        val name=tokens.getOrNull(index-1) as? COSName ?: reject("Invalid generated form operand")
                        val child=form.resources?.getXObject(name) as? com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
                            ?: reject("Missing generated annotation subform")
                        if(!verify(child,depth+1)) reject("Empty generated annotation subform")
                        paints=true
                    }
                }
            }
            return paints
        }
        if(!verify(appearance,0)) reject("Generated annotation appearance contains no painting operation")
    }

    /** Compute placement before writing any page operators. */
    fun placement(rect: PDRectangle?,appearance: PDAppearanceStream): Matrix {
        val r=bounds(rect); val box=bounds(appearance.bBox); val m=appearance.matrix
        val points=listOf(m.transformPoint(box.lowerLeftX,box.lowerLeftY),
            m.transformPoint(box.lowerLeftX,box.upperRightY),m.transformPoint(box.upperRightX,box.lowerLeftY),
            m.transformPoint(box.upperRightX,box.upperRightY))
        if(points.any { !it.x.isFinite() || !it.y.isFinite() }) reject("Invalid annotation appearance matrix")
        val minX=points.minOf { it.x }; val minY=points.minOf { it.y }
        val w=points.maxOf { it.x }-minX; val h=points.maxOf { it.y }-minY
        if(w<=0 || h<=0 || !w.isFinite() || !h.isFinite()) reject("Invalid annotation appearance transform")
        val sx=r.width/w; val sy=r.height/h
        val tx=r.lowerLeftX-minX*sx; val ty=r.lowerLeftY-minY*sy
        if(listOf(sx,sy,tx,ty).any { !it.isFinite() } || sx<=0 || sy<=0) reject("Invalid annotation placement")
        return Matrix(sx,0f,0f,sy,tx,ty)
    }
}
