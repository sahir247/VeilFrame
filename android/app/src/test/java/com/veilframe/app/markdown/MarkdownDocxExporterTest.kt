package com.veilframe.app.markdown

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
}
