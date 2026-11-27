package com.veilframe.app.pdf.operations

import org.junit.Assert.assertThrows
import org.junit.Test

class PdfImageRasterBudgetTest {
    @Test fun validatesGeometryBeforeRasterAllocation() {
        PdfImageRasterBudget.requireFits(595f,842f,150)
        assertThrows(IllegalArgumentException::class.java) { PdfImageRasterBudget.requireFits(595f,842f,600) }
        assertThrows(IllegalArgumentException::class.java) { PdfImageRasterBudget.requireFits(Float.NaN,842f,150) }
        assertThrows(IllegalArgumentException::class.java) { PdfImageRasterBudget.requireFits(20_000f,10f,150) }
        assertThrows(IllegalArgumentException::class.java) { PdfImageRasterBudget.requireFits(595f,842f,0) }
    }
}
