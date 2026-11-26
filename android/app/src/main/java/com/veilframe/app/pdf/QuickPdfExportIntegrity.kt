package com.veilframe.app.pdf

internal object QuickPdfExportIntegrity {
    /** SAF display names are untrusted; never allow paths or control characters in ZIP entries. */
    fun safeStem(input: String): String =
        input.map { c -> if (c.isLetterOrDigit() || c in " ._-") c else '_' }
            .joinToString("").take(100).trim(' ', '.').ifBlank { "document" }

    class Sequence(private val expected: Int) {
        init { require(expected in 1..10_000) }
        private var count = 0
        fun accept(index: Int) {
            check(count < expected && index == count + 1) { "Unexpected archive part $index" }
            count++
        }
        fun requireComplete(reported: Int) {
            check(count == expected && reported == expected) { "Incomplete export: $count of $expected parts" }
        }
    }
}
