package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test

class PptxChartCacheTest {
    private fun xml(kind: String): String = """<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart><c:plotArea>$kind</c:plotArea></c:chart></c:chartSpace>"""
    @Test fun slideCachePreservesSignedValuesAndCategoryOrder() {
        val series="""<c:ser><c:tx><c:v>Revenue</c:v></c:tx><c:cat><c:strLit><c:ptCount val="2"/><c:pt idx="0"><c:v>Alpha</c:v></c:pt><c:pt idx="1"><c:v>Beta</c:v></c:pt></c:strLit></c:cat><c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>-3</c:v></c:pt><c:pt idx="1"><c:v>7</c:v></c:pt></c:numLit></c:val></c:ser>"""
        val chart=OpenXmlDocumentParser.readPresentationChart(xml("""<c:barChart><c:barDir val="col"/><c:grouping val="clustered"/>$series</c:barChart>""").byteInputStream(),emptyMap())
        assertEquals("column",chart.kind)
        assertEquals(listOf("Alpha","Beta"),chart.categories)
        assertEquals(listOf(-3.0,7.0),chart.series.single().values)
    }
    @Test fun missingCachesAndUnsupportedChartTypesFailClosed() {
        for(kind in listOf("<c:radarChart/>","<c:lineChart/>"))
            assertTrue(runCatching { OpenXmlDocumentParser.readPresentationChart(xml(kind).byteInputStream(),emptyMap()) }.isFailure)
    }

    @Test fun dualAxisScaleHandlesReversedAxesPercentTicksAndIndependentZero() {
        val chart=XlsxCachedChart("Dual","combo",listOf("A","B"),listOf(
            XlsxCachedChart.Series("Volume",listOf(-500.0,1000.0),chartKind="column",valueAxisId="10"),
            XlsxCachedChart.Series("Rate",listOf(0.25,0.75),chartKind="line",valueAxisId="20")),
            valueAxes=listOf(XlsxCachedChart.ValueAxis("10","l",-1000.0,1000.0),
                XlsxCachedChart.ValueAxis("20","r",0.0,1.0,true,"0.0%")))
        val scales=SharedChartValueAxes.build(chart)
        assertEquals(0.25f,scales.getValue("10").fraction(-500.0),0.001f)
        assertEquals(0.5f,scales.getValue("10").zero(),0.001f)
        assertEquals(0.75f,scales.getValue("20").fraction(0.25),0.001f)
        assertEquals("100.0%",scales.getValue("20").tick(0f))
        assertEquals("0.0%",scales.getValue("20").tick(1f))
        assertFalse(SharedChartValueAxes.supportedFormat("0.0E+00"))
        assertThrows(java.io.IOException::class.java) {
            SharedChartValueAxes.build(chart.copy(series=listOf(chart.series.first())))
        }
    }

    @Test fun sharedChartInkPreservesNoFillMarkerOutlineDashAndPointOverrides() {
        val series="""<c:ser xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
            <c:spPr><a:ln cap="rnd" w="25400"><a:solidFill><a:srgbClr val="123456"/></a:solidFill><a:custDash><a:ds d="200000" sp="100000"/></a:custDash></a:ln></c:spPr>
            <c:marker><c:symbol val="circle"/><c:size val="8"/><c:spPr><a:noFill/><a:ln w="12700"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></a:ln></c:spPr></c:marker>
            <c:cat><c:strLit><c:ptCount val="2"/><c:pt idx="0"><c:v>A</c:v></c:pt><c:pt idx="1"><c:v>B</c:v></c:pt></c:strLit></c:cat>
            <c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>1</c:v></c:pt><c:pt idx="1"><c:v>2</c:v></c:pt></c:numLit></c:val></c:ser>"""
        val chart=OpenXmlDocumentParser.readPresentationChart(xml("<c:lineChart>$series</c:lineChart>").byteInputStream(),emptyMap())
        val s=chart.series.single()
        assertEquals(XlsxCachedChart.Ink(0x123456),s.stroke?.ink)
        assertEquals("rnd",s.stroke?.cap)
        assertArrayEquals(floatArrayOf(4f,2f),SharedChartInk.dashes(s.stroke,2f),0.001f)
        assertEquals(XlsxCachedChart.Ink(null),s.markerFill)
        assertEquals(XlsxCachedChart.Ink(0xff0000),s.markerStroke?.ink)
        assertNull(SharedChartInk.resolved(s.markerFill,0x123456))
        val bare=series.replace(Regex("<c:spPr>.*?</c:spPr>",RegexOption.DOT_MATCHES_ALL),"")
            .replace(Regex("<c:marker>.*?</c:marker>",RegexOption.DOT_MATCHES_ALL),"")
        val pie=bare.replace("<c:cat>","""<c:dPt><c:idx val="0"/><c:spPr><a:noFill/></c:spPr></c:dPt>
            <c:dPt><c:idx val="1"/><c:spPr><a:solidFill><a:schemeClr val="accent1"><a:shade val="50000"/></a:schemeClr></a:solidFill></c:spPr></c:dPt><c:cat>""")
        val parsed=OpenXmlDocumentParser.readPresentationChart(xml("<c:pieChart>$pie<c:firstSliceAng val=\"90\"/></c:pieChart>").byteInputStream(),mapOf("accent1" to 0x204060))
        assertEquals(90,parsed.firstSliceAngle)
        assertNull(SharedChartInk.pointFill(parsed.series.single(),0,0xffffff))
        assertEquals(0x102030,SharedChartInk.pointFill(parsed.series.single(),1,0xffffff))
        val invalid=pie.replace("idx val=\"1\"","idx val=\"0\"")
        assertTrue(runCatching { OpenXmlDocumentParser.readPresentationChart(xml("<c:pieChart>$invalid</c:pieChart>").byteInputStream(),mapOf("accent1" to 0x204060)) }.isFailure)
    }

    @Test fun translucentClosedChartInkAndPartialPointOutlineInheritance() {
        val series="""<c:ser xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
            <c:spPr><a:solidFill><a:srgbClr val="FF0000"><a:alpha val="80000"/><a:alphaMod val="50000"/><a:alphaOff val="10000"/></a:srgbClr></a:solidFill>
            <a:ln w="25400" cap="rnd"><a:solidFill><a:srgbClr val="0000FF"><a:alpha val="25000"/></a:srgbClr></a:solidFill><a:custDash><a:ds d="200000" sp="100000"/></a:custDash><a:bevel/></a:ln></c:spPr>
            <c:dPt><c:idx val="0"/><c:spPr><a:noFill/><a:ln w="38100"/></c:spPr></c:dPt>
            <c:dPt><c:idx val="1"/><c:spPr><a:ln><a:noFill/></a:ln></c:spPr></c:dPt>
            <c:cat><c:strLit><c:ptCount val="2"/><c:pt idx="0"><c:v>A</c:v></c:pt><c:pt idx="1"><c:v>B</c:v></c:pt></c:strLit></c:cat>
            <c:val><c:numLit><c:ptCount val="2"/><c:pt idx="0"><c:v>1</c:v></c:pt><c:pt idx="1"><c:v>2</c:v></c:pt></c:numLit></c:val></c:ser>"""
        val chart=OpenXmlDocumentParser.readPresentationChart(xml("<c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/>$series</c:barChart>").byteInputStream(),emptyMap())
        val s=chart.series.single()
        assertEquals(XlsxCachedChart.Ink(0xff0000,128),s.fill)
        assertNull(SharedChartInk.pointInk(s,0,0))
        val outline=SharedChartInk.pointStroke(s,0)!!
        assertEquals(XlsxCachedChart.Ink(0x0000ff,64),outline.ink)
        assertEquals(3f,outline.widthPt!!,0f)
        assertEquals("rnd",outline.cap)
        assertEquals("bevel",outline.join)
        assertArrayEquals(floatArrayOf(6f,3f),SharedChartInk.dashes(outline,3f),0.001f)
        assertNull(SharedChartInk.outlineInk(SharedChartInk.pointStroke(s,1),0))
        assertEquals(XlsxCachedChart.Ink(0xff0000,128),SharedChartInk.pointInk(s,1,0))
        val negative=series.replace("val=\"10000\"","val=\"-40000\"")
        val invisible=OpenXmlDocumentParser.readPresentationChart(xml("<c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/>$negative</c:barChart>").byteInputStream(),emptyMap())
        assertNull(SharedChartInk.pointInk(invisible.series.single(),1,0))
    }

    @Test fun logarithmicScaleUsesMultiplicativeSpacingAndRejectsZero() {
        val series="""<c:ser><c:cat><c:strLit><c:ptCount val="3"/><c:pt idx="0"><c:v>A</c:v></c:pt><c:pt idx="1"><c:v>B</c:v></c:pt><c:pt idx="2"><c:v>C</c:v></c:pt></c:strLit></c:cat><c:val><c:numLit><c:ptCount val="3"/><c:pt idx="0"><c:v>1</c:v></c:pt><c:pt idx="1"><c:v>10</c:v></c:pt><c:pt idx="2"><c:v>100</c:v></c:pt></c:numLit></c:val></c:ser>"""
        val source=xml("""<c:lineChart>$series<c:axId val="1"/><c:axId val="2"/></c:lineChart>
            <c:catAx><c:axId val="1"/></c:catAx><c:valAx><c:axId val="2"/><c:scaling><c:logBase val="10"/><c:min val="1"/><c:max val="100"/></c:scaling><c:axPos val="l"/><c:numFmt formatCode="0"/></c:valAx>""")
        val chart=OpenXmlDocumentParser.readPresentationChart(source.byteInputStream(),emptyMap())
        val scale=SharedChartValueAxes.build(chart).getValue("2")
        assertEquals(0f,scale.fraction(1.0),0.001f)
        assertEquals(0.5f,scale.fraction(10.0),0.001f)
        assertEquals(1f,scale.fraction(100.0),0.001f)
        assertEquals("10",scale.tick(0.5f))
        assertEquals(0f,scale.zero(),0.001f)
        assertTrue(runCatching { OpenXmlDocumentParser.readPresentationChart(source.replace("<c:v>1</c:v>","<c:v>0</c:v>").byteInputStream(),emptyMap()) }.isFailure)
        assertTrue(runCatching { OpenXmlDocumentParser.readPresentationChart(source.replace("catAx","dateAx").byteInputStream(),emptyMap()) }.isFailure)
    }
}
