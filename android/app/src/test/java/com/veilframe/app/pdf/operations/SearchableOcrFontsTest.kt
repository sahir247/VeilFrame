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
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
class SearchableOcrFontsTest {
    @Test fun staticCollectionFaceSurvivesSubsetAndUnicodeReadback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        // Build a one-face TTC from a bundled static OFL TTF. Table offsets
        // are relative to the start of the collection rather than the sfnt.
        val standalone = context.assets.open("fonts/ocr/NotoSansDevanagari-Regular.ttf").use { it.readBytes() }
        val face = ByteBuffer.wrap(standalone)
        val tables = face.getShort(4).toInt() and 0xffff
        repeat(tables) { index ->
            val field = 12 + index*16 + 8
            face.putInt(field, face.getInt(field)+16)
        }
        val file = File.createTempFile("ocr_test_",".ttc",context.cacheDir)
        try {
            val header = ByteBuffer.allocate(16).putInt(0x74746366).putInt(0x00010000).putInt(1).putInt(16).array()
            file.outputStream().use { it.write(header); it.write(standalone) }
            val word = "हिन्दी"
            val bytes = ByteArrayOutputStream()
            PDDocument().use { doc ->
                SearchableOcrCollectionFonts(doc,listOf(file)).use { fonts ->
                    val page = PDPage()
                    doc.addPage(page)
                    val selected = requireNotNull(fonts.select(word))
                    assertSame(selected.first, requireNotNull(fonts.select(word)).first)
                    PDPageContentStream(doc,page).use { stream ->
                        stream.beginText()
                        stream.setFont(selected.first,12f)
                        stream.showText(word)
                        stream.endText()
                    }
                    doc.save(bytes)
                }
            }
            PDDocument.load(bytes.toByteArray()).use { saved ->
                assertArrayEquals(digestOcrWords(sequenceOf(word)),digestSerializedOcrLayer(saved.getPage(0)))
            }
        } finally {
            file.delete()
        }
    }

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
