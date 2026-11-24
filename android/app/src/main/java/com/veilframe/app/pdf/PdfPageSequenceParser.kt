package com.veilframe.app.pdf

/** A manual sequence is a bounded, duplicate-free prefix, never a partial parse. */
internal object PdfPageSequenceParser {
    private const val MAX_PAGES = 10_000
    private const val MAX_INPUT = 131_072
    private val page = Regex("""([0-9]+)(?:\s*(?:-|–|\.\.)\s*([0-9]+))?""")
    private val separator = Regex("""(?:\s*(?:->|[,;>|])\s*|\s+)""")

    fun parse(input: String, totalPages: Int = 0): List<Int> {
        if (input.length > MAX_INPUT || totalPages < 0) return emptyList()
        val text = input.trim()
        if (text.isEmpty()) return emptyList()
        if (totalPages in 1..MAX_PAGES) {
            when (text.lowercase(java.util.Locale.ROOT)) {
                "reverse", "rev" -> return (totalPages downTo 1).toList()
                "odd", "odds" -> return (1..totalPages step 2).toList()
                "even", "evens" -> return (2..totalPages step 2).toList()
                "reset", "all" -> return (1..totalPages).toList()
            }
        }

        val result = LinkedHashSet<Int>()
        var position = 0
        val limit = if (totalPages > 0) totalPages else Int.MAX_VALUE
        while (position < text.length) {
            val match = page.matchAt(text, position) ?: return emptyList()
            val first = match.groupValues[1].toIntOrNull() ?: return emptyList()
            val last = if (match.groupValues[2].isEmpty()) first else
                match.groupValues[2].toIntOrNull() ?: return emptyList()
            if (first !in 1..limit || last !in 1..limit) return emptyList()
            val count = kotlin.math.abs(last.toLong() - first.toLong()) + 1
            if (count > MAX_PAGES - result.size) return emptyList()
            val range = if (first <= last) first..last else first downTo last
            for (number in range) if (!result.add(number)) return emptyList()
            position = match.range.last + 1
            if (position == text.length) break
            val delimiter = separator.matchAt(text, position) ?: return emptyList()
            position = delimiter.range.last + 1
            if (position == text.length) return emptyList()
        }
        return result.toList()
    }

    /** Preserve omitted items in their current relative order, without duplicating any. */
    fun <T> reorderPrefix(items: List<T>, requested: List<Int>, number: (T) -> Int): List<T> {
        if (items.isEmpty() || items.size > MAX_PAGES || requested.isEmpty()) return emptyList()
        val byNumber = items.associateBy(number)
        if (byNumber.size != items.size || requested.toSet().size != requested.size ||
            requested.any { it !in byNumber }) return emptyList()
        val prefix = requested.toHashSet()
        return requested.map { byNumber.getValue(it) } + items.filter { number(it) !in prefix }
    }
}
