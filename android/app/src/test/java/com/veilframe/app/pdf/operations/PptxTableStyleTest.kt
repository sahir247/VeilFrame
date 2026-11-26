package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import java.io.IOException

class PptxTableStyleTest {
    private val a="http://schemas.openxmlformats.org/drawingml/2006/main"
    private val p="http://schemas.openxmlformats.org/presentationml/2006/main"
    private fun xml(source: String)=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true }
        .newDocumentBuilder().parse(InputSource(StringReader(source))).documentElement

    @Test fun cornerAndBandRegionsApplyInDocumentedOrder() {
        val f=PptxTableStyle.Flags(true,true,true,true,true,true)
        assertEquals(listOf("wholeTbl","firstCol","firstRow","nwCell"),PptxTableStyle.regions(0,0,5,5,f))
        assertEquals(listOf("wholeTbl","band1H","band2V"),PptxTableStyle.regions(1,2,5,5,f))
        assertEquals(listOf("wholeTbl","band2H","band1V"),PptxTableStyle.regions(2,1,5,5,f))
        assertEquals(listOf("wholeTbl","lastCol","lastRow","seCell"),PptxTableStyle.regions(4,4,5,5,f))
        assertTrue(PptxTableStyle.priority("nwCell")>PptxTableStyle.priority("firstRow"))
    }

    @Test fun explicitStyleIdAndDefaultResolveButUnknownIdentityFails() {
        val id="{12345678-1234-1234-1234-123456789ABC}"
        val list=xml("""<a:tblStyleLst xmlns:a="$a" def="$id"><a:tblStyle styleId="$id"><a:wholeTbl/></a:tblStyle></a:tblStyleLst>""")
        val pr=xml("""<a:tblPr xmlns:a="$a"><a:tableStyleId>$id</a:tableStyleId></a:tblPr>""")
        assertEquals(id,PptxTableStyle.attr(PptxTableStyle.select(pr,list),"styleId"))
        assertEquals(id,PptxTableStyle.attr(PptxTableStyle.select(null,list),"styleId"))
        assertThrows(IOException::class.java) { PptxTableStyle.select(pr,null) }
        assertNull(PptxTableStyle.select(null,null))
    }

    @Test fun conditionalTextCascadesAndExplicitFalseAndColorWin() {
        val style=xml("""<a:tblStyle xmlns:a="$a"><a:wholeTbl><a:tcTxStyle b="on"><a:schemeClr val="tx1"/></a:tcTxStyle></a:wholeTbl>
            <a:firstRow><a:tcTxStyle i="on"/></a:firstRow><a:nwCell><a:tcTxStyle b="off"/></a:nwCell></a:tblStyle>""")
        val layers=PptxTableStyle.layers(style,0,0,3,3,PptxTableStyle.Flags(firstRow=true,firstCol=true))
        val base=PptxTableStyle.textStyle(layers) { 0xff123456.toInt() }
        assertEquals(PptxTableStyle.TextStyle(0xff123456.toInt(),false,true),base)
        val run=xml("""<a:r xmlns:a="$a"><a:rPr b="1" i="0"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></a:rPr></a:r>""")
        val resolved=PptxTableStyle.runStyle(base,null,run) { null }
        assertEquals(PptxTableStyle.TextStyle(0xffff0000.toInt(),true,false),resolved)
    }

    @Test fun themeFillReferencePreservesPlaceholderAlphaAndNoStyleIsExplicit() {
        val cell=xml("""<a:tcStyle xmlns:a="$a"><a:fillRef idx="1"><a:srgbClr val="204060"/></a:fillRef></a:tcStyle>""")
        val base=xml("""<a:solidFill xmlns:a="$a"><a:schemeClr val="phClr"><a:alpha val="50000"/></a:schemeClr></a:solidFill>""")
        val fill=requireNotNull(PptxTableStyle.inheritedFill(cell,{ if(it==1) base else null },{ null }))
        assertEquals(0x80204060.toInt(),PptxTableStyle.solid(fill.node,{ null },fill.placeholder))
        val none=xml("""<a:tcStyle xmlns:a="$a"><a:fillRef idx="0"><a:srgbClr val="FFFFFF"/></a:fillRef></a:tcStyle>""")
        val noFill=requireNotNull(PptxTableStyle.inheritedFill(none,{ null },{ null }))
        assertNull(PptxTableStyle.solid(noFill.node,{ null }))
    }

    @Test fun interiorAndPerimeterBordersAreDistinct() {
        val f=PptxTableStyle.Flags()
        assertEquals("insideH",PptxTableStyle.borderRole("wholeTbl","lnB",0,0,4,4,f))
        assertEquals("bottom",PptxTableStyle.borderRole("wholeTbl","lnB",3,0,4,4,f))
        assertEquals("insideV",PptxTableStyle.borderRole("wholeTbl","lnR",0,1,4,4,f))
        assertEquals("right",PptxTableStyle.borderRole("wholeTbl","lnR",0,3,4,4,f))
        assertEquals("bottom",PptxTableStyle.borderRole("band1H","lnB",1,2,4,4,f))
    }

    @Test fun actualColorMapOverridesMasterAndPreservesSystemColor() {
        val theme=xml("""<a:theme xmlns:a="$a"><a:themeElements><a:clrScheme><a:dk1><a:sysClr val="windowText" lastClr="112233"/></a:dk1>
            <a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:accent1><a:srgbClr val="AA0000"/></a:accent1></a:clrScheme></a:themeElements></a:theme>""")
        val master=xml("""<p:sldMaster xmlns:p="$p"><p:clrMap tx1="dk1"/></p:sldMaster>""")
        val slide=xml("""<p:sld xmlns:p="$p" xmlns:a="$a"><p:clrMapOvr><a:overrideClrMapping tx1="accent1"/></p:clrMapOvr></p:sld>""")
        assertEquals(0xff112233.toInt(),PptxTableStyle.themeColors(theme,null,null,master)["tx1"])
        assertEquals(0xffaa0000.toInt(),PptxTableStyle.themeColors(theme,slide,null,master)["tx1"])
    }
}
