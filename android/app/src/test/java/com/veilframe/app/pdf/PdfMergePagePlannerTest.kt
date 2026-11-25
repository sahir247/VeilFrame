package com.veilframe.app.pdf

import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.veilframe.app.pdf.operations.PdfMerger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfMergePagePlannerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun writePdf(file: File, count: Int) {
        PDDocument().use { doc ->
            repeat(count) { doc.addPage(PDPage()) }
            doc.save(file)
        }
    }

    @Test fun completePlanAndChangedSourceRefusal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val file = temp.newFile("source.pdf")
        writePdf(file, 2)
        val plan = PdfMergePagePlanner.build(context, listOf(Uri.fromFile(file))) { "source.pdf" }
        assertEquals(listOf(0, 1), plan.map { it.sourcePageIndex })
        val output = ByteArrayOutputStream()
        assertTrue(PdfMerger().mergePageItems(context, plan.reversed(), output).isSuccess)
        PDDocument.load(output.toByteArray()).use { assertEquals(2, it.numberOfPages) }
        writePdf(file, 1)
        val rejected = ByteArrayOutputStream()
        assertTrue(PdfMerger().mergePageItems(context, plan, rejected).isFailure)
        assertEquals(0, rejected.size())
    }

    @Test fun unreadablePdfDoesNotBecomeOnePagePlan() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val file = temp.newFile("corrupt.pdf").apply { writeText("not a PDF") }
        assertTrue(runCatching {
            PdfMergePagePlanner.build(context, listOf(Uri.fromFile(file))) { "corrupt.pdf" }
        }.isFailure)
    }

    @Test fun samePageCountReplacementIsRejectedBeforeOutput() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val file = temp.newFile("same-count.pdf")
        writePdf(file, 2)
        val uri = Uri.fromFile(file)
        val plan = PdfMergePagePlanner.build(context, listOf(uri)) { "same-count.pdf" }
        assertEquals(64, plan.first().sourceItem.sourceSha256?.length)
        PDDocument().use { replacement ->
            repeat(2) { replacement.addPage(PDPage()) }
            replacement.documentInformation.title = "Different source, same number of pages"
            replacement.save(file)
        }
        val output = ByteArrayOutputStream()
        assertTrue(PdfMerger().mergePageItems(context, plan, output).isFailure)
        assertEquals(0, output.size())
        val refreshed = PdfMergePagePlanner.build(context, listOf(uri)) { "same-count.pdf" }
        assertTrue(PdfMerger().mergePageItems(context, refreshed, output).isSuccess)
        PDDocument.load(output.toByteArray()).use { assertEquals(2, it.numberOfPages) }
    }

    @Test fun sameSizeImageReplacementIsRejectedBeforeOutput() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        val file = temp.newFile("same-size.png")
        val red = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFElEQVR4nGP8z8Dwn4GBgYGJAQoAHxcCAk+Uzr4AAAAASUVORK5CYII=")
        val green = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFElEQVR4nGNk+M/wn4GBgYGJAQoAHhgCAu/QjeYAAAAASUVORK5CYII=")
        assertEquals(red.size,green.size)
        file.writeBytes(red)
        val uri = Uri.fromFile(file)
        val plan = PdfMergePagePlanner.build(context,listOf(uri)) { "same-size.png" }
        assertTrue(plan.single().isImage)
        assertEquals(64,plan.single().sourceItem.sourceSha256?.length)
        file.writeBytes(green)
        val output = ByteArrayOutputStream()
        assertTrue(PdfMerger().mergePageItems(context,plan,output).isFailure)
        assertEquals(0,output.size())
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("vf_merge_image_") })
    }

    @Test fun malformedImageCannotBecomeAReadyPagePlan() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val file = temp.newFile("broken.png").apply { writeText("not an image") }
        assertTrue(runCatching {
            PdfMergePagePlanner.build(context,listOf(Uri.fromFile(file))) { "broken.png" }
        }.isFailure)
    }
}
