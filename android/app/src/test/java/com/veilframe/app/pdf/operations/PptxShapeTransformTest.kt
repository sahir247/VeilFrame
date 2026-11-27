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
    @Test fun textBodyRotationCancelsShapeRotationWithoutChangingShape() {
        val shape = requireNotNull(PptxShapeTransform.shape(xml("""<p:spPr><a:xfrm rot="5400000"><a:off x="10" y="20"/><a:ext cx="40" cy="20"/></a:xfrm></p:spPr>""")))
        val frame = PptxShapeTransform.textFrame(shape.matrix,shape.bounds,
            PptxShapeTransform.TextBody(false,"horz"),-90.0)
        point(10.0,20.0,shape.matrix*frame.correction,10.0,20.0)
        point(50.0,40.0,shape.matrix*frame.correction,50.0,40.0)
    }

    @Test fun mirroredShapeKeepsReadableTextAndVerticalFlowSwapsAxes() {
        val shape = requireNotNull(PptxShapeTransform.shape(xml("""<p:spPr><a:xfrm flipH="1"><a:off x="10" y="20"/><a:ext cx="40" cy="20"/></a:xfrm></p:spPr>""")))
        val body = PptxShapeTransform.textBody(xml("""<p:txBody><a:bodyPr vert="vert270"/></p:txBody>"""))
        val frame = PptxShapeTransform.textFrame(shape.matrix,shape.bounds,body,0.0)
        assertEquals(20.0,frame.bounds.width,0.0001)
        assertEquals(40.0,frame.bounds.height,0.0001)
        val final = shape.matrix*frame.correction
        assertTrue(final.determinant>0)
        point(10.0,40.0,final,20.0,10.0)
        point(50.0,20.0,final,40.0,50.0)
    }

    @Test fun uprightTextCancelsRotationAndKeepsShapeCenter() {
        val shape = requireNotNull(PptxShapeTransform.shape(xml("""<p:spPr><a:xfrm rot="5400000"><a:off x="10" y="20"/><a:ext cx="40" cy="20"/></a:xfrm></p:spPr>""")))
        val body = PptxShapeTransform.textBody(xml("""<p:txBody><a:bodyPr upright="1"/></p:txBody>"""))
        val frame = PptxShapeTransform.textFrame(shape.matrix,shape.bounds,body,45.0)
        assertEquals(20.0,frame.bounds.width,0.0001)
        assertEquals(40.0,frame.bounds.height,0.0001)
        val final = shape.matrix*frame.correction
        point(30.0,30.0,final,30.0,30.0)
        point(20.0,10.0,final,20.0,10.0)
    }

    @Test fun verticalScriptModesPreserveColumnDirection() {
        val rect = PptxShapeTransform.Rect(0.0,0.0,30.0,40.0)
        for(mode in listOf("eaVert","mongolianVert")) {
            val frame=PptxShapeTransform.textFrame(PptxShapeTransform.Affine(),rect,
                PptxShapeTransform.TextBody(false,mode),0.0)
            assertTrue(frame.nativeVertical && !frame.stacked)
            assertEquals(mode=="eaVert",frame.columnsRtl)
            assertEquals(rect,frame.bounds)
        }
    }

    @Test fun shearedUprightFitsInsideOriginalParallelogram() {
        val rect = PptxShapeTransform.Rect(0.0,0.0,30.0,40.0)
        val shape = PptxShapeTransform.Affine(c=0.5,tx=7.0,ty=3.0)
        val frame=PptxShapeTransform.textFrame(shape,rect,PptxShapeTransform.TextBody(true,null),0.0)
        val final=shape*frame.correction
        assertEquals(0.0,final.b,0.0001)
        assertEquals(0.0,final.c,0.0001)
        assertTrue(final.a>0 && final.d>0)
        point(32.0,23.0,final,15.0,20.0)
        for(x in listOf(0.0,30.0)) for(y in listOf(0.0,40.0)) {
            val (px,py)=final.point(x,y)
            val (localX,localY)=shape.inverse().point(px,py)
            assertTrue(localX in -0.0001..30.0001 && localY in -0.0001..40.0001)
        }
    }

    @Test fun textBodyAttributesResolveIndependentlyIncludingExplicitFalse() {
        val local = mapOf("upright" to "0")
        val layout = mapOf("upright" to "1","vert" to "wordArtVertRtl")
        val master = mapOf("rot" to "5400000")
        val body = PptxShapeTransform.resolveTextBody { local[it] ?: layout[it] ?: master[it] }
        assertEquals(false,body.upright)
        assertEquals("wordArtVertRtl",body.direction)
        assertEquals(90.0,requireNotNull(body.rotation),0.0001)
        val frame = PptxShapeTransform.textFrame(PptxShapeTransform.Affine(),
            PptxShapeTransform.Rect(0.0,0.0,40.0,80.0),body,requireNotNull(body.rotation))
        assertTrue(frame.stacked && frame.columnsRtl)
        assertEquals(40.0,frame.bounds.width,0.0001)
    }

    @Test fun missingOrZeroChildExtentFailsWithoutInventingGeometry() {
        assertTrue(runCatching { PptxShapeTransform.group(xml("""<p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="10" cy="20"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="10"/></a:xfrm></p:grpSpPr>""")) }.isFailure)
    }
}
