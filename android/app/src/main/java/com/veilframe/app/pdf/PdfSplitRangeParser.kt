package com.veilframe.app.pdf

import com.veilframe.app.pdf.operations.PageRange

/** All requested split parts must be valid; never silently discard bad tokens. */
internal object PdfSplitRangeParser {
    private val token = Regex("""([0-9]+)(?:-([0-9]+))?""")
    fun parse(input: String, pageCount: Int): List<PageRange> {
        if (pageCount <= 0 || input.isBlank() || input.length > 16384) return emptyList()
        val parts = input.trim().split(Regex("""[,;\s]+"""))
        if (parts.size > 2048) return emptyList()
        return parts.map { part ->
            val match = token.matchEntire(part) ?: return emptyList()
            val first = match.groupValues[1].toIntOrNull() ?: return emptyList()
            val last = if (match.groupValues[2].isEmpty()) first else
                match.groupValues[2].toIntOrNull() ?: return emptyList()
            if (first !in 1..pageCount || last !in 1..pageCount) return emptyList()
            PageRange(minOf(first,last),maxOf(first,last))
        }
    }
}
