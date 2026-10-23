package com.veilframe.app.pdf

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.veilframe.app.databinding.LayoutPdfExtractPagesBinding
import com.veilframe.app.databinding.LayoutPdfExtractTextBinding
import com.veilframe.app.databinding.LayoutPdfFlattenBinding
import com.veilframe.app.databinding.LayoutPdfImpositionBinding
import com.veilframe.app.databinding.LayoutPdfQuickToolBinding
import com.veilframe.app.databinding.LayoutPdfStudioBinding
import com.veilframe.app.databinding.LayoutPdfWatermarkBinding
import com.veilframe.app.navigation.MainNavigationController
import com.veilframe.app.navigation.ScreenState
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.storage.CreateDocumentWithMime
import com.veilframe.app.storage.SafStorageManager
import com.veilframe.app.workspace.WorkspaceHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modular coordinator orchestrating the universal PDF Operations Suite:
 * - PDF Studio Hub
 * - Watermark PDF (live preview, opacity, tamper-proof security mesh)
 * - Extract Text & OCR (embedded streams + in-place ML Kit optical fallback)
 * - Extract Pages (as PDF document or high-res WebP/PNG/JPG image gallery)
 * - Flatten PDF (interactive Before vs After comparison)
 * - Print Imposition Studio (booklets, N-Up, zines)
 * - Quick Tool dispatcher (Merge, Split, Compress, Rotate, Reorder, Delete, Lock, Sign, Forms, Metadata)
 */
class PdfSuiteCoordinator(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val workspaceHost: WorkspaceHost,
    private val navigationController: MainNavigationController,
    private val safStorageManager: SafStorageManager,
    private val onOpenPdfSignature: () -> Unit,
    private val onOpenPdfUnlock: () -> Unit,
    private val onSaveActiveToolState: () -> Unit,
    private val onPauseVideoPlayback: () -> Unit,
    private val onClearBackTimer: () -> Unit,
    private val canNavigate: () -> Boolean,
    private val log: (String) -> Unit
) {

    // Dedicated controllers
    lateinit var studioController: PdfStudioController
        private set
    lateinit var watermarkController: PdfWatermarkController
        private set
    lateinit var extractTextController: PdfExtractTextController
        private set
    lateinit var extractPagesController: PdfExtractPagesController
        private set
    lateinit var flattenController: PdfFlattenController
        private set
    lateinit var impositionController: PdfImpositionController
        private set
    lateinit var quickToolController: PdfQuickToolController
        private set

    // Pending export bytes
    private var pendingWatermarkSaveBytes: ByteArray? = null
    private var pendingExtractTextSaveBytes: ByteArray? = null
    private var pendingExtractPagesSaveBytes: ByteArray? = null
    private var pendingFlattenSaveBytes: ByteArray? = null
    private var pendingImpositionSaveBytes: ByteArray? = null
    private var pendingQuickToolSaveBytes: ByteArray? = null

    // Activity Result Launchers registered at creation time
    private val watermarkPickerLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                safStorageManager.persistReadPermission(uri)
                workspaceHost.getOrInflatePdfWatermark()
                watermarkController.onPdfSelected(uri)
            }
        }

    private val watermarkSaveLauncher: ActivityResultLauncher<Pair<String, String>> =
        activity.registerForActivityResult(CreateDocumentWithMime()) { destUri ->
            val bytes = pendingWatermarkSaveBytes
            pendingWatermarkSaveBytes = null
            if (destUri != null && bytes != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        activity.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Watermarked PDF saved", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    private val extractTextPickerLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                safStorageManager.persistReadPermission(uri)
                workspaceHost.getOrInflatePdfExtractText()
                extractTextController.handlePdfSelected(uri)
            }
        }

    private val extractTextSaveLauncher: ActivityResultLauncher<Pair<String, String>> =
        activity.registerForActivityResult(CreateDocumentWithMime()) { destUri ->
            val bytes = pendingExtractTextSaveBytes
            pendingExtractTextSaveBytes = null
            if (destUri != null && bytes != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        activity.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Extracted text saved", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    private val extractPagesPickerLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                safStorageManager.persistReadPermission(uri)
                workspaceHost.getOrInflatePdfExtractPages()
                extractPagesController.handlePdfSelected(uri)
            }
        }

    private val extractPagesSaveLauncher: ActivityResultLauncher<Pair<String, String>> =
        activity.registerForActivityResult(CreateDocumentWithMime()) { destUri ->
            val bytes = pendingExtractPagesSaveBytes
            pendingExtractPagesSaveBytes = null
            if (destUri != null && bytes != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        activity.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Extracted pages saved", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    private val flattenPickerLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                safStorageManager.persistReadPermission(uri)
                workspaceHost.getOrInflatePdfFlatten()
                flattenController.onPdfSelected(uri)
            }
        }

    private val flattenSaveLauncher: ActivityResultLauncher<Pair<String, String>> =
        activity.registerForActivityResult(CreateDocumentWithMime()) { destUri ->
            val bytes = pendingFlattenSaveBytes
            pendingFlattenSaveBytes = null
            if (destUri != null && bytes != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        activity.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Flattened PDF saved", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    private val impositionPickerLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                safStorageManager.persistReadPermission(uri)
                workspaceHost.getOrInflatePdfImposition()
                impositionController.onPdfSelected(uri)
            }
        }

    private val impositionSaveLauncher: ActivityResultLauncher<Pair<String, String>> =
        activity.registerForActivityResult(CreateDocumentWithMime()) { destUri ->
            val bytes = pendingImpositionSaveBytes
            pendingImpositionSaveBytes = null
            if (destUri != null && bytes != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        activity.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Print imposition PDF saved", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    private val quickToolSinglePickerLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                safStorageManager.persistReadPermission(uri)
                workspaceHost.getOrInflatePdfQuickTool()
                quickToolController.handleFilesSelected(listOf(uri))
            }
        }

    private val quickToolMultiplePickerLauncher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) {
                uris.forEach { safStorageManager.persistReadPermission(it) }
                workspaceHost.getOrInflatePdfQuickTool()
                quickToolController.handleFilesSelected(uris)
            }
        }

    private val quickToolSaveLauncher: ActivityResultLauncher<Pair<String, String>> =
        activity.registerForActivityResult(CreateDocumentWithMime()) { destUri ->
            val bytes = pendingQuickToolSaveBytes
            pendingQuickToolSaveBytes = null
            if (destUri != null && bytes != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        activity.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Processed PDF saved", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

    fun initPdfStudio(binding: LayoutPdfStudioBinding) {
        studioController = PdfStudioController(
            activity = activity,
            binding = binding,
            onOpenMerge = { openQuickTool(PdfQuickToolController.QuickMode.MERGE) },
            onOpenSplit = { openQuickTool(PdfQuickToolController.QuickMode.SPLIT) },
            onOpenReorder = { openQuickTool(PdfQuickToolController.QuickMode.REORDER) },
            onOpenRotate = { openQuickTool(PdfQuickToolController.QuickMode.ROTATE) },
            onOpenDelete = { openQuickTool(PdfQuickToolController.QuickMode.DELETE) },
            onOpenExtractPages = { openExtractPages() },
            onOpenCompress = { openQuickTool(PdfQuickToolController.QuickMode.COMPRESS) },
            onOpenPdfToImage = { openQuickTool(PdfQuickToolController.QuickMode.PDF_TO_IMAGE) },
            onOpenImageToPdf = { openQuickTool(PdfQuickToolController.QuickMode.IMAGE_TO_PDF) },
            onOpenExtractText = { openExtractText() },
            onOpenHtmlToPdf = { openQuickTool(PdfQuickToolController.QuickMode.HTML_TO_PDF) },
            onOpenDocToPdf = { openQuickTool(PdfQuickToolController.QuickMode.DOC_TO_PDF) },
            onOpenLock = { openQuickTool(PdfQuickToolController.QuickMode.LOCK) },
            onOpenUnlock = { onOpenPdfUnlock() },
            onOpenWatermark = { openWatermark() },
            onOpenSign = { openQuickTool(PdfQuickToolController.QuickMode.SIGN) },
            onOpenFlatten = { openFlatten() },
            onOpenMetadata = { openQuickTool(PdfQuickToolController.QuickMode.METADATA) },
            onOpenImposition = { openImposition() },
            onOpenFillForms = { openQuickTool(PdfQuickToolController.QuickMode.FILL_FORMS) },
            onOpenSignatureValidator = { onOpenPdfSignature() },
            onNavigateBack = { navigationController.navigateBackFromWorkspace() }
        ).apply { init() }
    }

    fun initPdfWatermark(binding: LayoutPdfWatermarkBinding) {
        watermarkController = PdfWatermarkController(
            activity = activity,
            binding = binding,
            scope = scope,
            onPickPdfRequest = { watermarkPickerLauncher.launch(arrayOf("application/pdf")) },
            onSavePdfRequest = { bytes, fileName ->
                pendingWatermarkSaveBytes = bytes
                watermarkSaveLauncher.launch(fileName to "application/pdf")
            },
            onNavigateBack = { navigationController.navigateBackFromWorkspace() }
        ).apply { init() }
    }

    fun initPdfExtractText(binding: LayoutPdfExtractTextBinding) {
        extractTextController = PdfExtractTextController(
            activity = activity,
            binding = binding,
            scope = scope,
            onPickPdfRequest = { extractTextPickerLauncher.launch(arrayOf("application/pdf")) },
            onSaveTextRequest = { bytes, fileName ->
                pendingExtractTextSaveBytes = bytes
                extractTextSaveLauncher.launch(fileName to "text/plain")
            },
            onNavigateBack = { navigationController.navigateBackFromWorkspace() }
        ).apply { init() }
    }

    fun initPdfExtractPages(binding: LayoutPdfExtractPagesBinding) {
        extractPagesController = PdfExtractPagesController(
            activity = activity,
            binding = binding,
            scope = scope,
            onPickPdfRequest = { extractPagesPickerLauncher.launch(arrayOf("application/pdf")) },
            onSaveFileRequest = { bytes, fileName ->
                pendingExtractPagesSaveBytes = bytes
                val mime = when {
                    fileName.endsWith(".zip", true) -> "application/zip"
                    fileName.endsWith(".webp", true) -> "image/webp"
                    fileName.endsWith(".png", true) -> "image/png"
                    fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
                    else -> "application/pdf"
                }
                extractPagesSaveLauncher.launch(fileName to mime)
            },
            onNavigateBack = { navigationController.navigateBackFromWorkspace() }
        ).apply { init() }
    }

    fun initPdfFlatten(binding: LayoutPdfFlattenBinding) {
        flattenController = PdfFlattenController(
            activity = activity,
            binding = binding,
            scope = scope,
            onPickPdfRequest = { flattenPickerLauncher.launch(arrayOf("application/pdf")) },
            onSavePdfRequest = { bytes, fileName ->
                pendingFlattenSaveBytes = bytes
                flattenSaveLauncher.launch(fileName to "application/pdf")
            },
            onNavigateBack = { navigationController.navigateBackFromWorkspace() }
        ).apply { init() }
    }

    fun initPdfImposition(binding: LayoutPdfImpositionBinding) {
        impositionController = PdfImpositionController(
            activity = activity,
            binding = binding,
            scope = scope,
            onPickPdfRequest = { impositionPickerLauncher.launch(arrayOf("application/pdf")) },
            onSavePdfRequest = { bytes, fileName ->
                pendingImpositionSaveBytes = bytes
                impositionSaveLauncher.launch(fileName to "application/pdf")
            },
            onNavigateBack = { navigationController.navigateBackFromWorkspace() }
        ).apply { init() }
    }

    fun initPdfQuickTool(binding: LayoutPdfQuickToolBinding) {
        quickToolController = PdfQuickToolController(
            activity = activity,
            binding = binding,
            scope = scope,
            onPickFileRequest = { mime, multiple ->
                if (multiple) {
                    quickToolMultiplePickerLauncher.launch(arrayOf(mime))
                } else {
                    quickToolSinglePickerLauncher.launch(arrayOf(mime))
                }
            },
            onSaveFileRequest = { bytes, fileName ->
                pendingQuickToolSaveBytes = bytes
                val mime = when {
                    fileName.endsWith(".zip", true) -> "application/zip"
                    fileName.endsWith(".webp", true) -> "image/webp"
                    fileName.endsWith(".png", true) -> "image/png"
                    fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
                    else -> "application/pdf"
                }
                quickToolSaveLauncher.launch(fileName to mime)
            },
            onNavigateBack = { navigationController.navigateBackFromWorkspace() }
        ).apply { init() }
    }

    fun openPdfStudio() {
        if (!canNavigate()) return
        onClearBackTimer()
        onPauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        workspaceHost.getOrInflatePdfStudio()
        navigationController.showPdfStudioScreen()
        log("[UI] Opened PDF Studio Hub workspace.")
    }

    fun openWatermark() {
        if (!canNavigate()) return
        onClearBackTimer()
        onPauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        workspaceHost.getOrInflatePdfWatermark()
        navigationController.showPdfWatermarkScreen()
        log("[UI] Opened Watermark PDF workspace.")
    }

    fun openExtractText() {
        if (!canNavigate()) return
        onClearBackTimer()
        onPauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        workspaceHost.getOrInflatePdfExtractText()
        navigationController.showPdfExtractTextScreen()
        log("[UI] Opened Extract Text & OCR workspace.")
    }

    fun openExtractPages() {
        if (!canNavigate()) return
        onClearBackTimer()
        onPauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        workspaceHost.getOrInflatePdfExtractPages()
        navigationController.showPdfExtractPagesScreen()
        log("[UI] Opened Extract Pages workspace.")
    }

    fun openFlatten() {
        if (!canNavigate()) return
        onClearBackTimer()
        onPauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        workspaceHost.getOrInflatePdfFlatten()
        navigationController.showPdfFlattenScreen()
        log("[UI] Opened Flatten PDF workspace.")
    }

    fun openImposition() {
        if (!canNavigate()) return
        onClearBackTimer()
        onPauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        workspaceHost.getOrInflatePdfImposition()
        navigationController.showPdfImpositionScreen()
        log("[UI] Opened Print Imposition Studio workspace.")
    }

    fun openQuickTool(mode: PdfQuickToolController.QuickMode) {
        if (!canNavigate()) return
        onClearBackTimer()
        onPauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            onSaveActiveToolState()
        }
        workspaceHost.getOrInflatePdfQuickTool()
        if (::quickToolController.isInitialized) {
            quickToolController.setMode(mode)
        }
        navigationController.showPdfQuickToolScreen()
        log("[UI] Opened PDF Quick Tool: ${mode.title}")
    }

    fun hasActiveProcessing(): Boolean {
        if (::studioController.isInitialized && studioController.isProcessing) return true
        if (::watermarkController.isInitialized && watermarkController.isProcessing) return true
        if (::extractTextController.isInitialized && extractTextController.isProcessing) return true
        if (::extractPagesController.isInitialized && extractPagesController.isProcessing) return true
        if (::flattenController.isInitialized && flattenController.isProcessing) return true
        if (::impositionController.isInitialized && impositionController.isProcessing) return true
        if (::quickToolController.isInitialized && quickToolController.isProcessing) return true
        return false
    }

    fun getActiveToolWorkContract(screen: ScreenState): ToolWorkStateContract? = when (screen) {
        ScreenState.PDF_STUDIO -> if (::studioController.isInitialized) studioController else null
        ScreenState.PDF_WATERMARK -> if (::watermarkController.isInitialized) watermarkController else null
        ScreenState.PDF_EXTRACT_TEXT -> if (::extractTextController.isInitialized) extractTextController else null
        ScreenState.PDF_EXTRACT_PAGES -> if (::extractPagesController.isInitialized) extractPagesController else null
        ScreenState.PDF_FLATTEN -> if (::flattenController.isInitialized) flattenController else null
        ScreenState.PDF_IMPOSITION -> if (::impositionController.isInitialized) impositionController else null
        ScreenState.PDF_QUICK_TOOL -> if (::quickToolController.isInitialized) quickToolController else null
        else -> null
    }

    fun discardInactiveResources() {
        if (::studioController.isInitialized && !studioController.isProcessing) studioController.discardAndReleaseAllResources()
        if (::watermarkController.isInitialized && !watermarkController.isProcessing) watermarkController.discardAndReleaseAllResources()
        if (::extractTextController.isInitialized && !extractTextController.isProcessing) extractTextController.discardAndReleaseAllResources()
        if (::extractPagesController.isInitialized && !extractPagesController.isProcessing) extractPagesController.discardAndReleaseAllResources()
        if (::flattenController.isInitialized && !flattenController.isProcessing) flattenController.discardAndReleaseAllResources()
        if (::impositionController.isInitialized && !impositionController.isProcessing) impositionController.discardAndReleaseAllResources()
        if (::quickToolController.isInitialized && !quickToolController.isProcessing) quickToolController.discardAndReleaseAllResources()
    }

    fun discardAllResources() {
        if (::studioController.isInitialized) studioController.discardAndReleaseAllResources()
        if (::watermarkController.isInitialized) watermarkController.discardAndReleaseAllResources()
        if (::extractTextController.isInitialized) extractTextController.discardAndReleaseAllResources()
        if (::extractPagesController.isInitialized) extractPagesController.discardAndReleaseAllResources()
        if (::flattenController.isInitialized) flattenController.discardAndReleaseAllResources()
        if (::impositionController.isInitialized) impositionController.discardAndReleaseAllResources()
        if (::quickToolController.isInitialized) quickToolController.discardAndReleaseAllResources()
    }
}
