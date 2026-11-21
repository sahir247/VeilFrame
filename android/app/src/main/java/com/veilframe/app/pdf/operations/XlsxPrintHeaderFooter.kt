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
    fun headerFor(page: Int): String = when {
        differentFirst && page == 1 -> firstHeader
        differentOddEven && page % 2 == 0 -> evenHeader
        else -> oddHeader
    }
    fun footerFor(page: Int): String = when {
        differentFirst && page == 1 -> firstFooter
        differentOddEven && page % 2 == 0 -> evenFooter
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
            require(page in 1..pages && pages <= SpreadsheetGeometry.MAX_PAGES)
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
                    'P' -> parts[section].append(page)
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
