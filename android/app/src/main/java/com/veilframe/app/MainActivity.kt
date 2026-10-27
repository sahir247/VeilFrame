package com.veilframe.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.databinding.LayoutBackgroundRemoverBinding
import com.veilframe.app.databinding.LayoutDocumentScannerBinding
import com.veilframe.app.databinding.LayoutImageUpscalerBinding
import com.veilframe.app.databinding.LayoutMotionLabBinding
import com.veilframe.app.databinding.LayoutProvenanceBinding
import com.veilframe.app.databinding.LayoutPdfSignatureBinding
import com.veilframe.app.databinding.LayoutPdfUnlockBinding
import com.veilframe.app.pdf.PdfSignatureController
import com.veilframe.app.pdf.PdfUnlockController
import com.veilframe.app.pdf.PdfSuiteCoordinator
import com.veilframe.app.pdf.PdfQuickToolController
import com.veilframe.app.pdf.PdfUnlockEngine
import com.veilframe.app.pdf.PdfInitialCheck
import com.veilframe.app.logging.ConsoleLogController
import com.veilframe.app.markdown.MarkdownViewerController
import com.veilframe.app.document.DocumentScannerController
import com.veilframe.app.media.BackgroundRemoverController
import com.veilframe.app.media.MotionLabController
import com.veilframe.app.media.ImageQualityController
import com.veilframe.app.media.ImageStudioController
import com.veilframe.app.media.ProvenanceController
import com.veilframe.app.media.SafDestinationManager
import com.veilframe.app.media.VideoPlayerController
import com.veilframe.app.media.VideoStudioController
import com.veilframe.app.navigation.MainNavigationController
import com.veilframe.app.navigation.ScreenState
import com.veilframe.app.navigation.WorkspaceRoute
import com.veilframe.app.navigation.WorkspaceCategory
import com.veilframe.app.ui.picker.SharedSourcePickerSheet
import com.veilframe.app.upscale.ui.ImageUpscalerController
import com.veilframe.app.workspace.WorkspaceHost
import com.veilframe.app.storage.CreateDocumentWithMime
import com.veilframe.app.storage.SafStorageManager
import com.veilframe.app.tools.JobState
import com.veilframe.app.tools.ToolExecutionController
import com.veilframe.app.tools.ToolMode
import com.veilframe.app.tools.ToolSessionManager
import com.veilframe.app.ui.dialogs.MainDialogHelper
import com.veilframe.app.ui.library.LibraryCoordinator
import com.veilframe.app.ui.motion.ExpressiveMotion
import com.veilframe.app.ui.motion.MorphDialogController
import com.veilframe.app.ui.motion.VeilFrameInteraction
import com.veilframe.app.ui.settings.SettingsOverlayCoordinator
import com.veilframe.app.ui.tools.ToolsCatalogueCoordinator
import com.veilframe.app.updates.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * VeilFrame Mobile Hub — Android Vertical Forensics & AI Bundler.
 * Orchestrates Home Dashboard, 4 Dedicated Tool Workflows (AI Bundler, Video Sanitizer,
 * Image Cleaner, Folder Scanner), and 2 Interactive Media Studios (Image Studio, Video Studio).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Modular Sub-Controllers & Feature Coordinators
    internal lateinit var safStorageManager: SafStorageManager
    private lateinit var consoleLogController: ConsoleLogController
    internal lateinit var workspaceHost: WorkspaceHost
    internal lateinit var navigationController: MainNavigationController
    private lateinit var toolSessionManager: ToolSessionManager
    private lateinit var toolExecutionController: ToolExecutionController
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var libraryCoordinator: LibraryCoordinator
    private lateinit var toolsCatalogueCoordinator: ToolsCatalogueCoordinator
    private lateinit var settingsOverlayCoordinator: SettingsOverlayCoordinator
    internal lateinit var homeFileIntakeCoordinator: com.veilframe.app.ui.intake.HomeFileIntakeCoordinator

    // Dedicated Media Studio Controllers
    private lateinit var safDestinationManager: SafDestinationManager
    private lateinit var videoPlayerController: VideoPlayerController
    internal lateinit var imageStudioController: ImageStudioController
    internal lateinit var videoStudioController: VideoStudioController
    internal lateinit var imageUpscalerController: ImageUpscalerController
    internal lateinit var markdownViewerController: MarkdownViewerController
    private lateinit var docScannerController: DocumentScannerController
    internal lateinit var backgroundRemoverController: BackgroundRemoverController
    internal lateinit var motionLabController: MotionLabController
    private lateinit var imageQualityController: ImageQualityController
    internal lateinit var provenanceController: ProvenanceController
    lateinit var pdfSignatureController: PdfSignatureController
    lateinit var pdfUnlockController: PdfUnlockController
    private var pendingImgStudioExportFile: File? = null
    private var pendingVidStudioExportFile: File? = null
    private var pendingDocScannerExportFile: File? = null
    private var pendingBgRemoverExportFile: File? = null
    private var pendingPdfSignatureSaveBytes: ByteArray? = null
    private var pendingPdfUnlockSaveBytes: ByteArray? = null

    // Dedicated PDF Suite Coordinator
    internal lateinit var pdfSuiteCoordinator: PdfSuiteCoordinator
    private var isConsoleExpanded: Boolean = false
    private var backClearTimerJob: Job? = null

    // Markdown file picker launcher
    private val markdownPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = safStorageManager.getDisplayName(uri)?.lowercase(Locale.ROOT) ?: ""
            if (name.isNotEmpty() && !name.endsWith(".md") && !name.endsWith(".markdown")) {
                Toast.makeText(this, "Please select a valid Markdown (.md) file", Toast.LENGTH_SHORT).show()
            } else {
                safStorageManager.persistReadPermission(uri)
                openMarkdownViewer(uri)
            }
        }
    }

    private var pendingMarkdownSaveContent: String? = null

    // Markdown Save As export launcher (Storage Access Framework)
    private val markdownSaveAsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { destUri ->
        val content = pendingMarkdownSaveContent
        if (destUri != null && content != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val success = try {
                    val stream = contentResolver.openOutputStream(destUri, "wt")
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
                        val name = DocumentFile.fromSingleUri(this@MainActivity, destUri)?.name ?: "Document.md"
                        markdownViewerController.onDocumentSavedAs(destUri, name)
                    } else {
                        Toast.makeText(this@MainActivity, "Save As write failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        pendingMarkdownSaveContent = null
    }

    // Multi-format export launcher (Storage Access Framework)
    private val exportDocumentLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destinationUri ->
        if (destinationUri != null) {
            val sourceFile = toolSessionManager.currentState.lastGeneratedFile
            if (sourceFile != null && sourceFile.exists()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = safStorageManager.copyFileToUri(sourceFile, destinationUri)
                    withContext(Dispatchers.Main) {
                        if (success) {
                            consoleLogController.log("[EXPORT] Artifact written to storage: ${destinationUri.lastPathSegment ?: destinationUri.path}")
                            Toast.makeText(this@MainActivity, "Saved to device storage", Toast.LENGTH_LONG).show()
                        } else {
                            consoleLogController.log("[ERR] Failed to write exported file to destination.")
                            Toast.makeText(this@MainActivity, "Export write failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // Media Studio SAF Export Launchers
    private val imgStudioExportLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingImgStudioExportFile
        pendingImgStudioExportFile = null
        if (destUri != null && file != null && file.exists()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    if (ok) Toast.makeText(this@MainActivity, "Image saved to device storage", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val vidStudioExportLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingVidStudioExportFile
        pendingVidStudioExportFile = null
        if (destUri != null && file != null && file.exists()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    if (ok) Toast.makeText(this@MainActivity, "Video saved to device storage", Toast.LENGTH_LONG).show()
                    else Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Studio SAF Pickers
    private val imgStudioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            imageStudioController.handleImagesSelected(uris)
        }
    }

    private val imgStudioAddMoreLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            imageStudioController.handleImagesAdded(uris)
        }
    }

    private val vidStudioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            videoStudioController.handleVideosSelected(uris)
        }
    }

    private val motionLabPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            workspaceHost.getOrInflateMotionLab()
            motionLabController.setSource(uri)
        }
    }

    private val vidStudioAddMoreLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            videoStudioController.handleVideosAdded(uris)
        }
    }

    private val imgFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            imageStudioController.onDestinationFolderSelected(uri)
        }
    }

    private val vidFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            videoStudioController.onDestinationFolderSelected(uri)
        }
    }

    private fun handleImageUpscalerResult(uri: Uri?, sourceName: String) {
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            workspaceHost.getOrInflateImageUpscaler()
            imageUpscalerController.handleImageSelected(uri)
        } else {
            consoleLogController.log("[UPSCALER] $sourceName returned no image.")
        }
    }

    private val imgUpscalerDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        handleImageUpscalerResult(uri, "System document picker")
    }

    private val imgUpscalerVisualMediaLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        handleImageUpscalerResult(uri, "Visual photo picker")
    }

    private fun openImageUpscalerPicker() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
            val options = arrayOf("Photo Gallery", "Browse Files & Storage (SAF)")
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Select Image Source")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        try {
                            imgUpscalerVisualMediaLauncher.launch(
                                PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build()
                            )
                        } catch (e: Exception) {
                            consoleLogController.log("[UPSCALER] PhotoPicker launch failed: ${e.message}")
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

    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            workspaceHost.getOrInflateImageUpscaler()
            imageUpscalerController.handleCustomModelImport(uri)
        }
    }

    // Dedicated Tools SAF and Photo Picker Activity Result Launchers
    private val homePhotoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleHomeFileIntake(uri)
        }
    }

    private val homeVideoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            handleHomeFileIntake(uri)
        }
    }

    private val homeDocPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            handleHomeFileIntake(uri)
        }
    }

    private val homeFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            handleHomeFileIntake(uri)
        }
    }

    private val visualMediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            handleSingleFileSelected(uri)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            handleSingleFileSelected(uri)
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadWritePermission(uri)
            handleFolderSelected(uri)
        }
    }

    // Dedicated Document Scanner Activity Result Launchers
    private val docScannerCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            workspaceHost.getOrInflateDocScanner()
            docScannerController.openCameraViewfinder()
        } else {
            Toast.makeText(this, "Camera permission is required to scan documents", Toast.LENGTH_SHORT).show()
        }
    }

    private val docScannerCameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            workspaceHost.getOrInflateDocScanner()
            docScannerController.handleCameraPhotoCaptured(bitmap)
        }
    }

    private val docScannerPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            workspaceHost.getOrInflateDocScanner()
            docScannerController.handlePhotosImported(uris)
        }
    }

    private val docScannerFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            workspaceHost.getOrInflateDocScanner()
            docScannerController.handlePhotosImported(uris)
        }
    }

    private val docScannerExportLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingDocScannerExportFile
        pendingDocScannerExportFile = null
        if (destUri != null && file != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@MainActivity, "PDF saved to storage", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to save PDF to storage", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Dedicated Background Remover Activity Result Launchers
    private val bgRemoverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            workspaceHost.getOrInflateBackgroundRemover()
            backgroundRemoverController.handleImageSelected(uri)
        }
    }

    private val bgRemoverExportLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingBgRemoverExportFile
        pendingBgRemoverExportFile = null
        if (destUri != null && file != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        Toast.makeText(this@MainActivity, "PNG cutout saved to storage", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to save PNG cutout to storage", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Dedicated Image Quality Activity Result Launcher
    private val qualityPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            imageQualityController.handleImageSelected(uri)
        }
    }

    // Dedicated Provenance Activity Result Launcher
    private val provenancePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            workspaceHost.getOrInflateProvenance()
            provenanceController.handleFileSelected(uri)
        }
    }

    // Dedicated PDF Signature Activity Result Launchers
    private val pdfSignaturePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            workspaceHost.getOrInflatePdfSignature()
            pdfSignatureController.handlePdfSelected(uri)
        }
    }

    private val pdfSignatureSaveLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val bytes = pendingPdfSignatureSaveBytes
        pendingPdfSignatureSaveBytes = null
        if (destUri != null && bytes != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Validated PDF saved to storage", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to save PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val pdfCaCertPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            if (::pdfSignatureController.isInitialized) {
                pdfSignatureController.handleCaCertImported(uri)
            }
        }
    }

    // Dedicated PDF Unlock Activity Result Launchers
    private val pdfUnlockPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            workspaceHost.getOrInflatePdfUnlock()
            pdfUnlockController.handlePdfSelected(uri)
        }
    }

    private val pdfUnlockSaveLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val bytes = pendingPdfUnlockSaveBytes
        pendingPdfUnlockSaveBytes = null
        if (destUri != null && bytes != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(destUri)?.use { it.write(bytes) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Unlocked PDF saved to storage", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to save PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.i("VeilFrame", "POST_NOTIFICATIONS permission declined by user.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        try {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        } catch (ignored: Exception) {}
        com.veilframe.app.settings.ThemeSettingsManager.applyActivityTheme(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Seamless settings theme restoration: immediately reveal container to eliminate 1-frame background reveal
        val wasSettingsOpen = (savedInstanceState?.getBoolean(KEY_SETTINGS_OPEN, false) == true) || pendingSeamlessSettingsRestore
        if (wasSettingsOpen) {
            binding.containerSettings.visibility = View.VISIBLE
            binding.containerSettings.translationX = 0f
            binding.containerSettings.alpha = 1f
            binding.scrimSettings.visibility = View.VISIBLE
            binding.scrimSettings.alpha = 1f
        }

        com.veilframe.app.settings.ThemeSettingsManager.applyTypography(this)
        val prefs = getSharedPreferences("veilframe_prefs", Context.MODE_PRIVATE)

        // Native notification permission prompt on 1st time launch (Android 13+ / API 33+)
        val hasPromptedNotifications = prefs.getBoolean("has_prompted_notification_permission", false)
        if (!hasPromptedNotifications) {
            prefs.edit().putBoolean("has_prompted_notification_permission", true).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        // Restore cached changelog if available
        val cachedTag = prefs.getString("cached_changelog_tag", null)
        val cachedChangelog = prefs.getString("cached_changelog", null)
        if (!cachedTag.isNullOrEmpty() && !cachedChangelog.isNullOrEmpty()) {
            binding.layoutSettingsPanel.tvSettingsWhatsNewHeader.text = "WHAT'S NEW IN $cachedTag"
            binding.layoutSettingsPanel.tvSettingsWhatsNewContent.text = cachedChangelog
        } else {
            binding.layoutSettingsPanel.tvSettingsWhatsNewHeader.text = "WHAT'S NEW IN ${com.veilframe.app.util.AppVersionProvider.getDisplayVersion(this)}"
        }

        // System insets handling: status bar top inset for AppBarLayout + navigation bar bottom inset for action dock + settings panel + bottom navigation / rail
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val navBarLeft = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).left
            binding.appBarLayout.setPadding(0, statusBarTop, 0, 0)
            binding.containerSettings.setPadding(0, statusBarTop, 0, navBarBottom)
            val layoutParams = binding.cardCleanerActionDock.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            if (layoutParams != null) {
                layoutParams.bottomMargin = navBarBottom + (16 * resources.displayMetrics.density).toInt()
                binding.cardCleanerActionDock.layoutParams = layoutParams
            }
            binding.bottomNavigation.setPadding(0, 0, 0, navBarBottom)
            binding.navigationRail.setPadding(navBarLeft, statusBarTop, 0, navBarBottom)

            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarRight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).right

            // Additive Inset Architecture (P0 Architectural Inset Resolution):
            // 1. Workspaces scrolled under AppBarLayout via appbar_scrolling_view_behavior:
            //    CoordinatorLayout + AppBarLayout already positions these below the status bar.
            //    Apply SCROLLED_APPBAR (padTop = false) to prevent double top status-bar gaps,
            //    while additively preserving XML base bottom clearances (e.g. 160dp on scrollTool).
            listOf(
                binding.scrollTool,
                binding.layoutToolsCatalogue.root,
                binding.layoutLibrary.root
            ).forEach { root ->
                com.veilframe.app.ui.insets.WorkspaceInsets.apply(
                    root = root,
                    statusBarTop = statusBarTop,
                    navBarBottom = navBarBottom,
                    imeBottom = imeBottom,
                    navBarLeft = navBarLeft,
                    navBarRight = navBarRight,
                    contract = com.veilframe.app.ui.insets.WorkspaceInsets.SCROLLED_APPBAR
                )
            }

            // 2. Full-screen / custom-toolbar workspaces spanning full window:
            //    Apply DEFAULT (padTop = true, padBottom = true) additively to initial padding.
            listOf(
                binding.layoutImageStudio.root,
                binding.layoutVideoStudio.root,
                binding.layoutMarkdownViewer.root,
                binding.layoutImageQuality.root,
                binding.fragmentQrStudio
            ).forEach { root ->
                com.veilframe.app.ui.insets.WorkspaceInsets.apply(
                    root = root,
                    statusBarTop = statusBarTop,
                    navBarBottom = navBarBottom,
                    imeBottom = imeBottom,
                    navBarLeft = navBarLeft,
                    navBarRight = navBarRight,
                    contract = com.veilframe.app.ui.insets.WorkspaceInsets.DEFAULT
                )
            }

            // 3. Secondary offline workspaces (lazy-inflated via WorkspaceHost):
            workspaceHost.updateInsets(
                statusBarTop = statusBarTop,
                navBarBottom = navBarBottom,
                imeBottom = imeBottom,
                navBarLeft = navBarLeft,
                navBarRight = navBarRight
            )
            insets
        }

        initSubControllers()
        initStudioWorkspaces()
        setupListeners()
        VeilFrameInteraction.bindWorkspace(binding.root)

        binding.layoutSettingsPanel.tvSettingsUpdateStatus.text = "Installed: ${com.veilframe.app.util.AppVersionProvider.getDisplayVersion(this)} • Local Engine"

        consoleLogController.log("[SYS] Initialized VeilFrame ${com.veilframe.app.util.AppVersionProvider.getVersionName(this)} Native Core Runtime")
        consoleLogController.log("[SYS] Native Media3, FFmpegKit 8.1.7, and AndroidX Privacy Engine active.")

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState)
        } else {
            navigationController.showHomeScreen()
            if (pendingSeamlessSettingsRestore) {
                val scrollY = pendingSeamlessSettingsScrollY
                pendingSeamlessSettingsRestore = false
                pendingSeamlessSettingsScrollY = 0
                openSettingsOverlay(animate = false, restoreScrollY = scrollY)
            }
        }

        // Asynchronous, non-blocking update check on launch
        appUpdateManager.checkForUpdates(isUserInitiated = false)

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val dest = intent.getStringExtra("extra_navigate_destination")
        if (!dest.isNullOrEmpty()) {
            when (dest) {
                "IMAGE_UPSCALER" -> navigationController.showImageUpscalerScreen()
                "MOTION_LAB" -> navigationController.showMotionLabScreen()
                "VIDEO_STUDIO" -> navigationController.showVideoStudioScreen()
            }
            return
        }
        val action = intent.action
        val type = intent.type ?: ""
        val uri: Uri? = if (action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } ?: intent.data
        } else if (action == Intent.ACTION_VIEW) {
            intent.data
        } else {
            null
        }

        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            val displayName = DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: ""
            val nameLower = displayName.lowercase(Locale.ROOT)
            val isMarkdown = nameLower.endsWith(".md") ||
                    nameLower.endsWith(".markdown") ||
                    nameLower.endsWith(".mdown") ||
                    nameLower.endsWith(".mkdn") ||
                    type == "text/markdown" ||
                    (type == "text/plain" && (nameLower.endsWith(".md") || nameLower.endsWith(".markdown")))

            if (isMarkdown) {
                openMarkdownViewer(uri, displayName)
            } else if (type.startsWith("video/") || nameLower.endsWith(".mp4") || nameLower.endsWith(".webm") || nameLower.endsWith(".mkv") || nameLower.endsWith(".mov") || nameLower.endsWith(".avi")) {
                openVideoStudio()
                videoStudioController.handleVideoSelected(uri)
            } else if (type.startsWith("image/") || nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg") || nameLower.endsWith(".png") || nameLower.endsWith(".webp") || nameLower.endsWith(".heic") || nameLower.endsWith(".heif") || nameLower.endsWith(".avif") || nameLower.endsWith(".bmp") || nameLower.endsWith(".tiff") || nameLower.endsWith(".tif") || nameLower.endsWith(".gif")) {
                openImageStudio()
                imageStudioController.handleImageSelected(uri)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        pauseVideoPlayback()
    }

    override fun onStop() {
        super.onStop()
        workspaceHost.pause()
        if (::docScannerController.isInitialized) {
            docScannerController.closeCameraViewfinder()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        workspaceHost.onDestroy()
        appUpdateManager.onDestroy()
        if (::docScannerController.isInitialized) {
            docScannerController.release()
        }
        if (::motionLabController.isInitialized) {
            motionLabController.cancel()
        }
        if (::imageStudioController.isInitialized) {
            imageStudioController.release()
        }
        if (::videoStudioController.isInitialized) {
            videoStudioController.release()
        }
        if (::videoPlayerController.isInitialized) {
            videoPlayerController.release()
        }
        if (::imageUpscalerController.isInitialized) {
            imageUpscalerController.release()
        }
        if (::markdownViewerController.isInitialized) {
            markdownViewerController.clear()
        }
        if (::backgroundRemoverController.isInitialized) {
            backgroundRemoverController.cleanup()
        }
        if (::pdfSignatureController.isInitialized) {
            pdfSignatureController.discardAndReleaseAllResources()
        }
        if (::pdfUnlockController.isInitialized) {
            pdfUnlockController.discardAndReleaseAllResources()
        }
        if (::pdfSuiteCoordinator.isInitialized) {
            pdfSuiteCoordinator.discardAllResources()
        }
    }

    private fun initSubControllers() {
        safStorageManager = SafStorageManager(this)

        consoleLogController = ConsoleLogController(
            context = this,
            tvConsoleLog = binding.tvConsoleLog,
            scrollConsole = binding.scrollConsole,
            btnCopyLogs = binding.btnCopyLogs,
            btnClearLogs = binding.btnClearLogs,
            btnExpandLogs = binding.btnExpandLogs,
            onLogUpdated = { newLog ->
                toolSessionManager.currentState.consoleLogs = newLog
            }
        )
        consoleLogController.init()

        workspaceHost = WorkspaceHost(
            binding = binding,
            onImageUpscalerInflated = { upscalerBinding ->
                initImageUpscaler(upscalerBinding)
            },
            onDocScannerInflated = { docBinding ->
                initDocScanner(docBinding)
            },
            onBackgroundRemoverInflated = { bgBinding ->
                initBackgroundRemover(bgBinding)
            },
            onMotionLabInflated = { motionBinding ->
                initMotionLab(motionBinding)
            },
            onProvenanceInflated = { provBinding ->
                initProvenance(provBinding)
            },
            onPdfSignatureInflated = { pdfSigBinding ->
                initPdfSignature(pdfSigBinding)
            },
            onPdfUnlockInflated = { pdfUnlockBinding ->
                initPdfUnlock(pdfUnlockBinding)
            },
            onPdfStudioInflated = { pdfSuiteCoordinator.initPdfStudio(it) },
            onPdfWatermarkInflated = { pdfSuiteCoordinator.initPdfWatermark(it) },
            onPdfExtractTextInflated = { pdfSuiteCoordinator.initPdfExtractText(it) },
            onPdfExtractPagesInflated = { pdfSuiteCoordinator.initPdfExtractPages(it) },
            onPdfFlattenInflated = { pdfSuiteCoordinator.initPdfFlatten(it) },
            onPdfImpositionInflated = { pdfSuiteCoordinator.initPdfImposition(it) },
            onPdfQuickToolInflated = { pdfSuiteCoordinator.initPdfQuickTool(it) }
        )

        navigationController = MainNavigationController(
            activity = this,
            binding = binding,
            workspaceHost = workspaceHost,
            onSaveActiveToolState = { toolSessionManager.saveCurrentToolState() },
            onPauseVideoPlayback = { pauseVideoPlayback() },
            onHomeScreenEntered = { scheduleBackClearWork() },
            onMarkdownBackPressed = {
                if (::markdownViewerController.isInitialized) {
                    markdownViewerController.handleBackPressed()
                } else {
                    false
                }
            },
            onLibraryScreenEntered = { refreshLibrary() }
        )
        navigationController.init()

        pdfSuiteCoordinator = PdfSuiteCoordinator(
            activity = this,
            scope = lifecycleScope,
            workspaceHost = workspaceHost,
            navigationController = navigationController,
            safStorageManager = safStorageManager,
            onOpenPdfSignature = { openPdfSignature() },
            onOpenPdfUnlock = { openPdfUnlock() },
            onSaveActiveToolState = { toolSessionManager.saveCurrentToolState() },
            onPauseVideoPlayback = { pauseVideoPlayback() },
            onClearBackTimer = { clearBackTimer() },
            canNavigate = { canNavigate() },
            log = { consoleLogController.log(it) }
        )

        toolSessionManager = ToolSessionManager(
            activity = this,
            binding = binding,
            safStorageManager = safStorageManager,
            onLog = { msg -> consoleLogController.log(msg) },
            onOpenFilePicker = { openFilePickerForCurrentTool() }
        )
        toolSessionManager.init()

        toolExecutionController = ToolExecutionController(
            activity = this,
            binding = binding,
            sessionManager = toolSessionManager,
            safStorageManager = safStorageManager,
            scope = lifecycleScope,
            onOpenFilePicker = { openFilePickerForCurrentTool() },
            onOpenFolderPicker = { folderPickerLauncher.launch(null) },
            onLog = { msg -> consoleLogController.log(msg) },
            getImageStudioController = { if (::imageStudioController.isInitialized) imageStudioController else null },
            getVideoStudioController = { if (::videoStudioController.isInitialized) videoStudioController else null }
        )
        setupToolScrollDockBehavior()

        appUpdateManager = AppUpdateManager(
            activity = this,
            binding = binding,
            safStorageManager = safStorageManager,
            scope = lifecycleScope,
            onLog = { msg -> consoleLogController.log(msg) }
        )

        markdownViewerController = MarkdownViewerController(
            activity = this,
            binding = binding.layoutMarkdownViewer,
            onNavigateBack = {
                navigationController.navigateBackFromWorkspace()
            },
            onOpenMarkdownFileRequest = {
                markdownPickerLauncher.launch(arrayOf("text/markdown", "text/x-markdown"))
            },
            onSaveAsMarkdownRequest = { suggestedName, content ->
                pendingMarkdownSaveContent = content
                markdownSaveAsLauncher.launch(suggestedName)
            }
        )
        markdownViewerController.init()

        libraryCoordinator = LibraryCoordinator(
            activity = this,
            binding = binding.layoutLibrary,
            safStorageManager = safStorageManager,
            scope = lifecycleScope,
            onResumeSession = { sessionId ->
                openDocumentScanner()
                docScannerController.resumeSession(sessionId)
            },
            onShareFile = { file, mime, contentUri ->
                shareStudioFile(file, mime, contentUri)
            },
            onExploreTools = {
                navigationController.showToolsScreen()
            }
        )
        libraryCoordinator.init()

        toolsCatalogueCoordinator = ToolsCatalogueCoordinator(
            binding = binding.layoutToolsCatalogue,
            onOpenRoute = { route, _ ->
                when (route) {
                    WorkspaceRoute.IMAGE_STUDIO -> openImageStudio()
                    WorkspaceRoute.VIDEO_STUDIO -> openVideoStudio()
                    WorkspaceRoute.QR_STUDIO -> openQrStudio()
                    WorkspaceRoute.DOCUMENT_SCANNER -> openDocumentScanner()
                    WorkspaceRoute.BACKGROUND_REMOVER -> openBackgroundRemover()
                    WorkspaceRoute.MOTION_LAB -> openMotionLab()
                    WorkspaceRoute.IMAGE_UPSCALER -> openImageUpscaler()
                    WorkspaceRoute.IMAGE_CLEANER -> openTool(ToolMode.IMAGE_CLEANER)
                    WorkspaceRoute.VIDEO_CLEANER -> openTool(ToolMode.VIDEO_CLEANER)
                    WorkspaceRoute.IMAGE_QUALITY -> showImageQualitySourcePicker()
                    WorkspaceRoute.FOLDER_SCANNER -> openTool(ToolMode.FOLDER_SCANNER)
                    WorkspaceRoute.AI_BUNDLE -> openTool(ToolMode.AI_BUNDLE)
                    WorkspaceRoute.MARKDOWN_STUDIO -> showMarkdownStudioEntryDialog()
                    WorkspaceRoute.PROVENANCE -> openProvenance()
                    WorkspaceRoute.PDF_SIGNATURE -> openPdfSignature()
                    WorkspaceRoute.PDF_UNLOCK -> openPdfUnlock()
                    WorkspaceRoute.PDF_STUDIO -> openPdfStudio()
                }
            },
            onOpenMarkdownStudio = {
                showMarkdownStudioEntryDialog()
            }
        )
        toolsCatalogueCoordinator.init()

        settingsOverlayCoordinator = SettingsOverlayCoordinator(
            activity = this,
            panelBinding = binding.layoutSettingsPanel,
            settingsContainer = binding.containerSettings,
            scrimSettings = binding.scrimSettings,
            rootCoordinator = binding.rootCoordinator,
            scope = lifecycleScope,
            onRecreateNeeded = { restoreScrollY ->
                seamlessRecreateSettings(restoreScrollY)
            },
            onOpenDiagnostics = { showDiagnosticsDialog() },
            onCheckUpdates = { appUpdateManager.checkForUpdates(isUserInitiated = true) },
            onRepairApp = { appUpdateManager.repairApp() },
            onOpenUrl = { url -> openWebUrl(url) },
            onShowAbout = { showAboutDialog() }
        )

        homeFileIntakeCoordinator = com.veilframe.app.ui.intake.HomeFileIntakeCoordinator(
            activity = this,
            binding = binding
        )
    }

    private fun initStudioWorkspaces() {
        safDestinationManager = SafDestinationManager(this)
        videoPlayerController = VideoPlayerController(
            context = this,
            textureView = binding.layoutVideoStudio.videoTextureView,
            scope = lifecycleScope
        )

        imageStudioController = ImageStudioController(
            activity = this,
            binding = binding.layoutImageStudio,
            safManager = safDestinationManager,
            scope = lifecycleScope,
            onPickImageRequest = { imgStudioPickerLauncher.launch("image/*") },
            onAddMoreImageRequest = { imgStudioAddMoreLauncher.launch("image/*") },
            onPickFolderRequest = { imgFolderPickerLauncher.launch(null) },
            onExportFileRequest = { file ->
                pendingImgStudioExportFile = file
                val mime = safStorageManager.getExportMimeType(file)
                imgStudioExportLauncher.launch(file.name to mime)
            },
            onShareFileRequest = { file, mime ->
                shareStudioFile(file, mime)
            },
            onNavigateHome = {
                navigationController.navigateBackFromWorkspace()
            },
            onOpenModelManager = {
                workspaceHost.getOrInflateImageUpscaler()
                imageUpscalerController.showModelManagerDialog()
            }
        )

        videoStudioController = VideoStudioController(
            activity = this,
            binding = binding.layoutVideoStudio,
            playerController = videoPlayerController,
            safManager = safDestinationManager,
            scope = lifecycleScope,
            onPickVideoRequest = { vidStudioPickerLauncher.launch("video/*") },
            onAddMoreVideoRequest = { vidStudioAddMoreLauncher.launch("video/*") },
            onPickFolderRequest = { vidFolderPickerLauncher.launch(null) },
            onExportFileRequest = { file ->
                pendingVidStudioExportFile = file
                val mime = safStorageManager.getExportMimeType(file)
                vidStudioExportLauncher.launch(file.name to mime)
            },
            onShareFileRequest = { file, mime ->
                shareStudioFile(file, mime)
            },
            onNavigateHome = {
                navigationController.navigateBackFromWorkspace()
            }
        )

        imageStudioController.initWorkspace()
        videoStudioController.initWorkspace()

        imageQualityController = ImageQualityController(
            activity = this,
            binding = binding.layoutImageQuality,
            scope = lifecycleScope,
            onPickImageRequest = { qualityPickerLauncher.launch("image/*") },
            onShareReportRequest = { reportText ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "VeilFrame Image Quality Report")
                    putExtra(Intent.EXTRA_TEXT, reportText)
                }
                startActivity(Intent.createChooser(sendIntent, "Share Quality Report"))
            },
            onNavigateBack = {
                navigationController.navigateBackFromWorkspace()
            }
        ).apply { init() }
    }

    private fun initImageUpscaler(upscalerBinding: LayoutImageUpscalerBinding) {
        imageUpscalerController = ImageUpscalerController(
            activity = this,
            upscalerBinding = upscalerBinding,
            onBackRequested = {
                navigationController.navigateBackFromWorkspace()
            },
            onPickImageRequested = { openImageUpscalerPicker() },
            onLog = { msg -> consoleLogController.log(msg) }
        ).apply {
            onImportCustomModelRequested = {
                modelPickerLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream"
                    )
                )
            }
        }
        imageUpscalerController.init()
    }

    private fun initDocScanner(docBinding: LayoutDocumentScannerBinding) {
        docScannerController = DocumentScannerController(
            activity = this,
            binding = docBinding,
            safStorageManager = safStorageManager,
            scope = lifecycleScope,
            onTakePhotoRequest = { docScannerController.openCameraViewfinder() },
            onChoosePhotosRequest = { docScannerPhotosLauncher.launch("image/*") },
            onExportFileRequest = { file, mime ->
                pendingDocScannerExportFile = file
                docScannerExportLauncher.launch(file.name to mime)
            },
            onNavigateBack = {
                navigationController.navigateBackFromWorkspace()
            },
            onRequestCameraPermission = {
                docScannerCameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        ).apply { init() }
    }

    private fun initMotionLab(motionBinding: LayoutMotionLabBinding) {
        motionLabController = MotionLabController(
            activity = this,
            binding = motionBinding,
            scope = lifecycleScope,
            onPickVideoRequest = { motionLabPickerLauncher.launch(arrayOf("video/*")) },
            onNavigateBack = {
                navigationController.navigateBackFromWorkspace()
            }
        )
    }

    private fun initBackgroundRemover(bgBinding: LayoutBackgroundRemoverBinding) {
        backgroundRemoverController = BackgroundRemoverController(
            activity = this,
            binding = bgBinding,
            scope = lifecycleScope,
            onPickImageRequest = { bgRemoverPickerLauncher.launch("image/*") },
            onExportPngRequest = { file ->
                pendingBgRemoverExportFile = file
                val mime = safStorageManager.getExportMimeType(file)
                bgRemoverExportLauncher.launch(file.name to mime)
            },
            onNavigateBack = {
                navigationController.navigateBackFromWorkspace()
            }
        ).apply { init() }
    }

    private fun initProvenance(provBinding: LayoutProvenanceBinding) {
        provenanceController = ProvenanceController(
            activity = this,
            binding = provBinding,
            safStorageManager = safStorageManager,
            scope = lifecycleScope,
            onPickFileRequest = { provenancePickerLauncher.launch(arrayOf("*/*")) },
            onNavigateBack = {
                navigationController.navigateBackFromWorkspace()
            }
        ).apply { init() }
    }

    private fun initPdfSignature(sigBinding: LayoutPdfSignatureBinding) {
        pdfSignatureController = PdfSignatureController(
            activity = this,
            binding = sigBinding,
            scope = lifecycleScope,
            onPickPdfRequest = { pdfSignaturePickerLauncher.launch(arrayOf("application/pdf")) },
            onSavePdfRequest = { bytes, fileName ->
                pendingPdfSignatureSaveBytes = bytes
                pdfSignatureSaveLauncher.launch(fileName to "application/pdf")
            },
            onNavigateBack = {
                navigationController.navigateBackFromWorkspace()
            },
            onImportCaCertRequest = {
                pdfCaCertPickerLauncher.launch(arrayOf("application/x-x509-ca-cert", "application/pkix-cert", "*/*"))
            },
            onOpenPdfStudio = {
                openPdfStudio()
            }
        ).apply { init() }
    }

    private fun initPdfUnlock(unlockBinding: LayoutPdfUnlockBinding) {
        pdfUnlockController = PdfUnlockController(
            activity = this,
            binding = unlockBinding,
            scope = lifecycleScope,
            onPickPdfRequest = { pdfUnlockPickerLauncher.launch(arrayOf("application/pdf")) },
            onSavePdfRequest = { bytes, fileName ->
                pendingPdfUnlockSaveBytes = bytes
                pdfUnlockSaveLauncher.launch(fileName to "application/pdf")
            },
            onNavigateBack = {
                navigationController.navigateBackFromWorkspace()
            },
            onOpenPdfStudio = {
                openPdfStudio()
            }
        ).apply { init() }
    }

    private fun setupListeners() {
        // Theme & Appearance actions
        binding.btnSettings.setOnClickListener { openSettingsOverlay() }
        binding.btnToolSettings.setOnClickListener { openSettingsOverlay() }

        // Material 3 Expressive Motion: tactile touch bounce on all primary interactive views
        val interactiveBounceViews = listOf(
            binding.btnSettings,
            binding.btnToolSettings,
            binding.cardHomeFileIntake,
            binding.btnHomeIntakePhoto,
            binding.btnHomeIntakeVideo,
            binding.btnHomeIntakeDoc,
            binding.btnHomeIntakeBrowse,
            binding.btnSuggestEngine1,
            binding.btnSuggestEngine2,
            binding.btnSuggestEngine3,
            binding.cardRecent1,
            binding.cardRecent2,
            binding.cardRecent3,
            binding.cardRecent4,
            binding.btnSeeAllTools,
            binding.cardPrivacyPulse,
            binding.btnPickFolder,
            binding.btnPickFile,
            binding.btnClearTarget,
            binding.btnChangeTarget,
            binding.btnToggleConsole,
            binding.btnExecute,
            binding.btnExportResult,
            binding.btnShareResult,
            binding.btnResultSave,
            binding.btnResultShare,
            binding.btnResultPreview
        )
        interactiveBounceViews.forEach { ExpressiveMotion.applyTouchBounce(it) }

        // Home Action Dashboard - Universal File Intake Pickers
        binding.btnHomeIntakePhoto.setOnClickListener { homePhotoPickerLauncher.launch("image/*") }
        binding.btnHomeIntakeVideo.setOnClickListener { homeVideoPickerLauncher.launch("video/*") }
        binding.btnHomeIntakeDoc.setOnClickListener { homeDocPickerLauncher.launch(arrayOf("application/pdf", "text/*", "application/*")) }
        binding.btnHomeIntakeBrowse.setOnClickListener { homeFilePickerLauncher.launch(arrayOf("*/*")) }
        binding.btnHomeIntakeClear.setOnClickListener { clearHomeFileIntake() }

        // Home Action Dashboard - Pinned / Recent Engines (2x2 Grid)
        binding.cardRecent1.setOnClickListener { openTool(ToolMode.VIDEO_CLEANER) }
        binding.cardRecent2.setOnClickListener { openTool(ToolMode.IMAGE_CLEANER) }
        binding.cardRecent3.setOnClickListener { openImageUpscaler() }
        binding.cardRecent4.setOnClickListener { openTool(ToolMode.AI_BUNDLE) }

        // Jump to Tools Catalogue tab
        binding.btnSeeAllTools.setOnClickListener {
            binding.bottomNavigation.selectedItemId = R.id.nav_tools
        }

        // Target Pickers inside Tool
        binding.btnPickFolder.setOnClickListener { folderPickerLauncher.launch(null) }
        binding.btnPickFile.setOnClickListener { openFilePickerForCurrentTool() }
        binding.btnClearTarget.setOnClickListener { toolSessionManager.clearSelectedTarget(logMessage = true) }
        binding.btnChangeTarget.setOnClickListener { openFilePickerForCurrentTool() }

        // Execution Monitor Console Toggle
        binding.btnToggleConsole.setOnClickListener {
            isConsoleExpanded = !isConsoleExpanded
            binding.layoutConsoleBody.visibility = if (isConsoleExpanded) View.VISIBLE else View.GONE
            binding.btnToggleConsole.setIconResource(
                if (isConsoleExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }

        // Result Card Direct Actions
        binding.btnResultSave.setOnClickListener { launchExportCurrentArtifact() }
        binding.btnResultShare.setOnClickListener { toolExecutionController.shareLastResult() }
        binding.btnResultPreview.setOnClickListener {
            val file = toolSessionManager.currentState.lastGeneratedFile
            if (file != null && file.exists()) {
                openMarkdownViewer(file)
            }
        }

        // Primary Execution Action
        binding.btnExecute.setOnClickListener { toolExecutionController.executeSelectedMode() }

        // Multi-Format Export Action (Save As to device storage)
        binding.btnExportResult.setOnClickListener { launchExportCurrentArtifact() }
        binding.btnShareResult.setOnClickListener { toolExecutionController.shareLastResult() }
    }

    internal fun handleHomeFileIntake(uri: Uri) {
        homeFileIntakeCoordinator.handleHomeFileIntake(uri)
    }





    internal fun clearHomeFileIntake() {
        homeFileIntakeCoordinator.clearHomeFileIntake()
    }


    private fun showImageQualitySourcePicker() {
        SharedSourcePickerSheet.newInstance(
            title = "Quality Inspector Source",
            mediaType = SharedSourcePickerSheet.MediaType.IMAGE_ONLY,
            isCameraEnabled = false,
            cameraDisabledReason = "Select an existing image from Photos or Files to inspect quality"
        ) { _ ->
            openImageQuality()
            qualityPickerLauncher.launch("image/*")
        }.show(supportFragmentManager, SharedSourcePickerSheet.TAG)
    }

    /**
     * Hides [cardCleanerActionDock] when the user scrolls to within ~48dp of the bottom of
     * [scrollTool], and shows it again when scrolling back toward the top.
     * Uses a simple alpha + translationY animation consistent with the rest of the Motion system.
     */
    private fun setupToolScrollDockBehavior() {
        val thresholdPx = com.veilframe.app.ui.motion.MotionSpec.DOCK_THRESHOLD_DP * resources.displayMetrics.density
        var lastScrollY = 0

        binding.scrollTool.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { sv, _, scrollY, _, _ ->
                if (navigationController.currentScreen != ScreenState.TOOL) return@OnScrollChangeListener
                val diff = kotlin.math.abs(scrollY - lastScrollY)
                if (diff < com.veilframe.app.ui.motion.MotionSpec.SCROLL_DEADZONE_PX) return@OnScrollChangeListener
                lastScrollY = scrollY

                val child = sv.getChildAt(0) ?: return@OnScrollChangeListener
                val scrollBottom = scrollY + sv.height
                val totalHeight = child.height
                val distanceToBottom = totalHeight - scrollBottom

                val progress = (1.0f - (distanceToBottom / thresholdPx)).coerceIn(0.0f, 1.0f)
                val targetDocked = if (binding.cardResultSummary.visibility == android.view.View.VISIBLE) {
                    binding.btnResultSave
                } else {
                    binding.cardPrivacySummary
                }
                com.veilframe.app.ui.motion.ExpressiveMotion.updateDockProgress(
                    floatingDock = binding.cardCleanerActionDock,
                    dockedActions = targetDocked,
                    progress = progress
                )
            }
        )
    }

    private fun launchExportCurrentArtifact() {
        val file = toolSessionManager.currentState.lastGeneratedFile
        if (file != null && file.exists()) {
            val mimeType = safStorageManager.getExportMimeType(file)
            exportDocumentLauncher.launch(file.name to mimeType)
        } else {
            Toast.makeText(this, "No output artifact to export", Toast.LENGTH_SHORT).show()
        }
    }

    private var lastNavClickTime = 0L
    private fun canNavigate(): Boolean {
        if (::navigationController.isInitialized && navigationController.isRestoringState) {
            return true
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNavClickTime < 500L) {
            return false
        }
        lastNavClickTime = now
        return true
    }

    private fun clearBackTimer() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
    }

    internal fun openTool(toolMode: ToolMode) {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.configureToolUI(toolMode)
        toolSessionManager.restoreToolState(toolSessionManager.currentState)
        navigationController.showToolScreen()
        consoleLogController.log("[UI] Opened ${toolSessionManager.getToolTitle(toolMode)} workspace.")
    }

    internal fun openImageStudio() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        com.veilframe.app.tools.ToolUsageTracker.recordToolLaunch(this, com.veilframe.app.tools.ToolUsageTracker.TrackedTool.IMAGE_STUDIO)
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.currentToolMode = ToolMode.IMAGE_COMPRESSOR
        navigationController.showImageStudioScreen()
        consoleLogController.log("[UI] Opened Image Studio workspace.")
    }

    internal fun openVideoStudio() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        com.veilframe.app.tools.ToolUsageTracker.recordToolLaunch(this, com.veilframe.app.tools.ToolUsageTracker.TrackedTool.VIDEO_STUDIO)
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.currentToolMode = ToolMode.VIDEO_COMPRESSOR
        navigationController.showVideoStudioScreen()
        consoleLogController.log("[UI] Opened Video Studio workspace.")
    }

    fun openImageUpscaler() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        com.veilframe.app.tools.ToolUsageTracker.recordToolLaunch(this, com.veilframe.app.tools.ToolUsageTracker.TrackedTool.IMAGE_UPSCALER)
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.currentToolMode = ToolMode.IMAGE_UPSCALER
        navigationController.showImageUpscalerScreen()
        consoleLogController.log("[UI] Opened Image Upscaler workspace.")
    }

    fun openQrStudio() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        com.veilframe.app.tools.ToolUsageTracker.recordToolLaunch(this, com.veilframe.app.tools.ToolUsageTracker.TrackedTool.QR_STUDIO)
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showQrStudioScreen()
        if (supportFragmentManager.findFragmentById(R.id.fragmentQrStudio) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentQrStudio, com.veilframe.app.qr.ui.QrStudioFragment())
                .commit()
        }
        consoleLogController.log("[UI] Opened QR Studio workspace.")
    }

    fun openMarkdownViewer(uri: Uri, title: String? = null) {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showMarkdownViewerScreen()
        markdownViewerController.loadMarkdown(uri, title)
        consoleLogController.log("[MARKDOWN] Opened viewer: ${title ?: uri.lastPathSegment}")
    }

    fun openMarkdownViewer(file: File, title: String? = null) {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showMarkdownViewerScreen()
        markdownViewerController.loadMarkdown(file, title)
        consoleLogController.log("[MARKDOWN] Opened viewer: ${file.name}")
    }

    fun openDocumentScanner() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        com.veilframe.app.tools.ToolUsageTracker.recordToolLaunch(this, com.veilframe.app.tools.ToolUsageTracker.TrackedTool.DOCUMENT_SCANNER)
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showDocumentScannerScreen()
        consoleLogController.log("[UI] Opened Document Scanner workspace.")
    }

    fun openBackgroundRemover() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showBackgroundRemoverScreen()
        consoleLogController.log("[UI] Opened Background Remover workspace.")
    }

    fun openMotionLab() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showMotionLabScreen()
        consoleLogController.log("[UI] Opened Motion Lab workspace.")
    }

    fun openImageQuality() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showImageQualityScreen()
        consoleLogController.log("[UI] Opened Image Quality Forensics workspace.")
    }

    fun openProvenance() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        if (::provenanceController.isInitialized) {
            provenanceController.reset()
        }
        navigationController.showProvenanceScreen()
        consoleLogController.log("[UI] Opened Provenance & Verify workspace.")
    }

    fun openPdfSignature() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        workspaceHost.getOrInflatePdfSignature()
        navigationController.showPdfSignatureScreen()
        consoleLogController.log("[UI] Opened PDF Signature Validator workspace.")
    }

    fun openPdfUnlock() {
        if (!canNavigate()) return
        clearBackTimer()
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        workspaceHost.getOrInflatePdfUnlock()
        navigationController.showPdfUnlockScreen()
        consoleLogController.log("[UI] Opened PDF Unlock workspace.")
    }

    fun openPdfStudio() {
        pdfSuiteCoordinator.openPdfStudio()
    }

    fun openPdfWatermark() {
        pdfSuiteCoordinator.openWatermark()
    }

    fun openPdfExtractText() {
        pdfSuiteCoordinator.openExtractText()
    }

    fun openPdfExtractPages() {
        pdfSuiteCoordinator.openExtractPages()
    }

    fun openPdfFlatten() {
        pdfSuiteCoordinator.openFlatten()
    }

    fun openPdfImposition() {
        pdfSuiteCoordinator.openImposition()
    }

    fun openPdfQuickTool(mode: PdfQuickToolController.QuickMode) {
        pdfSuiteCoordinator.openQuickTool(mode)
    }

    private fun showDiagnosticsDialog() {
        MainDialogHelper.showDiagnosticsDialog(this, consoleLogController)
    }

    private fun showFullConsoleLogDialog() {
        MainDialogHelper.showFullConsoleLogDialog(this, consoleLogController)
    }

    private fun refreshLibrary() {
        libraryCoordinator.refresh()
    }

    private fun scheduleBackClearWork() {
        clearBackTimer()
        // If a task is executing in background via foreground service, avoid tearing down active processing
        val isVideoProcessing = ::videoStudioController.isInitialized && videoStudioController.isProcessing
        val isUpscalerProcessing = ::imageUpscalerController.isInitialized && imageUpscalerController.isProcessing
        val isDocScannerProcessing = ::docScannerController.isInitialized && docScannerController.isBusyProcessing
        val isBgRemoverProcessing = ::backgroundRemoverController.isInitialized && backgroundRemoverController.isProcessing
        val isImageProcessing = ::imageStudioController.isInitialized && imageStudioController.isProcessing
        val isMotionLabProcessing = ::motionLabController.isInitialized && motionLabController.isProcessing
        val isToolExecuting = ::toolExecutionController.isInitialized && toolExecutionController.isExecuting
        val isPdfSigProcessing = ::pdfSignatureController.isInitialized && pdfSignatureController.isProcessing
        val isPdfUnlockProcessing = ::pdfUnlockController.isInitialized && pdfUnlockController.isProcessing
        val isPdfSuiteProcessing = ::pdfSuiteCoordinator.isInitialized && pdfSuiteCoordinator.hasActiveProcessing()

        if (::videoStudioController.isInitialized && !isVideoProcessing) videoStudioController.clear()
        if (::videoPlayerController.isInitialized && !isVideoProcessing) videoPlayerController.clearMedia()
        if (::imageStudioController.isInitialized && !isImageProcessing) imageStudioController.clear()
        if (::imageUpscalerController.isInitialized && !isUpscalerProcessing) imageUpscalerController.release()
        if (::docScannerController.isInitialized && !isDocScannerProcessing) docScannerController.release()
        if (::backgroundRemoverController.isInitialized && !isBgRemoverProcessing) backgroundRemoverController.cleanup()
        if (::motionLabController.isInitialized && !isMotionLabProcessing) motionLabController.reset()
        if (::provenanceController.isInitialized) provenanceController.reset()
        if (::markdownViewerController.isInitialized) markdownViewerController.clear()
        if (::pdfSignatureController.isInitialized && !isPdfSigProcessing) pdfSignatureController.discardAndReleaseAllResources()
        if (::pdfUnlockController.isInitialized && !isPdfUnlockProcessing) pdfUnlockController.discardAndReleaseAllResources()
        if (::pdfSuiteCoordinator.isInitialized) {
            pdfSuiteCoordinator.discardInactiveResources()
        }
        if (!isUpscalerProcessing && !isDocScannerProcessing && !isBgRemoverProcessing && !isVideoProcessing && !isImageProcessing && !isMotionLabProcessing && !isToolExecuting && !isPdfSigProcessing && !isPdfUnlockProcessing && !isPdfSuiteProcessing) {
            workspaceHost.pause()
        }
        if (!isToolExecuting) {
            toolSessionManager.clearCurrentTool()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                File(cacheDir, "temp_drafts").deleteRecursively()
                File(cacheDir, "temp_renders").deleteRecursively()
            } catch (_: Exception) {}
        }
        consoleLogController.log("[SESSION] Cleared inactive media studio resources on Home.")
    }

    private fun openFilePickerForCurrentTool() {
        when (toolSessionManager.currentToolMode) {
            ToolMode.VIDEO_CLEANER -> {
                if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
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
                if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
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

    internal fun handleSingleFileSelected(uri: Uri) {
        val state = toolSessionManager.currentState
        state.selectedUri = uri
        state.isFolderSelected = false
        state.selectedPathDisplay = safStorageManager.getDisplayName(uri)
        state.targetFileCount = 1
        state.targetTotalBytes = safStorageManager.queryFileSize(uri)

        val ext = state.selectedPathDisplay.substringAfterLast('.', "").lowercase(Locale.ROOT)
        when (toolSessionManager.currentToolMode) {
            ToolMode.VIDEO_CLEANER -> {
                when (ext) {
                    "mp4" -> toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 1)
                    "mkv" -> toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 2)
                    "webm" -> toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 3)
                    else -> toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 0)
                }
            }
            ToolMode.IMAGE_CLEANER -> {
                when (ext) {
                    "jpg", "jpeg" -> toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 1)
                    "png" -> toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 2)
                    "webp" -> toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 3)
                    else -> toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 0)
                }
            }
            else -> {}
        }
        state.formatOptionIndex = toolSessionManager.getSelectedFormatIndex()

        toolSessionManager.updateTargetCardUI(state)
        toolSessionManager.updatePrivacySummaryUI()
        toolSessionManager.updatePrimaryActionDock(state)
        consoleLogController.log("[TARGET] Mounted file: ${state.selectedPathDisplay} (${safStorageManager.formatBytes(state.targetTotalBytes)})")
    }

    private fun handleFolderSelected(uri: Uri) {
        val state = toolSessionManager.currentState
        state.selectedUri = uri
        state.isFolderSelected = true
        state.selectedPathDisplay = safStorageManager.getDisplayName(uri)

        if (toolSessionManager.currentToolMode == ToolMode.VIDEO_CLEANER || toolSessionManager.currentToolMode == ToolMode.IMAGE_CLEANER) {
            toolSessionManager.selectChipByIndex(binding.chipGroupFormat, 0)
            state.formatOptionIndex = 0
        }

        toolSessionManager.updateTargetCardUI(state)
        binding.tvTargetDetails.text = "Indexing directory contents..."
        binding.tvTargetDetails.setTextColor(getColor(R.color.vf_accent_amber))

        lifecycleScope.launch(Dispatchers.IO) {
            val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri)
            var count = 0
            var totalBytes = 0L

            fun inspectDoc(doc: DocumentFile) {
                if (doc.isDirectory) {
                    doc.listFiles().forEach { inspectDoc(it) }
                } else if (doc.isFile) {
                    count++
                    totalBytes += doc.length()
                }
            }

            rootDoc?.listFiles()?.forEach { inspectDoc(it) }

            state.targetFileCount = count
            state.targetTotalBytes = totalBytes

            withContext(Dispatchers.Main) {
                binding.tvTargetDetails.text = "$count files • ${safStorageManager.formatBytes(totalBytes)} • Ready"
                binding.tvTargetDetails.setTextColor(getColor(R.color.vf_accent_green))
                toolSessionManager.updatePrivacySummaryUI()
                toolSessionManager.updatePrimaryActionDock(state)
                consoleLogController.log("[TARGET] Mounted directory: ${state.selectedPathDisplay} ($count files, ${safStorageManager.formatBytes(totalBytes)})")
            }
        }
    }

    private fun pauseVideoPlayback() {
        if (::videoPlayerController.isInitialized) {
            videoPlayerController.pause()
        }
    }

    private fun shareStudioFile(file: File, mimeType: String, explicitContentUri: Uri? = null) {
        try {
            val uri = explicitContentUri ?: FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(contentResolver, "Share Media Artifact", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Media Artifact").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }


    internal fun openSettingsOverlay(animate: Boolean = true, restoreScrollY: Int? = null) {
        settingsOverlayCoordinator.open(animate, restoreScrollY)
    }

    private fun seamlessRecreateSettings(restoreScrollY: Int? = null) {
        val scrollY = restoreScrollY ?: binding.layoutSettingsPanel.scrollSettings.scrollY
        pendingSeamlessSettingsRestore = true
        pendingSeamlessSettingsScrollY = scrollY
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        recreate()
    }

    internal fun closeSettingsOverlay(animate: Boolean = true) {
        settingsOverlayCoordinator.close(animate)
    }

    private fun openWebUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMarkdownStudioEntryDialog() {
        MainDialogHelper.showMarkdownStudioEntryDialog(
            activity = this,
            onOpenExisting = {
                markdownPickerLauncher.launch(arrayOf("text/markdown", "text/x-markdown"))
            },
            onCreateNew = {
                navigationController.showMarkdownViewerScreen()
                markdownViewerController.createNewDocument()
            }
        )
    }

    private fun showAboutDialog() {
        MainDialogHelper.showAboutDialog(this)
    }

    companion object {
        private const val KEY_SCREEN_STATE = "key_screen_state"
        private const val KEY_PREVIOUS_SCREEN_STATE = "key_previous_screen_state"
        private const val KEY_TOOL_MODE = "key_tool_mode"
        private const val KEY_SELECTED_FILE_URI = "key_selected_file_uri"
        private const val KEY_IS_FOLDER_SELECTED = "key_is_folder_selected"
        private const val KEY_STUDIO_IMAGE_URIS = "key_studio_image_uris"
        private const val KEY_STUDIO_VIDEO_URIS = "key_studio_video_uris"
        private const val KEY_UPSCALER_URI = "key_upscaler_uri"
        private const val KEY_MOTION_LAB_URI = "key_motion_lab_uri"
        private const val KEY_DOC_SCANNER_SESSION_ID = "key_doc_scanner_session_id"
        private const val KEY_BG_REMOVER_URI = "key_bg_remover_uri"
        private const val KEY_IMAGE_QUALITY_URI = "key_image_quality_uri"
        private const val KEY_PROVENANCE_URI = "key_provenance_uri"
        private const val KEY_MARKDOWN_URI = "key_markdown_uri"
        private const val KEY_MARKDOWN_FILE_PATH = "key_markdown_file_path"
        private const val KEY_SETTINGS_OPEN = "key_settings_open"
        private const val KEY_SETTINGS_SCROLL_Y = "key_settings_scroll_y"
        private var pendingSeamlessSettingsRestore: Boolean = false
        private var pendingSeamlessSettingsScrollY: Int = 0
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SETTINGS_OPEN, binding.containerSettings.visibility == View.VISIBLE || pendingSeamlessSettingsRestore)
        outState.putInt(KEY_SETTINGS_SCROLL_Y, if (pendingSeamlessSettingsRestore) pendingSeamlessSettingsScrollY else binding.layoutSettingsPanel.scrollSettings.scrollY)
        outState.putString(KEY_SCREEN_STATE, navigationController.currentScreen.name)
        outState.putString(KEY_PREVIOUS_SCREEN_STATE, navigationController.previousScreen.name)
        outState.putString(KEY_TOOL_MODE, toolSessionManager.currentToolMode.name)

        // Save active Tool session state
        val toolState = toolSessionManager.currentState
        toolState.selectedUri?.let { uri ->
            outState.putString(KEY_SELECTED_FILE_URI, uri.toString())
            outState.putBoolean(KEY_IS_FOLDER_SELECTED, toolState.isFolderSelected)
        }

        // Save lightweight media references
        if (::imageStudioController.isInitialized && imageStudioController.selectedMediaList.isNotEmpty()) {
            val uris = ArrayList(imageStudioController.selectedMediaList.map { it.uri.toString() })
            outState.putStringArrayList(KEY_STUDIO_IMAGE_URIS, uris)
        }
        if (::videoStudioController.isInitialized && videoStudioController.selectedMediaList.isNotEmpty()) {
            val uris = ArrayList(videoStudioController.selectedMediaList.map { it.uri.toString() })
            outState.putStringArrayList(KEY_STUDIO_VIDEO_URIS, uris)
        }
        if (::imageUpscalerController.isInitialized) {
            imageUpscalerController.selectedUri?.let {
                outState.putString(KEY_UPSCALER_URI, it.toString())
            }
        }
        if (::motionLabController.isInitialized) {
            motionLabController.currentSourceUri?.let {
                outState.putString(KEY_MOTION_LAB_URI, it.toString())
            }
        }
        if (::docScannerController.isInitialized && !docScannerController.session.isEmpty) {
            docScannerController.session.saveToDisk(this)
            outState.putString(KEY_DOC_SCANNER_SESSION_ID, docScannerController.session.sessionId)
        }
        if (::backgroundRemoverController.isInitialized) {
            backgroundRemoverController.currentSourceUri?.let {
                outState.putString(KEY_BG_REMOVER_URI, it.toString())
            }
        }
        if (::imageQualityController.isInitialized) {
            imageQualityController.currentSourceUri?.let {
                outState.putString(KEY_IMAGE_QUALITY_URI, it.toString())
            }
        }
        if (::provenanceController.isInitialized) {
            provenanceController.currentSourceUri?.let {
                outState.putString(KEY_PROVENANCE_URI, it.toString())
            }
        }
        if (::markdownViewerController.isInitialized) {
            markdownViewerController.currentDocumentUri?.let {
                outState.putString(KEY_MARKDOWN_URI, it.toString())
            }
            markdownViewerController.currentDocumentFile?.let {
                outState.putString(KEY_MARKDOWN_FILE_PATH, it.absolutePath)
            }
        }
    }

    private fun restoreInstanceState(savedInstanceState: Bundle) {
        navigationController.isRestoringState = true
        try {
            val screenStateName = savedInstanceState.getString(KEY_SCREEN_STATE)
            val targetScreen = screenStateName?.let { runCatching { ScreenState.valueOf(it) }.getOrNull() } ?: ScreenState.HOME
            val toolModeName = savedInstanceState.getString(KEY_TOOL_MODE)
            val toolMode = toolModeName?.let { runCatching { ToolMode.valueOf(it) }.getOrNull() } ?: ToolMode.VIDEO_CLEANER
            toolSessionManager.currentToolMode = toolMode

            // Restore active Tool URI if present
            val toolUriStr = savedInstanceState.getString(KEY_SELECTED_FILE_URI)
            val isFolder = savedInstanceState.getBoolean(KEY_IS_FOLDER_SELECTED, false)
            if (toolUriStr != null) {
                val uri = Uri.parse(toolUriStr)
                if (isFolder) {
                    handleFolderSelected(uri)
                } else {
                    handleSingleFileSelected(uri)
                }
            }

            // Restore Media Studio items if present
            savedInstanceState.getStringArrayList(KEY_STUDIO_IMAGE_URIS)?.let { uriStrings ->
                val uris = uriStrings.map { Uri.parse(it) }
                if (uris.isNotEmpty() && ::imageStudioController.isInitialized) {
                    imageStudioController.handleImagesSelected(uris)
                }
            }
            savedInstanceState.getStringArrayList(KEY_STUDIO_VIDEO_URIS)?.let { uriStrings ->
                val uris = uriStrings.map { Uri.parse(it) }
                if (uris.isNotEmpty() && ::videoStudioController.isInitialized) {
                    videoStudioController.handleVideosSelected(uris)
                }
            }
            savedInstanceState.getString(KEY_UPSCALER_URI)?.let { uriStr ->
                if (targetScreen == ScreenState.IMAGE_UPSCALER) {
                    workspaceHost.getOrInflateImageUpscaler()
                }
                if (::imageUpscalerController.isInitialized) {
                    imageUpscalerController.handleImageSelected(Uri.parse(uriStr))
                }
            }
            savedInstanceState.getString(KEY_MOTION_LAB_URI)?.let { uriStr ->
                if (targetScreen == ScreenState.MOTION_LAB) {
                    workspaceHost.getOrInflateMotionLab()
                }
                if (::motionLabController.isInitialized) {
                    motionLabController.setSource(Uri.parse(uriStr))
                }
            }
            savedInstanceState.getString(KEY_DOC_SCANNER_SESSION_ID)?.let { sessionId ->
                if (targetScreen in listOf(
                        ScreenState.DOCUMENT_SCANNER_ENTRY,
                        ScreenState.DOCUMENT_SCANNER_CAMERA,
                        ScreenState.DOCUMENT_SCANNER_PAGES,
                        ScreenState.DOCUMENT_SCANNER_EDITOR,
                        ScreenState.DOCUMENT_SCANNER_EXPORT
                    )
                ) {
                    workspaceHost.getOrInflateDocScanner()
                }
                if (::docScannerController.isInitialized) {
                    docScannerController.resumeSession(sessionId)
                }
            }
            savedInstanceState.getString(KEY_BG_REMOVER_URI)?.let { uriStr ->
                if (targetScreen == ScreenState.BACKGROUND_REMOVER) {
                    workspaceHost.getOrInflateBackgroundRemover()
                }
                if (::backgroundRemoverController.isInitialized) {
                    backgroundRemoverController.handleImageSelected(Uri.parse(uriStr))
                }
            }
            savedInstanceState.getString(KEY_IMAGE_QUALITY_URI)?.let { uriStr ->
                if (::imageQualityController.isInitialized) {
                    imageQualityController.handleImageSelected(Uri.parse(uriStr))
                }
            }
            savedInstanceState.getString(KEY_PROVENANCE_URI)?.let { uriStr ->
                if (targetScreen == ScreenState.PROVENANCE) {
                    workspaceHost.getOrInflateProvenance()
                }
                if (::provenanceController.isInitialized) {
                    provenanceController.handleFileSelected(Uri.parse(uriStr))
                }
            }

            // Navigate back to the active screen
            when (targetScreen) {
                ScreenState.HOME -> navigationController.showHomeScreen()
                ScreenState.TOOLS -> navigationController.showToolsScreen()
                ScreenState.LIBRARY -> navigationController.showLibraryScreen()
                ScreenState.TOOL -> {
                    toolSessionManager.configureToolUI(toolMode)
                    toolSessionManager.restoreToolState(toolSessionManager.currentState)
                    navigationController.showToolScreen()
                }
                ScreenState.IMAGE_STUDIO -> openImageStudio()
                ScreenState.VIDEO_STUDIO -> openVideoStudio()
                ScreenState.QR_STUDIO -> openQrStudio()
                ScreenState.IMAGE_UPSCALER -> openImageUpscaler()
                ScreenState.DOCUMENT_SCANNER_ENTRY,
                ScreenState.DOCUMENT_SCANNER_CAMERA,
                ScreenState.DOCUMENT_SCANNER_PAGES,
                ScreenState.DOCUMENT_SCANNER_EDITOR,
                ScreenState.DOCUMENT_SCANNER_EXPORT -> openDocumentScanner()
                ScreenState.BACKGROUND_REMOVER -> openBackgroundRemover()
                ScreenState.IMAGE_QUALITY -> openImageQuality()
                ScreenState.MOTION_LAB -> openMotionLab()
                ScreenState.PROVENANCE -> openProvenance()
                ScreenState.PDF_SIGNATURE -> openPdfSignature()
                ScreenState.PDF_UNLOCK -> openPdfUnlock()
                ScreenState.PDF_STUDIO -> openPdfStudio()
                ScreenState.PDF_WATERMARK -> openPdfWatermark()
                ScreenState.PDF_EXTRACT_TEXT -> openPdfExtractText()
                ScreenState.PDF_EXTRACT_PAGES -> openPdfExtractPages()
                ScreenState.PDF_FLATTEN -> openPdfFlatten()
                ScreenState.PDF_IMPOSITION -> openPdfImposition()
                ScreenState.PDF_QUICK_TOOL -> openPdfQuickTool(PdfQuickToolController.QuickMode.MERGE)
                ScreenState.MARKDOWN_VIEWER -> {
                    val mdUriStr = savedInstanceState.getString(KEY_MARKDOWN_URI)
                    val mdFilePath = savedInstanceState.getString(KEY_MARKDOWN_FILE_PATH)
                    if (mdUriStr != null) {
                        openMarkdownViewer(Uri.parse(mdUriStr))
                    } else if (mdFilePath != null) {
                        openMarkdownViewer(File(mdFilePath))
                    } else {
                        navigationController.showMarkdownViewerScreen()
                    }
                }
            }

            if (savedInstanceState.getBoolean(KEY_SETTINGS_OPEN, false) || pendingSeamlessSettingsRestore) {
                val scrollY = if (pendingSeamlessSettingsRestore) pendingSeamlessSettingsScrollY else savedInstanceState.getInt(KEY_SETTINGS_SCROLL_Y, 0)
                pendingSeamlessSettingsRestore = false
                pendingSeamlessSettingsScrollY = 0
                openSettingsOverlay(animate = false, restoreScrollY = scrollY)
            }
        } finally {
            navigationController.isRestoringState = false
        }
    }

    fun getActiveToolWorkContract(screen: com.veilframe.app.navigation.ScreenState = navigationController.currentScreen): com.veilframe.app.navigation.ToolWorkStateContract? {
        return when (screen) {
            com.veilframe.app.navigation.ScreenState.VIDEO_STUDIO -> if (::videoStudioController.isInitialized) videoStudioController else null
            com.veilframe.app.navigation.ScreenState.IMAGE_STUDIO -> if (::imageStudioController.isInitialized) imageStudioController else null
            com.veilframe.app.navigation.ScreenState.IMAGE_UPSCALER -> if (::imageUpscalerController.isInitialized) imageUpscalerController else null
            com.veilframe.app.navigation.ScreenState.BACKGROUND_REMOVER -> if (::backgroundRemoverController.isInitialized) backgroundRemoverController else null
            com.veilframe.app.navigation.ScreenState.MOTION_LAB -> if (::motionLabController.isInitialized) motionLabController else null
            com.veilframe.app.navigation.ScreenState.QR_STUDIO -> supportFragmentManager.findFragmentById(R.id.fragmentQrStudio) as? com.veilframe.app.navigation.ToolWorkStateContract
            com.veilframe.app.navigation.ScreenState.DOCUMENT_SCANNER_ENTRY,
            com.veilframe.app.navigation.ScreenState.DOCUMENT_SCANNER_CAMERA,
            com.veilframe.app.navigation.ScreenState.DOCUMENT_SCANNER_PAGES,
            com.veilframe.app.navigation.ScreenState.DOCUMENT_SCANNER_EDITOR,
            com.veilframe.app.navigation.ScreenState.DOCUMENT_SCANNER_EXPORT -> if (::docScannerController.isInitialized) docScannerController else null
            com.veilframe.app.navigation.ScreenState.TOOL -> if (::toolExecutionController.isInitialized) toolExecutionController else null
            com.veilframe.app.navigation.ScreenState.PDF_SIGNATURE -> if (::pdfSignatureController.isInitialized) pdfSignatureController else null
            com.veilframe.app.navigation.ScreenState.PDF_UNLOCK -> if (::pdfUnlockController.isInitialized) pdfUnlockController else null
            com.veilframe.app.navigation.ScreenState.PDF_STUDIO,
            com.veilframe.app.navigation.ScreenState.PDF_WATERMARK,
            com.veilframe.app.navigation.ScreenState.PDF_EXTRACT_TEXT,
            com.veilframe.app.navigation.ScreenState.PDF_EXTRACT_PAGES,
            com.veilframe.app.navigation.ScreenState.PDF_FLATTEN,
            com.veilframe.app.navigation.ScreenState.PDF_IMPOSITION,
            com.veilframe.app.navigation.ScreenState.PDF_QUICK_TOOL -> if (::pdfSuiteCoordinator.isInitialized) pdfSuiteCoordinator.getActiveToolWorkContract(screen) else null
            else -> null
        }
    }

    fun hasActiveProcessing(): Boolean {
        if (getActiveToolWorkContract()?.isProcessing == true) return true
        if (::videoStudioController.isInitialized && videoStudioController.isProcessing) return true
        if (::imageStudioController.isInitialized && imageStudioController.isProcessing) return true
        if (::imageUpscalerController.isInitialized && imageUpscalerController.isProcessing) return true
        if (::backgroundRemoverController.isInitialized && backgroundRemoverController.isProcessing) return true
        if (::motionLabController.isInitialized && motionLabController.isProcessing) return true
        if (::docScannerController.isInitialized && docScannerController.isBusyProcessing) return true
        if (::toolExecutionController.isInitialized && toolExecutionController.isExecuting) return true
        if (::pdfSignatureController.isInitialized && pdfSignatureController.isProcessing) return true
        if (::pdfUnlockController.isInitialized && pdfUnlockController.isProcessing) return true
        if (::pdfSuiteCoordinator.isInitialized && pdfSuiteCoordinator.hasActiveProcessing()) return true
        if (VeilFrameProcessingService.activeJob?.state == com.veilframe.app.media.JobState.RUNNING) return true
        return false
    }

    fun cancelAllActiveProcessing() {
        getActiveToolWorkContract()?.discardAndReleaseAllResources()
        if (VeilFrameProcessingService.activeJob?.state == com.veilframe.app.media.JobState.RUNNING) {
            VeilFrameProcessingService.cancelProcessing(this)
        }
    }
}
