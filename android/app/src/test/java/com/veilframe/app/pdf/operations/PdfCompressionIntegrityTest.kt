package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
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

    @Test(expected = IllegalArgumentException::class)
    fun emptyPdfDoesNotReportSuccess() {
        PDDocument().use { doc -> inspectCompressionSource(doc) }
    }
}
