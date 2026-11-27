package com.veilframe.app.pdf.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PptxTableRendererTest {
    @Test fun mergedCellsOccupyGridOnce() {
        val plan=PptxTableRenderer.grid(2,3) { r,c ->
            if(c==0 && r==0) PptxTableRenderer.Span(2,2)
            else if(c<2) PptxTableRenderer.Span(covered=true)
            else PptxTableRenderer.Span()
        }
        assertEquals(listOf(PptxTableRenderer.Cell(0,0,2,2),PptxTableRenderer.Cell(0,2,1,1),
            PptxTableRenderer.Cell(1,2,1,1)),plan)
    }

    @Test fun malformedAndOverlappingMergesFail() {
        assertTrue(runCatching { PptxTableRenderer.grid(1,2) { _,_ -> PptxTableRenderer.Span(1,2) } }.isFailure)
        assertTrue(runCatching { PptxTableRenderer.grid(1,1) { _,_ -> PptxTableRenderer.Span(covered=true) } }.isFailure)
        assertTrue(runCatching { PptxTableRenderer.grid(101,100) { _,_ -> PptxTableRenderer.Span() } }.isFailure)
    }

    @Test fun proportionalGridPreservesSavedRowAndColumnSizes() {
        val edges=PptxTableRenderer.edges(listOf(10.0,20.0,30.0),15f,120f)
        assertEquals(listOf(15f,35f,75f,135f),edges.toList())
        assertTrue(runCatching { PptxTableRenderer.edges(listOf(10.0,0.0),0f,100f) }.isFailure)
    }
}
