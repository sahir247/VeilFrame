package com.veilframe.app.pdf.operations

import org.apache.poi.openxml4j.opc.PackagePart
import org.apache.poi.openxml4j.opc.TargetMode
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.xslf.usermodel.XSLFSheet
import org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape
import org.openxmlformats.schemas.presentationml.x2006.main.SldDocument
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Adapt the saved Microsoft diagram drawing to the existing native shape
 * renderer. Do not execute diagram layout algorithms, infer a drawing filename,
 * or resolve images through the slide's unrelated relationship namespace.
 */
internal object PptxSmartArtCache {
    private const val a="http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val p="http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val dgm="http://schemas.openxmlformats.org/drawingml/2006/diagram"
    private const val dsp="http://schemas.microsoft.com/office/drawing/2008/diagram"
    private const val r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val dataType="application/vnd.openxmlformats-officedocument.drawingml.diagramData+xml"
    private const val drawingType="application/vnd.ms-office.drawingml.diagramDrawing+xml"
    private const val drawingRelation="http://schemas.microsoft.com/office/2007/relationships/diagramDrawing"
    private fun elements(n: Node?): List<Element> = buildList {
        var c=n?.firstChild
        while(c!=null) {
            if(c is Element) {
                require(size<20000) { "SmartArt child budget exceeded" }
                add(c)
            }
            c=c.nextSibling
        }
    }
    private fun one(n: Node?,ns: String,name: String): Element? {
        val list=elements(n).filter { it.namespaceURI==ns && it.localName==name }
        require(list.size<=1) { "Ambiguous SmartArt element: "+name }
        return list.singleOrNull()
    }
    private fun required(n: Node?,ns: String,name: String)=one(n,ns,name)
        ?: throw IOException("Missing SmartArt element: "+name)
    private fun root(n: Node)=if(n is Document) n.documentElement else n
    private fun parse(xml: String): Document {
        require(!xml.contains("<!DOCTYPE")) { "SmartArt DTD forbidden" }
        val f=DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware=true
            isValidating=false
            for((feature,value) in listOf("http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false)) {
                try { setFeature(feature,value) } catch (_: javax.xml.parsers.ParserConfigurationException) {}
            }
        }
        return f.newDocumentBuilder().apply {
            setEntityResolver { _,_ -> throw org.xml.sax.SAXException("External SmartArt entities forbidden") }
        }.parse(InputSource(StringReader(xml)))
    }
    private fun read(part: PackagePart,limit: Long): Document =
        part.inputStream.use { parse(OpenXmlDocumentParser.XmlReadBudget(limit,limit).read(it)) }

    private fun graphicData(frame: Node): Element? =
        one(one(root(frame),a,"graphic"),a,"graphicData")
    fun isDiagram(frame: Node): Boolean=graphicData(frame)?.getAttribute("uri")==dgm
    internal fun dataRelationship(frame: Node): String {
        val data=graphicData(frame) ?: throw IOException("Missing SmartArt graphic data")
        if(data.getAttribute("uri")!=dgm) throw IOException("Not a SmartArt frame")
        val id=required(data,dgm,"relIds").getAttributeNS(r,"dm")
        require(id.isNotBlank() && id.length<=128) { "Invalid SmartArt data relationship" }
        return id
    }
    internal fun drawingRelationship(data: Document): String {
        val root=data.documentElement
        require(root.namespaceURI==dgm && root.localName=="dataModel") { "Invalid SmartArt data model" }
        val extension=elements(one(root,dgm,"extLst")).filter { it.namespaceURI==a && it.localName=="ext" }
            .mapNotNull { one(it,dsp,"dataModelExt") }.singleOrNull()
            ?: throw IOException("SmartArt has no unique saved layout cache")
        return extension.getAttribute("relId").also { require(it.isNotBlank() && it.length<=128) }
    }
    private fun related(owner: PackagePart,id: String,type: String,contentType: String): PackagePart {
        val rel=owner.getRelationship(id) ?: throw IOException("Missing SmartArt relationship: "+id)
        if(rel.targetMode!=TargetMode.INTERNAL || rel.relationshipType!=type)
            throw IOException("Invalid SmartArt internal relationship")
        val part=owner.getRelatedPart(rel) ?: throw IOException("Missing SmartArt part")
        if(part.contentType!=contentType) throw IOException("Invalid SmartArt part content type")
        return part
    }

    data class Loaded(val group: XSLFGroupShape,val cachePart: PackagePart,
        val mapping: PptxShapeTransform.Affine)
    private class CachedGroup(xml: CTGroupShape,sheet: XSLFSheet): XSLFGroupShape(xml,sheet)
    fun load(frame: Node,sheet: XSLFSheet,transform: PptxShapeTransform.Transform): Loaded {
        val owner=sheet.packagePart
        val model=related(owner,dataRelationship(frame),r+"/diagramData",dataType)
        val id=drawingRelationship(read(model,2L*1024*1024))
        // The presentation owns the diagramDrawing relationship; some writers
        // put it on the data part. Match the relationship type before selecting.
        val candidates=listOf(owner,model).filter { it.getRelationship(id)?.relationshipType==drawingRelation }
        if(candidates.size!=1) throw IOException("Missing or ambiguous SmartArt drawing relationship")
        val drawing=related(candidates.single(),id,drawingRelation,drawingType)
        val normalized=normalize(read(drawing,4L*1024*1024),transform.bounds)
        val group=CachedGroup(SldDocument.Factory.parse(normalized.document).sld.cSld.spTree,sheet)
        if(group.shapes.isEmpty()) throw IOException("Empty SmartArt saved layout")
        return Loaded(group,drawing,transform.matrix*normalized.mapping)
    }
    data class PaintStyle(val fill: Int?,val line: Int?,val stroke: PptxStrokeStyle.Spec?,
        val text: PptxTableStyle.TextStyle)
    fun paintStyle(shape: Node,sheet: XSLFSheet,scale: Float,theme: (String)->Int?,picture: Boolean): PaintStyle {
        val element=root(shape)
        val props=required(element,p,"spPr")
        val style=one(element,p,"style")
        val format=sheet.theme?.xmlObject?.themeElements?.fmtScheme
        fun fillLookup(index: Int): Node? {
            val list=if(index>1000) format?.bgFillStyleLst?.domNode else format?.fillStyleLst?.domNode
            return list?.let { PptxTableStyle.children(it).getOrNull(if(index>1000) index-1001 else index-1) }
        }
        fun lineLookup(index: Int): Node? {
            val list=format?.lnStyleLst
            return if(list!=null && index in 1..list.sizeOfLnArray()) list.getLnArray(index-1).domNode else null
        }
        if(elements(props).any { it.namespaceURI==a && it.localName in setOf("scene3d","sp3d","effectDag") } ||
            elements(one(props,a,"effectLst")).isNotEmpty())
            throw IOException("Unsupported SmartArt shape effects")
        val effectRef=one(style,a,"effectRef")
        val effectIndex=effectRef?.getAttribute("idx")?.toIntOrNull() ?: 0
        if(effectIndex!=0 && one(props,a,"effectLst")==null) {
            val effect=format?.effectStyleLst?.domNode?.let { PptxTableStyle.children(it).getOrNull(effectIndex-1) }
                ?: throw IOException("Unresolved SmartArt effect style")
            if(elements(effect).any { it.localName!="effectLst" || elements(it).isNotEmpty() })
                throw IOException("Unsupported SmartArt inherited effects")
        }
        val direct=PptxTableStyle.directFill(props)
        val inherited=if(direct==null && !picture) PptxTableStyle.inheritedFill(style,::fillLookup,theme) else null
        val fill=if(picture) null else when {
            direct!=null -> PptxTableStyle.solid(direct,theme)
            inherited!=null -> PptxTableStyle.solid(inherited.node,theme,inherited.placeholder)
            else -> null
        }
        val localLine=one(props,a,"ln")
        val localLineFill=PptxTableStyle.directFill(localLine)
        val base=if(localLineFill?.localName=="noFill") null else style?.let {
            PptxTableStyle.inheritedBorder(it,::lineLookup,theme)
        }
        val lineFill=localLineFill ?: PptxTableStyle.directFill(base?.node)
        val line=lineFill?.let { PptxTableStyle.solid(it,theme,if(localLineFill!=null) null else base?.placeholder) }
        val stroke=if(line!=null) PptxStrokeStyle.parse(localLine ?: requireNotNull(base).node,scale,scale,
            inherited=base?.node) else null
        val font=one(style,a,"fontRef")
        val color=font?.let { PptxTableStyle.children(it).singleOrNull() }?.let { PptxTableStyle.colorNode(it,theme) }
        val fontRef=font?.getAttribute("idx")?.takeIf { it in setOf("major","minor","none") }
        return PaintStyle(fill,line,stroke,PptxTableStyle.TextStyle(color=color,fontRef=fontRef))
    }

    data class Normalized(val document: Document,val mapping: PptxShapeTransform.Affine,val shapeCount: Int)
    internal fun normalize(source: Document,frame: PptxShapeTransform.Rect): Normalized {
        val original=source.documentElement
        require(original.namespaceURI==dsp && original.localName=="drawing") { "Invalid SmartArt drawing root" }
        val tree=required(original,dsp,"spTree")
        val out=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }.newDocumentBuilder().newDocument()
        fun add(parent: Node,ns: String,name: String): Element =
            out.createElementNS(ns,(if(ns==p) "p:" else "a:")+name).also { parent.appendChild(it) }
        fun copy(parent: Node,node: Element,ns: String,name: String): Element {
            val dest=add(parent,ns,name)
            for(i in 0 until node.attributes.length) {
                val at=node.attributes.item(i)
                if(at.namespaceURI!="http://www.w3.org/2000/xmlns/") dest.setAttributeNS(at.namespaceURI,at.nodeName,at.nodeValue)
            }
            var c=node.firstChild
            while(c!=null) { dest.appendChild(out.importNode(c,true)); c=c.nextSibling }
            return dest
        }
        val slide=add(out,p,"sld").apply {
            setAttributeNS("http://www.w3.org/2000/xmlns/","xmlns:p",p)
            setAttributeNS("http://www.w3.org/2000/xmlns/","xmlns:a",a)
            setAttributeNS("http://www.w3.org/2000/xmlns/","xmlns:r",r)
        }
        val target=add(add(slide,p,"cSld"),p,"spTree")
        var nextId=1
        var count=0
        fun nonVisual(parent: Element,group: Boolean,name: String,text: Boolean=false,hidden: Boolean=false) {
            val nv=add(parent,p,if(group) "nvGrpSpPr" else "nvSpPr")
            add(nv,p,"cNvPr").apply {
                setAttribute("id",(nextId++).toString()); setAttribute("name",name)
                if(hidden) setAttribute("hidden","1")
            }
            add(nv,p,if(group) "cNvGrpSpPr" else "cNvSpPr").apply { if(text) setAttribute("txBox","1") }
            add(nv,p,"nvPr")
        }
        nonVisual(target,true,"SmartArt cache")
        add(target,p,"grpSpPr")
        fun supportedGroupProperties(props: Element?) {
            if(elements(props).any { it.namespaceURI!=a || it.localName!="xfrm" })
                throw IOException("Unsupported saved SmartArt group effects or fill")
        }
        fun copyGroup(from: Element,to: Element,depth: Int) {
            require(depth<=32) { "SmartArt group nesting exceeds budget" }
            for(child in elements(from)) {
                if(child.namespaceURI!=dsp) throw IOException("Unsupported SmartArt drawing child namespace")
                when(child.localName) {
                    "nvGrpSpPr","grpSpPr" -> Unit
                    "grpSp" -> {
                        require(++count<=4096) { "SmartArt shape budget exceeded" }
                        val props=required(child,dsp,"grpSpPr")
                        supportedGroupProperties(props)
                        required(props,a,"xfrm")
                        val nested=add(to,p,"grpSp")
                        nonVisual(nested,true,"SmartArt group")
                        copy(nested,props,p,"grpSpPr")
                        copyGroup(child,nested,depth+1)
                    }
                    "sp" -> {
                        require(++count<=4096) { "SmartArt shape budget exceeded" }
                        val props=required(child,dsp,"spPr")
                        required(props,a,"xfrm")
                        if(elements(child).any { it.namespaceURI!=dsp || it.localName !in
                            setOf("nvSpPr","spPr","style","txBody","txXfrm") })
                            throw IOException("Unsupported saved SmartArt shape extension")
                        val nv=one(one(child,dsp,"nvSpPr"),dsp,"cNvPr")
                        if(nv?.getAttribute("hidden") in setOf("1","true")) continue
                        val name=nv?.getAttribute("name")?.take(256)?.ifBlank { "SmartArt shape" } ?: "SmartArt shape"
                        val style=one(child,dsp,"style")
                        val body=one(child,dsp,"txBody")
                        val textTransform=one(child,dsp,"txXfrm")
                        val shape=add(to,p,"sp")
                        nonVisual(shape,false,name)
                        copy(shape,props,p,"spPr")
                        style?.let { copy(shape,it,p,"style") }
                        if(body!=null && textTransform==null) copy(shape,body,p,"txBody")
                        else if(body!=null && textTransform!=null) {
                            require(++count<=4096) { "SmartArt text shape budget exceeded" }
                            val overlay=add(to,p,"sp")
                            nonVisual(overlay,false,name+" text",text=true)
                            val textProps=add(overlay,p,"spPr")
                            copy(textProps,textTransform,a,"xfrm")
                            add(textProps,a,"noFill")
                            add(add(textProps,a,"ln"),a,"noFill")
                            style?.let { copy(overlay,it,p,"style") }
                            copy(overlay,body,p,"txBody")
                        }
                    }
                    // Unknown extension content must not disappear silently.
                    else -> throw IOException("Unsupported saved SmartArt drawing element: "+child.localName)
                }
            }
        }
        val rootProps=one(tree,dsp,"grpSpPr")
        supportedGroupProperties(rootProps)
        val rootXfrm=one(rootProps,a,"xfrm")?.takeIf {
            elements(it).isNotEmpty() || it.attributes.length>0
        }
        val frameMapping=if(rootXfrm==null) PptxShapeTransform.Affine(tx=frame.x,ty=frame.y) else {
            fun number(n: Element,name: String)=n.getAttribute(name).toLongOrNull()?.toDouble()
                ?: throw IOException("Invalid SmartArt root coordinate")
            val off=required(rootXfrm,a,"off")
            val ext=required(rootXfrm,a,"ext")
            val box=PptxShapeTransform.Rect(number(off,"x"),number(off,"y"),number(ext,"cx"),number(ext,"cy"))
            val group=add(target,p,"grpSp")
            nonVisual(group,true,"SmartArt root coordinates")
            copy(group,requireNotNull(rootProps),p,"grpSpPr")
            copyGroup(tree,group,0)
            PptxShapeTransform.Affine(a=frame.width/box.width,d=frame.height/box.height,
                tx=frame.x-box.x*frame.width/box.width,ty=frame.y-box.y*frame.height/box.height)
        }
        if(rootXfrm==null) copyGroup(tree,target,0)
        require(count>0) { "SmartArt cache has no shapes" }
        return Normalized(out,frameMapping,count)
    }
}
