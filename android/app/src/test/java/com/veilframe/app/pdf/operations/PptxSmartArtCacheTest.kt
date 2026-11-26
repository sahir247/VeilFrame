package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class PptxSmartArtCacheTest {
    private val p="http://schemas.openxmlformats.org/presentationml/2006/main"
    private val a="http://schemas.openxmlformats.org/drawingml/2006/main"
    private val dgm="http://schemas.openxmlformats.org/drawingml/2006/diagram"
    private val dsp="http://schemas.microsoft.com/office/drawing/2008/diagram"
    private val r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun xml(source: String)=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
        .newDocumentBuilder().parse(InputSource(StringReader(source)))
    @Test fun usesRelationshipIdentitiesRatherThanDrawingFilenameConvention() {
        val frame=xml("""<p:graphicFrame xmlns:p="$p" xmlns:a="$a" xmlns:dgm="$dgm" xmlns:r="$r"><a:graphic>
            <a:graphicData uri="$dgm"><dgm:relIds r:dm="rId9" r:lo="rId10" r:qs="rId11" r:cs="rId12"/></a:graphicData></a:graphic></p:graphicFrame>""")
        assertTrue(PptxSmartArtCache.isDiagram(frame))
        assertEquals("rId9",PptxSmartArtCache.dataRelationship(frame))
        val data=xml("""<dgm:dataModel xmlns:dgm="$dgm" xmlns:a="$a" xmlns:dsp="$dsp"><dgm:extLst>
            <a:ext uri="$dsp"><dsp:dataModelExt relId="rId42"/></a:ext></dgm:extLst></dgm:dataModel>""")
        assertEquals("rId42",PptxSmartArtCache.drawingRelationship(data))
        assertTrue(runCatching { PptxSmartArtCache.drawingRelationship(xml("""<dgm:dataModel xmlns:dgm="$dgm"/>""")) }.isFailure)
    }
    @Test fun nestedGroupAndSeparateTextTransformStayInSavedOrder() {
        fun xfrm()="""<a:xfrm><a:off x="0" y="0"/><a:ext cx="1000" cy="1000"/></a:xfrm>"""
        fun shape(name: String,text: Boolean=false)="""<dsp:sp modelId="$name"><dsp:nvSpPr><dsp:cNvPr id="1" name="$name"/><dsp:cNvSpPr/></dsp:nvSpPr>
            <dsp:spPr>${xfrm()}<a:prstGeom prst="rect"/><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></dsp:spPr>
            ${if(text) """<dsp:txBody><a:bodyPr/><a:p><a:r><a:t>Saved label</a:t></a:r></a:p></dsp:txBody>
            <dsp:txXfrm><a:off x="10" y="20"/><a:ext cx="900" cy="800"/></dsp:txXfrm>""" else ""}</dsp:sp>"""
        val source=xml("""<dsp:drawing xmlns:dsp="$dsp" xmlns:a="$a"><dsp:spTree><dsp:grpSpPr/>
            ${shape("first",true)}<dsp:grpSp><dsp:grpSpPr><a:xfrm><a:off x="100" y="200"/><a:ext cx="1000" cy="1000"/>
            <a:chOff x="0" y="0"/><a:chExt cx="1000" cy="1000"/></a:xfrm></dsp:grpSpPr>${shape("nested")}</dsp:grpSp>
            ${shape("last")}</dsp:spTree></dsp:drawing>""")
        val normalized=PptxSmartArtCache.normalize(source,PptxShapeTransform.Rect(300.0,400.0,3000.0,2000.0))
        assertEquals(5,normalized.shapeCount)
        assertEquals(300.0 to 400.0,normalized.mapping.point(0.0,0.0))
        val names=normalized.document.getElementsByTagNameNS(p,"cNvPr")
        assertEquals(listOf("SmartArt cache","first","first text","SmartArt group","nested","last"),
            (0 until names.length).map { (names.item(it) as org.w3c.dom.Element).getAttribute("name") })
        val shapes=normalized.document.getElementsByTagNameNS(p,"sp")
        assertEquals(4,shapes.length)
        val overlay=shapes.item(1)
        val transform=requireNotNull(PptxShapeTransform.shape(overlay))
        assertEquals(10.0,transform.bounds.x,0.0)
        assertEquals(20.0,transform.bounds.y,0.0)
        assertEquals("Saved label",(overlay as org.w3c.dom.Element).getElementsByTagNameNS(a,"t").item(0).textContent)
        val parsed=org.openxmlformats.schemas.presentationml.x2006.main.SldDocument.Factory.parse(normalized.document)
        assertEquals(3,parsed.sld.cSld.spTree.sizeOfSpArray())
        assertEquals(1,parsed.sld.cSld.spTree.sizeOfGrpSpArray())
    }
}
