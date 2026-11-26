package com.veilframe.app.pdf.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PptxPictureReferenceTest {
    private fun reference(xml: String) = PptxPictureRenderer.reference(
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
            .newDocumentBuilder().parse(xml.byteInputStream()))
    private fun fill(blip: String,extra: String = "") =
        """<p:blipFill xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:asvg="http://schemas.microsoft.com/office/drawing/2016/SVG/main">
            <a:blip $blip><a:extLst><a:ext><asvg:svgBlip r:embed="rId99"/></a:ext></a:extLst></a:blip>$extra</p:blipFill>"""

    @Test fun primaryEmbeddedRasterWinsOverSvgExtension() {
        val value = requireNotNull(reference(fill("""r:embed="rId17"""","""<a:srcRect l="25000" b="10%"/>""")))
        assertEquals("rId17",value.relationship)
        assertEquals(0.25f,value.crop.left,0.0001f)
        assertEquals(0.1f,value.crop.bottom,0.0001f)
    }

    @Test fun invalidOrExternalPicturesFailRatherThanGuess() {
        assertTrue(runCatching { reference(fill("""r:link="rId2"""")) }.isFailure)
        assertTrue(runCatching { reference(fill("""r:embed="rId2"""","""<a:srcRect l="75000" r="30000"/>""")) }.isFailure)
        assertTrue(runCatching { reference(fill("""r:embed="rId2"""","<a:tile/>")) }.isFailure)
    }
}
