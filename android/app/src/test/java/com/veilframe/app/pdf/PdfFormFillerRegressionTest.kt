package com.veilframe.app.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.veilframe.app.pdf.operations.FieldValue
import com.veilframe.app.pdf.operations.PdfFormFiller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class PdfFormFillerRegressionTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context)
    }

    @Test
    fun fillFormToStream_persistsTextCheckboxAndRespectsReadOnlyFields() = runBlocking {
        val source = File.createTempFile("form_source_", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                document.addPage(PDPage())
                val form = PDAcroForm(document)
                document.documentCatalog.acroForm = form

                val name = PDTextField(form).apply {
                    partialName = "name"
                    isRequired = true
                }
                form.fields.add(name)

                val locked = PDTextField(form).apply {
                    partialName = "locked"
                    isReadOnly = true
                    value = "original"
                }
                form.fields.add(locked)

                val accepted = PDCheckBox(form).apply { partialName = "accepted" }
                form.fields.add(accepted)

                FileOutputStream(source).use { document.save(it) }
            }

            val output = ByteArrayOutputStream()
            val result = PdfFormFiller().fillFormToStream(
                context = context,
                inputUri = Uri.fromFile(source),
                outputStream = output,
                fieldValues = mapOf(
                    "name" to FieldValue.Text("Sahir"),
                    "locked" to FieldValue.Text("changed"),
                    "accepted" to FieldValue.CheckBox(true)
                )
            )

            assertTrue("Form fill failed: ${result.errorMessage}", result.success)
            assertEquals(2, result.fieldsUpdated)
            assertTrue(output.size() > 0)

            PDDocument.load(ByteArrayInputStream(output.toByteArray())).use { filled ->
                val form = filled.documentCatalog.acroForm!!
                assertEquals("Sahir", (form.getField("name") as PDTextField).value)
                assertEquals("original", (form.getField("locked") as PDTextField).value)
                assertTrue((form.getField("accepted") as PDCheckBox).isChecked)
            }
        } finally {
            source.delete()
        }
    }

    @Test
    fun fillFormToStream_returnsFailureWhenNoEditableValuesCanBeApplied() = runBlocking {
        val source = File.createTempFile("form_readonly_", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document ->
                document.addPage(PDPage())
                val form = PDAcroForm(document)
                document.documentCatalog.acroForm = form
                val locked = PDTextField(form).apply {
                    partialName = "locked"
                    isReadOnly = true
                    value = "fixed"
                }
                form.fields.add(locked)
                FileOutputStream(source).use { document.save(it) }
            }

            val result = PdfFormFiller().fillFormToStream(
                context = context,
                inputUri = Uri.fromFile(source),
                outputStream = ByteArrayOutputStream(),
                fieldValues = mapOf("locked" to FieldValue.Text("overwrite"))
            )

            assertFalse(result.success)
            assertEquals(0, result.fieldsUpdated)
            assertTrue(result.errorMessage.orEmpty().contains("No editable form fields"))
        } finally {
            source.delete()
        }
    }
}
