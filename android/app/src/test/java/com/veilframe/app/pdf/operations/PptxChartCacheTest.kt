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
}
