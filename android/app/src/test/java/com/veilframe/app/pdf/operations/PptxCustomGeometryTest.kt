package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PptxCustomGeometryTest {
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
        .newDocumentBuilder().parse("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:spPr><a:custGeom><a:pathLst>$path</a:pathLst></a:custGeom></p:spPr></p:sp>""".byteInputStream())
    @Test fun preservesIndividualPathCoordinateSpacesAndFlags() {
        val paths=requireNotNull(PptxCustomGeometry.parse(xml("""<a:path w="100" h="200" fill="none" stroke="1"><a:moveTo><a:pt x="10" y="20"/></a:moveTo><a:quadBezTo><a:pt x="30" y="40"/><a:pt x="50" y="60"/></a:quadBezTo></a:path><a:path w="10" h="10" stroke="0"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="10" y="10"/></a:lnTo><a:close/></a:path>""")))
        assertEquals(2,paths.size)
        assertFalse(paths[0].fill); assertTrue(paths[0].stroke)
        assertEquals(listOf(30.0,40.0,50.0,60.0),paths[0].commands[1].args)
        assertTrue(paths[1].fill); assertFalse(paths[1].stroke)
    }
    @Test fun rejectsUnknownGuideAndMissingMove() {
        for(path in listOf("""<a:path w="10" h="10"><a:lnTo><a:pt x="0" y="0"/></a:lnTo></a:path>""",
            """<a:path w="10" h="10"><a:moveTo><a:pt x="adj1" y="0"/></a:moveTo><a:lnTo><a:pt x="0" y="0"/></a:lnTo></a:path>"""))
            assertTrue(runCatching { PptxCustomGeometry.parse(xml(path)) }.isFailure)
    }
    @Test fun guidesUseOfficeAngleAndSqrtRulesAndRejectInvalidReferences() {
        val guides=DrawingMlGuides(200.0,100.0,listOf("adjust" to "val 25","pos" to "*/ w adjust 100",
            "angle" to "at2 -1 1","root" to "sqrt -16","len" to "mod 3 4 12"))
        assertEquals(50.0,guides.value("pos"),1e-6)
        assertEquals(8100000.0,guides.value("angle"),1e-6)
        assertEquals(4.0,guides.value("root"),1e-6)
        assertEquals(13.0,guides.value("len"),1e-6)
        assertTrue(runCatching { DrawingMlGuides(10.0,10.0,listOf("a" to "*/ 1 2 0")) }.isFailure)
        assertTrue(runCatching { DrawingMlGuides(10.0,10.0,listOf("a" to "val a")) }.isFailure)
    }

    @Test fun bundledPresetUsesSavedAdjustmentWithoutJava2D() {
        fun shape(adj: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
            .newDocumentBuilder().parse("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="20000" cy="10000"/></a:xfrm><a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val $adj"/></a:avLst></a:prstGeom></p:spPr></p:sp>""".byteInputStream())
        val a=requireNotNull(PptxCustomGeometry.parse(shape("10000"),includePresets=true))
        val b=requireNotNull(PptxCustomGeometry.parse(shape("30000"),includePresets=true))
        assertTrue(a.any { path -> path.commands.any { it.kind=="arcTo" } })
        assertNotEquals(a,b)
    }

    @Test fun textRectangleUsesShapeCoordinatesNotPathCoordinates() {
        val doc=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
            .newDocumentBuilder().parse("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="20000" cy="10000"/></a:xfrm><a:custGeom><a:gdLst><a:gd name="leftInset" fmla="*/ w 1 4"/></a:gdLst><a:rect l="leftInset" t="hd4" r="w" b="h"/><a:pathLst><a:path w="100" h="100"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="100" y="100"/></a:lnTo></a:path></a:pathLst></a:custGeom></p:spPr></p:sp>""".byteInputStream())
        val bounds=requireNotNull(PptxCustomGeometry.textBounds(doc,20000.0,10000.0))
        assertEquals(PptxCustomGeometry.TextBounds(0.25,0.25,1.0,1.0),bounds)
        assertArrayEquals(floatArrayOf(60f,45f,150f,75f),bounds.mapped(10f,20f,200f,100f),0.001f)
    }

    @Test fun adjustedRoundRectTextInsetChangesWithAdjustment() {
        fun doc(adj: String)=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
            .newDocumentBuilder().parse("""<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:spPr><a:prstGeom prst="roundRect"><a:avLst><a:gd name="adj" fmla="val $adj"/></a:avLst></a:prstGeom></p:spPr></p:sp>""".byteInputStream())
        val a=requireNotNull(PptxCustomGeometry.textBounds(doc("10000"),20000.0,10000.0))
        val b=requireNotNull(PptxCustomGeometry.textBounds(doc("30000"),20000.0,10000.0))
        assertTrue(b.left>a.left && b.top>a.top && b.right<a.right && b.bottom<a.bottom)
    }

    @Test fun ellipticalRayAngleDiffersFromParametricAngle() {
        assertEquals(63.4349488,PptxCustomGeometry.ellipseAngle(2.0,1.0,45.0),1e-6)
    }
}
