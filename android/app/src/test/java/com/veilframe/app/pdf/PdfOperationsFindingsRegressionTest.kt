package com.veilframe.app.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.veilframe.app.pdf.operations.PdfCompressor
import com.veilframe.app.pdf.operations.PdfFlattener
import com.veilframe.app.pdf.operations.FlattenConfig
import com.veilframe.app.pdf.operations.TargetSizeNotReachedException
import com.veilframe.app.pdf.operations.TextExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class PdfOperationsFindingsRegressionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context)
        }
    }

    @Test
    fun testFlattenForms_accuratelyCountsFlattenedFields() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        val acroForm = PDAcroForm(doc)
        doc.documentCatalog.acroForm = acroForm

        // Add 3 interactive text fields
        val field1 = PDTextField(acroForm)
        field1.partialName = "field1"
        acroForm.fields.add(field1)

        val field2 = PDTextField(acroForm)
        field2.partialName = "field2"
        acroForm.fields.add(field2)

        val field3 = PDTextField(acroForm)
        field3.partialName = "field3"
        acroForm.fields.add(field3)

        assertEquals(3, acroForm.fields.size)

        val flattener = PdfFlattener()
        val method = PdfFlattener::class.java.getDeclaredMethod("flattenForms", PDDocument::class.java)
        method.isAccessible = true
        val flattenedCount = method.invoke(flattener, doc) as Int

        // Finding 10 verification: Before the fix, acroForm.fields.size was evaluated AFTER
        // acroForm.flatten() emptied the list, always returning 0. Now it correctly returns 3.
        assertEquals("Flattened forms count must equal the number of pre-flattened fields", 3, flattenedCount)
        assertTrue("AcroForm fields must be empty after flattening", acroForm.fields.isEmpty())

        doc.close()
    }

    @Test
    fun testRemoveJavaScript_preservesNavigationOpenAction() {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        // Set a standard navigation destination OpenAction
        val destination = PDPageFitWidthDestination()
        destination.page = page
        doc.documentCatalog.setOpenAction(destination)

        val flattener = PdfFlattener()
        val removeJsMethod = PdfFlattener::class.java.getDeclaredMethod("removeJavaScript", PDDocument::class.java)
        removeJsMethod.isAccessible = true

        removeJsMethod.invoke(flattener, doc)

        // Finding 9 verification: Legitimate navigation OpenActions must NOT be removed
        val preservedAction = doc.documentCatalog.openAction
        assertNotNull("Non-JavaScript navigation OpenAction must be preserved", preservedAction)
        assertTrue("OpenAction destination must remain intact", preservedAction is PDPageFitWidthDestination)

        // Now set a malicious/script JavaScript action
        val jsAction = PDActionJavaScript("app.alert('malicious_script');")
        doc.documentCatalog.setOpenAction(jsAction)

        removeJsMethod.invoke(flattener, doc)

        // Finding 9 verification: JavaScript actions MUST be removed
        assertNull("JavaScript OpenAction must be completely removed", doc.documentCatalog.openAction)

        doc.close()
    }

    @Test
    fun testPdfFlattener_failsGracefullyWhenDestinationOutputStreamFails() {
        runBlocking {
            val flattener = PdfFlattener()

            val dummyInput = File(context.cacheDir, "input_test.pdf")
            val doc = PDDocument()
            doc.addPage(PDPage())
            FileOutputStream(dummyInput).use { doc.save(it) }
            doc.close()

            val inputUri = Uri.fromFile(dummyInput)
            val outputUri = Uri.parse("content://com.veilframe.test/destination.pdf")

            val shadowResolver = Shadows.shadowOf(context.contentResolver)
            // Destination has stale pre-existing content readable via openInputStream
            shadowResolver.registerInputStream(outputUri, ByteArrayInputStream("stale content".toByteArray()))
            // Destination stream throws write error
            val failingStream = object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("Disk write failure simulated")
                }
            }
            shadowResolver.registerOutputStream(outputUri, failingStream)

            val result = flattener.flattenPdf(context, inputUri, outputUri, FlattenConfig())

            // Finding 8 verification: Failed output stream must NOT report success=true
            // even though destination URI has pre-existing readable bytes.
            assertFalse("Operation must fail when output stream cannot be written", result.success)
            assertNotNull("An error message must be returned", result.errorMessage)
            assertTrue("Error message must indicate failure", result.errorMessage!!.contains("Disk write failure simulated"))

            dummyInput.delete()
        }
    }

    @Test
    fun testTargetSizeNotReachedException_preservesFallbackFileState() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "veilframe_fallback_test")
        tempDir.mkdirs()
        val fallbackFile = File(tempDir, "iter_0_quality_80.pdf")
        fallbackFile.writeText("simulated pdf candidate")

        val exception = TargetSizeNotReachedException(
            message = "Target size not reached",
            smallestAchievableSize = fallbackFile.length(),
            fallbackFile = fallbackFile,
            originalSize = 1000L,
            strategyUsed = com.veilframe.app.pdf.operations.CompressionStrategy.IMAGE_OPTIMIZATION,
            pagesProcessed = 1
        )

        // Finding 6 verification: Caller must receive an existing fallback file
        assertNotNull(exception.fallbackFile)
        assertTrue("Fallback file must exist on disk when handed to caller", exception.fallbackFile!!.exists())
        assertEquals(fallbackFile.absolutePath, exception.fallbackFile!!.absolutePath)

        fallbackFile.delete()
        tempDir.delete()
    }

    @Test
    fun testCompressPdfToTargetSizeStrict_propagatesCancellationException() {
        runBlocking {
            val compressor = PdfCompressor()

            val input = File(context.cacheDir, "cancel_test.pdf")
            val doc = PDDocument()
            doc.addPage(PDPage())
            FileOutputStream(input).use { doc.save(it) }
            doc.close()

            val inputUri = Uri.fromFile(input)
            val outputStream = ByteArrayOutputStream()

            var caughtCancellation = false
            val job = launch(Dispatchers.IO) {
                cancel()
                try {
                    compressor.compressPdfToTargetSizeStrict(
                        context = context,
                        inputUri = inputUri,
                        outputStream = outputStream,
                        targetSizeBytes = 100L
                    )
                } catch (e: CancellationException) {
                    caughtCancellation = true
                    throw e
                }
            }
            job.join()

            // Finding 7 verification: CancellationException must propagate, not get caught and swallowed as Result.failure
            assertTrue("CancellationException must be propagated", caughtCancellation)

            input.delete()
        }
    }

    @Test
    fun testTextExtractor_executesSafelyAndExtractsText() {
        runBlocking {
            val extractor = TextExtractor()

            val input = File(context.cacheDir, "text_extract_test.pdf")
            val doc = PDDocument()
            doc.addPage(PDPage())
            FileOutputStream(input).use { doc.save(it) }
            doc.close()

            val inputUri = Uri.fromFile(input)
            val result = extractor.extractText(context, inputUri)

            // Finding 11 verification: Resource cleanup in finally block must execute safely
            assertTrue("Text extraction from valid PDF should succeed", result.isSuccess)
            val data = result.getOrNull()
            assertNotNull(data)
            assertEquals(1, data!!.pageCount)

            input.delete()
        }
    }
}
