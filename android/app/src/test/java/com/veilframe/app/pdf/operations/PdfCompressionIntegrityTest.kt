package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCompressionIntegrityTest {
    @Test
    fun onlyPlainRgbImagesAreEligible() {
        val image = COSDictionary()
        image.setItem(COSName.getPDFName("ColorSpace"), COSName.getPDFName("DeviceRGB"))
        image.setInt(COSName.getPDFName("BitsPerComponent"), 8)
        assertTrue(isPlainRgbCompressionImage(image))
        listOf("SMask", "Mask", "Decode", "Interpolate", "OC", "Metadata", "Intent").forEach { key ->
            val name = COSName.getPDFName(key)
            image.setString(name, "semantic property")
            assertFalse("Must preserve $key", isPlainRgbCompressionImage(image))
            image.removeItem(name)
        }
        image.setItem(COSName.getPDFName("ColorSpace"), COSName.getPDFName("DeviceCMYK"))
        assertFalse(isPlainRgbCompressionImage(image))
    }

    @Test
    fun defaultRgbOverrideIsDetectedInSharedResourceGraph() {
        PDDocument().use { document ->
            val resources = com.tom_roush.pdfbox.pdmodel.PDResources()
            val page = PDPage().apply { this.resources = resources }
            document.addPage(page)
            assertFalse(hasDefaultRgbOverride(document))
            resources.cosObject.setItem(COSName.getPDFName("ColorSpace"), COSDictionary().apply {
                setItem(COSName.getPDFName("DefaultRGB"), COSName.getPDFName("DeviceRGB"))
            })
            assertTrue(hasDefaultRgbOverride(document))
        }
    }

    @Test
    fun unsignedDocumentWithPagesPassesPreflight() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            assertEquals(1, inspectCompressionSource(doc))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rightsEnabledDocumentIsNeverRewritten() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.documentCatalog.cosObject.setItem(COSName.getPDFName("Perms"), COSDictionary())
            inspectCompressionSource(doc)
        }
    }

    @Test
    fun higherDisplayedQualityMeansLessCompression() {
        assertEquals(80, qualitySliderToCompressionPercent(20))
        assertEquals(35, qualitySliderToCompressionPercent(65))
        assertEquals(10, qualitySliderToCompressionPercent(90))
    }

    @Test
    fun structuralRewriteRejectsChangedCommandsAndGeometry() {
        PDDocument().use { original ->
            val page = PDPage(PDRectangle(300f, 420f))
            original.addPage(page)
            PDPageContentStream(original, page).use { stream ->
                stream.addRect(10f, 20f, 30f, 40f)
                stream.fill()
            }
            val bytes = ByteArrayOutputStream().also { original.save(it) }.toByteArray()
            PDDocument.load(bytes).use { candidate ->
                requireCompressionStructure(original, candidate)
                candidate.getPage(0).rotation = 90
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    requireCompressionStructure(original, candidate)
                }
                candidate.getPage(0).rotation = 0
                PDPageContentStream(candidate, candidate.getPage(0),
                    PDPageContentStream.AppendMode.APPEND, true).use { stream ->
                    stream.addRect(1f, 1f, 2f, 2f)
                    stream.fill()
                }
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    requireCompressionStructure(original, candidate)
                }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPdfDoesNotReportSuccess() {
        PDDocument().use { doc -> inspectCompressionSource(doc) }
    }
}
