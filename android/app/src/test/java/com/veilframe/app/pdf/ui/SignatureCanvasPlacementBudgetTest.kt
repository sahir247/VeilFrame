package com.veilframe.app.pdf.ui

import org.junit.Assert.*
import org.junit.Test

class SignatureCanvasPlacementBudgetTest {
    @Test fun preservesAspectAndFitsSmallPages() {
        val (w,h) = SignatureCanvasPlacementBudget.fit(1000,100,50f,50f)
        assertEquals(30f,w,0.001f)
        assertEquals(3f,h,0.001f)
        val (tw,th) = SignatureCanvasPlacementBudget.fit(100,1000,50f,50f)
        assertEquals(3f,tw,0.001f)
        assertEquals(30f,th,0.001f)
    }

    @Test fun boundsUniqueImagesAndPlacementCountIndependently() {
        val a = Any()
        val four = List(4) { Any() }
        assertTrue(SignatureCanvasPlacementBudget.admit(four, four.first()) { 4_000_000L })
        assertFalse(SignatureCanvasPlacementBudget.admit(four,a) { 4_000_000L })
        assertFalse(SignatureCanvasPlacementBudget.admit(List(256){a},a){1L})
        assertFalse(SignatureCanvasPlacementBudget.admit(emptyList(),a){4_000_001L})
    }
}
