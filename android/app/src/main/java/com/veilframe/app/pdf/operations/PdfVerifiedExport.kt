package com.veilframe.app.pdf.operations

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.cos.COSName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Full rewrites invalidate digital signatures. Refuse instead of silently doing so. */
internal fun requirePdfRewriteAllowed(document: PDDocument) {
    if (document.numberOfPages <= 0) throw IOException("PDF contains no pages")
    if (document.signatureDictionaries.isNotEmpty() ||
        document.documentCatalog.cosObject.containsKey(COSName.getPDFName("Perms"))) {
        throw IOException("This digitally signed or rights-enabled PDF cannot be safely rewritten")
    }
    if (!document.currentAccessPermission.canModify()) throw IOException("PDF modification is not permitted")
    if (document.isEncrypted) throw IOException("Encrypted PDF rewrite is unsupported; unlock a permitted copy first")
}

/** Stage and reopen the actual serialized document before opening a destination.
 * A SAF provider can still fail while copying; that failure must propagate.
 */
internal suspend fun exportVerifiedPdf(
    context: Context,
    document: PDDocument,
    expectedPages: Int,
    verify: (PDDocument) -> Unit = {},
    openDestination: () -> OutputStream
) {
    val staged = File.createTempFile("vf_verified_", ".pdf", context.cacheDir)
    try {
        currentCoroutineContext().ensureActive()
        document.save(staged)
        PDDocument.load(staged, MemoryUsageSetting.setupTempFileOnly()).use { saved ->
            if (saved.numberOfPages != expectedPages) throw IOException("Saved PDF page count changed")
            verify(saved)
        }
        currentCoroutineContext().ensureActive()
        openDestination().use { output ->
            staged.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) throw IOException("Verified PDF copy made no read progress")
                    output.write(buffer, 0, n)
                }
            }
            output.flush()
        }
    } finally {
        staged.delete()
    }
}
