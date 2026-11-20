package com.veilframe.app.pdf.operations

/** Raw saved settings; unsupported print settings never alter the full-sheet export. */
data class XlsxSavedPrintSettings(
    val setup: Map<String, String> = emptyMap(), val margins: Map<String, String> = emptyMap(),
    val fitToPage: Boolean = false, val gridLines: Boolean = false,
    val centerHorizontal: Boolean = false, val centerVertical: Boolean = false,
    val rowBreaks: List<String> = emptyList(), val colBreaks: List<String> = emptyList(),
    val area: String? = null, val ambiguousArea: Boolean = false, val unsupportedFeatures: Boolean = false
)

internal object XlsxSavedPrintLayout {
    data class Plan(val width: Float, val height: Float, val left: Float, val top: Float,
        val bottom: Float, val contentWidth: Float, val contentHeight: Float, val scale: Float,
        val tiles: List<Pair<IntRange, IntRange>>)

    fun areas(name: String, settings: XlsxSavedPrintSettings, rows: List<List<String>>,
        merges: List<OpenXmlDocumentParser.CellRangeAddress>): List<OpenXmlDocumentParser.CellRangeAddress> {
        require(!settings.ambiguousArea) { "Duplicate saved print-area definitions are ambiguous" }
        val ref = settings.area
        if (ref == null) {
            val extent = SpreadsheetGeometry.inspect(rows, merges)
            require(extent.rows > 0 && extent.columns > 0) { "Empty print area" }
            return listOf(OpenXmlDocumentParser.CellRangeAddress(0, extent.rows - 1, 0, extent.columns - 1))
        }
        require(ref.length <= 16_384) { "Saved print-area definition exceeds budget" }
        val parts = mutableListOf<String>()
        var quoted = false
        var start = 0
        var i = 0
        while (i < ref.length) {
            when (ref[i]) {
                '\'' -> {
                    if (quoted && ref.getOrNull(i+1) == '\'') i++ else quoted = !quoted
                }
                ',' -> if (!quoted) {
                    parts.add(ref.substring(start,i).trim()); start = i+1
                    require(parts.size < 64) { "Too many saved print areas" }
                }
            }
            i++
        }
        require(!quoted) { "Malformed quoted print-area sheet name" }
        parts.add(ref.substring(start).trim())
        val ranges = parts.map { parseArea(name, it) }
        for ((index, range) in ranges.withIndex()) for (other in ranges.take(index)) {
            require(range.lastRow < other.firstRow || other.lastRow < range.firstRow ||
                range.lastCol < other.firstCol || other.lastCol < range.firstCol) {
                "Overlapping saved print areas are ambiguous"
            }
        }
        return ranges
    }

    private fun parseArea(name: String, ref: String): OpenXmlDocumentParser.CellRangeAddress {
        val separator = ref.lastIndexOf('!')
        require(separator > 0) { "Unsupported saved print area" }
        val owner = ref.substring(0, separator).let {
            if (it.startsWith("'") && it.endsWith("'")) it.substring(1,it.length-1).replace("''","'") else it
        }
        require(owner == name) { "Unsupported cross-sheet print area" }
        val range = ref.substring(separator + 1).replace("$","")
        require(range.matches(Regex("[A-Za-z]{1,3}[1-9][0-9]{0,6}(?::[A-Za-z]{1,3}[1-9][0-9]{0,6})?"))) {
            "Saved print areas must be rectangular cell ranges"
        }
        val parts = range.split(':')
        fun coord(value: String): Pair<Int,Int> {
            val col = OpenXmlDocumentParser.colRefToIndex(value)
            val row = value.dropWhile { it.isLetter() }.toInt()
            require(col in 0 until 16_384 && row in 1..1_048_576) { "Print area out of bounds" }
            return row-1 to col
        }
        val first = coord(parts.first()); val last = coord(parts.last())
        require(first.first <= last.first && first.second <= last.second) { "Reversed saved print area" }
        return OpenXmlDocumentParser.CellRangeAddress(first.first,last.first,first.second,last.second)
    }

    fun plan(layout: SheetLayout, area: OpenXmlDocumentParser.CellRangeAddress, settings: XlsxSavedPrintSettings): Plan {
        fun mm(v: Float) = v * 72f / 25.4f
        val portrait = when (settings.setup["paperSize"] ?: "1") {
            "1" -> 612f to 792f
            "3" -> 792f to 1224f
            "5" -> 612f to 1008f
            "8" -> mm(297f) to mm(420f)
            "9" -> mm(210f) to mm(297f)
            "11" -> mm(148f) to mm(210f)
            else -> throw IllegalArgumentException("Unsupported saved paper size; use full worksheet export")
        }
        val (width,height) = when (settings.setup["orientation"]) {
            null, "", "default", "portrait" -> portrait
            "landscape" -> portrait.second to portrait.first
            else -> throw IllegalArgumentException("Unsupported saved print orientation")
        }
        fun margin(key: String, default: Float): Float {
            val raw = settings.margins[key] ?: return default * 72f
            val n = raw.toFloatOrNull()
            require(n != null && n.isFinite() && n in 0f..20f) { "Invalid saved print margin" }
            return n * 72f
        }
        val left = margin("left",0.7f); val right = margin("right",0.7f)
        val top = margin("top",0.75f); val bottom = margin("bottom",0.75f)
        val w = width-left-right; val h = height-top-bottom
        require(w > 0f && h > 0f) { "Saved margins leave no printable area" }
        fun breaks(raw: List<String>, max: Int): Set<Int> = raw.map {
            val n = it.toIntOrNull()
            require(n != null && n in 1..max) { "Invalid manual print break" }; n
        }.toSet()
        val rb = if (settings.fitToPage) emptySet() else breaks(settings.rowBreaks,1_048_575)
        val cb = if (settings.fitToPage) emptySet() else breaks(settings.colBreaks,16_383)
        fun groups(range: IntRange, sizes: FloatArray, limit: Float, scale: Float, breaks: Set<Int>): List<IntRange>? {
            val result = mutableListOf<IntRange>()
            var start = range.first; var used = 0f
            for (i in range) {
                val size = sizes[i] * scale
                if (size > limit + 0.01f) return null
                if (i > start && (i in breaks || used + size > limit + 0.01f)) {
                    result.add(start until i); start = i; used = 0f
                    if (result.size >= SpreadsheetGeometry.MAX_PAGES) return null
                }
                used += size
            }
            result.add(start..range.last)
            return result
        }
        fun target(key: String): Int {
            val raw = settings.setup[key] ?: return 1
            val n = raw.toIntOrNull()
            require(n != null && n in 0..SpreadsheetGeometry.MAX_PAGES) { "Invalid fit-to-page target" }
            return n
        }
        val fitW = if (settings.fitToPage) target("fitToWidth") else 0
        val fitH = if (settings.fitToPage) target("fitToHeight") else 0
        val requested = if (settings.fitToPage) 100 else {
            val raw = settings.setup["scale"] ?: "100"
            val n = raw.toIntOrNull()
            require(n != null && n in 10..400) { "Invalid saved print scaling" }; n
        }
        // With fit-to-page, choose the largest integer percentage satisfying the
        // requested page counts. Manual breaks are ignored in this mode.
        for (percent in requested downTo (if (settings.fitToPage) 10 else requested)) {
            val scale = percent / 100f
            val rs = groups(area.firstRow..area.lastRow,layout.rowHeights,h,scale,rb) ?: continue
            val cs = groups(area.firstCol..area.lastCol,layout.colWidths,w,scale,cb) ?: continue
            if ((fitW > 0 && cs.size > fitW) || (fitH > 0 && rs.size > fitH)) continue
            if (rs.size.toLong()*cs.size > SpreadsheetGeometry.MAX_PAGES) continue
            val tiles = when (settings.setup["pageOrder"]) {
                "overThenDown" -> rs.flatMap { row -> cs.map { col -> row to col } }
                null, "", "downThenOver" -> cs.flatMap { col -> rs.map { row -> row to col } }
                else -> throw IllegalArgumentException("Unsupported saved print page order")
            }
            return Plan(width,height,left,top,bottom,w,h,scale,tiles)
        }
        throw IllegalArgumentException("Saved print geometry cannot fit without clipping; use full worksheet export")
    }
}
