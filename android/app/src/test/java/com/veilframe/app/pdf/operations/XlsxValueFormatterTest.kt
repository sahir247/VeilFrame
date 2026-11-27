package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test

class XlsxValueFormatterTest {
    @Test fun usesCorrectDateEpochAndNeverInventsGregorianLeapDay() {
        val f = XlsxValueFormatter(false, listOf(164, 46, 0), mapOf(164 to "yyyy-mm-dd"))
        assertEquals("1900-02-28", f.format("59", "n", 0))
        assertEquals("60 [format 164 unsupported]", f.format("60", "n", 0))
        assertEquals("1900-03-01", f.format("61", "n", 0))
        assertEquals("36:00:00", f.format("1.5", "n", 1))
        assertEquals("9007199254740993", f.format("9007199254740993", "", 2))
        val other = XlsxValueFormatter(true, listOf(164), mapOf(164 to "yyyy-mm-dd"))
        assertEquals("1904-01-01", other.format("0", "n", 0))
    }

    @Test fun numbersAndCurrencyDoNotChangeTextOrFormulaErrors() {
        val f = XlsxValueFormatter(false, listOf(2, 164, 10), mapOf(164 to "$#,##0.00"))
        assertEquals("1.24", f.format("1.235", "n", 0))
        assertEquals("-$1,234.50", f.format("-1234.5", "n", 1))
        assertEquals("12.50%", f.format("0.125", "n", 2))
        assertEquals("0001", f.format("0001", "s", 0))
        assertEquals("#DIV/0!", f.format("#DIV/0!", "e", 0))
        assertThrows(IllegalArgumentException::class.java) { f.format("1", "n", 3) }
    }
}
