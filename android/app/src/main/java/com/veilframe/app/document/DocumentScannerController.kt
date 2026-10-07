package com.veilframe.app.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.R
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.cv.document.DocumentScanner
import com.veilframe.app.databinding.LayoutDocumentScannerBinding
import com.veilframe.app.storage.SafStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Controller orchestrating the dedicated Document Scanner workspace:
 * - Multi-page document session management
 * - OpenCV quadrilateral corner detection and perspective correction
 * - Document filters (Original, Enhanced, Black & White, Grayscale, Receipt)
 * - Native offline multi-page PDF document generation and export
 */
class DocumentScannerController(
    private val activity: AppCompatActivity,
    private val binding: LayoutDocumentScannerBinding,
    private val safStorageManager: SafStorageManager,
    private val scope: CoroutineScope,
    private val onTakePhotoRequest: () -> Unit,
    private val onChoosePhotosRequest: () -> Unit,
    private val onExportPdfRequest: (File) -> Unit,
    private val onNavigateBack: () -> Unit
) {
    val session = DocumentSession()

    fun init() {
        binding.toolbarDocScanner.setNavigationOnClickListener {
            onNavigateBack()
        }

        binding.btnDocEmptyCamera.setOnClickListener {
            onTakePhotoRequest()
        }

        binding.btnDocEmptyImport.setOnClickListener {
            onChoosePhotosRequest()
        }

        binding.btnDocAddCamera.setOnClickListener {
            onTakePhotoRequest()
        }

        binding.btnDocAddPhotos.setOnClickListener {
            onChoosePhotosRequest()
        }

        binding.btnDocAutoDetect.setOnClickListener {
            runAutoCropOnCurrentPage()
        }

        binding.btnDocExportPdf.setOnClickListener {
            exportToPdf()
        }

        binding.chipGroupDocFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val mode = when (checkedIds[0]) {
                    R.id.chipFilterOriginal -> DocumentScanner.DocumentMode.ORIGINAL
                    R.id.chipFilterEnhanced -> DocumentScanner.DocumentMode.ENHANCED
                    R.id.chipFilterBw -> DocumentScanner.DocumentMode.BLACK_AND_WHITE
                    R.id.chipFilterGrayscale -> DocumentScanner.DocumentMode.GRAYSCALE
                    R.id.chipFilterReceipt -> DocumentScanner.DocumentMode.RECEIPT
                    else -> DocumentScanner.DocumentMode.ENHANCED
                }
                applyFilterToCurrentPage(mode)
            }
        }

        updateUi()
    }

    fun handleCameraPhotoCaptured(bitmap: Bitmap) {
        val page = ScannedPage(
            originalBitmap = bitmap,
            processedBitmap = bitmap
        )
        session.addPage(page)
        updateUi()
        runAutoCropOnCurrentPage()
    }

    fun handlePhotosImported(uris: List<Uri>) {
        if (uris.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            val loadedPages = mutableListOf<ScannedPage>()
            for (uri in uris) {
                try {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) {
                            loadedPages.add(
                                ScannedPage(
                                    sourceUri = uri,
                                    originalBitmap = bmp,
                                    processedBitmap = bmp
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Ignore corrupted image
                }
            }

            withContext(Dispatchers.Main) {
                session.addPages(loadedPages)
                updateUi()
                if (loadedPages.isNotEmpty()) {
                    runAutoCropOnCurrentPage()
                }
            }
        }
    }

    private fun runAutoCropOnCurrentPage() {
        val page = session.currentPage ?: return

        scope.launch(Dispatchers.IO) {
            var warpedBitmap: Bitmap? = null
            try {
                val srcMat = BitmapBridge.toMat(page.originalBitmap)
                val scanResult = DocumentScanner.scan(
                    srcMat,
                    DocumentScanner.Options(mode = page.mode)
                )
                if (scanResult != null) {
                    warpedBitmap = BitmapBridge.toBitmap(scanResult.warped)
                    page.corners = scanResult.corners
                    scanResult.warped.release()
                }
                srcMat.release()
            } catch (e: Throwable) {
                warpedBitmap = null
            }

            withContext(Dispatchers.Main) {
                if (warpedBitmap != null) {
                    page.processedBitmap = warpedBitmap
                    binding.ivDocPagePreview.setImageBitmap(warpedBitmap)
                    Toast.makeText(activity, "Auto-detected document bounds", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "No document quadrilateral found; using full frame", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun applyFilterToCurrentPage(mode: DocumentScanner.DocumentMode) {
        val page = session.currentPage ?: return
        page.mode = mode

        scope.launch(Dispatchers.IO) {
            var filteredBitmap: Bitmap? = null
            try {
                val srcMat = BitmapBridge.toMat(page.originalBitmap)
                val scanResult = DocumentScanner.scan(
                    srcMat,
                    DocumentScanner.Options(mode = mode)
                )
                if (scanResult != null) {
                    filteredBitmap = BitmapBridge.toBitmap(scanResult.warped)
                    scanResult.warped.release()
                }
                srcMat.release()
            } catch (e: Throwable) {
                filteredBitmap = null
            }

            withContext(Dispatchers.Main) {
                if (filteredBitmap != null) {
                    page.processedBitmap = filteredBitmap
                    binding.ivDocPagePreview.setImageBitmap(filteredBitmap)
                }
            }
        }
    }

    private fun exportToPdf() {
        if (session.isEmpty) {
            Toast.makeText(activity, "Add at least one page before exporting PDF", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            val pdfDoc = PdfDocument()
            try {
                for ((i, page) in session.pages.withIndex()) {
                    val bmp = page.processedBitmap ?: page.originalBitmap
                    val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                    val pdfPage = pdfDoc.startPage(pageInfo)
                    pdfPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                    pdfDoc.finishPage(pdfPage)
                }

                val exportDir = File(activity.cacheDir, "exports").apply { mkdirs() }
                val outFile = File(exportDir, "Scanned_Document_${System.currentTimeMillis()}.pdf")
                FileOutputStream(outFile).use { fos ->
                    pdfDoc.writeTo(fos)
                }

                withContext(Dispatchers.Main) {
                    onExportPdfRequest(outFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Failed to compile PDF: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                pdfDoc.close()
            }
        }
    }

    private fun updateUi() {
        if (session.isEmpty) {
            binding.containerDocEmptyState.visibility = View.VISIBLE
            binding.containerDocPreview.visibility = View.GONE
            binding.btnDocExportPdf.isEnabled = false
            binding.cardDocActionDock.visibility = View.GONE
        } else {
            binding.containerDocEmptyState.visibility = View.GONE
            binding.containerDocPreview.visibility = View.VISIBLE
            binding.btnDocExportPdf.isEnabled = true
            binding.cardDocActionDock.visibility = View.VISIBLE

            val currentPage = session.currentPage
            val bmp = currentPage?.processedBitmap ?: currentPage?.originalBitmap
            binding.ivDocPagePreview.setImageBitmap(bmp)
            binding.tvDocPageIndicator.text = "Page ${session.activePageIndex + 1} of ${session.pageCount}"
        }
    }
}
