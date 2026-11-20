package com.veilframe.app.pdf.operations

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * High-performance, offline OpenXML (.docx and .xlsx) parser.
 * Operates purely on standard Java ZIP streams and standard DOM XML parsing,
 * bypassing Apache POI/AWT entirely to guarantee zero crashes on Android ART
 * and seamless JVM test execution.
 */
object OpenXmlDocumentParser {

    // -------------------------------------------------------------------------
    // DOCX Models & Parsing
    // -------------------------------------------------------------------------

    data class DocxRun(
        val text: String,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val fontSizePt: Float = 11f
    )

    data class DocxParagraph(
        val runs: List<DocxRun>,
        val isHeading: Boolean = false,
        val headingLevel: Int = 0, // 1: Title/H1, 2: H2, 3: H3
        val alignment: String = "left", // left, center, right, both
        val spaceBeforePt: Float = 4f,
        val spaceAfterPt: Float = 6f,
        val isBullet: Boolean = false
    ) {
        val fullText: String
            get() = runs.joinToString("") { it.text }
    }

    data class DocxTable(
        val rows: List<List<String>>
    )

    sealed class DocxNode {
        data class ParagraphNode(val paragraph: DocxParagraph) : DocxNode()
        data class TableNode(val table: DocxTable) : DocxNode()
    }

    data class DocxDocument(
        val nodes: List<DocxNode>
    ) {
        val paragraphs: List<DocxParagraph>
            get() = nodes.filterIsInstance<DocxNode.ParagraphNode>().map { it.paragraph }

        val tables: List<DocxTable>
            get() = nodes.filterIsInstance<DocxNode.TableNode>().map { it.table }
    }

    private fun newDocBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        try {
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Throwable) {}
    }.newDocumentBuilder()

    private fun Node.localName(): String = nodeName.substringAfterLast(':')

    fun parseDocx(file: File): DocxDocument {
        val nodes = mutableListOf<DocxNode>()
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: throw java.io.IOException("Word document XML missing")
            zip.getInputStream(entry).use { stream ->
                parseDocxStream(stream, nodes)
            }
        }
        return DocxDocument(nodes)
    }

    private fun parseDocxStream(stream: InputStream, outNodes: MutableList<DocxNode>) {
        val doc = newDocBuilder().parse(stream)
        val bodyElements = doc.getElementsByTagName("w:body").let {
            if (it.length > 0) it else doc.getElementsByTagName("body")
        }
        val body: Element = (if (bodyElements.length > 0) bodyElements.item(0) as? Element else doc.documentElement)
            ?: return

        var child = body.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                when (child.localName()) {
                    "p" -> {
                        val p = parseParagraph(child as Element)
                        if (p != null && (p.runs.isNotEmpty() || p.spaceBeforePt > 0)) {
                            outNodes.add(DocxNode.ParagraphNode(p))
                        }
                    }
                    "tbl" -> {
                        val t = parseTable(child as Element)
                        if (t != null && t.rows.isNotEmpty()) {
                            outNodes.add(DocxNode.TableNode(t))
                        }
                    }
                }
            }
            child = child.nextSibling
        }
    }

    private fun parseParagraph(pElem: Element): DocxParagraph? {
        var isHeading = false
        var headingLevel = 0
        var alignment = "left"
        var spaceBeforePt = 4f
        var spaceAfterPt = 6f
        var isBullet = false
        val runs = mutableListOf<DocxRun>()

        var child = pElem.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val elem = child as Element
                when (elem.localName()) {
                    "pPr" -> {
                        var pPrChild = elem.firstChild
                        while (pPrChild != null) {
                            if (pPrChild.nodeType == Node.ELEMENT_NODE) {
                                val pPrElem = pPrChild as Element
                                when (pPrElem.localName()) {
                                    "pStyle" -> {
                                        val styleVal = (pPrElem.getAttribute("w:val").ifEmpty {
                                            pPrElem.getAttribute("val")
                                        }).lowercase()
                                        if (styleVal.contains("heading 1") || styleVal.contains("heading1") || styleVal.contains("title")) {
                                            isHeading = true
                                            headingLevel = 1
                                            spaceBeforePt = 16f
                                            spaceAfterPt = 8f
                                        } else if (styleVal.contains("heading 2") || styleVal.contains("heading2") || styleVal.contains("subtitle")) {
                                            isHeading = true
                                            headingLevel = 2
                                            spaceBeforePt = 12f
                                            spaceAfterPt = 6f
                                        } else if (styleVal.contains("heading 3") || styleVal.contains("heading3")) {
                                            isHeading = true
                                            headingLevel = 3
                                            spaceBeforePt = 8f
                                            spaceAfterPt = 4f
                                        }
                                    }
                                    "jc" -> {
                                        val jcVal = (pPrElem.getAttribute("w:val").ifEmpty {
                                            pPrElem.getAttribute("val")
                                        }).lowercase()
                                        if (jcVal in listOf("center", "right", "both")) {
                                            alignment = jcVal
                                        }
                                    }
                                    "spacing" -> {
                                        val before = pPrElem.getAttribute("w:before").ifEmpty { pPrElem.getAttribute("before") }
                                        val after = pPrElem.getAttribute("w:after").ifEmpty { pPrElem.getAttribute("after") }
                                        before.toFloatOrNull()?.let { spaceBeforePt = (it / 20f).coerceIn(0f, 40f) }
                                        after.toFloatOrNull()?.let { spaceAfterPt = (it / 20f).coerceIn(0f, 40f) }
                                    }
                                    "numPr" -> {
                                        isBullet = true
                                    }
                                }
                            }
                            pPrChild = pPrChild.nextSibling
                        }
                    }
                    "r" -> {
                        val run = parseRun(elem, isHeading, headingLevel)
                        if (run != null && run.text.isNotEmpty()) {
                            runs.add(run)
                        }
                    }
                }
            }
            child = child.nextSibling
        }

        if (runs.isEmpty()) return null
        return DocxParagraph(
            runs = runs,
            isHeading = isHeading,
            headingLevel = headingLevel,
            alignment = alignment,
            spaceBeforePt = spaceBeforePt,
            spaceAfterPt = spaceAfterPt,
            isBullet = isBullet
        )
    }

    private fun parseRun(rElem: Element, parentHeading: Boolean, headingLevel: Int): DocxRun? {
        var isBold = parentHeading
        var isItalic = false
        var fontSizePt = when (headingLevel) {
            1 -> 18f
            2 -> 15f
            3 -> 13f
            else -> 11f
        }
        val sb = StringBuilder()

        var child = rElem.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val elem = child as Element
                when (elem.localName()) {
                    "rPr" -> {
                        var rPrChild = elem.firstChild
                        while (rPrChild != null) {
                            if (rPrChild.nodeType == Node.ELEMENT_NODE) {
                                val rPrElem = rPrChild as Element
                                when (rPrElem.localName()) {
                                    "b" -> {
                                        val v = rPrElem.getAttribute("w:val").ifEmpty { rPrElem.getAttribute("val") }
                                        isBold = v.isEmpty() || v == "1" || v.equals("true", ignoreCase = true)
                                    }
                                    "i" -> {
                                        val v = rPrElem.getAttribute("w:val").ifEmpty { rPrElem.getAttribute("val") }
                                        isItalic = v.isEmpty() || v == "1" || v.equals("true", ignoreCase = true)
                                    }
                                    "sz" -> {
                                        val v = rPrElem.getAttribute("w:val").ifEmpty { rPrElem.getAttribute("val") }
                                        v.toFloatOrNull()?.let {
                                            if (!parentHeading) fontSizePt = (it / 2f).coerceIn(8f, 32f)
                                        }
                                    }
                                }
                            }
                            rPrChild = rPrChild.nextSibling
                        }
                    }
                    "t" -> {
                        sb.append(elem.textContent ?: "")
                    }
                    "br" -> {
                        sb.append("\n")
                    }
                }
            }
            child = child.nextSibling
        }

        return DocxRun(
            text = sb.toString(),
            isBold = isBold,
            isItalic = isItalic,
            fontSizePt = fontSizePt
        )
    }

    private fun parseTable(tblElem: Element): DocxTable? {
        val rows = mutableListOf<List<String>>()
        var child = tblElem.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.localName() == "tr") {
                val rowCells = mutableListOf<String>()
                var trChild = child.firstChild
                while (trChild != null) {
                    if (trChild.nodeType == Node.ELEMENT_NODE && trChild.localName() == "tc") {
                        val tcText = StringBuilder()
                        var tcChild = trChild.firstChild
                        while (tcChild != null) {
                            if (tcChild.nodeType == Node.ELEMENT_NODE && tcChild.localName() == "p") {
                                val p = parseParagraph(tcChild as Element)
                                if (p != null) {
                                    if (tcText.isNotEmpty()) tcText.append("\n")
                                    tcText.append(p.fullText)
                                }
                            }
                            tcChild = tcChild.nextSibling
                        }
                        rowCells.add(tcText.toString())
                    }
                    trChild = trChild.nextSibling
                }
                if (rowCells.isNotEmpty()) {
                    rows.add(rowCells)
                }
            }
            child = child.nextSibling
        }
        return if (rows.isEmpty()) null else DocxTable(rows)
    }

    // -------------------------------------------------------------------------
    // XLSX Models & Parsing
    // -------------------------------------------------------------------------

    data class CellRangeAddress(
        val firstRow: Int,
        val lastRow: Int,
        val firstCol: Int,
        val lastCol: Int
    ) {
        fun contains(row: Int, col: Int): Boolean {
            return row in firstRow..lastRow && col in firstCol..lastCol
        }
    }

    data class XlsxRow(
        val rowIndex: Int,
        val cells: List<String>
    )

    data class XlsxSheetData(
        val name: String,
        val rows: List<List<String>>,
        val sparseRows: List<XlsxRow> = rows.mapIndexed { idx, cells -> XlsxRow(idx + 1, cells) },
        val mergedRegions: List<CellRangeAddress> = emptyList(),
        val frozenColumns: Int = 0,
        val frozenRows: Int = 0
    )

    const val MAX_SUPPORTED_ROW_GAP = 500

    data class XlsxWorkbookData(
        val sheets: List<XlsxSheetData>
    )

    private data class SheetPart(val name: String, val path: String)
    private data class WorkbookRelationship(val type: String, val path: String)

    private fun workbookPartPath(target: String): String {
        val uri = java.net.URI(target)
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null &&
            uri.rawFragment == null && !target.contains('\\')) { "Invalid workbook relationship target" }
        val resolved = java.net.URI("/xl/workbook.xml").resolve(uri).normalize().path
        require(resolved.startsWith("/") && !resolved.startsWith("//") &&
            resolved.split('/').none { it == ".." || it == "." }) { "Invalid workbook part path" }
        return resolved.removePrefix("/")
    }

    private fun workbookRelationships(zip: ZipFile): Map<String, WorkbookRelationship>? {
        val entry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return null
        val doc = zip.getInputStream(entry).use { newDocBuilder().parse(it) }
        val relationships = doc.getElementsByTagName("*")
        val result = linkedMapOf<String, WorkbookRelationship>()
        for (i in 0 until relationships.length) {
            val node = relationships.item(i) as? Element ?: continue
            if (node.localName() != "Relationship") continue
            val id = node.getAttribute("Id")
            val type = node.getAttribute("Type")
            // External links are irrelevant to local worksheet rendering. If a sheet
            // references one, the absent relationship will cause a visible failure.
            if (node.getAttribute("TargetMode").equals("External", ignoreCase = true)) continue
            require(id.isNotBlank() && type.isNotBlank()) { "Malformed workbook relationship" }
            require(result.put(id, WorkbookRelationship(type, workbookPartPath(node.getAttribute("Target")))) == null) {
                "Duplicate workbook relationship ID"
            }
        }
        return result
    }

    private fun orderedSheetParts(zip: ZipFile, relationships: Map<String, WorkbookRelationship>?): List<SheetPart> {
        val entry = zip.getEntry("xl/workbook.xml") ?: throw java.io.IOException("Workbook XML missing")
        val doc = zip.getInputStream(entry).use { newDocBuilder().parse(it) }
        val nodes = doc.getElementsByTagName("*")
        val parts = mutableListOf<SheetPart>()
        val usedPaths = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            if (element.localName() != "sheet") continue
            val title = element.getAttribute("name")
            require(title.isNotBlank()) { "Workbook contains an unnamed sheet" }
            val path = if (relationships != null) {
                // Namespace prefix is not fixed; the relationship attribute is identified
                // by local name. sheetId is not the relationship ID.
                val attrs = element.attributes
                val id = (0 until attrs.length).map { attrs.item(it) }
                    .firstOrNull { it.nodeName.substringAfterLast(':') == "id" }?.nodeValue
                val relationship = relationships[id] ?: throw java.io.IOException("Missing relationship for worksheet $title")
                require(relationship.type.endsWith("/worksheet")) {
                    "Unsupported non-worksheet sheet: $title"
                }
                relationship.path
            } else {
                // Compatibility only for minimally packaged workbooks used by older
                // exports/tests. Do not infer a missing, reordered or extra worksheet.
                require(element.getAttribute("sheetId").toIntOrNull() == parts.size + 1) {
                    "Workbook relationships are missing; worksheet order is ambiguous"
                }
                "xl/worksheets/sheet${parts.size + 1}.xml"
            }
            require(usedPaths.add(path)) { "Multiple workbook sheets reference $path" }
            require(zip.getEntry(path) != null) { "Missing worksheet part: $title ($path)" }
            parts.add(SheetPart(title, path))
        }
        require(parts.isNotEmpty()) { "Workbook has no worksheets" }
        if (relationships == null) {
            val actual = zip.entries().asSequence().filter {
                !it.isDirectory && it.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))
            }.map { it.name }.toSet()
            require(actual == usedPaths) { "Workbook relationships are missing; worksheet set is ambiguous" }
        }
        return parts
    }

    /** A malformed later sheet must fail the entire workbook, never return a prefix. */
    fun parseXlsx(file: File): XlsxWorkbookData {
        val sheets = mutableListOf<XlsxSheetData>()
        ZipFile(file).use { zip ->
            val relationships = workbookRelationships(zip)
            val parts = orderedSheetParts(zip, relationships)
            val sharedStrings = mutableListOf<String>()
            val sharedPart = relationships?.values?.singleOrNull { it.type.endsWith("/sharedStrings") }?.path
            val sstEntry = if (sharedPart != null) zip.getEntry(sharedPart)
                ?: throw java.io.IOException("Missing shared strings part")
                else zip.getEntry("xl/sharedStrings.xml")
            if (sstEntry != null) {
                zip.getInputStream(sstEntry).use { stream -> parseSharedStrings(stream, sharedStrings) }
            }
            for (part in parts) {
                val sheetRows = mutableListOf<List<String>>()
                val sheetSparseRows = mutableListOf<XlsxRow>()
                val sheetMergedRegions = mutableListOf<CellRangeAddress>()
                val (frozenCols, frozenRows) = zip.getInputStream(zip.getEntry(part.path)).use { stream ->
                    parseWorksheet(stream, sharedStrings, sheetRows, sheetSparseRows, sheetMergedRegions)
                }
                sheets.add(XlsxSheetData(
                    name = part.name, rows = sheetRows, sparseRows = sheetSparseRows,
                    mergedRegions = sheetMergedRegions, frozenColumns = frozenCols, frozenRows = frozenRows
                ))
            }
        }
        return XlsxWorkbookData(sheets)
    }

    private fun parseSharedStrings(stream: InputStream, outStrings: MutableList<String>) {
        val doc = newDocBuilder().parse(stream)
        val siList = doc.getElementsByTagName("si")
        for (i in 0 until siList.length) {
            val si = siList.item(i) as Element
            val tList = si.getElementsByTagName("t")
            val sb = StringBuilder()
            for (j in 0 until tList.length) {
                sb.append(tList.item(j).textContent ?: "")
            }
            outStrings.add(sb.toString())
        }
    }

    fun colRefToIndex(ref: String): Int {
        var col = 0
        var hasLetters = false
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
                hasLetters = true
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
                hasLetters = true
            } else {
                break
            }
        }
        return if (hasLetters) (col - 1).coerceAtLeast(0) else -1
    }

    private fun parseWorksheet(
        stream: InputStream,
        sharedStrings: List<String>,
        outRows: MutableList<List<String>>,
        outSparseRows: MutableList<XlsxRow>? = null,
        outMergedRegions: MutableList<CellRangeAddress>? = null
    ): Pair<Int, Int> {
        val doc = newDocBuilder().parse(stream)
        require(doc.documentElement.localName() == "worksheet") { "Invalid worksheet XML root" }
        // This parser uses unprefixed worksheet element names. Refuse unsupported
        // prefixed XML rather than silently returning an empty worksheet.
        require(doc.documentElement.nodeName == "worksheet") { "Unsupported prefixed worksheet XML" }

        var frozenCols = 0
        var frozenRows = 0
        val paneNodes = doc.getElementsByTagName("pane")
        for (p in 0 until paneNodes.length) {
            val pElem = paneNodes.item(p) as? Element ?: continue
            val state = pElem.getAttribute("state")
            if (state.isEmpty() || state.equals("frozen", ignoreCase = true) || state.equals("frozenSplit", ignoreCase = true)) {
                val xSplit = pElem.getAttribute("xSplit").toFloatOrNull()?.toInt() ?: 0
                val ySplit = pElem.getAttribute("ySplit").toFloatOrNull()?.toInt() ?: 0
                if (xSplit > 0) frozenCols = maxOf(frozenCols, xSplit)
                if (ySplit > 0) frozenRows = maxOf(frozenRows, ySplit)
            }
        }

        // Parse merged cell ranges if present
        if (outMergedRegions != null) {
            val mergeNodes = doc.getElementsByTagName("mergeCell")
            for (m in 0 until mergeNodes.length) {
                val mElem = mergeNodes.item(m) as? Element ?: continue
                val ref = mElem.getAttribute("ref")
                if (ref.contains(":")) {
                    val parts = ref.split(":")
                    val startRef = parts[0].trim()
                    val endRef = parts[1].trim()
                    val startCol = colRefToIndex(startRef)
                    val startRow = (startRef.filter { it.isDigit() }.toIntOrNull() ?: 1) - 1
                    val endCol = colRefToIndex(endRef)
                    val endRow = (endRef.filter { it.isDigit() }.toIntOrNull() ?: 1) - 1
                    if (startCol >= 0 && endCol >= 0 && startRow >= 0 && endRow >= 0) {
                        outMergedRegions.add(
                            CellRangeAddress(
                                firstRow = minOf(startRow, endRow),
                                lastRow = maxOf(startRow, endRow),
                                firstCol = minOf(startCol, endCol),
                                lastCol = maxOf(startCol, endCol)
                            )
                        )
                    }
                }
            }
        }

        val rowList = doc.getElementsByTagName("row")
        val rowMap = java.util.TreeMap<Int, MutableList<String>>()
        var autoRowIndex = 1

        for (i in 0 until rowList.length) {
            val rowElem = rowList.item(i) as Element
            val explicitRowAttr = rowElem.getAttribute("r")
            val explicitRowNumber = explicitRowAttr.toIntOrNull()
            val targetRow = if (explicitRowNumber != null && explicitRowNumber > 0) {
                explicitRowNumber
            } else {
                autoRowIndex
            }
            autoRowIndex = maxOf(autoRowIndex, targetRow + 1)

            val existingCells = rowMap.getOrPut(targetRow) { mutableListOf() }

            var child = rowElem.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE && child.localName() == "c") {
                    val cElem = child as Element
                    val cellType = cElem.getAttribute("t")
                    val cellRef = cElem.getAttribute("r")
                    var cellVal = ""
                    val vList = cElem.getElementsByTagName("v")
                    if (vList.length > 0) {
                        cellVal = vList.item(0).textContent?.trim() ?: ""
                    } else {
                        val tList = cElem.getElementsByTagName("t")
                        if (tList.length > 0) {
                            cellVal = tList.item(0).textContent?.trim() ?: ""
                        }
                    }

                    if (cellType == "s") {
                        val idx = cellVal.toIntOrNull()
                        if (idx != null && idx in sharedStrings.indices) {
                            cellVal = sharedStrings[idx]
                        }
                    }

                    val targetCol = if (cellRef.isNotEmpty()) colRefToIndex(cellRef) else existingCells.size
                    if (targetCol >= 0) {
                        while (existingCells.size < targetCol) {
                            existingCells.add("")
                        }
                        if (existingCells.size == targetCol) {
                            existingCells.add(cellVal)
                        } else {
                            existingCells[targetCol] = cellVal
                        }
                    } else {
                        existingCells.add(cellVal)
                    }
                }
                child = child.nextSibling
            }
        }

        if (rowMap.isEmpty()) return Pair(frozenCols, frozenRows)

        var prevRow = 0
        for ((rowIdx, cells) in rowMap) {
            val gap = rowIdx - prevRow - 1
            if (gap > MAX_SUPPORTED_ROW_GAP) {
                throw IllegalArgumentException(
                    "Worksheet contains an unsupported row gap of $gap empty rows between row $prevRow and row $rowIdx (max continuous gap: $MAX_SUPPORTED_ROW_GAP). Large sparse spreadsheets cannot be rendered without data misplacement or excessive empty pages."
                )
            }
            if (gap in 1..MAX_SUPPORTED_ROW_GAP) {
                for (g in 0 until gap) {
                    outRows.add(emptyList())
                }
            }
            outRows.add(cells)
            outSparseRows?.add(XlsxRow(rowIndex = rowIdx, cells = cells))
            prevRow = rowIdx
        }

        // Trim trailing completely empty rows from the end of the worksheet
        while (outRows.isNotEmpty() && outRows.last().all { it.isBlank() }) {
            outRows.removeAt(outRows.size - 1)
        }
        return Pair(frozenCols, frozenRows)
    }
}
