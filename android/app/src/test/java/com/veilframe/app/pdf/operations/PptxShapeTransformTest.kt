package com.veilframe.app.pdf.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PptxShapeTransformTest {
    private fun xml(body: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
        .newDocumentBuilder().parse("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">$body</p:sp>""".byteInputStream())
    private fun point(expectedX: Double,expectedY: Double,m: PptxShapeTransform.Affine,x: Double,y: Double) {
        val (px,py) = m.point(x,y)
        assertEquals(expectedX,px,0.0001)
        assertEquals(expectedY,py,0.0001)
    }
    @Test fun shapeRotatesClockwiseAfterHorizontalFlip() {
        val shape = requireNotNull(PptxShapeTransform.shape(xml("""<p:spPr><a:xfrm rot="5400000" flipH="1"><a:off x="10" y="20"/><a:ext cx="40" cy="20"/></a:xfrm></p:spPr>""")))
        point(40.0,50.0,shape.matrix,10.0,20.0)
        point(20.0,10.0,shape.matrix,50.0,40.0)
    }
    @Test fun nestedGroupsComposeChildOffsetScaleFlipAndRotation() {
        val inner = PptxShapeTransform.group(xml("""<p:grpSpPr><a:xfrm flipV="1"><a:off x="10" y="20"/><a:ext cx="40" cy="60"/><a:chOff x="100" y="200"/><a:chExt cx="20" cy="20"/></a:xfrm></p:grpSpPr>"""))
        val outer = PptxShapeTransform.group(xml("""<p:grpSpPr><a:xfrm rot="5400000"><a:off x="200" y="300"/><a:ext cx="80" cy="120"/><a:chOff x="10" y="20"/><a:chExt cx="40" cy="60"/></a:xfrm></p:grpSpPr>"""))
        point(180.0,320.0,outer*inner,100.0,200.0)
        point(300.0,400.0,outer*inner,120.0,220.0)
    }
    @Test fun missingOrZeroChildExtentFailsWithoutInventingGeometry() {
        assertTrue(runCatching { PptxShapeTransform.group(xml("""<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="10" cy="20"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="10"/></a:xfrm></p:grpSpPr>""")) }.isFailure)
    }
}
