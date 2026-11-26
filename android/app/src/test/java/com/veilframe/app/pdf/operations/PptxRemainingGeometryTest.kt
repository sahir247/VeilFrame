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
}
