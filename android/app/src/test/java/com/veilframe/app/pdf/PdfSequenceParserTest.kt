package com.veilframe.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test validating robust page sequence parsing across arrow notation,
 * comma/space delimiters, ascending/descending ranges, and keyword presets.
 */
class PdfSequenceParserTest {

    @Test
    fun parsePageSequence_arrowNotation_returnsOrderedList() {
        val result = PdfQuickToolController.parsePageSequence("3>4>2>1>5")
        assertEquals(listOf(3, 4, 2, 1, 5), result)
    }

    @Test
    fun parsePageSequence_longArrowNotation_returnsOrderedList() {
        val result = PdfQuickToolController.parsePageSequence("3->4->2->1->5")
        assertEquals(listOf(3, 4, 2, 1, 5), result)
    }

    @Test
    fun parsePageSequence_granularInterleaving_returnsExactOrder() {
        val input = "1, 2, 7, 6, 3, 4, 5, 10, 8, 9"
        val result = PdfQuickToolController.parsePageSequence(input)
        assertEquals(listOf(1, 2, 7, 6, 3, 4, 5, 10, 8, 9), result)
    }

    @Test
    fun parsePageSequence_rangesWithSingles_expandsCorrectly() {
        val input = "1-3, 5, 8-10"
        val result = PdfQuickToolController.parsePageSequence(input)
        assertEquals(listOf(1, 2, 3, 5, 8, 9, 10), result)
    }

    @Test
    fun parsePageSequence_twoDotRangeSyntax_expandsCorrectly() {
        val input = "1..4, 6..8"
        val result = PdfQuickToolController.parsePageSequence(input)
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8), result)
    }

    @Test
    fun parsePageSequence_descendingRange_expandsInReverse() {
        val input = "5-1"
        val result = PdfQuickToolController.parsePageSequence(input)
        assertEquals(listOf(5, 4, 3, 2, 1), result)
    }

    @Test
    fun parsePageSequence_keywordReverse_returnsReversedList() {
        val result = PdfQuickToolController.parsePageSequence("reverse", totalPages = 5)
        assertEquals(listOf(5, 4, 3, 2, 1), result)

        val shortResult = PdfQuickToolController.parsePageSequence("rev", totalPages = 4)
        assertEquals(listOf(4, 3, 2, 1), shortResult)
    }

    @Test
    fun parsePageSequence_keywordOdd_returnsOddPages() {
        val result = PdfQuickToolController.parsePageSequence("odd", totalPages = 7)
        assertEquals(listOf(1, 3, 5, 7), result)
    }

    @Test
    fun parsePageSequence_keywordEven_returnsEvenPages() {
        val result = PdfQuickToolController.parsePageSequence("even", totalPages = 6)
        assertEquals(listOf(2, 4, 6), result)
    }

    @Test
    fun parsePageSequence_keywordReset_returnsSequentialPages() {
        val result = PdfQuickToolController.parsePageSequence("reset", totalPages = 4)
        assertEquals(listOf(1, 2, 3, 4), result)
    }

    @Test
    fun parsePageSequence_blankInput_returnsEmptyList() {
        assertTrue(PdfQuickToolController.parsePageSequence("").isEmpty())
        assertTrue(PdfQuickToolController.parsePageSequence("   ").isEmpty())
    }

    @Test
    fun parsePageSequence_exceedingTotalPages_rejectsEntireRequest() {
        val result = PdfQuickToolController.parsePageSequence("1, 2, 5, 10", totalPages = 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun malformedOrDuplicateTokensRejectEntireRequest() {
        for (text in listOf("1, abc2", "1, 2, 2", "1-3,3-4", "1,,2", "1-2-", "1,0", "1, 2147483648", "1,")) {
            assertTrue(text, PdfPageSequenceParser.parse(text, 10).isEmpty())
        }
    }

    @Test
    fun enormousRangesAndPresetsAreRejectedBeforeExpansion() {
        for (text in listOf("1-2147483647", "2147483647-1", "all", "reverse")) {
            assertTrue(text, PdfPageSequenceParser.parse(text, Int.MAX_VALUE).isEmpty())
        }
        assertTrue(PdfPageSequenceParser.parse("1-10001").isEmpty())
        assertEquals(10_000, PdfPageSequenceParser.parse("1-10000").size)
    }

    @Test
    fun mixedDelimitersAndSpacedRangesWork() {
        assertEquals(listOf(1, 2, 3, 6, 5, 4, 7, 8),
            PdfPageSequenceParser.parse("1 - 3 -> 6..4 | 7 8", 8))
    }

    @Test
    fun mergePrefixPreservesEveryOriginalItemExactlyOnce() {
        val original = listOf(3, 1, 4, 2)
        assertEquals(listOf(4, 3, 1, 2), PdfPageSequenceParser.reorderPrefix(original, listOf(4)) { it })
        assertTrue(PdfPageSequenceParser.reorderPrefix(original, listOf(4, 4)) { it }.isEmpty())
        assertTrue(PdfPageSequenceParser.reorderPrefix(original, listOf(5)) { it }.isEmpty())
    }
}
