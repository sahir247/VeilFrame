package com.veilframe.app.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class MarkdownDocxExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `exports valid docx structure with content types rels and document xml`() {
        val markdown = """
            # Project Overview
            VeilFrame is a privacy-first video cleaner.
            
            ## Features
            - Document scanner with 100% original quality
            - QR code generator with animated GIF support
            - Markdown viewer with badge rendering
            
            ```kotlin
            fun cleanVideo() {
                println("cleaned")
            }
            ```
        """.trimIndent()

        val outputFile = File(tempFolder.root, "test_document.docx")
        MarkdownDocxExporter.exportToDocx(markdown, outputFile, "Project Overview")

        assertTrue("Output file must exist", outputFile.exists())
        assertTrue("Output file must have non-zero size", outputFile.length() > 0)

        // Open as ZipFile and verify required OpenXML parts
        ZipFile(outputFile).use { zip ->
            val contentTypesEntry = zip.getEntry("[Content_Types].xml")
            assertNotNull("[Content_Types].xml must be present", contentTypesEntry)

            val relsEntry = zip.getEntry("_rels/.rels")
            assertNotNull("_rels/.rels must be present", relsEntry)

            val documentEntry = zip.getEntry("word/document.xml")
            assertNotNull("word/document.xml must be present", documentEntry)

            val documentXml = zip.getInputStream(documentEntry).bufferedReader().readText()
            assertTrue("Document must contain title", documentXml.contains("Project Overview"))
            assertTrue("Document must contain body content", documentXml.contains("VeilFrame is a privacy-first video cleaner"))
            assertTrue("Document must contain list items", documentXml.contains("Document scanner with 100% original quality"))
            assertTrue("Document must contain code block snippet", documentXml.contains("cleanVideo()"))
        }
    }

    @Test
    fun `strips raw markdown syntax and produces styled OpenXML runs`() {
        val markdown = """
            # Modern Architecture
            This is **bold text** and *italic style* and ***bold italic combined***.
            Here is ~~strikethrough text~~ and `val apiKey = "secret"` code.
            Visit [VeilFrame](https://veilframe.app) for details.
        """.trimIndent()

        val outputFile = File(tempFolder.root, "styled_document.docx")
        MarkdownDocxExporter.exportToDocx(markdown, outputFile, "Modern Architecture")

        ZipFile(outputFile).use { zip ->
            val documentEntry = zip.getEntry("word/document.xml")
            val documentXml = zip.getInputStream(documentEntry).bufferedReader().readText()

            // Verify clean rendered text without markdown delimiters
            assertTrue("Must contain bold text", documentXml.contains("bold text"))
            assertTrue("Must contain italic text", documentXml.contains("italic style"))
            assertTrue("Must contain bold italic text", documentXml.contains("bold italic combined"))
            assertTrue("Must contain strike text", documentXml.contains("strikethrough text"))
            assertTrue("Must contain inline code", documentXml.contains("val apiKey = &quot;secret&quot;"))
            assertTrue("Must contain link text", documentXml.contains("VeilFrame"))

            // Verify raw markdown delimiters are NOT present in the text nodes
            assertFalse("Must not contain ** in text", documentXml.contains("<w:t xml:space=\"preserve\">**bold text**"))
            assertFalse("Must not contain * in text", documentXml.contains("<w:t xml:space=\"preserve\">*italic style*"))
            assertFalse("Must not contain ~~ in text", documentXml.contains("<w:t xml:space=\"preserve\">~~strikethrough text~~"))
            assertFalse("Must not contain backticks in text", documentXml.contains("<w:t xml:space=\"preserve\">`val apiKey"))
            assertFalse("Must not contain raw markdown link syntax", documentXml.contains("[VeilFrame](https://veilframe.app)"))

            // Verify XML styling tags are properly generated
            assertTrue("Must contain bold tag <w:b/>", documentXml.contains("<w:b/>"))
            assertTrue("Must contain italic tag <w:i/>", documentXml.contains("<w:i/>"))
            assertTrue("Must contain strike tag <w:strike/>", documentXml.contains("<w:strike/>"))
            assertTrue("Must contain Consolas font for code", documentXml.contains("w:ascii=\"Consolas\""))
            assertTrue("Must contain hyperlink", documentXml.contains("<w:hyperlink r:id=\"rId1\""))

            // Verify document rels was written for the hyperlink
            val relsEntry = zip.getEntry("word/_rels/document.xml.rels")
            assertNotNull("Hyperlink rels must be created", relsEntry)
            val relsXml = zip.getInputStream(relsEntry).bufferedReader().readText()
            assertTrue("Must contain target URL", relsXml.contains("https://veilframe.app"))
            assertTrue("Must contain external hyperlink type", relsXml.contains("Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\""))
        }
    }

    @Test
    fun `renders markdown tables into OpenXML tables with headers and alignment`() {
        val markdown = """
            # Performance Metrics

            | Feature | Status | Accuracy |
            | :--- | :---: | ---: |
            | Redaction | **Active** | 99.8% |
            | OCR Cleaner | *Ready* | 100% |
        """.trimIndent()

        val outputFile = File(tempFolder.root, "table_document.docx")
        MarkdownDocxExporter.exportToDocx(markdown, outputFile, "Performance Metrics")

        ZipFile(outputFile).use { zip ->
            val documentEntry = zip.getEntry("word/document.xml")
            val documentXml = zip.getInputStream(documentEntry).bufferedReader().readText()

            // Verify OpenXML table tags
            assertTrue("Must contain table element <w:tbl>", documentXml.contains("<w:tbl>"))
            assertTrue("Must contain table header <w:tblHeader/>", documentXml.contains("<w:tblHeader/>"))
            assertTrue("Must contain table cells <w:tc>", documentXml.contains("<w:tc>"))
            assertTrue("Must contain header shading", documentXml.contains("w:fill=\"F6F8FA\""))

            // Verify cells and alignments
            assertTrue("Must contain Feature header", documentXml.contains("Feature"))
            assertTrue("Must contain Status header", documentXml.contains("Status"))
            assertTrue("Must contain Accuracy header", documentXml.contains("Accuracy"))
            assertTrue("Must contain center alignment", documentXml.contains("<w:jc w:val=\"center\"/>"))
            assertTrue("Must contain right alignment", documentXml.contains("<w:jc w:val=\"right\"/>"))

            // Verify markdown delimiters are stripped from table text
            assertTrue("Must contain bold Active", documentXml.contains("Active"))
            assertFalse("Must not contain raw pipe table syntax in text", documentXml.contains("| Feature |"))
            assertFalse("Must not contain raw delimiter syntax", documentXml.contains("| :--- |"))
            assertFalse("Must not contain **Active**", documentXml.contains("**Active**"))
        }
    }

    @Test
    fun `renders checklists and numbered lists properly`() {
        val markdown = """
            # Release Checklist

            - [ ] Prepare release build
            - [x] Verify offline parity

            1. Step One
            2. Step Two with **emphasis**
        """.trimIndent()

        val outputFile = File(tempFolder.root, "checklist_document.docx")
        MarkdownDocxExporter.exportToDocx(markdown, outputFile, "Release Checklist")

        ZipFile(outputFile).use { zip ->
            val documentEntry = zip.getEntry("word/document.xml")
            val documentXml = zip.getInputStream(documentEntry).bufferedReader().readText()

            // Verify ballot boxes for task lists
            assertTrue("Must contain unchecked ballot box", documentXml.contains("☐ "))
            assertTrue("Must contain checked ballot box", documentXml.contains("☑ "))
            assertTrue("Must contain task text", documentXml.contains("Prepare release build"))
            assertTrue("Must contain checked task text", documentXml.contains("Verify offline parity"))

            // Verify numbered list
            assertTrue("Must contain 1.", documentXml.contains("1. "))
            assertTrue("Must contain Step One", documentXml.contains("Step One"))
            assertTrue("Must contain 2.", documentXml.contains("2. "))
            assertTrue("Must contain Step Two with", documentXml.contains("Step Two with"))
            assertTrue("Must contain bold emphasis", documentXml.contains("emphasis"))
            assertFalse("Must not contain raw **emphasis**", documentXml.contains("**emphasis**"))
        }
    }
}
