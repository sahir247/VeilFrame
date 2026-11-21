package com.veilframe.app.pdf.operations

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import org.xml.sax.InputSource
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * High-performance, offline OpenXML (.docx and .xlsx) parser.
 * Operates purely on standard Java ZIP streams and standard DOM XML parsing,
 * bypassing Apache POI/AWT for the supported fallback document subset.
 * Explicit XML admission bounds are not a guarantee of heap usage or full Office fidelity.
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

    private const val MAX_XML_PART_BYTES = 16L * 1024 * 1024
    private const val MAX_XML_WORKBOOK_BYTES = 64L * 1024 * 1024

    /**
     * Measure actual inflated bytes, not just ZIP metadata. The fallback uses DOM,
     * so its memory admission must happen before constructing the node tree.
     * OpenXML XML parts use Unicode encodings; decode explicitly so the DTD guard
     * cannot be bypassed by a different encoding or unsupported Android XML feature.
     */
    internal class XmlReadBudget(private val totalLimit: Long = MAX_XML_WORKBOOK_BYTES,
                                 private val partLimit: Long = MAX_XML_PART_BYTES) {
        private var consumed = 0L
        fun read(stream: InputStream, reportedSize: Long = -1): String {
            val allowed = minOf(totalLimit - consumed, partLimit)
            if (allowed <= 0 || reportedSize > allowed) throw IOException("OpenXML XML admission limit exceeded")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("OpenXML XML stream made no progress")
                if (out.size().toLong() + count > allowed) throw IOException("OpenXML XML admission limit exceeded")
                out.write(buffer, 0, count)
            }
            val bytes = out.toByteArray()
            consumed += bytes.size
            fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255) ?: -1
            val (charset, offset) = when {
                b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> Charsets.UTF_8 to 3
                b(0) == 0xFE && b(1) == 0xFF -> Charsets.UTF_16BE to 2
                b(0) == 0xFF && b(1) == 0xFE -> Charsets.UTF_16LE to 2
                b(0) == 0 && b(1) == 0x3C -> Charsets.UTF_16BE to 0
                b(0) == 0x3C && b(1) == 0 -> Charsets.UTF_16LE to 0
                else -> Charsets.UTF_8 to 0
            }
            val xml = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
            // DTDs are unnecessary in OpenXML; refuse instead of expanding entities.
            if (xml.contains("<!DOCTYPE") || xml.indexOf('\u0000') >= 0) {
                throw IOException("OpenXML DTD or invalid XML character is not supported")
            }
            return xml
        }
        fun read(zip: ZipFile, entry: ZipEntry): String =
            zip.getInputStream(entry).use { read(it, entry.size) }
    }

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            for ((feature, value) in listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false
            )) {
                try { setFeature(feature, value) } catch (_: javax.xml.parsers.ParserConfigurationException) {}
            }
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External OpenXML entities are forbidden") }
        return builder.parse(InputSource(StringReader(xml)))
    }

    private fun Node.localName(): String = nodeName.substringAfterLast(':')

    private fun Node.childElements(name: String): List<Element> = buildList {
        var child = firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && child.localName() == name) add(child as Element)
            child = child.nextSibling
        }
    }

    private fun Element.localAttribute(name: String): String {
        val matches = (0 until attributes.length).map { attributes.item(it) }
            .filter { it.localName() == name && !it.nodeName.startsWith("xmlns") }
        require(matches.size <= 1) { "Ambiguous OpenXML attribute: $name" }
        return matches.singleOrNull()?.nodeValue.orEmpty()
    }

    // Only direct display text and rich-text runs are visible cell text.
    // Phonetic guides (rPh) and extension metadata must not enter cell values.
    private fun spreadsheetRichText(element: Element): String = buildString {
        var child = element.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                when (child.localName()) {
                    "t" -> append(child.textContent.orEmpty())
                    "r" -> child.childElements("t").forEach { append(it.textContent.orEmpty()) }
                }
            }
            child = child.nextSibling
        }
    }

    fun parseDocx(file: File): DocxDocument {
        val nodes = mutableListOf<DocxNode>()
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: throw java.io.IOException("Word document XML missing")
            parseDocxStream(XmlReadBudget().read(zip, entry), nodes)
        }
        return DocxDocument(nodes)
    }

    private fun parseDocxStream(xml: String, outNodes: MutableList<DocxNode>) {
        val doc = parseXml(xml)
        require(doc.documentElement.localName() == "document") { "Invalid Word document XML root" }
        val body = doc.documentElement.childElements("body").singleOrNull()
            ?: throw IOException("Word document body missing or ambiguous")

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

        // Word hyperlinks, inserted text and content controls contain visible runs
        // in nested containers. Ignore deleted text and metadata rather than
        // dropping all nested runs or importing hidden revision text.
        fun appendVisibleRuns(element: Element, depth: Int) {
            require(depth <= 64) { "Word inline nesting limit exceeded" }
            when (element.localName()) {
                "r" -> parseRun(element, isHeading, headingLevel)?.let {
                    if (it.text.isNotEmpty()) runs.add(it)
                }
                "hyperlink", "smartTag", "ins", "moveTo", "fldSimple", "customXml", "sdtContent", "sdt" -> {
                    var nested = element.firstChild
                    while (nested != null) {
                        if (nested.nodeType == Node.ELEMENT_NODE) appendVisibleRuns(nested as Element, depth + 1)
                        nested = nested.nextSibling
                    }
                }
            }
        }

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
                                        val styleVal = (pPrElem.localAttribute("val")).lowercase()
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
                                        val jcVal = (pPrElem.localAttribute("val")).lowercase()
                                        if (jcVal in listOf("center", "right", "both")) {
                                            alignment = jcVal
                                        }
                                    }
                                    "spacing" -> {
                                        val before = pPrElem.localAttribute("before")
                                        val after = pPrElem.localAttribute("after")
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
                    else -> appendVisibleRuns(elem, 0)
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
                                        val v = rPrElem.localAttribute("val")
                                        isBold = v.isEmpty() || v == "1" || v.equals("true", ignoreCase = true)
                                    }
                                    "i" -> {
                                        val v = rPrElem.localAttribute("val")
                                        isItalic = v.isEmpty() || v == "1" || v.equals("true", ignoreCase = true)
                                    }
                                    "sz" -> {
                                        val v = rPrElem.localAttribute("val")
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
                    "br", "cr" -> sb.append("\n")
                    "tab" -> sb.append("\t")
                    "noBreakHyphen" -> sb.append('\u2011')
                    "softHyphen" -> sb.append('\u00AD')
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
        val frozenRows: Int = 0,
        val cachedFormulaCount: Int = 0,
        val dimensions: XlsxDimensions = XlsxDimensions(),
        val styles: XlsxCellStyles = XlsxCellStyles(),
        val unsupportedConditionalRules: Int = 0,
        val charts: List<XlsxCachedChart> = emptyList(),
        val unsupportedDrawings: Int = 0,
        val printSettings: XlsxSavedPrintSettings = XlsxSavedPrintSettings(),
        val visible: Boolean = true
    )

    const val MAX_SUPPORTED_ROW_GAP = 500

    data class XlsxWorkbookData(
        val sheets: List<XlsxSheetData>
    )

    private data class SheetPart(val name: String, val path: String, val visible: Boolean)
    private data class WorkbookRelationship(val type: String, val path: String)

    private fun relatedPartPath(source: String, target: String): String {
        val uri = java.net.URI(target)
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null &&
            uri.rawFragment == null && !target.contains('\\')) { "Invalid workbook relationship target" }
        val resolved = java.net.URI("/" + source).resolve(uri).normalize().path
        require(resolved.startsWith("/") && !resolved.startsWith("//") &&
            resolved.split('/').none { it == ".." || it == "." }) { "Invalid workbook part path" }
        return resolved.removePrefix("/")
    }

    private fun partRelationships(zip: ZipFile, budget: XmlReadBudget, source: String): Map<String, WorkbookRelationship>? {
        val relPath = source.substringBeforeLast('/', "") + "/_rels/" + source.substringAfterLast('/') + ".rels"
        val entry = zip.getEntry(relPath.removePrefix("/")) ?: return null
        val doc = parseXml(budget.read(zip, entry))
        require(doc.documentElement.localName() == "Relationships") { "Invalid workbook relationships root" }
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
            require(result.put(id, WorkbookRelationship(type, relatedPartPath(source, node.getAttribute("Target")))) == null) {
                "Duplicate workbook relationship ID"
            }
        }
        return result
    }

    private fun orderedSheetParts(zip: ZipFile, relationships: Map<String, WorkbookRelationship>?, doc: org.w3c.dom.Document): List<SheetPart> {
        require(doc.documentElement.localName() == "workbook") { "Invalid workbook XML root" }
        val nodes = doc.getElementsByTagName("*")
        val parts = mutableListOf<SheetPart>()
        val usedPaths = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            if (element.localName() != "sheet") continue
            val title = element.getAttribute("name")
            require(title.isNotBlank()) { "Workbook contains an unnamed sheet" }
            val visible = when (element.getAttribute("state")) {
                "", "visible" -> true
                "hidden", "veryHidden" -> false
                else -> throw IOException("Invalid workbook sheet visibility: $title")
            }
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
            parts.add(SheetPart(title, path, visible))
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
        var totalGridCells = 0L
        var totalTextChars = 0L
        ZipFile(file).use { zip ->
            val budget = XmlReadBudget()
            val relationships = partRelationships(zip, budget, "xl/workbook.xml")
            val workbookEntry = zip.getEntry("xl/workbook.xml") ?: throw IOException("Workbook XML missing")
            val workbook = parseXml(budget.read(zip, workbookEntry))
            val parts = orderedSheetParts(zip, relationships, workbook)
            val properties = workbook.documentElement.childElements("workbookPr")
            require(properties.size <= 1) { "Ambiguous workbook date system" }
            val date1904 = when (properties.singleOrNull()?.getAttribute("date1904").orEmpty()) {
                "", "0", "false" -> false
                "1", "true" -> true
                else -> throw IOException("Invalid workbook date system")
            }
            val styleParts = relationships?.values?.filter { it.type.endsWith("/styles") }.orEmpty()
            require(styleParts.size <= 1) { "Ambiguous workbook styles relationship" }
            val styleEntry = styleParts.singleOrNull()?.let {
                zip.getEntry(it.path) ?: throw IOException("Missing workbook styles part")
            } ?: zip.getEntry("xl/styles.xml")
            val themeParts = relationships?.values?.filter { it.type.endsWith("/theme") }.orEmpty()
            require(themeParts.size <= 1) { "Ambiguous workbook theme relationship" }
            val themeEntry = themeParts.singleOrNull()?.let {
                zip.getEntry(it.path) ?: throw IOException("Missing workbook theme part")
            } ?: zip.getEntry("xl/theme/theme1.xml")
            val theme = themeEntry?.let { parseXlsxTheme(budget.read(zip, it)) }.orEmpty()
            val styleTable = if (styleEntry == null) ParsedXlsxStyles(
                XlsxValueFormatter(date1904, listOf(0), emptyMap()), listOf(XlsxCellStyle()))
                else parseXlsxStyles(budget.read(zip, styleEntry), date1904, theme)
            val sharedStrings = mutableListOf<String>()
            val sharedParts = relationships?.values?.filter { it.type.endsWith("/sharedStrings") }.orEmpty()
            require(sharedParts.size <= 1) { "Ambiguous workbook shared strings relationship" }
            val sharedPart = sharedParts.singleOrNull()?.path
            val sstEntry = if (sharedPart != null) zip.getEntry(sharedPart)
                ?: throw java.io.IOException("Missing shared strings part")
                else zip.getEntry("xl/sharedStrings.xml")
            if (sstEntry != null) {
                parseSharedStrings(budget.read(zip, sstEntry), sharedStrings)
            }
            val definedNames = workbook.documentElement.childElements("definedNames").flatMap { it.childElements("definedName") }
            for ((sheetIndex, part) in parts.withIndex()) {
                val sheetRows = mutableListOf<List<String>>()
                val sheetSparseRows = mutableListOf<XlsxRow>()
                val sheetMergedRegions = mutableListOf<CellRangeAddress>()
                val metadata = parseWorksheet(
                    budget.read(zip, requireNotNull(zip.getEntry(part.path))),
                    sharedStrings, styleTable, sheetRows, sheetSparseRows, sheetMergedRegions
                )
                val geometry = SpreadsheetGeometry.inspect(sheetRows, sheetMergedRegions)
                totalGridCells += geometry.cells
                totalTextChars += geometry.textChars
                require(totalGridCells <= SpreadsheetGeometry.MAX_WORKBOOK_CELLS &&
                    totalTextChars <= SpreadsheetGeometry.MAX_WORKBOOK_TEXT_CHARS) {
                    "Workbook exceeds aggregate worksheet layout budget"
                }
                val (charts, unsupportedDrawings) = readWorksheetCharts(zip, budget, part.path, metadata.drawingIds)
                require(sheets.sumOf { it.charts.size } + charts.size <= 64) { "Workbook chart count exceeds budget" }
                val localNames = definedNames.filter { it.getAttribute("localSheetId").toIntOrNull() == sheetIndex }
                val printAreas = localNames.filter { it.getAttribute("name") == "_xlnm.Print_Area" }
                val printTitles = localNames.filter { it.getAttribute("name") == "_xlnm.Print_Titles" }
                sheets.add(XlsxSheetData(
                    name = part.name, rows = sheetRows, sparseRows = sheetSparseRows,
                    visible = part.visible,
                    mergedRegions = sheetMergedRegions, frozenColumns = metadata.frozenCols, frozenRows = metadata.frozenRows,
                    cachedFormulaCount = metadata.cachedFormulaCount, dimensions = metadata.dimensions,
                    styles = metadata.styles, unsupportedConditionalRules = metadata.unsupportedConditionalRules,
                    charts = charts, unsupportedDrawings = unsupportedDrawings,
                    printSettings = metadata.printSettings.copy(area = printAreas.singleOrNull()?.textContent,
                        titles = printTitles.singleOrNull()?.textContent, ambiguousTitles = printTitles.size > 1,
                        ambiguousArea = printAreas.size > 1)
                ))
            }
        }
        return XlsxWorkbookData(sheets)
    }

    private fun relationshipId(element: Element): String {
        val attrs = element.attributes
        val ids = (0 until attrs.length).map { attrs.item(it) }
            .filter { it.nodeName.substringAfterLast(':') == "id" }.map { it.nodeValue }
        require(ids.size == 1 && ids.single().isNotBlank()) { "Missing or ambiguous drawing relationship ID" }
        return ids.single()
    }

    private fun descendants(element: Element, name: String): List<Element> {
        val nodes = element.getElementsByTagName("*")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.localName() == name }
    }

    private fun readWorksheetCharts(zip: ZipFile, budget: XmlReadBudget, sheet: String,
        drawingIds: List<String>): Pair<List<XlsxCachedChart>, Int> {
        if (drawingIds.isEmpty()) return emptyList<XlsxCachedChart>() to 0
        require(drawingIds.size <= 16 && drawingIds.distinct().size == drawingIds.size) { "Ambiguous worksheet drawings" }
        val sheetRels = partRelationships(zip, budget, sheet).orEmpty()
        val charts = mutableListOf<XlsxCachedChart>()
        var unsupported = 0
        for (id in drawingIds) {
            val rel = sheetRels[id] ?: throw IOException("Missing worksheet drawing relationship")
            require(rel.type.endsWith("/drawing")) { "Invalid worksheet drawing relationship type" }
            val entry = zip.getEntry(rel.path) ?: throw IOException("Missing worksheet drawing part")
            val drawing = parseXml(budget.read(zip, entry)).documentElement
            require(drawing.localName() == "wsDr") { "Invalid spreadsheet drawing root" }
            val drawingRels = partRelationships(zip, budget, rel.path).orEmpty()
            val refs = descendants(drawing, "chart")
            unsupported += descendants(drawing, "pic").size + descendants(drawing, "sp").size +
                descendants(drawing, "graphicFrame").size.coerceAtLeast(refs.size) - refs.size
            require(refs.size + charts.size <= 32) { "Worksheet chart count exceeds budget" }
            for (ref in refs) {
                val chartRel = drawingRels[relationshipId(ref)] ?: throw IOException("Missing drawing chart relationship")
                require(chartRel.type.endsWith("/chart")) { "Invalid drawing chart relationship type" }
                val chartEntry = zip.getEntry(chartRel.path) ?: throw IOException("Missing chart part")
                val chart = parseCachedChart(parseXml(budget.read(zip, chartEntry)).documentElement)
                if (chart == null) unsupported++ else {
                    val anchor = chartSourceAnchor(ref)
                    charts.add(chart.copy(sourceAnchor = anchor.description,
                        printAreaAnchorTrusted = anchor.printAreaTrusted))
                }
            }
        }
        return charts to unsupported
    }

    private data class ChartAnchor(val description: String?, val printAreaTrusted: Boolean)

    private fun chartSourceAnchor(ref: Element): ChartAnchor {
        var node: Node? = ref.parentNode
        while (node is Element) {
            val anchor = node
            if (anchor.localName() in setOf("twoCellAnchor", "oneCellAnchor")) {
                fun cell(name: String): Pair<String, Boolean>? {
                    val marker = anchor.childElements(name).singleOrNull() ?: return null
                    val row = marker.childElements("row").singleOrNull()?.textContent?.toIntOrNull() ?: return null
                    val col = marker.childElements("col").singleOrNull()?.textContent?.toIntOrNull() ?: return null
                    require(row in 0 until 1_048_576 && col in 0 until 16_384) { "Invalid chart cell anchor" }
                    // Cell markers alone are not a full drawing rectangle. A
                    // nonzero/invalid offset can move a chart beyond the listed
                    // cells. Without measuring EMUs against the original sheet
                    // geometry it must not authorize excluding a chart from a
                    // saved print area. Keep provenance for full export.
                    val bounded = listOf("colOff", "rowOff").all { key ->
                        val parts = marker.childElements(key)
                        parts.size <= 1 && (parts.isEmpty() || parts.single().textContent.trim() == "0")
                    }
                    return (SpreadsheetLayoutEngine.colIndexToRef(col) + (row + 1)) to bounded
                }
                val first = cell("from") ?: return ChartAnchor(null, false)
                val last = cell("to")
                val description = if (last == null) first.first else "${first.first}:${last.first}"
                return ChartAnchor(description,
                    anchor.localName() == "twoCellAnchor" && last != null && first.second && last.second)
            }
            node = node.parentNode
        }
        return ChartAnchor(null, false)
    }

    private fun parseCachedChart(root: Element): XlsxCachedChart? {
        require(root.localName() == "chartSpace") { "Invalid spreadsheet chart root" }
        val chart = root.childElements("chart").singleOrNull() ?: return null
        val plot = chart.childElements("plotArea").singleOrNull() ?: return null
        val chartTypes = (0 until plot.childNodes.length).mapNotNull { plot.childNodes.item(it) as? Element }
            .filter { it.localName().endsWith("Chart") }
        val combo = chartTypes.size == 2 &&
            chartTypes.map { it.localName() }.toSet() == setOf("barChart", "lineChart")
        if (combo) {
            val bar = chartTypes.first { it.localName() == "barChart" }
            val line = chartTypes.first { it.localName() == "lineChart" }
            if (bar.childElements("barDir").singleOrNull()?.getAttribute("val") != "col" ||
                bar.childElements("grouping").singleOrNull()?.getAttribute("val") != "clustered" ||
                line.childElements("grouping").singleOrNull()?.getAttribute("val") !in setOf(null, "standard") ||
                bar.childElements("ser").isEmpty() || line.childElements("ser").isEmpty()) return null
            fun axes(type: Element) = type.childElements("axId").map { it.getAttribute("val") }
            val barAxes = axes(bar); val lineAxes = axes(line)
            // Secondary-axis charts cannot be mapped to one value scale.
            if (barAxes.size != 2 || barAxes.any { it.isBlank() } || barAxes.distinct().size != 2 ||
                barAxes.toSet() != lineAxes.toSet()) return null
        } else if (chartTypes.size != 1) return null
        val type = chartTypes.firstOrNull() ?: return null
        val grouping = type.childElements("grouping").singleOrNull()?.getAttribute("val")
        val kind = if (combo) "combo" else when (type.localName()) {
            "barChart" -> {
                if (grouping !in setOf("clustered", "stacked", "percentStacked")) return null
                when (type.childElements("barDir").singleOrNull()?.getAttribute("val")) {
                    "col" -> "column"
                    "bar" -> "bar"
                    else -> return null
                }
            }
            "pieChart" -> "pie"
            "scatterChart" -> {
                val style = type.childElements("scatterStyle").singleOrNull()?.getAttribute("val").orEmpty().ifEmpty { "marker" }
                if (style !in setOf("marker", "line", "lineMarker")) return null
                "scatter"
            }
            "lineChart" -> {
                if (type.childElements("grouping").singleOrNull()?.getAttribute("val") !in setOf(null, "standard")) return null
                "line"
            }
            else -> return null
        }
        fun text(element: Element?): String? {
            if (element == null) return null
            val str = descendants(element, "t").joinToString(" ") { it.textContent }
                .ifBlank { descendants(element, "v").firstOrNull()?.textContent.orEmpty() }
            require(str.length <= 1024) { "Chart label exceeds budget" }
            return str.takeIf { it.isNotBlank() }
        }
        fun seriesRgb(ser: Element): Int? {
            val sp = ser.childElements("spPr").singleOrNull() ?: return null
            val solid = sp.childElements("solidFill").singleOrNull()
                ?: sp.childElements("ln").singleOrNull()?.childElements("solidFill")?.singleOrNull()
                ?: return null
            val color = solid.childElements("srgbClr").singleOrNull()?.getAttribute("val") ?: return null
            return if (color.matches(Regex("[0-9A-Fa-f]{6}"))) color.toInt(16) else null
        }

        fun cache(parent: Element?, numeric: Boolean): List<String?>? {
            if (parent == null) return null
            val names = if (numeric) setOf("numCache", "numLit") else setOf("strCache", "strLit", "numCache", "numLit")
            val nodes = parent.getElementsByTagName("*")
            val caches = (0 until nodes.length)
                .mapNotNull { nodes.item(it) as? Element }
                .filter { it.localName() in names }
            val cache = caches.singleOrNull() ?: return null
            val count = cache.childElements("ptCount").singleOrNull()?.getAttribute("val")?.toIntOrNull() ?: return null
            require(count in 1..240) { "Chart point count exceeds supported budget" }
            val points = MutableList<String?>(count) { null }
            val seen = mutableSetOf<Int>()
            for (point in cache.childElements("pt")) {
                val index = point.getAttribute("idx").toIntOrNull()
                require(index != null && index in points.indices && seen.add(index)) { "Invalid chart cache point index" }
                val value = point.childElements("v").singleOrNull()?.textContent ?: return null
                require(value.length <= 1024) { "Chart point value exceeds budget" }
                points[index] = value
            }
            return points
        }
        val seriesTypes = if (combo) chartTypes else listOf(type)
        val series = seriesTypes.flatMap { it.childElements("ser") }
        val seriesKinds = seriesTypes.flatMap { t ->
            t.childElements("ser").map { if (t.localName() == "lineChart") "line" else "column" }
        }
        if (series.isEmpty() || series.size > 6 || (kind == "pie" && series.size != 1)) return null
        if (kind == "scatter") {
            val style = type.childElements("scatterStyle").singleOrNull()?.getAttribute("val").orEmpty().ifEmpty { "marker" }
            val parsedScatter = mutableListOf<XlsxCachedChart.Series>()
            var longest = 0
            for ((index, ser) in series.withIndex()) {
                val xs = cache(ser.childElements("xVal").singleOrNull(), true) ?: return null
                val ys = cache(ser.childElements("yVal").singleOrNull(), true) ?: return null
                if (xs.size != ys.size) return null
                fun numeric(cached: List<String?>): List<Double?>? {
                    val values = mutableListOf<Double?>()
                    for (value in cached) {
                        val number = value?.toDoubleOrNull()
                        if (value != null && (number == null || !number.isFinite())) return null
                        values.add(number)
                    }
                    return values
                }
                val xValues = numeric(xs) ?: return null
                val yValues = numeric(ys) ?: return null
                longest = maxOf(longest, xValues.size)
                parsedScatter.add(XlsxCachedChart.Series(
                    text(ser.childElements("tx").singleOrNull()) ?: "Series " + (index + 1),
                    yValues, xValues, rgb = seriesRgb(ser)))
            }
            // An all-empty cache is an unsupported drawing, not a chart that
            // can be assigned invented axes and subsequently abort the PDF.
            if (parsedScatter.none { series -> series.xValues.zip(series.values).any { (x,y) -> x != null && y != null } }) return null
            return XlsxCachedChart(text(chart.childElements("title").singleOrNull()) ?: "Chart",
                "scatter", List(longest) { (it + 1).toString() }, parsedScatter, scatterStyle = style)
        }
        var labels: List<String?>? = null
        val parsed = mutableListOf<XlsxCachedChart.Series>()
        for ((index, ser) in series.withIndex()) {
            val raw = cache(ser.childElements("val").singleOrNull(), true) ?: return null
            val values = raw.map { value ->
                if (value == null) null else value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            }
            if (kind == "pie" && (values.size > 12 || values.any { it != null && it < 0.0 } ||
                values.none { it != null && it > 0.0 })) return null
            val cat = ser.childElements("cat").singleOrNull()
            val categories = if (cat == null) List(values.size) { (it + 1).toString() }
                else cache(cat, false) ?: return null
            if (categories.size != values.size || (labels != null && labels != categories)) return null
            labels = categories
            parsed.add(XlsxCachedChart.Series(text(ser.childElements("tx").singleOrNull()) ?: "Series " + (index + 1),
                values, chartKind = if (combo) seriesKinds[index] else null, rgb = seriesRgb(ser)))
        }
        return XlsxCachedChart(text(chart.childElements("title").singleOrNull()) ?: "Chart", kind, labels.orEmpty(), parsed,
            grouping ?: "clustered")
    }

    private data class ParsedXlsxStyles(
        val formatter: XlsxValueFormatter, val visuals: List<XlsxCellStyle>,
        val differential: List<XlsxConditionalFormatting.Delta?> = emptyList()
    )

    private fun parseXlsxTheme(xml: String): Map<String, Int> {
        val root = parseXml(xml).documentElement
        require(root.localName() == "theme") { "Invalid workbook theme root" }
        val elements = root.childElements("themeElements").singleOrNull()
            ?: throw IOException("Missing or ambiguous workbook theme elements")
        val scheme = elements.childElements("clrScheme").singleOrNull()
            ?: throw IOException("Missing or ambiguous workbook color scheme")
        val result = mutableMapOf<String, Int>()
        for (slot in XlsxColors.themeSlots) {
            val entries = scheme.childElements(slot)
            require(entries.size <= 1) { "Duplicate theme color slot" }
            val element = entries.singleOrNull() ?: continue
            val srgb = element.childElements("srgbClr")
            val system = element.childElements("sysClr")
            require(srgb.size + system.size <= 1) { "Ambiguous theme color" }
            val value = srgb.singleOrNull()?.getAttribute("val")
                ?: system.singleOrNull()?.getAttribute("lastClr")?.takeIf { it.isNotEmpty() }
                ?: continue
            result[slot] = XlsxColors.parseRgb(value)
        }
        return result
    }

    private fun parseXlsxStyles(xml: String, date1904: Boolean, theme: Map<String, Int>): ParsedXlsxStyles {
        val root = parseXml(xml).documentElement
        require(root.localName() == "styleSheet") { "Invalid workbook styles root" }
        fun collection(name: String, item: String): List<Element> {
            val groups = root.childElements(name)
            require(groups.size <= 1) { "Ambiguous style collection" }
            val entries = groups.singleOrNull()?.childElements(item).orEmpty()
            require(entries.size <= 8192) { "Workbook style count exceeds admission limit" }
            return entries
        }
        fun id(value: String): Int {
            val result = value.toIntOrNull()
            require(result != null && result in 0..65535) { "Invalid number format ID" }
            return result
        }
        val custom = mutableMapOf<Int, String>()
        for (entry in collection("numFmts", "numFmt")) {
            val key = id(entry.getAttribute("numFmtId"))
            val code = entry.getAttribute("formatCode")
            require(code.isNotEmpty() && code.length <= 256 && custom.put(key, code) == null) {
                "Invalid or duplicate number format"
            }
        }
        val palettes = root.childElements("colors").flatMap { it.childElements("indexedColors") }
        require(palettes.size <= 1) { "Ambiguous indexed color palette" }
        val indexed = palettes.singleOrNull()?.childElements("rgbColor")?.let { entries ->
            require(entries.size in 1..256) { "Invalid indexed color palette length" }
            entries.map { XlsxColors.parseArgb(it.getAttribute("rgb")) }
        }
        fun rgb(element: Element?): Int? {
            if (element == null) return null
            val sources = listOf("rgb", "theme", "indexed").filter { element.hasAttribute(it) }
            require(sources.size <= 1) { "Ambiguous cell color source" }
            val tint = if (element.hasAttribute("tint")) {
                val parsed = element.getAttribute("tint").toDoubleOrNull()
                require(parsed != null && parsed.isFinite() && parsed in -1.0..1.0) { "Invalid cell tint" }
                parsed
            } else 0.0
            val base = when (sources.singleOrNull()) {
                "rgb" -> XlsxColors.parseArgb(element.getAttribute("rgb"))
                "theme" -> {
                    val index = element.getAttribute("theme").toIntOrNull()
                    require(index != null && index in XlsxColors.themeSlots.indices) { "Invalid theme color index" }
                    theme[XlsxColors.themeSlots[index]]
                }
                "indexed" -> {
                    val index = element.getAttribute("indexed").toIntOrNull()
                        ?: throw IOException("Invalid indexed color")
                    XlsxColors.indexed(index, indexed)
                }
                else -> null // Automatic/system-dependent or absent color.
            }
            return base?.let { XlsxColors.tint(it, tint) }
        }
        fun one(parent: Element, name: String): Element? {
            val children = parent.childElements(name)
            require(children.size <= 1) { "Ambiguous cell style property: $name" }
            return children.singleOrNull()
        }
        fun flag(element: Element?): Boolean = when (element?.getAttribute("val")) {
            null, "0", "false" -> false
            "", "1", "true" -> true
            else -> throw IOException("Invalid cell font Boolean")
        }
        val fonts = collection("fonts", "font").map {
            XlsxCellStyle(bold = flag(one(it, "b")), italic = flag(one(it, "i")),
                textRgb = rgb(one(it, "color")), fontSizePt = one(it, "sz")?.getAttribute("val")?.let { raw ->
                    raw.toFloatOrNull()?.takeIf { size -> size.isFinite() && size in 1f..144f }
                        ?: throw IOException("Invalid or unsupported worksheet font size")
                }, fontFace = one(it, "name")?.getAttribute("val")?.takeIf { raw ->
                    raw.isNotBlank() && raw.length <= 128
                })
        }.ifEmpty { listOf(XlsxCellStyle()) }
        val fills = collection("fills", "fill").map {
            val pattern = one(it, "patternFill")
            if (pattern?.getAttribute("patternType") == "solid") rgb(one(pattern, "fgColor")) else null
        }.ifEmpty { listOf(null) }
        val borders = collection("borders", "border").map { border ->
            fun side(name: String): XlsxBorderLine? {
                val element = one(border, name) ?: return null
                val kind = element.getAttribute("style")
                if (kind.isEmpty() || kind == "none") return null
                require(kind in XlsxBorderLine.supported) { "Unsupported orthogonal border style" }
                return XlsxBorderLine(kind, rgb(one(element, "color")) ?: 0)
            }
            XlsxCellBorders(side("left"), side("right"), side("top"), side("bottom"))
        }.ifEmpty { listOf(XlsxCellBorders()) }
        data class Xf(val number: Int = 0, val font: Int = 0, val fill: Int = 0,
                      val align: String = "left", val border: Int = 0, val vertical: String = "top", val rotation: Int = 0)
        fun resolve(xf: Element, base: Xf): Xf {
            fun selected(attribute: String, apply: String, inherited: Int): Int =
                when (xf.getAttribute(apply)) {
                    "0", "false" -> inherited
                    "", "1", "true" -> xf.getAttribute(attribute).takeIf { it.isNotEmpty() }?.let(::id) ?: inherited
                    else -> throw IOException("Invalid style application flag")
                }
            val alignment = when (xf.getAttribute("applyAlignment")) {
                "0", "false" -> null
                "", "1", "true" -> one(xf, "alignment")
                else -> throw IOException("Invalid alignment application flag")
            }
            val align = alignment?.getAttribute("horizontal")?.takeIf { it.isNotEmpty() } ?: base.align
            val vertical = alignment?.getAttribute("vertical")?.takeIf { it.isNotEmpty() } ?: base.vertical
            val rotation = alignment?.getAttribute("textRotation")?.takeIf { it.isNotEmpty() }?.let {
                val value = it.toIntOrNull()
                require(value != null && (value in 0..180 || value == 255)) { "Invalid worksheet text rotation" }
                value
            } ?: base.rotation
            val result = Xf(selected("numFmtId", "applyNumberFormat", base.number),
                selected("fontId", "applyFont", base.font),
                selected("fillId", "applyFill", base.fill), align,
                selected("borderId", "applyBorder", base.border), vertical, rotation)
            require(result.font in fonts.indices && result.fill in fills.indices && result.border in borders.indices) {
                "Invalid cell font/fill/border reference"
            }
            return result
        }
        val parents = collection("cellStyleXfs", "xf").map { resolve(it, Xf()) }
        val cells = collection("cellXfs", "xf").map { xf ->
            val parent = xf.getAttribute("xfId").takeIf { it.isNotEmpty() }?.let {
                parents.getOrNull(id(it)) ?: throw IOException("Invalid parent cell style")
            } ?: Xf()
            resolve(xf, parent)
        }.ifEmpty { listOf(Xf()) }
        val visuals = cells.map {
            fonts[it.font].copy(fillRgb = fills[it.fill], borders = borders[it.border], horizontal = when (it.align) {
                "center", "right", "left" -> it.align
                else -> "left"
            }, vertical = when (it.vertical) {
                "top", "center", "bottom" -> it.vertical
                else -> "top"
            }, textRotation = it.rotation)
        }
        val differential = collection("dxfs", "dxf").map { dxf ->
            val font = one(dxf, "font")
            val pattern = one(dxf, "fill")?.let { one(it, "patternFill") }
            val allowed = dxf.childElements("font").size + dxf.childElements("fill").size
            val invalid = (0 until dxf.childNodes.length).map { dxf.childNodes.item(it) }
                .count { it.nodeType == Node.ELEMENT_NODE } != allowed ||
                (font != null && (0 until font.childNodes.length).map { font.childNodes.item(it) }
                    .any { it.nodeType == Node.ELEMENT_NODE && it.localName() !in setOf("b", "i", "color") }) ||
                (one(dxf, "fill") != null && pattern == null) ||
                (pattern != null && pattern.getAttribute("patternType") !in setOf("", "solid"))
            if (invalid) null else XlsxConditionalFormatting.Delta(
                bold = font?.let { one(it, "b") }?.let(::flag),
                italic = font?.let { one(it, "i") }?.let(::flag),
                textRgb = font?.let { rgb(one(it, "color")) },
                fillRgb = pattern?.let { rgb(one(it, "fgColor")) ?: rgb(one(it, "bgColor")) }
            )
        }
        return ParsedXlsxStyles(XlsxValueFormatter(date1904, cells.map { it.number }, custom), visuals, differential)
    }

    private fun parseSharedStrings(xml: String, outStrings: MutableList<String>) {
        val doc = parseXml(xml)
        require(doc.documentElement.localName() == "sst") { "Unsupported shared strings XML root" }
        for (item in doc.documentElement.childElements("si")) {
            outStrings.add(spreadsheetRichText(item))
        }
    }

    private const val MAX_XLSX_COLUMNS = 16_384
    private const val MAX_XLSX_ROWS = 1_048_576
    // Limits expanded dense placeholder cells, not the width or paper size of an
    // otherwise admitted workbook. Reject instead of truncating.
    private const val MAX_EXPANDED_CELLS_PER_SHEET = 2_000_000L

    fun colRefToIndex(ref: String): Int {
        var col = 0
        var hasLetters = false
        for (ch in ref) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
                if (col > MAX_XLSX_COLUMNS) return -1
                hasLetters = true
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
                if (col > MAX_XLSX_COLUMNS) return -1
                hasLetters = true
            } else {
                break
            }
        }
        return if (hasLetters && col in 1..MAX_XLSX_COLUMNS) col - 1 else -1
    }

    internal fun parseMergeRange(ref: String): CellRangeAddress {
        val parts = ref.split(':')
        require(parts.size == 2) { "Invalid merged range: $ref" }
        fun coordinate(value: String): Pair<Int, Int> {
            require(value.matches(Regex("[A-Za-z]{1,3}[1-9][0-9]{0,6}"))) { "Invalid merged cell reference" }
            val col = colRefToIndex(value)
            val row = value.dropWhile { it.isLetter() }.toIntOrNull()
            require(col in 0 until MAX_XLSX_COLUMNS && row != null && row in 1..MAX_XLSX_ROWS) {
                "Merged range exceeds worksheet bounds"
            }
            return (row - 1) to col
        }
        val (firstRow, firstCol) = coordinate(parts[0])
        val (lastRow, lastCol) = coordinate(parts[1])
        require(firstRow <= lastRow && firstCol <= lastCol &&
            (firstRow != lastRow || firstCol != lastCol)) { "Invalid or reversed merged range" }
        return CellRangeAddress(firstRow, lastRow, firstCol, lastCol)
    }

    private data class WorksheetMetadata(
        val frozenCols: Int, val frozenRows: Int, val cachedFormulaCount: Int, val dimensions: XlsxDimensions,
        val styles: XlsxCellStyles, val unsupportedConditionalRules: Int, val drawingIds: List<String>,
        val printSettings: XlsxSavedPrintSettings
    )

    private fun parsePrintSettings(root: Element): XlsxSavedPrintSettings {
        fun attrs(element: Element?): Map<String, String> {
            if (element == null) return emptyMap()
            return (0 until element.attributes.length).associate {
                val node = element.attributes.item(it)
                node.nodeName.substringAfterLast(':') to node.nodeValue
            }
        }
        fun one(name: String) = root.childElements(name).singleOrNull()
        val setup = attrs(one("pageSetup"))
        val props = attrs(one("sheetPr")?.childElements("pageSetUpPr")?.singleOrNull())
        val options = attrs(one("printOptions"))
        val headings = when (options["headings"]) {
            null, "", "0", "false" -> false
            "1", "true" -> true
            else -> throw IOException("Invalid saved print-heading flag")
        }
        var partialBreaks = false
        fun breaks(name: String, fullExtent: Int): List<String> {
            val result = mutableListOf<String>()
            for (brk in one(name)?.childElements("brk").orEmpty().filter { it.getAttribute("man") in setOf("1", "true") }) {
                val min = brk.getAttribute("min").ifEmpty { "0" }.toIntOrNull()
                val max = brk.getAttribute("max").ifEmpty { fullExtent.toString() }.toIntOrNull()
                if (min == 0 && max == fullExtent) result.add(brk.getAttribute("id"))
                else partialBreaks = true // do not promote a partial break to a whole-sheet break
            }
            return result
        }
        val rowBreaks = breaks("rowBreaks", 16383)
        val colBreaks = breaks("colBreaks", 1048575)
        val headerFooter = XlsxPrintHeaderFooter.parse(one("headerFooter")?.let { element ->
            fun part(name: String): String {
                val nodes = element.childElements(name)
                require(nodes.size <= 1) { "Duplicate worksheet $name" }
                return nodes.singleOrNull()?.textContent.orEmpty()
            }
            val raw = mutableMapOf<String,String>()
            for (name in listOf("oddHeader","oddFooter","evenHeader","evenFooter","firstHeader","firstFooter"))
                raw[name] = part(name)
            raw["differentOddEven"] = element.getAttribute("differentOddEven")
            raw["differentFirst"] = element.getAttribute("differentFirst")
            raw
        })
        fun hiddenFlag(element: Element): Boolean? = when (element.getAttribute("hidden")) {
            "" -> null
            "1", "true" -> true
            "0", "false" -> false
            else -> throw IOException("Invalid hidden geometry flag")
        }
        val hiddenColumns = mutableSetOf<Int>()
        for (column in root.childElements("cols").flatMap { it.childElements("col") }) {
            if (hiddenFlag(column) != true) continue
            val first = column.getAttribute("min").toIntOrNull()
            val last = column.getAttribute("max").toIntOrNull()
            require(first != null && last != null && first in 1..MAX_XLSX_COLUMNS && last in first..MAX_XLSX_COLUMNS) {
                "Invalid hidden column range"
            }
            for (c in first-1 until last) hiddenColumns.add(c)
        }
        val rowVisibility = mutableMapOf<Int,Boolean>()
        var auto = 1
        for (row in one("sheetData")?.childElements("row").orEmpty()) {
            val index = row.getAttribute("r").ifEmpty { auto.toString() }.toIntOrNull()
            require(index != null && index in 1..MAX_XLSX_ROWS) { "Invalid hidden row reference" }
            auto = maxOf(auto,index+1)
            hiddenFlag(row)?.let { flag ->
                val prior = rowVisibility.put(index-1, flag)
                require(prior == null || prior == flag) { "Conflicting hidden row declarations" }
            }
        }
        val hiddenRows = rowVisibility.filterValues { it }.keys.toSet()
        val visibleRows = rowVisibility.filterValues { !it }.keys.toSet()
        val zeroHeight = when (one("sheetFormatPr")?.getAttribute("zeroHeight").orEmpty()) {
            "", "0", "false" -> false
            "1", "true" -> true
            else -> throw IOException("Invalid default hidden-row flag")
        }
        return XlsxSavedPrintSettings(setup = setup, margins = attrs(one("pageMargins")),
            fitToPage = props["fitToPage"] in setOf("1", "true"), gridLines = options["gridLines"] in setOf("1", "true"),
            centerHorizontal = options["horizontalCentered"] in setOf("1", "true"),
            centerVertical = options["verticalCentered"] in setOf("1", "true"),
            printHeadings = headings,
            rowBreaks = rowBreaks, colBreaks = colBreaks, hiddenRows = hiddenRows, hiddenColumns = hiddenColumns,
            defaultRowsHidden = zeroHeight, visibleRows = visibleRows,
            headerFooter = headerFooter,
            unsupportedFeatures = partialBreaks ||
                setup["blackAndWhite"] in setOf("1", "true") || setup["draft"] in setOf("1", "true"))
    }

    private fun parseConditionalFormatting(root: Element, differential: List<XlsxConditionalFormatting.Delta?>):
        List<XlsxConditionalFormatting.Rule> {
        val result = mutableListOf<XlsxConditionalFormatting.Rule>()
        val priorities = mutableSetOf<Int>()
        var rangeCount = 0
        for (group in root.childElements("conditionalFormatting")) {
            val ranges = group.getAttribute("sqref").trim().split(Regex("\\s+")).map { ref ->
                val clean = ref.replace("$", "")
                val endpoints = clean.split(':')
                val single = if (endpoints.size == 2 && endpoints[0].equals(endpoints[1], ignoreCase = true))
                    endpoints[0] else clean
                if (':' in single) parseMergeRange(single) else {
                    require(single.matches(Regex("[A-Za-z]{1,3}[1-9][0-9]{0,6}"))) { "Invalid conditional range" }
                    val c = colRefToIndex(single)
                    val r = single.dropWhile { it.isLetter() }.toInt()
                    require(c in 0 until MAX_XLSX_COLUMNS && r in 1..MAX_XLSX_ROWS) { "Conditional range out of bounds" }
                    CellRangeAddress(r - 1, r - 1, c, c)
                }
            }
            rangeCount += ranges.size
            require(rangeCount <= 2048) { "Too many conditional formatting ranges" }
            for (rule in group.childElements("cfRule")) {
                val priority = rule.getAttribute("priority").toIntOrNull()
                require(priority != null && priority > 0 && priorities.add(priority)) { "Invalid conditional priority" }
                val stop = when (rule.getAttribute("stopIfTrue")) {
                    "", "0", "false" -> false
                    "1", "true" -> true
                    else -> throw IOException("Invalid conditional stop flag")
                }
                val op = rule.getAttribute("operator")
                val operands = rule.childElements("formula").map { XlsxConditionalFormatting.number(it.textContent.trim()) }
                val id = rule.getAttribute("dxfId").toIntOrNull()
                if (rule.hasAttribute("dxfId")) require(id != null && id in differential.indices) { "Invalid differential style ID" }
                val type = rule.getAttribute("type")
                val literal = rule.getAttribute("text")
                val numeric = type == "cellIs" &&
                    op in XlsxConditionalFormatting.operators && operands.size == (if (op in setOf("between", "notBetween")) 2 else 1) &&
                    operands.all { it != null }
                // Only literal text rules: wildcards and arbitrary formula-based rules
                // are deliberately disclosed as unsupported rather than guessed.
                val text = type in XlsxConditionalFormatting.textOperators && literal.length in 1..1024 &&
                    literal.none { it in "*?~" } && (op.isEmpty() || op == type)
                val scale = if (type == "colorScale") parseConditionalColorScale(rule) else null
                val bar = if (type == "dataBar") parseConditionalDataBar(rule) else null
                val icons = if (type == "iconSet") parseConditionalIconSet(rule) else null
                val expression = if (type == "expression" && rule.childElements("formula").size == 1)
                    XlsxConditionalFormatting.expression(rule.childElements("formula").single().textContent, ranges) else null
                val delta = if (scale != null || bar != null || icons != null) XlsxConditionalFormatting.Delta() else if (numeric || text || expression != null) {
                    if (id == null) XlsxConditionalFormatting.Delta() else differential[id]
                } else null
                result.add(XlsxConditionalFormatting.Rule(priority, stop, ranges, if (text) type else op,
                    operands.filterNotNull(), delta, if (text) literal else null, scale, bar, icons, expression))
                require(result.size <= 256) { "Too many conditional formatting rules" }
            }
        }
        return result.sortedBy { it.priority }
    }

    private fun parseConditionalColorScale(rule: Element): XlsxConditionalFormatting.ColorScale? {
        val scale = rule.childElements("colorScale").singleOrNull() ?: return null
        val thresholds = scale.childElements("cfvo")
        val colors = scale.childElements("color")
        if (thresholds.size !in 2..3 || colors.size != thresholds.size) return null
        val stops = thresholds.zip(colors).map { (threshold, color) ->
            val type = threshold.getAttribute("type")
            if (type !in setOf("min", "max", "num", "percent", "percentile")) return null
            val raw = threshold.getAttribute("val")
            val value = if (type in setOf("num", "percent", "percentile"))
                XlsxConditionalFormatting.number(raw) ?: return null else null
            if (type in setOf("percent", "percentile") && (value == null ||
                value < java.math.BigDecimal.ZERO || value > java.math.BigDecimal("100"))) return null
            val rgb = color.getAttribute("rgb")
            if (!rgb.matches(Regex("[0-9A-Fa-f]{8}")) || color.hasAttribute("theme") ||
                color.hasAttribute("indexed") || color.hasAttribute("tint")) return null
            XlsxConditionalFormatting.ScaleStop(type, value, rgb.takeLast(6).toInt(16))
        }
        if (stops.drop(1).any { it.type == "min" } || stops.dropLast(1).any { it.type == "max" }) return null
        return XlsxConditionalFormatting.ColorScale(stops)
    }

    private fun conditionalStops(elements: List<Element>): List<XlsxConditionalFormatting.ScaleStop>? {
        return elements.map { element ->
            val type = element.getAttribute("type")
            if (type !in setOf("min","max","num","percent","percentile")) return null
            val number = if (type in setOf("num","percent","percentile"))
                XlsxConditionalFormatting.number(element.getAttribute("val")) ?: return null else null
            if (type in setOf("percent","percentile") && (number == null ||
                number < java.math.BigDecimal.ZERO || number > java.math.BigDecimal("100"))) return null
            val inclusive = when (element.getAttribute("gte")) {
                "", "1", "true" -> true
                "0", "false" -> false
                else -> return null
            }
            XlsxConditionalFormatting.ScaleStop(type,number,0,inclusive)
        }
    }

    private fun parseConditionalDataBar(rule: Element): XlsxConditionalFormatting.DataBar? {
        val bar = rule.childElements("dataBar").singleOrNull() ?: return null
        if (rule.childElements("extLst").isNotEmpty()) return null // x14 custom rendering cannot be inferred.
        val stops = conditionalStops(bar.childElements("cfvo")) ?: return null
        if (stops.size != 2) return null
        val rgb = bar.childElements("color").singleOrNull()?.getAttribute("rgb") ?: return null
        if (!rgb.matches(Regex("[0-9A-Fa-f]{8}"))) return null
        fun flag(key: String, default: Boolean): Boolean? = when (bar.getAttribute(key)) {
            "" -> default
            "0","false" -> false
            "1","true" -> true
            else -> null
        }
        val show = flag("showValue",true) ?: return null
        val minimum = bar.getAttribute("minLength").ifEmpty { "10" }.toIntOrNull() ?: return null
        val maximum = bar.getAttribute("maxLength").ifEmpty { "90" }.toIntOrNull() ?: return null
        if (minimum !in 0..100 || maximum !in minimum..100) return null
        return XlsxConditionalFormatting.DataBar(stops[0],stops[1],show,minimum,maximum,rgb.takeLast(6).toInt(16))
    }

    private fun parseConditionalIconSet(rule: Element): XlsxConditionalFormatting.IconSet? {
        val icons = rule.childElements("iconSet").singleOrNull() ?: return null
        if (icons.getAttribute("iconSet") !in setOf("","3TrafficLights1","3TrafficLights2",
                "3Arrows","3ArrowsGray") ||
            icons.childElements("cfIcon").isNotEmpty() || icons.getAttribute("custom") in setOf("1","true")) return null
        val stops = conditionalStops(icons.childElements("cfvo")) ?: return null
        if (stops.size != 3) return null
        fun flag(key: String, default: Boolean): Boolean? = when (icons.getAttribute(key)) {
            "" -> default
            "0","false" -> false
            "1","true" -> true
            else -> null
        }
        val reverse = flag("reverse",false) ?: return null
        val show = flag("showValue",true) ?: return null
        if (flag("percent",true) != true) return null
        return XlsxConditionalFormatting.IconSet(stops,reverse,show,
            icons.getAttribute("iconSet").ifEmpty { "3TrafficLights1" })
    }

    private fun optionalDimension(element: Element, name: String, maximum: Float): Float? {
        if (!element.hasAttribute(name)) return null
        val value = element.getAttribute(name).toFloatOrNull()
        require(value != null && value.isFinite() && value in 0f..maximum) {
            "Invalid worksheet dimension: $name"
        }
        return value
    }

    private fun parseWorksheet(
        xml: String,
        sharedStrings: List<String>,
        styleTable: ParsedXlsxStyles,
        outRows: MutableList<List<String>>,
        outSparseRows: MutableList<XlsxRow>? = null,
        outMergedRegions: MutableList<CellRangeAddress>? = null
    ): WorksheetMetadata {
        val doc = parseXml(xml)
        require(doc.documentElement.localName() == "worksheet") { "Invalid worksheet XML root" }
        val root = doc.documentElement
        val sheetData = root.childElements("sheetData").singleOrNull()
            ?: throw IOException("Worksheet data missing or ambiguous")

        val sheetFormats = root.childElements("sheetFormatPr")
        require(sheetFormats.size <= 1) { "Ambiguous worksheet default dimensions" }
        val defaultWidth = sheetFormats.singleOrNull()?.let { optionalDimension(it, "defaultColWidth", 255f) }
        val defaultHeight = sheetFormats.singleOrNull()?.let { optionalDimension(it, "defaultRowHeight", 409.5f) }
        val columnWidths = mutableMapOf<Int, Float>()
        val declaredColumns = java.util.BitSet(MAX_XLSX_COLUMNS)
        for (col in root.childElements("cols").flatMap { it.childElements("col") }) {
            val first = col.getAttribute("min").toIntOrNull()
            val last = col.getAttribute("max").toIntOrNull()
            require(first != null && last != null && first in 1..MAX_XLSX_COLUMNS && last in first..MAX_XLSX_COLUMNS) {
                "Invalid worksheet column range"
            }
            val already = declaredColumns.nextSetBit(first - 1)
            require(already < 0 || already >= last) { "Overlapping worksheet column declarations" }
            declaredColumns.set(first - 1, last)
            optionalDimension(col, "width", 255f)?.let { width ->
                for (index in first - 1 until last) columnWidths[index] = width
            }
        }
        val rowHeights = mutableMapOf<Int, Float>()
        val styledRows = mutableMapOf<Int, IntArray>()
        val conditional = parseConditionalFormatting(root, styleTable.differential)
        val visuals = styleTable.visuals.toMutableList()
        val derivedStyles = mutableMapOf<XlsxCellStyle, Int>()
        var conditionalChecks = 0L
        val conditionalCost = conditional.sumOf { it.ranges.size.toLong() }
        val scaleRules = conditional.filter { it.scale != null || it.bar != null || it.icons != null }
        fun needsPercentiles(rule: XlsxConditionalFormatting.Rule): Boolean =
            rule.scale?.stops?.any { it.type == "percentile" } == true ||
                rule.bar?.let { it.low.type == "percentile" || it.high.type == "percentile" } == true ||
                rule.icons?.stops?.any { it.type == "percentile" } == true
        val percentileRules = scaleRules.filter(::needsPercentiles)
        val percentileBuckets = mutableMapOf<Int, MutableList<java.math.BigDecimal>>()
        var percentileSamples = 0
        val scaleExtents = mutableMapOf<Int, XlsxConditionalFormatting.NumericExtent>()
        data class DeferredNumeric(val row: Int, val col: Int, val style: Int, val raw: java.math.BigDecimal)
        val deferredNumeric = mutableListOf<DeferredNumeric>()
        fun internStyle(baseIndex: Int, applied: XlsxCellStyle): Int {
            if (applied == styleTable.visuals[baseIndex]) return baseIndex
            return derivedStyles.getOrPut(applied) {
                require(visuals.size < 16384) { "Conditional style count exceeds budget" }
                visuals.add(applied)
                visuals.lastIndex
            }
        }
        // Prefer the first saved worksheet view, not the device's display setting.
        val firstView = root.childElements("sheetViews").flatMap { it.childElements("sheetView") }.firstOrNull()
        val showGridLines = when (firstView?.getAttribute("showGridLines").orEmpty()) {
            "", "1", "true" -> true
            "0", "false" -> false
            else -> throw IOException("Invalid worksheet gridline flag")
        }

        var frozenCols = 0
        var frozenRows = 0
        var cachedFormulaCount = 0
        fun metadata() = WorksheetMetadata(frozenCols, frozenRows, cachedFormulaCount,
            XlsxDimensions(defaultWidth, defaultHeight, columnWidths, rowHeights),
            XlsxCellStyles(visuals, styledRows, showGridLines), conditional.count { it.delta == null },
            root.childElements("drawing").map { relationshipId(it) }, parsePrintSettings(root))
        val panes = root.childElements("sheetViews").flatMap { it.childElements("sheetView") }
            .flatMap { it.childElements("pane") }
        for (pElem in panes) {
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
            val mergeNodes = root.childElements("mergeCells").flatMap { it.childElements("mergeCell") }
            for (mElem in mergeNodes) {
                val ref = mElem.getAttribute("ref")
                outMergedRegions.add(parseMergeRange(ref))
                require(outMergedRegions.size <= SpreadsheetGeometry.MAX_MERGES) {
                    "Worksheet exceeds merged-range count limit"
                }
            }
        }

        val rowList = sheetData.childElements("row")
        val rowMap = java.util.TreeMap<Int, MutableList<String>>()
        var autoRowIndex = 1
        var expandedCells = 0L
        var expandedTextChars = 0L
        val occupied = mutableMapOf<Int, MutableSet<Int>>()

        for (rowElem in rowList) {
            val explicitRowAttr = rowElem.getAttribute("r")
            val explicitRowNumber = explicitRowAttr.toIntOrNull()
            val targetRow = if (explicitRowAttr.isEmpty()) autoRowIndex
                else explicitRowNumber ?: throw java.io.IOException("Invalid worksheet row reference")
            require(targetRow in 1..MAX_XLSX_ROWS) { "Worksheet row exceeds XLSX bounds" }
            autoRowIndex = maxOf(autoRowIndex, targetRow + 1)

            // Some producers split a row across disjoint row elements. Preserve
            // their cells while rejecting conflicting geometry or cell identities.
            optionalDimension(rowElem, "ht", 409.5f)?.let { height ->
                val prior = rowHeights[targetRow - 1]
                require(prior == null || prior == height) { "Conflicting worksheet row heights" }
                rowHeights[targetRow - 1] = height
            }
            val existingCells = rowMap.getOrPut(targetRow) { mutableListOf() }
            val explicitVisuals = mutableListOf<Pair<Int, Int>>()

            var child = rowElem.firstChild
            while (child != null) {
                if (child.nodeType == Node.ELEMENT_NODE && child.localName() == "c") {
                    val cElem = child as Element
                    val cellType = cElem.getAttribute("t")
                    val cellRef = cElem.getAttribute("r")
                    var cellVal = ""
                    val values = cElem.childElements("v")
                    val inline = cElem.childElements("is")
                    val formulas = cElem.childElements("f")
                    require(formulas.size <= 1) { "Ambiguous cached formula" }
                    val styleAttribute = cElem.getAttribute("s")
                    val style = if (styleAttribute.isEmpty()) 0 else styleAttribute.toIntOrNull()
                        ?: throw IOException("Invalid cell style index")
                    require(values.size <= 1 && inline.size <= 1 && (values.isEmpty() || inline.isEmpty())) {
                        "Ambiguous cell value at $cellRef"
                    }
                    if (values.isNotEmpty()) {
                        val stored = values.single().textContent.orEmpty()
                        cellVal = if (cellType == "str") stored else stored.trim()
                        if (formulas.isNotEmpty()) {
                            require(cellVal.isNotEmpty() || cellType == "str") { "Formula has no usable cached value" }
                            cachedFormulaCount++
                        }
                    } else {
                        if (formulas.isNotEmpty()) {
                            throw java.io.IOException("Formula at $cellRef has no cached result; recalculate and save the workbook first")
                        }
                        cellVal = inline.singleOrNull()?.let { spreadsheetRichText(it) }.orEmpty()
                    }

                    if (cellType == "s") {
                        val idx = cellVal.toIntOrNull()
                        if (idx == null || idx !in sharedStrings.indices)
                            throw java.io.IOException("Invalid shared string reference at $cellRef")
                        cellVal = sharedStrings[idx]
                    }
                    val rawNumeric = if (conditional.isNotEmpty() && (cellType.isEmpty() || cellType == "n"))
                        XlsxConditionalFormatting.number(cellVal) else null
                    val rawText = if (conditional.isNotEmpty() && cellType in setOf("s", "str", "inlineStr"))
                        cellVal else null
                    cellVal = styleTable.formatter.format(cellVal, cellType, style)
                    expandedTextChars += cellVal.length
                    require(expandedTextChars <= SpreadsheetGeometry.MAX_TEXT_CHARS) {
                        "Worksheet expanded text exceeds layout budget"
                    }

                    val targetCol = if (cellRef.isEmpty()) existingCells.size else {
                        require(cellRef.matches(Regex("[A-Za-z]{1,3}[1-9][0-9]{0,6}"))) { "Invalid cell reference: $cellRef" }
                        require(cellRef.dropWhile { it.isLetter() }.toIntOrNull() == targetRow) {
                            "Cell $cellRef is assigned to the wrong row"
                        }
                        colRefToIndex(cellRef)
                    }
                    require(targetCol in 0 until MAX_XLSX_COLUMNS) { "Cell column exceeds XLSX bounds" }
                    require(occupied.getOrPut(targetRow) { mutableSetOf() }.add(targetCol)) {
                        "Duplicate cell reference at row $targetRow, column ${targetCol + 1}"
                    }
                    val growth = (targetCol + 1 - existingCells.size).coerceAtLeast(0)
                    expandedCells += growth
                    require(expandedCells <= MAX_EXPANDED_CELLS_PER_SHEET) {
                        "Worksheet exceeds expanded cell memory limit"
                    }
                    while (existingCells.size <= targetCol) existingCells.add("")
                    existingCells[targetCol] = cellVal
                    var visualIndex = style
                    if (rawNumeric != null || rawText != null) {
                        conditionalChecks += conditionalCost
                        require(conditionalChecks <= 8_000_000L) { "Conditional formatting evaluation exceeds budget" }
                        val r = targetRow - 1
                        if (rawNumeric != null && scaleRules.any { rule -> rule.ranges.any { it.contains(r, targetCol) } }) {
                            // A scale needs the complete raw range, including later rows.
                            conditionalChecks += conditionalCost
                            require(conditionalChecks <= 8_000_000L && deferredNumeric.size < 200_000) {
                                "Conditional visual evaluation exceeds budget"
                            }
                            deferredNumeric.add(DeferredNumeric(r, targetCol, style, rawNumeric))
                            for (rule in scaleRules) if (rule.ranges.any { it.contains(r, targetCol) }) {
                                scaleExtents[rule.priority] = scaleExtents[rule.priority]?.include(rawNumeric)
                                    ?: XlsxConditionalFormatting.NumericExtent(rawNumeric, rawNumeric)
                                if (rule in percentileRules) {
                                    require(++percentileSamples <= 200_000) {
                                        "Conditional percentile sample budget exceeded"
                                    }
                                    percentileBuckets.getOrPut(rule.priority) { mutableListOf() }.add(rawNumeric)
                                }
                            }
                        } else {
                            val base = styleTable.visuals[style]
                            val applied = XlsxConditionalFormatting.apply(base, r, targetCol, rawNumeric, conditional, rawText)
                            visualIndex = internStyle(style, applied)
                        }
                    }
                    if (visualIndex != 0 && visuals[visualIndex] != visuals[0]) {
                        explicitVisuals.add(targetCol to visualIndex)
                    }
                }
                child = child.nextSibling
            }
            if (explicitVisuals.isNotEmpty()) {
                val prior = styledRows[targetRow - 1]
                val needed = explicitVisuals.maxOf { it.first } + 1
                val codes = if (prior != null && prior.size >= needed) prior else {
                    val capacity = maxOf(needed, (prior?.size ?: 0) * 2).coerceAtMost(MAX_XLSX_COLUMNS)
                    prior?.copyOf(capacity) ?: IntArray(capacity)
                }
                for ((col, style) in explicitVisuals) codes[col] = style
                styledRows[targetRow - 1] = codes
            }
        }

        for (rule in percentileRules) {
            val samples = percentileBuckets[rule.priority].orEmpty()
            scaleExtents[rule.priority]?.let { extent ->
                scaleExtents[rule.priority] = extent.withPercentiles(samples)
            }
        }
        for (cell in deferredNumeric) {
            val base = styleTable.visuals[cell.style]
            val applied = XlsxConditionalFormatting.apply(base, cell.row, cell.col, cell.raw, conditional,
                scaleExtents = scaleExtents)
            val code = internStyle(cell.style, applied)
            if (code == 0 && cell.row !in styledRows) continue
            val prior = styledRows[cell.row]
            val codes = if (prior != null && prior.size > cell.col) prior else
                prior?.copyOf(maxOf(cell.col + 1, prior.size * 2).coerceAtMost(MAX_XLSX_COLUMNS)) ?: IntArray(cell.col + 1)
            codes[cell.col] = code
            styledRows[cell.row] = codes
        }

        if (rowMap.isEmpty()) return metadata()

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

        // SpreadsheetML does not serialize most blank cells. Self-contained
        // row/column expressions still style blanks inside the admitted visible
        // rectangle. Never expand a whole-column sqref beyond the saved grid.
        val expressionRules = conditional.filter { it.expression != null }
        if (expressionRules.isNotEmpty()) {
            val visibleRows = outRows.size
            val visibleColumns = outRows.maxOfOrNull { it.size } ?: 0
            require(visibleRows.toLong() * visibleColumns <= SpreadsheetGeometry.MAX_GRID_CELLS) {
                "Conditional blank-cell styling exceeds visible grid budget"
            }
            for (rule in expressionRules) for (range in rule.ranges) {
                val firstRow = range.firstRow.coerceAtLeast(0)
                val lastRow = minOf(range.lastRow,visibleRows-1)
                val firstCol = range.firstCol.coerceAtLeast(0)
                val lastCol = minOf(range.lastCol,visibleColumns-1)
                if (firstRow > lastRow || firstCol > lastCol) continue
                // Count *every* candidate check before evaluating. Counting only
                // matches allowed 256 rules x 2M blanks to traverse unboundedly.
                val visited = (lastRow - firstRow + 1).toLong() * (lastCol - firstCol + 1)
                val costPerCell = conditionalCost.coerceAtLeast(1L)
                require(visited <= (8_000_000L - conditionalChecks).coerceAtLeast(0L) / costPerCell) {
                    "Conditional formatting blank-cell evaluation exceeds budget"
                }
                conditionalChecks += visited * costPerCell
                for (r in firstRow..lastRow) for (c in firstCol..lastCol) {
                    if (occupied[r+1]?.contains(c) == true) continue
                    if (rule.expression?.matches(r,c,null) != true) continue
                    val applied = XlsxConditionalFormatting.apply(styleTable.visuals[0],
                        r,c,null,conditional,scaleExtents=scaleExtents)
                    val code = internStyle(0,applied)
                    if (code == 0) continue
                    val prior = styledRows[r]
                    val entries = if (prior != null && prior.size > c) prior else
                        prior?.copyOf(maxOf(c+1,prior.size*2).coerceAtMost(MAX_XLSX_COLUMNS)) ?: IntArray(c+1)
                    entries[c] = code
                    styledRows[r] = entries
                }
            }
        }

        // Trim trailing completely empty rows from the end of the worksheet
        while (outRows.isNotEmpty() && outRows.last().all { it.isBlank() } &&
            outRows.lastIndex !in styledRows && outRows.lastIndex !in rowHeights) {
            outRows.removeAt(outRows.size - 1)
        }
        return metadata()
    }
}
