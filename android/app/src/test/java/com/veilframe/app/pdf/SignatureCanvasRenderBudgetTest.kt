package com.veilframe.app.pdf

import com.veilframe.app.pdf.ui.SignatureCanvasRenderBudget
import org.junit.Assert.*
import org.junit.Test

class SignatureCanvasRenderBudgetTest {
    @Test fun normalPageKeepsExistingPreviewScale() {
        assertEquals(952 to 1347,SignatureCanvasRenderBudget.dimensions(595,842))
    }

    @Test fun hostilePageGeometryCannotTriggerUnboundedBitmapAllocation() {
        for ((w,h) in listOf(50000 to 50000,Int.MAX_VALUE to 1,1 to Int.MAX_VALUE,100000 to 400)) {
            val (bw,bh) = SignatureCanvasRenderBudget.dimensions(w,h)
            assertTrue(bw in 1..4096 && bh in 1..4096)
            assertTrue(bw.toLong()*bh <= 4_000_000)
        }
        assertThrows(IllegalArgumentException::class.java) { SignatureCanvasRenderBudget.dimensions(0,100) }
    }
}
