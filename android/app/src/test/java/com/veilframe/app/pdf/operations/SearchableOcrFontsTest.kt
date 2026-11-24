package com.veilframe.app.pdf.operations

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class SearchableOcrFontsTest {
    @Test fun bundledFontsRoundTripExactUnicodeThroughSavedPdf() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val words = listOf("English","हिन्दी","বাংলা","Ελληνικά","Русский")
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            SearchableOcrFonts(context,document).use { fonts ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document,page).use { stream ->
                    stream.beginText()
                    for (word in words) {
                        val (font,width) = fonts.select(word)
                        assertTrue(width > 0)
                        stream.setFont(font,12f)
                        stream.showText(word)
                    }
                    stream.endText()
                }
                document.save(bytes)
            }
        }
        PDDocument.load(bytes.toByteArray()).use { saved ->
            assertArrayEquals(digestOcrWords(words.asSequence()),digestSerializedOcrLayer(saved.getPage(0)))
        }
    }
}
