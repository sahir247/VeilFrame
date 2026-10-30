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
        try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("word/document.xml") ?: return DocxDocument(emptyList())
                zip.getInputStream(entry).use { stream ->
                    parseDocxStream(stream, nodes)
                }
            }
        } catch (_: Throwable) {}
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

    data class XlsxSheetData(
        val name: String,
        val rows: List<List<String>>
    )

    data class XlsxWorkbookData(
        val sheets: List<XlsxSheetData>
    )

    fun parseXlsx(file: File): XlsxWorkbookData {
        val sheets = mutableListOf<XlsxSheetData>()
        try {
            ZipFile(file).use { zip ->
                // 1. Parse shared strings table (xl/sharedStrings.xml)
                val sharedStrings = mutableListOf<String>()
                val sstEntry = zip.getEntry("xl/sharedStrings.xml")
                if (sstEntry != null) {
                    zip.getInputStream(sstEntry).use { stream ->
                        parseSharedStrings(stream, sharedStrings)
                    }
                }

                // 2. Parse sheet list from xl/workbook.xml
                val sheetNames = mutableListOf<String>()
                val wbEntry = zip.getEntry("xl/workbook.xml")
                if (wbEntry != null) {
                    zip.getInputStream(wbEntry).use { stream ->
                        parseWorkbookSheetNames(stream, sheetNames)
                    }
                }

                // 3. Parse individual sheets
                var sheetIdx = 1
                while (true) {
                    val sheetEntry = zip.getEntry("xl/worksheets/sheet$sheetIdx.xml") ?: break
                    val name = if (sheetIdx - 1 < sheetNames.size) sheetNames[sheetIdx - 1] else "Sheet$sheetIdx"
                    val sheetRows = mutableListOf<List<String>>()
                    zip.getInputStream(sheetEntry).use { stream ->
                        parseWorksheet(stream, sharedStrings, sheetRows)
                    }
                    sheets.add(XlsxSheetData(name = name, rows = sheetRows))
                    sheetIdx++
                }
            }
        } catch (_: Throwable) {}
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

    private fun parseWorkbookSheetNames(stream: InputStream, outNames: MutableList<String>) {
        val doc = newDocBuilder().parse(stream)
        val sheetList = doc.getElementsByTagName("sheet")
        for (i in 0 until sheetList.length) {
            val sheet = sheetList.item(i) as Element
            val name = sheet.getAttribute("name")
            if (name.isNotEmpty()) {
                outNames.add(name)
            }
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
        outRows: MutableList<List<String>>
    ) {
        val doc = newDocBuilder().parse(stream)
        val rowList = doc.getElementsByTagName("row")
        for (i in 0 until rowList.length) {
            val rowElem = rowList.item(i) as Element
            val rowCells = mutableListOf<String>()
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

                    val targetCol = if (cellRef.isNotEmpty()) colRefToIndex(cellRef) else rowCells.size
                    if (targetCol >= 0) {
                        while (rowCells.size < targetCol) {
                            rowCells.add("")
                        }
                        if (rowCells.size == targetCol) {
                            rowCells.add(cellVal)
                        } else {
                            rowCells[targetCol] = cellVal
                        }
                    } else {
                        rowCells.add(cellVal)
                    }
                }
                child = child.nextSibling
            }
            if (rowCells.any { it.isNotBlank() }) {
                outRows.add(rowCells)
            }
        }
    }
}
