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

    @Test fun ellipticalRayAngleDiffersFromParametricAngle() {
        assertEquals(63.4349488,PptxCustomGeometry.ellipseAngle(2.0,1.0,45.0),1e-6)
    }
}
