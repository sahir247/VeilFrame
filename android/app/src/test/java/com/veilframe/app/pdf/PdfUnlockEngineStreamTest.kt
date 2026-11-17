package com.veilframe.app.pdf

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfUnlockEngineStreamTest {

    @Test
    fun `unlock stream writes complete unencrypted PDF without byte array result`() = runBlocking {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
        val original = ByteArrayOutputStream()
        PDDocument().use { doc ->
            repeat(6) { doc.addPage(PDPage()) }
            doc.save(original)
        }

        val outputFile = File.createTempFile("unlock_stream_test_", ".pdf")
        try {
            val originalBytes = original.toByteArray()
            assertFalse(PdfUnlockEngine.checkEncryption(ByteArrayInputStream(originalBytes)))
            val result = ByteArrayInputStream(originalBytes).use { input ->
                outputFile.outputStream().buffered().use { output ->
                    PdfUnlockEngine.unlockPdfStream(input, output, "").getOrThrow()
                }
            }
            assertEquals(6, result)
            assertTrue(outputFile.length() > 0)
            PDDocument.load(outputFile).use { doc ->
                assertEquals(6, doc.numberOfPages)
                assertFalse(doc.isEncrypted)
            }
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `unlock stream rejects corrupt inputs`() = runBlocking {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
        val encoded = "invalid pdf data".toByteArray()
        val result = PdfUnlockEngine.unlockPdfStream(
            ByteArrayInputStream(encoded), ByteArrayOutputStream(), ""
        )
        assertTrue(result.isFailure)
    }
}
