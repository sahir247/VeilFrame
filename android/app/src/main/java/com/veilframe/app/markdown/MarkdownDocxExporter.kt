package com.veilframe.app.markdown

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Clean offline OpenXML DOCX exporter for Markdown documents.
 * Produces valid, standard Microsoft Word (.docx) files without third-party dependencies.
 */
object MarkdownDocxExporter {

    fun exportToDocx(markdownText: String, outputFile: File, title: String = "Document") {
        outputFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            // 1. [Content_Types].xml
            writeEntry(zos, "[Content_Types].xml", buildContentTypesXml())

            // 2. _rels/.rels
            writeEntry(zos, "_rels/.rels", buildRelsXml())

            // 3. word/document.xml
            writeEntry(zos, "word/document.xml", buildDocumentXml(markdownText, title))
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

    private fun buildDocumentXml(markdown: String, title: String): String {
        val bodyContent = StringBuilder()
        val lines = markdown.lines()
        var inCodeBlock = false
        val codeBuffer = StringBuilder()

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.startsWith("```")) {
                if (inCodeBlock) {
                    val escapedCode = escapeXml(codeBuffer.toString().trimEnd())
                    bodyContent.append("""
    <w:p>
      <w:pPr>
        <w:shd w:val="clear" w:color="auto" w:fill="F4F4F4"/>
      </w:pPr>
      <w:r>
        <w:rPr>
          <w:rFonts w:ascii="Courier New" w:hAnsi="Courier New"/>
          <w:sz w:val="20"/>
        </w:rPr>
        <w:t xml:space="preserve">$escapedCode</w:t>
      </w:r>
    </w:p>""")
                    codeBuffer.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBuffer.append(rawLine).append("\n")
                continue
            }

            if (line.isBlank()) {
                continue
            }

            when {
                line.startsWith("# ") -> {
                    val text = escapeXml(line.removePrefix("# ").trim())
                    bodyContent.append(buildParagraphXml(text, isBold = true, fontSizeHalfPts = 48, spaceBefore = 240, spaceAfter = 120))
                }
                line.startsWith("## ") -> {
                    val text = escapeXml(line.removePrefix("## ").trim())
                    bodyContent.append(buildParagraphXml(text, isBold = true, fontSizeHalfPts = 36, spaceBefore = 200, spaceAfter = 100))
                }
                line.startsWith("### ") -> {
                    val text = escapeXml(line.removePrefix("### ").trim())
                    bodyContent.append(buildParagraphXml(text, isBold = true, fontSizeHalfPts = 28, spaceBefore = 160, spaceAfter = 80))
                }
                line.startsWith("#### ") -> {
                    val text = escapeXml(line.removePrefix("#### ").trim())
                    bodyContent.append(buildParagraphXml(text, isBold = true, fontSizeHalfPts = 24, spaceBefore = 120, spaceAfter = 60))
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> {
                    val bulletText = escapeXml(line.substring(2).trim())
                    bodyContent.append(buildBulletXml(bulletText))
                }
                line.startsWith("> ") -> {
                    val quoteText = escapeXml(line.removePrefix("> ").trim())
                    bodyContent.append("""
    <w:p>
      <w:pPr>
        <w:pBdr>
          <w:left w:val="single" w:sz="24" w:space="12" w:color="007ACC"/>
        </w:pBdr>
        <w:ind w:left="360"/>
      </w:pPr>
      <w:r>
        <w:rPr><w:i/><w:color w:val="555555"/></w:rPr>
        <w:t xml:space="preserve">$quoteText</w:t>
      </w:r>
    </w:p>""")
                }
                else -> {
                    val cleanText = escapeXml(stripHtmlTags(line))
                    if (cleanText.isNotBlank()) {
                        bodyContent.append(buildParagraphXml(cleanText, isBold = false, fontSizeHalfPts = 22, spaceBefore = 0, spaceAfter = 120))
                    }
                }
            }
        }

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
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
        text: String,
        isBold: Boolean,
        fontSizeHalfPts: Int,
        spaceBefore: Int,
        spaceAfter: Int
    ): String {
        val boldTag = if (isBold) "<w:b/>" else ""
        return """
    <w:p>
      <w:pPr>
        <w:spacing w:before="$spaceBefore" w:after="$spaceAfter" w:line="276" w:lineRule="auto"/>
      </w:pPr>
      <w:r>
        <w:rPr>
          $boldTag
          <w:sz w:val="$fontSizeHalfPts"/>
        </w:rPr>
        <w:t xml:space="preserve">$text</w:t>
      </w:r>
    </w:p>"""
    }

    private fun buildBulletXml(text: String): String {
        return """
    <w:p>
      <w:pPr>
        <w:spacing w:after="80"/>
        <w:ind w:left="360"/>
      </w:pPr>
      <w:r>
        <w:t xml:space="preserve">• $text</w:t>
      </w:r>
    </w:p>"""
    }

    private fun stripHtmlTags(input: String): String {
        return input.replace(Regex("<[^>]*>"), "")
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
