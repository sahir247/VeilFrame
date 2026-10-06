package com.veilframe.app.cv.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CvContractsTest {

    @Test
    fun requireFiniteAcceptsFiniteValues() {
        CvContracts.requireFinite(1.0, "test")
        CvContracts.requireFinite(0.0, "test")
        CvContracts.requireFinite(-42.5, "test")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireFiniteRejectsNaN() {
        CvContracts.requireFinite(Double.NaN, "nan")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireFiniteRejectsInfinity() {
        CvContracts.requireFinite(Double.POSITIVE_INFINITY, "inf")
    }

    @Test
    fun requirePositiveAcceptsPositiveValues() {
        CvContracts.requirePositive(1, "int")
        CvContracts.requirePositive(0.001, "double")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requirePositiveRejectsZeroInt() {
        CvContracts.requirePositive(0, "zero")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requirePositiveRejectsZeroDouble() {
        CvContracts.requirePositive(0.0, "zero")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requirePositiveRejectsNegativeDouble() {
        CvContracts.requirePositive(-1.5, "negative")
    }

    @Test
    fun requireNonNegativeAcceptsZeroAndPositive() {
        CvContracts.requireNonNegative(0, "zero")
        CvContracts.requireNonNegative(10, "positive")
        CvContracts.requireNonNegative(0.0, "zeroDouble")
        CvContracts.requireNonNegative(5.5, "posDouble")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireNonNegativeRejectsNegativeInt() {
        CvContracts.requireNonNegative(-1, "negative")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireNonNegativeRejectsNegativeDouble() {
        CvContracts.requireNonNegative(-0.01, "negative")
    }

    @Test
    fun requireInRangeAcceptsBoundaryValues() {
        CvContracts.requireInRange(0.0, 0.0, 1.0, "zero")
        CvContracts.requireInRange(1.0, 0.0, 1.0, "one")
        CvContracts.requireInRange(0.5, 0.0, 1.0, "half")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireInRangeRejectsOutOfRangeBelow() {
        CvContracts.requireInRange(-0.01, 0.0, 1.0, "below")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireInRangeRejectsOutOfRangeAbove() {
        CvContracts.requireInRange(1.01, 0.0, 1.0, "above")
    }

    @Test
    fun requireOddPositiveAcceptsOddPositiveIntegers() {
        CvContracts.requireOddPositive(1, "one")
        CvContracts.requireOddPositive(3, "three")
        CvContracts.requireOddPositive(5, "five")
        CvContracts.requireOddPositive(31, "thirtyOne")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireOddPositiveRejectsEvenNumber() {
        CvContracts.requireOddPositive(4, "four")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireOddPositiveRejectsZero() {
        CvContracts.requireOddPositive(0, "zero")
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireOddPositiveRejectsNegativeOddNumber() {
        CvContracts.requireOddPositive(-3, "negativeThree")
    }
}
