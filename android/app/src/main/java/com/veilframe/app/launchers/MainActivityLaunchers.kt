package com.veilframe.app.launchers

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.MainActivity
import com.veilframe.app.storage.CreateDocumentWithMime
import com.veilframe.app.tools.ToolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Encapsulates Activity Result Launchers and pending export payloads for MainActivity.
 * All launchers are registered synchronously during activity instantiation before lifecycle start.
 */
class MainActivityLaunchers(private val activity: MainActivity) {

    // Pending export buffers and files for SAF CreateDocument callbacks
    private var pendingMarkdownSaveContent: String? = null
    private var pendingImgStudioExportFile: File? = null
    private var pendingVidStudioExportFile: File? = null
    private var pendingDocScannerExportFile: File? = null
    private var pendingBgRemoverExportFile: File? = null
    private var pendingPdfSignatureSaveBytes: ByteArray? = null
    private var pendingPdfUnlockSaveBytes: ByteArray? = null

    // Markdown file picker & Save As
    private val markdownPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = activity.safStorageManager.getDisplayName(uri)?.lowercase(Locale.ROOT) ?: ""
            if (name.isNotEmpty() && !name.endsWith(".md") && !name.endsWith(".markdown")) {
                Toast.makeText(activity, "Please select a valid Markdown (.md) file", Toast.LENGTH_SHORT).show()
            } else {
                activity.safStorageManager.persistReadPermission(uri)
                activity.openMarkdownViewer(uri)
            }
        }
    }

    private val markdownSaveAsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { destUri ->
        val content = pendingMarkdownSaveContent
        pendingMarkdownSaveContent = null
        if (destUri != null && content != null) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val success = try {
                    val stream = activity.contentResolver.openOutputStream(destUri, "wt")
                    if (stream != null) {
                        stream.use { out ->
                            out.write(content.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                        true
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.e("VeilFrame", "Save As write failed: ${e.message}", e)
                    false
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        val name = DocumentFile.fromSingleUri(activity, destUri)?.name ?: "Document.md"
                        activity.markdownViewerController.onDocumentSavedAs(destUri, name)
                    } else {
                        Toast.makeText(activity, "Save As write failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Generic Artifact Export launcher
    private val exportDocumentLauncher = activity.registerForActivityResult(
        CreateDocumentWithMime()
    ) { destinationUri ->
        if (destinationUri != null) {
            val sourceFile = activity.toolSessionManager.currentState.lastGeneratedFile
            if (sourceFile != null && sourceFile.exists()) {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    val success = activity.safStorageManager.copyFileToUri(sourceFile, destinationUri)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            activity.consoleLogController.log("[EXPORT] Artifact written to storage: ${destinationUri.lastPathSegment ?: destinationUri.path}")
                            Toast.makeText(activity, "Saved to device storage", Toast.LENGTH_LONG).show()
                        } else {
                            activity.consoleLogController.log("[ERR] Failed to write exported file to destination.")
                            Toast.makeText(activity, "Export write failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // Media Studio SAF Export Launchers
    private val imgStudioExportLauncher = activity.registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingImgStudioExportFile
        pendingImgStudioExportFile = null
        if (destUri != null && file != null && file.exists()) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val ok = activity.safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    if (ok) Toast.makeText(activity, "Image saved to device storage", Toast.LENGTH_LONG).show()
                    else Toast.makeText(activity, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val vidStudioExportLauncher = activity.registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingVidStudioExportFile
        pendingVidStudioExportFile = null
        if (destUri != null && file != null && file.exists()) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val ok = activity.safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    if (ok) Toast.makeText(activity, "Video saved to device storage", Toast.LENGTH_LONG).show()
                    else Toast.makeText(activity, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Studio SAF Pickers
    private val imgStudioPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            activity.imageStudioController.handleImagesSelected(uris)
        }
    }

    private val imgStudioAddMoreLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            activity.imageStudioController.handleImagesAdded(uris)
        }
    }

    private val vidStudioPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            activity.videoStudioController.handleVideosSelected(uris)
        }
    }

    private val vidStudioAddMoreLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            activity.videoStudioController.handleVideosAdded(uris)
        }
    }

    private val imgFolderPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            activity.imageStudioController.onDestinationFolderSelected(uri)
        }
    }

    private val vidFolderPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            activity.videoStudioController.onDestinationFolderSelected(uri)
        }
    }

    // Motion Lab Picker
    private val motionLabPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.workspaceHost.getOrInflateMotionLab()
            activity.motionLabController.setSource(uri)
        }
    }

    // Image Upscaler Pickers
    private fun handleImageUpscalerResult(uri: Uri?, sourceName: String) {
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            activity.workspaceHost.getOrInflateImageUpscaler()
            activity.imageUpscalerController.handleImageSelected(uri)
        } else {
            activity.consoleLogController.log("[UPSCALER] $sourceName returned no image.")
        }
    }

    private val imgUpscalerDocumentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleImageUpscalerResult(uri, "System document picker")
    }

    private val imgUpscalerVisualMediaLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        handleImageUpscalerResult(uri, "Visual photo picker")
    }

    private val modelPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            activity.workspaceHost.getOrInflateImageUpscaler()
            activity.imageUpscalerController.handleCustomModelImport(uri)
        }
    }

    // Home Intake Pickers
    private val homePhotoPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            activity.handleHomeFileIntake(uri)
        }
    }

    private val homeVideoPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            activity.handleHomeFileIntake(uri)
        }
    }

    private val homeDocPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            activity.handleHomeFileIntake(uri)
        }
    }

    private val homeFilePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            activity.handleHomeFileIntake(uri)
        }
    }

    // Standard Tools Pickers
    private val visualMediaPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            activity.handleSingleFileSelected(uri)
        }
    }

    private val filePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            activity.handleSingleFileSelected(uri)
        }
    }

    private val folderPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadWritePermission(uri)
            activity.handleFolderSelected(uri)
        }
    }

    // Document Scanner Launchers
    private val docScannerCameraPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            activity.workspaceHost.getOrInflateDocScanner()
            activity.docScannerController.openCameraViewfinder()
        } else {
            Toast.makeText(activity, "Camera permission is required to scan documents", Toast.LENGTH_SHORT).show()
        }
    }

    private val docScannerCameraLauncher = activity.registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            activity.workspaceHost.getOrInflateDocScanner()
            activity.docScannerController.handleCameraPhotoCaptured(bitmap)
        }
    }

    private val docScannerPhotosLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            activity.workspaceHost.getOrInflateDocScanner()
            activity.docScannerController.handlePhotosImported(uris)
        }
    }

    private val docScannerFilesLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            activity.workspaceHost.getOrInflateDocScanner()
            activity.docScannerController.handlePhotosImported(uris)
        }
    }

    private val docScannerExportLauncher = activity.registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingDocScannerExportFile
        pendingDocScannerExportFile = null
        if (destUri != null && file != null) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val ok = activity.safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(activity, "PDF saved to storage", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, "Failed to save PDF to storage", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Background Remover Launchers
    private val bgRemoverPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            activity.workspaceHost.getOrInflateBackgroundRemover()
            activity.backgroundRemoverController.handleImageSelected(uri)
        }
    }

    private val bgRemoverExportLauncher = activity.registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingBgRemoverExportFile
        pendingBgRemoverExportFile = null
        if (destUri != null && file != null) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val ok = activity.safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(activity, "PNG cutout saved to storage", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(activity, "Failed to save PNG cutout to storage", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Image Quality Launcher
    private val qualityPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            activity.imageQualityController.handleImageSelected(uri)
        }
    }

    // Provenance Launcher
    private val provenancePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            activity.workspaceHost.getOrInflateProvenance()
            activity.provenanceController.handleFileSelected(uri)
        }
    }

    // PDF Signature Launchers
    private val pdfSignaturePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            activity.workspaceHost.getOrInflatePdfSignature()
            activity.pdfSignatureController.handlePdfSelected(uri)
        }
    }

    private val pdfSignatureSaveLauncher = activity.registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val bytes = pendingPdfSignatureSaveBytes
        pendingPdfSignatureSaveBytes = null
        if (destUri != null && bytes != null) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    activity.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Validated PDF saved to storage", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Failed to save PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val pdfCaCertPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            if (activity.isPdfSignatureControllerInitialized) {
                activity.pdfSignatureController.handleCaCertImported(uri)
            }
        }
    }

    // PDF Unlock Launchers
    private val pdfUnlockPickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            activity.safStorageManager.persistReadPermission(uri)
            activity.workspaceHost.getOrInflatePdfUnlock()
            activity.pdfUnlockController.handlePdfSelected(uri)
        }
    }

    private val pdfUnlockSaveLauncher = activity.registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val bytes = pendingPdfUnlockSaveBytes
        pendingPdfUnlockSaveBytes = null
        if (destUri != null && bytes != null) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    activity.contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Unlocked PDF saved to storage", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Failed to save PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Notification Permission Launcher
    private val notificationPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.i("VeilFrame", "POST_NOTIFICATIONS permission declined by user.")
        }
    }

    // Public Trigger APIs

    fun launchMarkdownPicker() {
        markdownPickerLauncher.launch(arrayOf("text/markdown", "text/x-markdown"))
    }

    fun launchMarkdownSaveAs(suggestedName: String, content: String) {
        pendingMarkdownSaveContent = content
        markdownSaveAsLauncher.launch(suggestedName)
    }

    fun launchExportDocument(fileName: String, mimeType: String) {
        exportDocumentLauncher.launch(fileName to mimeType)
    }

    fun launchImgStudioExport(file: File, mime: String = activity.safStorageManager.getExportMimeType(file)) {
        pendingImgStudioExportFile = file
        imgStudioExportLauncher.launch(file.name to mime)
    }

    fun launchVidStudioExport(file: File, mime: String = activity.safStorageManager.getExportMimeType(file)) {
        pendingVidStudioExportFile = file
        vidStudioExportLauncher.launch(file.name to mime)
    }

    fun launchImgStudioPicker(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) {
        imgStudioPickerLauncher.launch(request)
    }

    fun launchImgStudioAddMore(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) {
        imgStudioAddMoreLauncher.launch(request)
    }

    fun launchVidStudioPicker(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly).build()) {
        vidStudioPickerLauncher.launch(request)
    }

    fun launchVidStudioAddMore(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly).build()) {
        vidStudioAddMoreLauncher.launch(request)
    }

    fun launchImgFolderPicker() {
        imgFolderPickerLauncher.launch(null)
    }

    fun launchVidFolderPicker() {
        vidFolderPickerLauncher.launch(null)
    }

    fun launchMotionLabPicker(mimes: Array<String> = arrayOf("video/*")) {
        motionLabPickerLauncher.launch(mimes)
    }

    fun openImageUpscalerPicker() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(activity)) {
            val options = arrayOf("Photo Gallery", "Browse Files & Storage (SAF)")
            MaterialAlertDialogBuilder(activity)
                .setTitle("Select Image Source")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        try {
                            imgUpscalerVisualMediaLauncher.launch(
                                PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()
                            )
                        } catch (e: Exception) {
                            activity.consoleLogController.log("[UPSCALER] PhotoPicker launch failed: ${e.message}")
                            imgUpscalerDocumentLauncher.launch(arrayOf("image/*"))
                        }
                    } else {
                        imgUpscalerDocumentLauncher.launch(arrayOf("image/*"))
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            imgUpscalerDocumentLauncher.launch(arrayOf("image/*"))
        }
    }

    fun launchModelPicker(mimes: Array<String> = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) {
        modelPickerLauncher.launch(mimes)
    }

    fun launchHomePhotoPicker(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) {
        homePhotoPickerLauncher.launch(request)
    }

    fun launchHomeVideoPicker(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly).build()) {
        homeVideoPickerLauncher.launch(request)
    }

    fun launchHomeDocPicker(mimes: Array<String> = arrayOf("application/pdf", "text/*", "application/*")) {
        homeDocPickerLauncher.launch(mimes)
    }

    fun launchHomeFilePicker(mimes: Array<String> = arrayOf("*/*")) {
        homeFilePickerLauncher.launch(mimes)
    }

    fun launchFolderPicker() {
        folderPickerLauncher.launch(null)
    }

    fun openFilePickerForCurrentTool(toolMode: ToolMode?) {
        when (toolMode) {
            ToolMode.VIDEO_CLEANER -> {
                if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(activity)) {
                    visualMediaPickerLauncher.launch(
                        PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            .build()
                    )
                } else {
                    filePickerLauncher.launch(arrayOf("video/*"))
                }
            }
            ToolMode.IMAGE_CLEANER -> {
                if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(activity)) {
                    visualMediaPickerLauncher.launch(
                        PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            .build()
                    )
                } else {
                    filePickerLauncher.launch(arrayOf("image/*"))
                }
            }
            else -> {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        }
    }

    fun launchDocScannerCameraPermission() {
        docScannerCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
    }

    fun launchDocScannerPhotos(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) {
        docScannerPhotosLauncher.launch(request)
    }

    fun launchDocScannerFiles(mimes: Array<String>) {
        docScannerFilesLauncher.launch(mimes)
    }

    fun launchDocScannerExport(file: File, mime: String) {
        pendingDocScannerExportFile = file
        docScannerExportLauncher.launch(file.name to mime)
    }

    fun launchBgRemoverPicker(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) {
        bgRemoverPickerLauncher.launch(request)
    }

    fun launchBgRemoverExport(file: File, mime: String = activity.safStorageManager.getExportMimeType(file)) {
        pendingBgRemoverExportFile = file
        bgRemoverExportLauncher.launch(file.name to mime)
    }

    fun launchQualityPicker(request: PickVisualMediaRequest = PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()) {
        qualityPickerLauncher.launch(request)
    }

    fun launchProvenancePicker(mimes: Array<String> = arrayOf("*/*")) {
        provenancePickerLauncher.launch(mimes)
    }

    fun launchPdfSignaturePicker(mimes: Array<String> = arrayOf("application/pdf")) {
        pdfSignaturePickerLauncher.launch(mimes)
    }

    fun launchPdfSignatureSave(fileName: String, bytes: ByteArray) {
        pendingPdfSignatureSaveBytes = bytes
        pdfSignatureSaveLauncher.launch(fileName to "application/pdf")
    }

    fun launchPdfCaCertPicker(mimes: Array<String> = arrayOf("application/x-x509-ca-cert", "application/pkix-cert", "*/*")) {
        pdfCaCertPickerLauncher.launch(mimes)
    }

    fun launchPdfUnlockPicker(mimes: Array<String> = arrayOf("application/pdf")) {
        pdfUnlockPickerLauncher.launch(mimes)
    }

    fun launchPdfUnlockSave(fileName: String, bytes: ByteArray) {
        pendingPdfUnlockSaveBytes = bytes
        pdfUnlockSaveLauncher.launch(fileName to "application/pdf")
    }

    fun launchNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
