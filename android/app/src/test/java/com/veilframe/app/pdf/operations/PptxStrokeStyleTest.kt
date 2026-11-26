package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class PptxStrokeStyleTest {
    private fun line(attrs: String,body: String)=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
        .newDocumentBuilder().parse("""<a:ln xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" $attrs>$body</a:ln>""".byteInputStream()).documentElement

    @Test fun explicitWidthCapJoinAndCustomDashes() {
        val spec=PptxStrokeStyle.parse(line("""w="25400" cap="rnd" """,
            """<a:bevel/><a:custDash><a:ds d="200000" sp="50%"/></a:custDash>"""),2f,1f)
        assertEquals(4f,spec.width,0.001f)
        assertEquals("rnd",spec.cap)
        assertEquals("bevel",spec.join)
        assertEquals(listOf(8f,2f),spec.dash)
    }

    @Test fun presetSolidAndDashedUseActualWidth() {
        val dashed=PptxStrokeStyle.parse(line("""w="12700" ""","""<a:prstDash val="dash"/>"""),2f,1f)
        assertEquals(listOf(6f,8f),dashed.dash)
        assertTrue(PptxStrokeStyle.parse(line("","""<a:prstDash val="solid"/>"""),2f,1f).dash.isEmpty())
    }

    @Test fun refusesInvalidOrUnsupportedOutlines() {
        for(n in listOf(line("""w="-1" """,""),line("""cmpd="dbl" """,""),
            line("","""<a:custDash><a:ds d="0" sp="100000"/></a:custDash>"""),
            line("","""<a:headEnd type="triangle"/>""")))
            assertTrue(runCatching { PptxStrokeStyle.parse(n,1f,1f) }.isFailure)
    }
}
