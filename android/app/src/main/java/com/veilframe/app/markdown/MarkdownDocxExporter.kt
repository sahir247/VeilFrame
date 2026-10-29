package com.veilframe.app.markdown

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Clean offline OpenXML DOCX exporter for Markdown documents.
 * Produces valid, standard Microsoft Word (.docx) files without third-party dependencies,
 * faithfully translating Markdown structure and inline styling to Word OpenXML elements.
 */
object MarkdownDocxExporter {

    data class InlineRun(
        val text: String,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isStrike: Boolean = false,
        val isCode: Boolean = false,
        val isLink: Boolean = false,
        val linkUrl: String? = null,
        val isMath: Boolean = false
    )

    private enum class TokenType {
        CODE, IMAGE, LINK, MATH, BOLD_ITALIC, BOLD, STRIKE, ITALIC
    }

    private data class MatchResultHolder(
        val range: IntRange,
        val type: TokenType,
        val innerText: String,
        val extra: String? = null
    )

    private val codeRegex = Regex("""`([^`\n]+)`""")
    private val imageRegex = Regex("""!\[([^\]]*)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    private val linkRegex = Regex("""\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)""")
    private val mathRegex = Regex("""(?<!\$)\$(?!\$)(.+?)(?<!\$)\$(?!\$)""")
    private val boldItalicRegex = Regex("""(?:\*\*\*|___)(.+?)(?:\*\*\*|___)""")
    private val boldRegex = Regex("""(?:\*\*|__)(.+?)(?:\*\*|__)""")
    private val strikeRegex = Regex("""~~(.+?)~~""")
    private val italicAsteriskRegex = Regex("""\*([^*\n]+?)\*""")
    private val italicUnderscoreRegex = Regex("""(?<=\s|^|[({\[])_([^_\n]+?)_(?=\s|$|[.,!?;:)}\]])""")

    fun exportToDocx(markdownText: String, outputFile: File, title: String = "Document") {
        outputFile.parentFile?.mkdirs()
        val linkMap = mutableMapOf<String, String>()
        val getLinkId: (String) -> String = { url ->
            linkMap.getOrPut(url) { "rId${linkMap.size + 1}" }
        }
        val docXml = buildDocumentXml(markdownText, title, getLinkId)

        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            // 1. [Content_Types].xml
            writeEntry(zos, "[Content_Types].xml", buildContentTypesXml())

            // 2. _rels/.rels
            writeEntry(zos, "_rels/.rels", buildRelsXml())

            // 3. word/_rels/document.xml.rels (if hyperlinks exist)
            if (linkMap.isNotEmpty()) {
                val relsList = linkMap.map { (url, id) -> id to url }
                writeEntry(zos, "word/_rels/document.xml.rels", buildDocumentRelsXml(relsList))
            }

            // 4. word/document.xml
            writeEntry(zos, "word/document.xml", docXml)
        }
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun buildContentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private fun buildRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun buildDocumentRelsXml(hyperlinks: List<Pair<String, String>>): String {
        val rels = hyperlinks.joinToString("\n") { (id, url) ->
            """  <Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${escapeXml(url)}" TargetMode="External"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$rels
</Relationships>"""
    }

    fun parseInline(
        input: String,
        isBold: Boolean = false,
        isItalic: Boolean = false,
        isStrike: Boolean = false,
        isCode: Boolean = false,
        isLink: Boolean = false,
        linkUrl: String? = null
    ): List<InlineRun> {
        if (input.isEmpty()) return emptyList()
        if (isCode) {
            return listOf(InlineRun(input, isBold, isItalic, isStrike, isCode = true, isLink, linkUrl))
        }

        val matches = mutableListOf<MatchResultHolder>()
        codeRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.CODE, it.groupValues[1])) }
        imageRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.IMAGE, it.groupValues[1])) }
        linkRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.LINK, it.groupValues[1], it.groupValues[2])) }
        mathRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.MATH, it.groupValues[1])) }
        boldItalicRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.BOLD_ITALIC, it.groupValues[1])) }
        boldRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.BOLD, it.groupValues[1])) }
        strikeRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.STRIKE, it.groupValues[1])) }
        italicAsteriskRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.ITALIC, it.groupValues[1])) }
        italicUnderscoreRegex.find(input)?.let { matches.add(MatchResultHolder(it.range, TokenType.ITALIC, it.groupValues[1])) }

        if (matches.isEmpty()) {
            return listOf(InlineRun(input, isBold, isItalic, isStrike, isCode, isLink, linkUrl))
        }

        val earliest = matches.minWithOrNull(compareBy({ it.range.first }, { -it.range.last }))!!
        val before = input.substring(0, earliest.range.first)
        val after = input.substring(earliest.range.last + 1)
        val result = mutableListOf<InlineRun>()

        if (before.isNotEmpty()) {
            result.addAll(parseInline(before, isBold, isItalic, isStrike, isCode, isLink, linkUrl))
        }

        when (earliest.type) {
            TokenType.CODE -> {
                result.add(InlineRun(earliest.innerText, isBold, isItalic, isStrike, isCode = true, isLink, linkUrl))
            }
            TokenType.IMAGE -> {
                val alt = earliest.innerText.trim()
                val label = if (alt.isNotEmpty()) "[$alt]" else "[Image]"
                result.add(InlineRun(label, isBold = false, isItalic = true, isStrike = false, isCode = false, isLink = false))
            }
            TokenType.LINK -> {
                result.addAll(parseInline(earliest.innerText, isBold, isItalic, isStrike, isCode, isLink = true, linkUrl = earliest.extra))
            }
            TokenType.MATH -> {
                result.add(InlineRun(earliest.innerText, isBold = false, isItalic = true, isStrike = false, isCode = false, isLink = false, isMath = true))
            }
            TokenType.BOLD_ITALIC -> {
                result.addAll(parseInline(earliest.innerText, isBold = true, isItalic = true, isStrike, isCode, isLink, linkUrl))
            }
            TokenType.BOLD -> {
                result.addAll(parseInline(earliest.innerText, isBold = true, isItalic, isStrike, isCode, isLink, linkUrl))
            }
            TokenType.STRIKE -> {
                result.addAll(parseInline(earliest.innerText, isBold, isItalic, isStrike = true, isCode, isLink, linkUrl))
            }
            TokenType.ITALIC -> {
                result.addAll(parseInline(earliest.innerText, isBold, isItalic = true, isStrike, isCode, isLink, linkUrl))
            }
        }

        if (after.isNotEmpty()) {
            result.addAll(parseInline(after, isBold, isItalic, isStrike, isCode, isLink, linkUrl))
        }

        return result
    }

    private fun buildDocumentXml(markdown: String, title: String, getLinkId: (String) -> String): String {
        val bodyContent = StringBuilder()
        val lines = markdown.lines()
        var i = 0
        var inCodeBlock = false
        var codeBlockLang = ""
        val codeBuffer = mutableListOf<String>()

        while (i < lines.size) {
            val rawLine = lines[i]
            val line = rawLine.trimEnd()
            val trimmedLine = line.trim()

            // 1. Fenced Code Block
            if (trimmedLine.startsWith("```")) {
                if (inCodeBlock) {
                    if (codeBlockLang == "mermaid") {
                        bodyContent.append(buildMermaidBlockXml(codeBuffer))
                    } else {
                        bodyContent.append(buildCodeBlockXml(codeBuffer))
                    }
                    codeBuffer.clear()
                    inCodeBlock = false
                    codeBlockLang = ""
                } else {
                    inCodeBlock = true
                    codeBlockLang = trimmedLine.removePrefix("```").trim().lowercase()
                }
                i++
                continue
            }

            if (inCodeBlock) {
                codeBuffer.add(rawLine)
                i++
                continue
            }

            // 2. Display LaTeX Math ($$ ... $$)
            if (trimmedLine.startsWith("$$")) {
                if (trimmedLine.endsWith("$$") && trimmedLine.length > 2) {
                    val mathText = trimmedLine.removePrefix("$$").removeSuffix("$$").trim()
                    bodyContent.append(buildDisplayMathXml(mathText))
                    i++
                    continue
                } else {
                    val mathLines = mutableListOf<String>()
                    i++
                    while (i < lines.size) {
                        val mLine = lines[i].trim()
                        if (mLine.startsWith("$$")) {
                            i++
                            break
                        }
                        mathLines.add(lines[i])
                        i++
                    }
                    bodyContent.append(buildDisplayMathXml(mathLines.joinToString("\n")))
                    continue
                }
            }

            // 3. Blank line
            if (trimmedLine.isBlank()) {
                i++
                continue
            }

            // 3. Table detection
            if (trimmedLine.contains("|") && i + 1 < lines.size && isTableDelimiter(lines[i + 1])) {
                val headerLine = trimmedLine
                val delimiterLine = lines[i + 1].trim()
                val headers = splitTableRow(headerLine)
                val alignments = splitTableRow(delimiterLine).map { parseAlignment(it) }
                val dataRows = mutableListOf<List<String>>()

                i += 2
                while (i < lines.size) {
                    val rowLine = lines[i].trim()
                    if (rowLine.isBlank() || !rowLine.contains("|")) break
                    dataRows.add(splitTableRow(rowLine))
                    i++
                }

                bodyContent.append(buildTableXml(headers, alignments, dataRows, getLinkId))
                continue
            }

            // 4. Headings
            when {
                trimmedLine.startsWith("# ") -> {
                    val text = trimmedLine.removePrefix("# ").trimEnd('#').trim()
                    val runs = parseInline(text, isBold = true)
                    bodyContent.append(buildParagraphXml(runs, fontSizeHalfPts = 40, baseBold = true, spaceBefore = 280, spaceAfter = 120, getLinkId = getLinkId))
                    i++
                }
                trimmedLine.startsWith("## ") -> {
                    val text = trimmedLine.removePrefix("## ").trimEnd('#').trim()
                    val runs = parseInline(text, isBold = true)
                    bodyContent.append(buildParagraphXml(runs, fontSizeHalfPts = 32, baseBold = true, spaceBefore = 220, spaceAfter = 100, getLinkId = getLinkId))
                    i++
                }
                trimmedLine.startsWith("### ") -> {
                    val text = trimmedLine.removePrefix("### ").trimEnd('#').trim()
                    val runs = parseInline(text, isBold = true)
                    bodyContent.append(buildParagraphXml(runs, fontSizeHalfPts = 26, baseBold = true, spaceBefore = 180, spaceAfter = 80, getLinkId = getLinkId))
                    i++
                }
                trimmedLine.startsWith("#### ") -> {
                    val text = trimmedLine.removePrefix("#### ").trimEnd('#').trim()
                    val runs = parseInline(text, isBold = true)
                    bodyContent.append(buildParagraphXml(runs, fontSizeHalfPts = 24, baseBold = true, spaceBefore = 140, spaceAfter = 60, getLinkId = getLinkId))
                    i++
                }
                trimmedLine.startsWith("##### ") -> {
                    val text = trimmedLine.removePrefix("##### ").trimEnd('#').trim()
                    val runs = parseInline(text, isBold = true)
                    bodyContent.append(buildParagraphXml(runs, fontSizeHalfPts = 22, baseBold = true, spaceBefore = 100, spaceAfter = 40, getLinkId = getLinkId))
                    i++
                }
                trimmedLine.startsWith("###### ") -> {
                    val text = trimmedLine.removePrefix("###### ").trimEnd('#').trim()
                    val runs = parseInline(text, isBold = true)
                    bodyContent.append(buildParagraphXml(runs, fontSizeHalfPts = 20, baseBold = true, spaceBefore = 80, spaceAfter = 40, colorHex = "57606A", getLinkId = getLinkId))
                    i++
                }

                // 5. Horizontal rule: ---, ***, ___
                trimmedLine.matches(Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")) -> {
                    bodyContent.append("""
    <w:p>
      <w:pPr>
        <w:pBdr>
          <w:bottom w:val="single" w:sz="6" w:space="4" w:color="D0D7DE"/>
        </w:pBdr>
        <w:spacing w:before="160" w:after="160"/>
      </w:pPr>
    </w:p>""")
                    i++
                }

                // 6. Blockquote or Callout/Alert: >
                trimmedLine.startsWith(">") -> {
                    val rawQuote = trimmedLine.removePrefix(">").trim()
                    val alertMatch = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*(.*)$""", RegexOption.IGNORE_CASE).find(rawQuote)
                    if (alertMatch != null) {
                        val alertType = alertMatch.groupValues[1].uppercase()
                        val alertBody = alertMatch.groupValues[2]
                        val (borderCol, fillCol, labelCol) = when (alertType) {
                            "TIP" -> Triple("1A7F37", "F0FDF4", "15803D")
                            "IMPORTANT" -> Triple("8250DF", "FAF5FF", "7E22CE")
                            "WARNING" -> Triple("9A6700", "FFFBEB", "B45309")
                            "CAUTION" -> Triple("CF222E", "FEF2F2", "B91C1C")
                            else -> Triple("0969DA", "F0F9FF", "0369A1")
                        }
                        val runs = listOf(InlineRun("[$alertType] ", isBold = true)) + parseInline(alertBody)
                        bodyContent.append(buildParagraphXml(
                            runs = runs,
                            fontSizeHalfPts = 21,
                            spaceBefore = 80,
                            spaceAfter = 80,
                            colorHex = labelCol,
                            borderLeftHex = borderCol,
                            shdFillHex = fillCol,
                            indentLeftDxa = 360,
                            getLinkId = getLinkId
                        ))
                    } else {
                        val runs = parseInline(rawQuote, isItalic = true)
                        bodyContent.append(buildParagraphXml(
                            runs = runs,
                            fontSizeHalfPts = 22,
                            baseItalic = true,
                            spaceBefore = 60,
                            spaceAfter = 80,
                            colorHex = "57606A",
                            borderLeftHex = "007ACC",
                            shdFillHex = "F8FAFC",
                            indentLeftDxa = 360,
                            getLinkId = getLinkId
                        ))
                    }
                    i++
                }

                // 7. Task list item: - [ ] or - [x]
                trimmedLine.matches(Regex("""^[-*+]\s+\[([ xX])\]\s*(.*)$""")) -> {
                    val match = Regex("""^[-*+]\s+\[([ xX])\]\s*(.*)$""").find(trimmedLine)!!
                    val isChecked = match.groupValues[1].equals("x", ignoreCase = true)
                    val box = if (isChecked) "☑ " else "☐ "
                    val taskText = match.groupValues[2]
                    val leadingSpaces = line.takeWhile { it == ' ' }.length
                    val indentDxa = 480 + (leadingSpaces / 2) * 240
                    val runs = mutableListOf(InlineRun(box, isBold = true))
                    runs.addAll(parseInline(taskText))
                    bodyContent.append(buildParagraphXml(
                        runs = runs,
                        fontSizeHalfPts = 22,
                        spaceBefore = 40,
                        spaceAfter = 80,
                        indentLeftDxa = indentDxa,
                        hangingDxa = 240,
                        getLinkId = getLinkId
                    ))
                    i++
                }

                // 8. Numbered list: 1. Item
                trimmedLine.matches(Regex("""^(\d+)\.\s+(.*)$""")) -> {
                    val match = Regex("""^(\d+)\.\s+(.*)$""").find(trimmedLine)!!
                    val num = match.groupValues[1]
                    val itemText = match.groupValues[2]
                    val leadingSpaces = line.takeWhile { it == ' ' }.length
                    val indentDxa = 480 + (leadingSpaces / 2) * 240
                    val runs = mutableListOf(InlineRun("$num. ", isBold = true))
                    runs.addAll(parseInline(itemText))
                    bodyContent.append(buildParagraphXml(
                        runs = runs,
                        fontSizeHalfPts = 22,
                        spaceBefore = 40,
                        spaceAfter = 80,
                        indentLeftDxa = indentDxa,
                        hangingDxa = 240,
                        getLinkId = getLinkId
                    ))
                    i++
                }

                // 9. Bullet list: - Item or * Item or + Item
                trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ") || trimmedLine.startsWith("+ ") -> {
                    val bulletText = trimmedLine.substring(2).trim()
                    val leadingSpaces = line.takeWhile { it == ' ' }.length
                    val indentDxa = 480 + (leadingSpaces / 2) * 240
                    val runs = mutableListOf(InlineRun("• "))
                    runs.addAll(parseInline(bulletText))
                    bodyContent.append(buildParagraphXml(
                        runs = runs,
                        fontSizeHalfPts = 22,
                        spaceBefore = 40,
                        spaceAfter = 80,
                        indentLeftDxa = indentDxa,
                        hangingDxa = 240,
                        getLinkId = getLinkId
                    ))
                    i++
                }

                // 10. Standard paragraph
                else -> {
                    val cleanLine = stripHtmlTags(trimmedLine)
                    if (cleanLine.isNotBlank()) {
                        val runs = parseInline(cleanLine)
                        bodyContent.append(buildParagraphXml(
                            runs = runs,
                            fontSizeHalfPts = 22,
                            spaceBefore = 0,
                            spaceAfter = 120,
                            getLinkId = getLinkId
                        ))
                    }
                    i++
                }
            }
        }

        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            bodyContent.append(buildCodeBlockXml(codeBuffer))
            codeBuffer.clear()
        }

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <w:body>
    $bodyContent
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/>
    </w:sectPr>
  </w:body>
</w:document>"""
    }

    private fun buildParagraphXml(
        runs: List<InlineRun>,
        fontSizeHalfPts: Int = 22,
        baseBold: Boolean = false,
        baseItalic: Boolean = false,
        spaceBefore: Int = 0,
        spaceAfter: Int = 120,
        colorHex: String? = null,
        borderBottomHex: String? = null,
        borderLeftHex: String? = null,
        shdFillHex: String? = null,
        indentLeftDxa: Int = 0,
        hangingDxa: Int = 0,
        align: String = "left",
        getLinkId: ((String) -> String)? = null
    ): String {
        val pPr = StringBuilder()
        pPr.append("<w:pPr>")
        if (align != "left") {
            pPr.append("<w:jc w:val=\"$align\"/>")
        }
        pPr.append("<w:spacing w:before=\"$spaceBefore\" w:after=\"$spaceAfter\" w:line=\"276\" w:lineRule=\"auto\"/>")

        if (borderBottomHex != null || borderLeftHex != null) {
            pPr.append("<w:pBdr>")
            if (borderLeftHex != null) {
                pPr.append("<w:left w:val=\"single\" w:sz=\"24\" w:space=\"12\" w:color=\"$borderLeftHex\"/>")
            }
            if (borderBottomHex != null) {
                pPr.append("<w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"4\" w:color=\"$borderBottomHex\"/>")
            }
            pPr.append("</w:pBdr>")
        }
        if (shdFillHex != null) {
            pPr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$shdFillHex\"/>")
        }

        if (indentLeftDxa > 0) {
            if (hangingDxa > 0) {
                pPr.append("<w:ind w:left=\"$indentLeftDxa\" w:hanging=\"$hangingDxa\"/>")
            } else {
                pPr.append("<w:ind w:left=\"$indentLeftDxa\"/>")
            }
        }
        pPr.append("</w:pPr>")

        val runsXml = StringBuilder()
        for (run in runs) {
            if (run.text.isEmpty()) continue

            val runPr = StringBuilder()
            val isB = run.isBold || baseBold
            val isI = run.isItalic || baseItalic

            if (isB) runPr.append("<w:b/>")
            if (isI) runPr.append("<w:i/>")
            if (run.isStrike) runPr.append("<w:strike/>")

            if (run.isMath) {
                runPr.append("<w:rFonts w:ascii=\"Cambria Math\" w:hAnsi=\"Cambria Math\"/>")
                runPr.append("<w:i/>")
                runPr.append("<w:sz w:val=\"21\"/>")
                runPr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F1F5F9\"/>")
                runPr.append("<w:color w:val=\"1E293B\"/>")
            } else if (run.isCode) {
                runPr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>")
                runPr.append("<w:sz w:val=\"20\"/>")
                runPr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EFF1F3\"/>")
                runPr.append("<w:color w:val=\"CF222E\"/>")
            } else {
                runPr.append("<w:sz w:val=\"$fontSizeHalfPts\"/>")
                if (run.isLink) {
                    runPr.append("<w:color w:val=\"0969DA\"/><w:u w:val=\"single\"/>")
                } else if (colorHex != null) {
                    runPr.append("<w:color w:val=\"$colorHex\"/>")
                }
            }

            val rContent = if (runPr.isNotEmpty()) {
                "<w:r><w:rPr>$runPr</w:rPr><w:t xml:space=\"preserve\">${escapeXml(run.text)}</w:t></w:r>"
            } else {
                "<w:r><w:t xml:space=\"preserve\">${escapeXml(run.text)}</w:t></w:r>"
            }

            if (run.isLink && !run.linkUrl.isNullOrBlank() && getLinkId != null) {
                val linkId = getLinkId(run.linkUrl)
                runsXml.append("<w:hyperlink r:id=\"$linkId\" w:history=\"1\">$rContent</w:hyperlink>")
            } else {
                runsXml.append(rContent)
            }
        }

        return """
    <w:p>
      $pPr
      $runsXml
    </w:p>"""
    }


    private fun buildDisplayMathXml(mathFormula: String): String {
        val escaped = escapeXml(mathFormula.trim())
        return """
    <w:p>
      <w:pPr>
        <w:jc w:val="center"/>
        <w:spacing w:before="160" w:after="160" w:line="260" w:lineRule="auto"/>
        <w:pBdr>
          <w:top w:val="single" w:sz="4" w:space="8" w:color="CBD5E1"/>
          <w:bottom w:val="single" w:sz="4" w:space="8" w:color="CBD5E1"/>
        </w:pBdr>
        <w:shd w:val="clear" w:color="auto" w:fill="F8FAFC"/>
        <w:ind w:left="400" w:right="400"/>
      </w:pPr>
      <w:r>
        <w:rPr>
          <w:rFonts w:ascii="Cambria Math" w:hAnsi="Cambria Math"/>
          <w:i/>
          <w:sz w:val="23"/>
          <w:color w:val="0F172A"/>
        </w:rPr>
        <w:t xml:space="preserve">$escaped</w:t>
      </w:r>
    </w:p>"""
    }

    private fun buildMermaidBlockXml(diagramLines: List<String>): String {
        val sb = StringBuilder()
        sb.append("""
    <w:p>
      <w:pPr>
        <w:spacing w:before="140" w:after="40"/>
        <w:pBdr>
          <w:left w:val="single" w:sz="24" w:space="12" w:color="7C3AED"/>
        </w:pBdr>
        <w:shd w:val="clear" w:color="auto" w:fill="F5F3FF"/>
        <w:ind w:left="240" w:right="240"/>
      </w:pPr>
      <w:r>
        <w:rPr>
          <w:b/>
          <w:sz w:val="18"/>
          <w:color w:val="6D28D9"/>
        </w:rPr>
        <w:t xml:space="preserve">[Mermaid Diagram Architecture]</w:t>
      </w:r>
    </w:p>
    <w:p>
      <w:pPr>
        <w:spacing w:before="40" w:after="140" w:line="240" w:lineRule="auto"/>
        <w:pBdr>
          <w:left w:val="single" w:sz="24" w:space="12" w:color="7C3AED"/>
        </w:pBdr>
        <w:shd w:val="clear" w:color="auto" w:fill="F5F3FF"/>
        <w:ind w:left="240" w:right="240"/>
      </w:pPr>""")
        for ((index, codeLine) in diagramLines.withIndex()) {
            val escaped = escapeXml(codeLine)
            sb.append("""
      <w:r>
        <w:rPr>
          <w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>
          <w:sz w:val="18"/>
          <w:color w:val="4C1D95"/>
        </w:rPr>
        <w:t xml:space="preserve">$escaped</w:t>
      </w:r>""")
            if (index < diagramLines.size - 1) {
                sb.append("<w:r><w:br/></w:r>")
            }
        }
        sb.append("\n    </w:p>")
        return sb.toString()
    }

    private fun buildCodeBlockXml(codeLines: List<String>): String {
        val sb = StringBuilder()
        sb.append("""
    <w:p>
      <w:pPr>
        <w:spacing w:before="120" w:after="120" w:line="240" w:lineRule="auto"/>
        <w:pBdr>
          <w:left w:val="single" w:sz="18" w:space="12" w:color="007ACC"/>
        </w:pBdr>
        <w:shd w:val="clear" w:color="auto" w:fill="F6F8FA"/>
        <w:ind w:left="240" w:right="240"/>
      </w:pPr>""")

        for ((index, rawLine) in codeLines.withIndex()) {
            val escaped = escapeXml(rawLine)
            sb.append("""
      <w:r>
        <w:rPr>
          <w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>
          <w:sz w:val="19"/>
          <w:color w:val="24292F"/>
        </w:rPr>
        <w:t xml:space="preserve">$escaped</w:t>
      </w:r>""")
            if (index < codeLines.size - 1) {
                sb.append("<w:r><w:br/></w:r>")
            }
        }
        sb.append("\n    </w:p>")
        return sb.toString()
    }

    private fun isTableDelimiter(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains("-") || !trimmed.contains("|")) return false
        val cells = splitTableRow(trimmed)
        if (cells.isEmpty()) return false
        return cells.all { it.matches(Regex("""^:?-+:?$""")) }
    }

    private fun splitTableRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|")) s = s.substring(0, s.length - 1)
        return s.split("|").map { it.trim() }
    }

    private fun parseAlignment(cell: String): String {
        val trimmed = cell.trim()
        val startsWithColon = trimmed.startsWith(":")
        val endsWithColon = trimmed.endsWith(":")
        return when {
            startsWithColon && endsWithColon -> "center"
            endsWithColon -> "right"
            else -> "left"
        }
    }

    private fun buildTableXml(
        headers: List<String>,
        alignments: List<String>,
        rows: List<List<String>>,
        getLinkId: ((String) -> String)? = null
    ): String {
        val maxRowCols = rows.maxOfOrNull { it.size } ?: 0
        val numCols = maxOf(headers.size, maxRowCols).coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("""
    <w:tbl>
      <w:tblPr>
        <w:tblStyle w:val="TableGrid"/>
        <w:tblW w:w="0" w:type="auto"/>
        <w:tblBorders>
          <w:top w:val="single" w:sz="6" w:space="0" w:color="D0D7DE"/>
          <w:left w:val="single" w:sz="6" w:space="0" w:color="D0D7DE"/>
          <w:bottom w:val="single" w:sz="6" w:space="0" w:color="D0D7DE"/>
          <w:right w:val="single" w:sz="6" w:space="0" w:color="D0D7DE"/>
          <w:insideH w:val="single" w:sz="4" w:space="0" w:color="E1E4E8"/>
          <w:insideV w:val="single" w:sz="4" w:space="0" w:color="E1E4E8"/>
        </w:tblBorders>
        <w:tblCellMar>
          <w:top w:w="120" w:type="dxa"/>
          <w:bottom w:w="120" w:type="dxa"/>
          <w:left w:w="160" w:type="dxa"/>
          <w:right w:w="160" w:type="dxa"/>
        </w:tblCellMar>
      </w:tblPr>""")

        // Header row
        sb.append("""
      <w:tr>
        <w:trPr><w:tblHeader/></w:trPr>""")
        for (c in 0 until numCols) {
            val text = headers.getOrElse(c) { "" }
            val align = alignments.getOrElse(c) { "left" }
            val runs = parseInline(text)
            val pXml = buildParagraphXml(
                runs = runs,
                fontSizeHalfPts = 20,
                baseBold = true,
                spaceBefore = 60,
                spaceAfter = 60,
                align = align,
                getLinkId = getLinkId
            )
            sb.append("""
        <w:tc>
          <w:tcPr>
            <w:shd w:val="clear" w:color="auto" w:fill="F6F8FA"/>
          </w:tcPr>
          $pXml
        </w:tc>""")
        }
        sb.append("\n      </w:tr>")

        // Data rows
        for (row in rows) {
            sb.append("\n      <w:tr>")
            for (c in 0 until numCols) {
                val text = row.getOrElse(c) { "" }
                val align = alignments.getOrElse(c) { "left" }
                val runs = parseInline(text)
                val pXml = buildParagraphXml(
                    runs = runs,
                    fontSizeHalfPts = 20,
                    spaceBefore = 40,
                    spaceAfter = 40,
                    align = align,
                    getLinkId = getLinkId
                )
                sb.append("""
        <w:tc>
          $pXml
        </w:tc>""")
            }
            sb.append("\n      </w:tr>")
        }

        sb.append("\n    </w:tbl>")
        sb.append("""
    <w:p>
      <w:pPr><w:spacing w:before="0" w:after="80"/></w:pPr>
    </w:p>""")
        return sb.toString()
    }

    private fun stripHtmlTags(input: String): String {
        return input.replace("&nbsp;", " ")
            .replace(Regex("""<[^>]*>"""), "")
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
