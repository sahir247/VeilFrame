package com.veilframe.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.veilframe.app.pdf.operations.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Immutable, view-independent operations captured before dispatch to IO. */
internal sealed interface QuickPdfRequest {
    data class Merge(val uris: List<Uri>, val plan: List<MergePageItem>?) : QuickPdfRequest
    data class SplitAll(val uri: Uri, val expectedPages: Int) : QuickPdfRequest
    data class SplitSelected(val uri: Uri, val pages: List<Int>) : QuickPdfRequest
    data class SplitRanges(val uri: Uri, val ranges: List<PageRange>) : QuickPdfRequest
    data class Delete(val uri: Uri, val pages: Set<Int>) : QuickPdfRequest
    data class Rotate(val uri: Uri, val pages: Set<Int>, val angle: RotationAngle) : QuickPdfRequest
    data class Reorder(val uri: Uri, val pages: List<Int>) : QuickPdfRequest
    data class Images(val uris: List<Uri>) : QuickPdfRequest
    data class Compress(val uri: Uri, val quality: Int, val raster: Boolean) : QuickPdfRequest
    data class Lock(val uri: Uri, val options: PdfSecurityOptions) : QuickPdfRequest
    data class SignCanvas(val uri: Uri, val items: List<SignatureItemPlacement>) : QuickPdfRequest
    data class SignRelative(val uri: Uri, val bitmap: Bitmap, val pages: List<Int>,
        val x: Float, val y: Float, val scale: Float) : QuickPdfRequest
    data class Html(val input: String) : QuickPdfRequest
    data class Office(val uri: Uri, val name: String, val identity: OfficeSourceSnapshot.Identity,
        val savedPrint: Boolean) : QuickPdfRequest
    data class PdfImages(val uri: Uri, val expectedPages: Int) : QuickPdfRequest
}

internal class PdfQuickOperationExecutor(private val context: Context, private val signer: PdfSigner) {
    private val merger by lazy { PdfMerger() }
    private val splitter by lazy { PdfSplitter() }
    private val organizer by lazy { PdfOrganizer() }
    private val rotator by lazy { PdfRotator() }
    private val compressor by lazy { PdfCompressor() }
    private val security by lazy { PdfSecurityManager() }
    private val images by lazy { ImageConverter() }
    private val html by lazy { HtmlToPdfConverter() }
    private val office by lazy { OfficeConverter() }

    suspend fun execute(request: QuickPdfRequest, displayStem: String,
        publish: suspend (Boolean, File, String) -> Boolean) {
        val base = QuickPdfExportIntegrity.safeStem(displayStem)
        suspend fun pdf(name: String, generate: suspend (OutputStream) -> Unit) {
            PdfQuickExportStage.generate(context.cacheDir, {
                generate(it)
                true
            }) { success, file -> publish(success, file, name) }
        }
        suspend fun archive(name: String, expected: Int,
            generate: suspend (ZipOutputStream, QuickPdfExportIntegrity.Sequence) -> Int) {
            require(expected in 1..10_000) { "Unsupported archive entry count" }
            val file = File.createTempFile("quick_pdf_", ".zip", context.cacheDir)
            var transferred = false
            try {
                val sequence = QuickPdfExportIntegrity.Sequence(expected)
                val reported = ZipOutputStream(file.outputStream().buffered()).use { generate(it, sequence) }
                sequence.requireComplete(reported)
                currentCoroutineContext().ensureActive()
                check(file.length() > 0) { "Archive is empty" }
                transferred = publish(true, file, name)
            } finally {
                if (!transferred) file.delete()
            }
        }
        when (request) {
            is QuickPdfRequest.Merge -> pdf("merged_document.pdf") {
                if (request.plan != null) merger.mergePageItems(context, request.plan, it).getOrThrow()
                else merger.mergePdfs(context, request.uris, it).getOrThrow()
            }
            is QuickPdfRequest.SplitAll -> archive("${base}_split_pages.zip", request.expectedPages) { zip, sequence ->
                val result = splitter.splitAllPages(context, request.uri, outputCallback = { page, input ->
                    sequence.accept(page)
                    zip.putNextEntry(ZipEntry("${base}_page_$page.pdf"))
                    copyCancellable(input, zip)
                    zip.closeEntry()
                }).getOrThrow()
                check(result.totalPagesProcessed == request.expectedPages) { "Source page count changed" }
                result.totalFilesCreated
            }
            is QuickPdfRequest.SplitSelected -> pdf("${base}_split_selected.pdf") {
                val result = organizer.extractPagesAsPdf(context, request.uri, it, request.pages).getOrThrow()
                check(result.resultPageCount == request.pages.size) { "Incomplete page extraction" }
            }
            is QuickPdfRequest.SplitRanges -> {
                val expectedPages = request.ranges.sumOf { it.end.toLong() - it.start + 1L }
                require(expectedPages in 1..10_000) { "Split output page limit exceeded" }
                if (request.ranges.size == 1) {
                    pdf("${base}_split_part1.pdf") { output ->
                        val sequence = QuickPdfExportIntegrity.Sequence(1)
                        val result = splitter.splitByRanges(context, request.uri, request.ranges,
                            outputCallback = { part, input -> sequence.accept(part); copyCancellable(input, output) }).getOrThrow()
                        sequence.requireComplete(result.totalFilesCreated)
                        check(result.totalPagesProcessed.toLong() == expectedPages) { "Incomplete split" }
                    }
                } else {
                    archive("${base}_split_ranges.zip", request.ranges.size) { zip, sequence ->
                        val result = splitter.splitByRanges(context, request.uri, request.ranges,
                            outputCallback = { part, input ->
                                sequence.accept(part)
                                zip.putNextEntry(ZipEntry("${base}_part_$part.pdf"))
                                copyCancellable(input, zip)
                                zip.closeEntry()
                            }).getOrThrow()
                        check(result.totalPagesProcessed.toLong() == expectedPages) { "Incomplete split" }
                        result.totalFilesCreated
                    }
                }
            }
            is QuickPdfRequest.Delete -> pdf("${base}_pruned.pdf") {
                organizer.removePages(context, request.uri, it, request.pages).getOrThrow()
            }
            is QuickPdfRequest.Rotate -> pdf("${base}_rotated.pdf") {
                if (request.pages.isEmpty()) rotator.rotateAllPages(context, request.uri, it, request.angle).getOrThrow()
                else rotator.rotateSpecificPages(context, request.uri, it, request.pages.associateWith { request.angle }).getOrThrow()
            }
            is QuickPdfRequest.Reorder -> pdf("${base}_reordered.pdf") {
                val result = organizer.reorderPages(context, request.uri, it, request.pages).getOrThrow()
                check(result.resultPageCount == request.pages.size) { "Incomplete page reorder" }
            }
            is QuickPdfRequest.Images -> pdf("compiled_images.pdf") {
                images.imagesToPdf(context, request.uris, it).getOrThrow()
            }
            is QuickPdfRequest.Compress -> {
                val suffix = if (request.raster) "image_only" else "compressed"
                pdf("${base}_$suffix.pdf") {
                    if (request.raster) compressor.compressPdfRasterized(context, request.uri, it, request.quality).getOrThrow()
                    else compressor.compressPdf(context, request.uri, it,
                        qualityPercent = qualitySliderToCompressionPercent(request.quality)).getOrThrow()
                }
            }
            is QuickPdfRequest.Lock -> pdf("${base}_locked.pdf") {
                security.encryptPdf(context, request.uri, it, request.options).getOrThrow()
            }
            is QuickPdfRequest.SignCanvas -> pdf("${base}_signed.pdf") {
                val result = signer.signPdfWithItems(request.uri, it, request.items)
                check(result.success) { result.errorMessage ?: "Signature export failed" }
            }
            is QuickPdfRequest.SignRelative -> pdf("${base}_signed.pdf") {
                val result = signer.addRelativeSignatureBitmaps(request.uri, it, request.bitmap,
                    request.pages, request.x, request.y, request.scale)
                check(result.success) { result.errorMessage ?: "Signature export failed" }
            }
            is QuickPdfRequest.Html -> pdf("html_document.pdf") {
                val input = request.input
                val content = if (!input.contains("<html", true) && !input.contains("<!doctype", true))
                    "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>body{font-family:sans-serif;padding:24px;line-height:1.6;color:#222;}</style></head><body>$input</body></html>"
                else input
                html.convertHtmlToPdf(context, content, it).getOrThrow()
            }
            is QuickPdfRequest.Office -> OfficeQuickExport.generate(context, request.uri, request.name,
                request.identity, request.savedPrint, office) { publish(true, it, "$base.pdf") }
            is QuickPdfRequest.PdfImages -> archive("${base}_images.zip", request.expectedPages) { zip, sequence ->
                images.pdfToImages(context, request.uri, format = ImageFormat.WEBP, dpi = 150,
                    outputCallback = { page, bitmap ->
                        sequence.accept(page)
                        currentCoroutineContext().ensureActive()
                        zip.putNextEntry(ZipEntry("${base}_page_$page.webp"))
                        check(bitmap.compress(Bitmap.CompressFormat.WEBP, 90, zip)) { "Could not encode page $page" }
                        zip.closeEntry()
                    }).getOrThrow()
            }
        }
    }

    private suspend fun copyCancellable(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            check(n > 0) { "PDF part stream made no progress" }
            output.write(buffer, 0, n)
            total += n
        }
        check(total > 0) { "Empty PDF part" }
    }
}
