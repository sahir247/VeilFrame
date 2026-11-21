package com.veilframe.app.pdf.operations

/**
 * Supported deterministic subset of SpreadsheetML headers and footers. Any
 * unsupported formatting token rejects saved-print export rather than dropping
 * important text or misrepresenting printer-exact fidelity.
 */
data class XlsxPrintHeaderFooter(
    val oddHeader: String = "", val oddFooter: String = "",
    val evenHeader: String = "", val evenFooter: String = "",
    val firstHeader: String = "", val firstFooter: String = "",
    val differentOddEven: Boolean = false, val differentFirst: Boolean = false
) {
    fun headerFor(page: Int, printedPage: Int = page): String = when {
        differentFirst && page == 1 -> firstHeader
        differentOddEven && printedPage % 2 == 0 -> evenHeader
        else -> oddHeader
    }
    fun footerFor(page: Int, printedPage: Int = page): String = when {
        differentFirst && page == 1 -> firstFooter
        differentOddEven && printedPage % 2 == 0 -> evenFooter
        else -> oddFooter
    }

    data class Sections(val left: String, val center: String, val right: String)

    companion object {
        fun parse(parts: Map<String,String>?): XlsxPrintHeaderFooter {
            if (parts == null) return XlsxPrintHeaderFooter()
            fun flag(name: String): Boolean = when (parts[name].orEmpty()) {
                "", "0", "false" -> false
                "1", "true" -> true
                else -> throw IllegalArgumentException("Invalid $name flag")
            }
            fun value(name: String): String = parts[name].orEmpty().also {
                require(it.length <= 255) { "Saved $name exceeds Excel header/footer budget" }
            }
            return XlsxPrintHeaderFooter(value("oddHeader"),value("oddFooter"),
                value("evenHeader"),value("evenFooter"),value("firstHeader"),value("firstFooter"),
                flag("differentOddEven"),flag("differentFirst"))
        }

        fun expand(raw: String, sheet: String, page: Int, pages: Int): Sections {
            require(page in 1..999_999 && pages in 1..SpreadsheetGeometry.MAX_PAGES)
            require(raw.length <= 255 && sheet.length <= 1024)
            val parts = Array(3) { StringBuilder() }
            var section = 1 // Excel's unqualified text belongs to the center.
            var i = 0
            while (i < raw.length) {
                val ch = raw[i++]
                if (ch != '&') {
                    require(ch >= ' ' && ch != '\u007f') { "Invalid saved header/footer character" }
                    parts[section].append(ch)
                    continue
                }
                require(i < raw.length) { "Truncated saved header/footer field" }
                when (val token = raw[i++]) {
                    'L' -> section = 0
                    'C' -> section = 1
                    'R' -> section = 2
                    '&' -> parts[section].append('&')
                    'P' -> {
                        // Excel &P+number / &P-number adjust the printed page,
                        // not a literal suffix. Reject malformed or excessive
                        // offsets instead of emitting a misleading label.
                        var offset = 0
                        if (i < raw.length && (raw[i] == '+' || raw[i] == '-')) {
                            val sign = if (raw[i++] == '+') 1 else -1
                            val start = i
                            while (i < raw.length && raw[i] in '0'..'9') i++
                            require(i > start && i - start <= 6) { "Invalid header/footer page offset" }
                            offset = sign * (raw.substring(start,i).toIntOrNull()
                                ?: throw IllegalArgumentException("Invalid header/footer page offset"))
                        }
                        val printed = page.toLong() + offset
                        require(printed in 1..999_999L) { "Header/footer page offset out of range" }
                        parts[section].append(printed)
                    }
                    'N' -> parts[section].append(pages)
                    'A' -> parts[section].append(sheet)
                    else -> throw IllegalArgumentException("Unsupported Excel header/footer field &$token")
                }
                require(parts.sumOf { it.length } <= 1024) { "Expanded header/footer exceeds budget" }
            }
            return Sections(parts[0].toString(),parts[1].toString(),parts[2].toString())
        }
    }
}
