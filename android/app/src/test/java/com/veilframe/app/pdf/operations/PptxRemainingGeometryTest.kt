package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class PptxRemainingGeometryTest {
    @Test fun solidFaceShadingPreservesAlphaAndUsesIndependentBaseColor() {
        val base=0x806496c8.toInt()
        assertEquals(base,PptxCustomGeometry.shadeColor(base,"norm"))
        assertEquals(0x8083abd3.toInt(),PptxCustomGeometry.shadeColor(base,"lightenLess"))
        assertEquals(0x80a2c0de.toInt(),PptxCustomGeometry.shadeColor(base,"lighten"))
        assertEquals(0x805078a0.toInt(),PptxCustomGeometry.shadeColor(base,"darkenLess"))
        assertEquals(0x803c5a78.toInt(),PptxCustomGeometry.shadeColor(base,"darken"))
    }

    @Test fun shadedGeometryRetainsEachFaceAndStrokeFlag() {
        val xml="""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:spPr><a:custGeom>
            <a:pathLst><a:path w="100" h="100" fill="lighten" stroke="0">
            <a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="100" y="100"/></a:lnTo>
            </a:path><a:path w="100" h="100" fill="none">
            <a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="100" y="0"/></a:lnTo>
            </a:path></a:pathLst></a:custGeom></p:spPr></p:sp>"""
        val document=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
            .newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val paths=requireNotNull(PptxCustomGeometry.parse(document))
        assertEquals(listOf("lighten","none"),paths.map { it.fillMode })
        assertTrue(paths[0].fill)
        assertFalse(paths[0].stroke)
        assertFalse(paths[1].fill)
        assertTrue(paths[1].stroke)
    }

    @Test fun verticalIndentOnlyAppliesToFirstColumnAndCannotEscapeFrame() {
        assertEquals(25f to 65f,PptxVerticalText.inlineBounds(100f,10f,10f,15f,true))
        assertEquals(10f to 80f,PptxVerticalText.inlineBounds(100f,10f,10f,15f,false))
        assertEquals(0f to 90f,PptxVerticalText.inlineBounds(100f,10f,10f,-10f,true))
        assertThrows(OfficeTextEncodingException::class.java) {
            PptxVerticalText.inlineBounds(100f,10f,10f,-11f,true)
        }
        assertThrows(OfficeTextEncodingException::class.java) {
            PptxVerticalText.inlineBounds(100f,10f,10f,90f,true)
        }
    }

    @Test fun sharedExplicitEdgesRespectAnchorAndNoFill() {
        assertEquals("left",PptxTableRenderer.sharedBorder("left","right",true,true))
        assertEquals("noFill",PptxTableRenderer.sharedBorder("noFill","visible",true,true))
        assertEquals("right",PptxTableRenderer.sharedBorder("merged","right",false,true))
        assertEquals("merged",PptxTableRenderer.sharedBorder("merged",null,false,false))
        assertNull(PptxTableRenderer.sharedBorder<String>(null,null,true,true))
    }

    private fun xml(source: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
        .newDocumentBuilder().parse(InputSource(StringReader(source)))

    @Test fun straightConnectorsAdmitZeroAxisButNotZeroLengthOrAreaShapes() {
        val source="""<p:cxnSp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:spPr>
            <a:xfrm flipH="1"><a:off x="100" y="200"/><a:ext cx="400" cy="0"/></a:xfrm>
            <a:prstGeom prst="line"/></p:spPr></p:cxnSp>"""
        val root=xml(source)
        assertTrue(PptxConnectorRenderer.isStraight(root))
        val transform=requireNotNull(PptxShapeTransform.shape(root,true))
        assertEquals(500.0 to 200.0,transform.matrix.point(100.0,200.0))
        assertEquals(100.0 to 200.0,transform.matrix.point(500.0,200.0))
        assertThrows(IllegalArgumentException::class.java) { PptxShapeTransform.shape(root) }
        assertThrows(IllegalArgumentException::class.java) {
            PptxShapeTransform.shape(xml(source.replace("cx=\"400\"","cx=\"0\"")),true)
        }
    }

    @Test fun connectorStyleInheritsThemeAndResolvesDirectOverridesAndPlaceholderColor() {
        val a="http://schemas.openxmlformats.org/drawingml/2006/main"
        val p="http://schemas.openxmlformats.org/presentationml/2006/main"
        val base=xml("""<a:ln xmlns:a="$a" w="38100" cap="rnd" cmpd="dbl">
            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:headEnd type="diamond" w="lg" len="sm"/>
            </a:ln>""").documentElement
        val source="""<p:cxnSp xmlns:p="$p" xmlns:a="$a"><p:spPr><a:prstGeom prst="line"/>
            <a:ln w="50800"><a:tailEnd type="triangle" len="lg"/></a:ln></p:spPr>
            <p:style><a:lnRef idx="1"><a:srgbClr val="ABCDEF"/></a:lnRef></p:style></p:cxnSp>"""
        fun resolve(value: String)=PptxConnectorRenderer.resolve(xml(value),2f,1f,
            { idx -> if(idx==1) base else null },{ null })
        val result=requireNotNull(resolve(source))
        assertEquals(8f,result.spec.width,0.001f)
        assertEquals("rnd",result.spec.cap)
        assertEquals("dbl",result.spec.compound)
        assertEquals(PptxStrokeStyle.End("diamond","lg","sm"),result.spec.head)
        assertEquals(PptxStrokeStyle.End("triangle","med","lg"),result.spec.tail)
        assertEquals(0xffabcdef.toInt(),result.color)
        assertNull(resolve(source.replace("<a:tailEnd","<a:noFill/><a:tailEnd")))
        assertThrows(java.io.IOException::class.java) { resolve(source.replace("idx=\"1\"","idx=\"2\"")) }
    }

    @Test fun compoundBandsStayInsideEnvelopeWithoutOverlappingAndArrowDimensionsAreIndependent() {
        for(kind in listOf("sng","dbl","thickThin","thinThick","tri")) {
            val bands=PptxConnectorRenderer.bands(kind)
            for(band in bands) {
                assertTrue(band.fraction>0)
                assertTrue(band.center-band.fraction/2>=-0.50001f)
                assertTrue(band.center+band.fraction/2<=0.50001f)
            }
            for((first,next) in bands.zipWithNext())
                assertTrue(first.center+first.fraction/2 < next.center-next.fraction/2)
        }
        assertEquals(2f,PptxConnectorRenderer.multiplier("sm"),0f)
        assertEquals(3f,PptxConnectorRenderer.multiplier("med"),0f)
        assertEquals(5f,PptxConnectorRenderer.multiplier("lg"),0f)
        assertEquals(0f,PptxConnectorRenderer.trim("arrow",20f),0f)
        assertEquals(13f,PptxConnectorRenderer.trim("stealth",20f),0.001f)
    }

    @Test fun compressedColumnFlowKeepsInkEnvelopeAndParagraphSpacing() {
        val flow=PptxVerticalText.ColumnFlow(100f)
        assertEquals(5f,flow.admit(20f,5f,-10f),0f)
        assertEquals(15f,flow.cursor,0f)
        assertEquals(25f,flow.extent,0f)
        assertEquals(15f,flow.admit(30f,0f,6f),0f)
        assertEquals(51f,flow.extent,0f)
        assertThrows(OfficeTextEncodingException::class.java) { flow.admit(60f,0f,-30f) }
        assertEquals(51f,flow.cursor,0f)
        assertEquals(51f,flow.extent,0f)
    }

    @Test fun sharedLuminancePreservesHueAndSaturationAndClamps() {
        val rgb=doubleArrayOf(22/255.0,170/255.0,68/255.0)
        val dark=DrawingMlColorMath.lightness(rgb) { it/2 }
        assertArrayEquals(doubleArrayOf(11/255.0,85/255.0,34/255.0),dark,1e-9)
        assertArrayEquals(doubleArrayOf(1.0,1.0,1.0),DrawingMlColorMath.lightness(rgb) { it+1 },1e-9)
        assertArrayEquals(doubleArrayOf(0.0,0.0,0.0),DrawingMlColorMath.lightness(rgb) { 0.0 },1e-9)
        assertArrayEquals(doubleArrayOf(0.6,0.6,0.6),DrawingMlColorMath.lightness(doubleArrayOf(0.4,0.4,0.4)) { it+0.2 },1e-9)
    }

    @Test fun centeredAndInsetCompoundEnvelopesRetainGaps() {
        val spec=PptxStrokeStyle.Spec(12f,compound="dbl")
        val centered=PptxClosedOutline.intervals(spec)
        assertEquals(-6f,centered[0].low,0.001f)
        assertEquals(-2f,centered[0].high,0.001f)
        assertEquals(2f,centered[1].low,0.001f)
        assertEquals(6f,centered[1].high,0.001f)
        val inset=PptxClosedOutline.intervals(spec.copy(alignment="in"))
        assertEquals(0f,inset[0].low,0.001f)
        assertEquals(4f,inset[0].high,0.001f)
        assertEquals(8f,inset[1].low,0.001f)
        assertEquals(12f,inset[1].high,0.001f)
    }
}
