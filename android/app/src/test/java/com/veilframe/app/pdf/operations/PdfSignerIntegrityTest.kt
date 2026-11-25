package com.veilframe.app.pdf.operations

import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfSignerIntegrityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun changedPreviewSourceIsRejectedBeforeOutput() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val source = temp.newFile("preview.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(source) }
        val uri = Uri.fromFile(source)
        val hash = loadMergePdf(context,uri).use { it.sha256 }
        PDDocument.load(source).use { doc ->
            doc.documentInformation.title = "Changed bytes, unchanged page geometry"
            doc.save(source)
        }
        val bitmap = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        try {
            val output = ByteArrayOutputStream()
            val result = PdfSigner(context).signPdfWithItems(uri,output,
                listOf(SignatureItemPlacement(0,10f,10f,40f,20f,bitmap=bitmap,sourceSha256=hash)))
            assertFalse(result.success)
            assertTrue(result.errorMessage.orEmpty().contains("changed"))
            assertEquals(0,output.size())
        } finally { bitmap.recycle() }
    }

    @Test fun rightsEnabledPdfIsNotRewrittenByAnyStreamSigningRoute() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val source = temp.newFile("rights.pdf")
        PDDocument().use {
            it.addPage(PDPage())
            it.documentCatalog.cosObject.setItem(COSName.getPDFName("Perms"),COSDictionary())
            it.save(source)
        }
        val uri = Uri.fromFile(source)
        val bitmap = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        try {
            val signer = PdfSigner(context)
            val output = ByteArrayOutputStream()
            assertFalse(signer.signPdfWithItems(uri,output,
                listOf(SignatureItemPlacement(0,10f,10f,40f,20f,bitmap=bitmap))).success)
            assertFalse(signer.addSignatureBitmaps(uri,output,bitmap,
                listOf(SignaturePlacement(0,10f,10f,40f,20f))).success)
            assertFalse(signer.addRelativeSignatureBitmaps(uri,output,bitmap,listOf(0),0.5f,0.5f,1f).success)
            assertEquals(0,output.size())
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }
}
