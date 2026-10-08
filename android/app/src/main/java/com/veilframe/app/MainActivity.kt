package com.veilframe.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
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
import com.veilframe.app.logging.ConsoleLogController
import com.veilframe.app.markdown.MarkdownViewerController
import com.veilframe.app.document.DocumentScannerController
import com.veilframe.app.media.BackgroundRemoverController
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
import com.veilframe.app.storage.CreateDocumentWithMime
import com.veilframe.app.storage.SafStorageManager
import com.veilframe.app.tools.JobState
import com.veilframe.app.tools.ToolExecutionController
import com.veilframe.app.tools.ToolMode
import com.veilframe.app.tools.ToolSessionManager
import com.veilframe.app.ui.motion.ExpressiveMotion
import com.veilframe.app.ui.motion.MorphDialogController
import com.veilframe.app.ui.motion.VeilFrameInteraction
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

    // Modular Sub-Controllers
    private lateinit var safStorageManager: SafStorageManager
    private lateinit var consoleLogController: ConsoleLogController
    private lateinit var navigationController: MainNavigationController
    private lateinit var toolSessionManager: ToolSessionManager
    private lateinit var toolExecutionController: ToolExecutionController
    private lateinit var appUpdateManager: AppUpdateManager

    // Dedicated Media Studio Controllers
    private lateinit var safDestinationManager: SafDestinationManager
    private lateinit var videoPlayerController: VideoPlayerController
    private lateinit var imageStudioController: ImageStudioController
    private lateinit var videoStudioController: VideoStudioController
    private lateinit var imageUpscalerController: ImageUpscalerController
    private lateinit var markdownViewerController: MarkdownViewerController
    private lateinit var docScannerController: DocumentScannerController
    private lateinit var backgroundRemoverController: BackgroundRemoverController
    private lateinit var imageQualityController: ImageQualityController
    private lateinit var provenanceController: ProvenanceController
    private var pendingExportFile: File? = null
    private var isConsoleExpanded: Boolean = false
    private var backClearTimerJob: Job? = null

    // Markdown file picker launcher
    private val markdownPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            safStorageManager.persistReadPermission(uri)
            openMarkdownViewer(uri)
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
                    contentResolver.openOutputStream(destUri, "wt")?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                    true
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
        val file = pendingExportFile
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
        val file = pendingExportFile
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

    private val imgUpscalerPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            imageUpscalerController.handleImageSelected(uri)
        }
    }

    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            imageUpscalerController.handleCustomModelImport(uri)
        }
    }

    // Dedicated Tools SAF and Photo Picker Activity Result Launchers
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
    private val docScannerCameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            docScannerController.handleCameraPhotoCaptured(bitmap)
        }
    }

    private val docScannerPhotosLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            docScannerController.handlePhotosImported(uris)
        }
    }

    private val docScannerExportLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingExportFile
        if (destUri != null && file != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "PDF saved to storage", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingExportFile = null
    }

    // Dedicated Background Remover Activity Result Launchers
    private val bgRemoverPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            backgroundRemoverController.handleImageSelected(uri)
        }
    }

    private val bgRemoverExportLauncher = registerForActivityResult(
        CreateDocumentWithMime()
    ) { destUri ->
        val file = pendingExportFile
        if (destUri != null && file != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                safStorageManager.copyFileToUri(file, destUri)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "PNG cutout saved to storage", Toast.LENGTH_LONG).show()
                }
            }
        }
        pendingExportFile = null
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
            provenanceController.handleFileSelected(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.veilframe.app.settings.ThemeSettingsManager.applyActivityTheme(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        com.veilframe.app.settings.ThemeSettingsManager.applyTypography(this)
        val prefs = getSharedPreferences("veilframe_prefs", Context.MODE_PRIVATE)

        // Restore cached changelog if available
        val cachedTag = prefs.getString("cached_changelog_tag", null)
        val cachedChangelog = prefs.getString("cached_changelog", null)
        if (!cachedTag.isNullOrEmpty() && !cachedChangelog.isNullOrEmpty()) {
            binding.tvWhatsNewHeader.text = "WHAT'S NEW IN $cachedTag"
            binding.tvWhatsNewContent.text = cachedChangelog
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
            insets
        }

        initSubControllers()
        initStudioWorkspaces()
        setupListeners()
        VeilFrameInteraction.bindWorkspace(binding.root)

        binding.tvVersionBadge.text = "v${BuildConfig.VERSION_NAME}"
        binding.tvUpdateStatus.text = "Installed: v${BuildConfig.VERSION_NAME} • Local Engine"

        consoleLogController.log("[SYS] Initialized VeilFrame ${BuildConfig.VERSION_NAME} Native Core Runtime")
        consoleLogController.log("[SYS] Native Media3, FFmpegKit 8.1.7, and AndroidX Privacy Engine active.")

        navigationController.showHomeScreen()

        // Asynchronous, non-blocking update check on launch
        appUpdateManager.checkForUpdates(isUserInitiated = false)

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
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

    override fun onDestroy() {
        super.onDestroy()
        appUpdateManager.onDestroy()
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

        navigationController = MainNavigationController(
            activity = this,
            binding = binding,
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
                if (navigationController.previousScreen == ScreenState.TOOL) {
                    navigationController.showToolScreen()
                } else {
                    navigationController.showHomeScreen()
                }
            },
            onOpenMarkdownFileRequest = {
                markdownPickerLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
            },
            onSaveAsMarkdownRequest = { suggestedName, content ->
                pendingMarkdownSaveContent = content
                markdownSaveAsLauncher.launch(suggestedName)
            }
        )
        markdownViewerController.init()
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
                pendingExportFile = file
                val mime = safStorageManager.getExportMimeType(file)
                imgStudioExportLauncher.launch(file.name to mime)
            },
            onShareFileRequest = { file, mime ->
                shareStudioFile(file, mime)
            },
            onNavigateHome = { navigationController.showHomeScreen() },
            onOpenModelManager = { imageUpscalerController.showModelManagerDialog() }
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
                pendingExportFile = file
                val mime = safStorageManager.getExportMimeType(file)
                vidStudioExportLauncher.launch(file.name to mime)
            },
            onShareFileRequest = { file, mime ->
                shareStudioFile(file, mime)
            },
            onNavigateHome = { navigationController.showHomeScreen() }
        )

        imageUpscalerController = ImageUpscalerController(
            activity = this,
            binding = binding,
            onBackRequested = { navigationController.showHomeScreen() },
            onPickImageRequested = { imgUpscalerPickerLauncher.launch("image/*") },
            onLog = { msg -> consoleLogController.log(msg) }
        ).apply {
            onImportCustomModelRequested = { modelPickerLauncher.launch("*/*") }
        }

        imageStudioController.initWorkspace()
        videoStudioController.initWorkspace()
        imageUpscalerController.init()

        docScannerController = DocumentScannerController(
            activity = this,
            binding = binding.layoutDocumentScanner,
            safStorageManager = safStorageManager,
            scope = lifecycleScope,
            onTakePhotoRequest = { docScannerController.openCameraViewfinder() },
            onChoosePhotosRequest = { docScannerPhotosLauncher.launch("image/*") },
            onExportFileRequest = { file, mime ->
                pendingExportFile = file
                docScannerExportLauncher.launch(file.name to mime)
            },
            onNavigateBack = {
                if (navigationController.previousScreen == ScreenState.TOOLS) {
                    navigationController.showToolsScreen()
                } else {
                    navigationController.showHomeScreen()
                }
            }
        ).apply { init() }

        backgroundRemoverController = BackgroundRemoverController(
            activity = this,
            binding = binding.layoutBackgroundRemover,
            scope = lifecycleScope,
            onPickImageRequest = { bgRemoverPickerLauncher.launch("image/*") },
            onExportPngRequest = { file ->
                pendingExportFile = file
                val mime = safStorageManager.getExportMimeType(file)
                bgRemoverExportLauncher.launch(file.name to mime)
            },
            onNavigateBack = {
                if (navigationController.previousScreen == ScreenState.TOOLS) {
                    navigationController.showToolsScreen()
                } else {
                    navigationController.showHomeScreen()
                }
            }
        ).apply { init() }

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
                if (navigationController.previousScreen == ScreenState.TOOLS) {
                    navigationController.showToolsScreen()
                } else {
                    navigationController.showHomeScreen()
                }
            }
        ).apply { init() }

        provenanceController = ProvenanceController(
            activity = this,
            binding = binding.layoutProvenance,
            safStorageManager = safStorageManager,
            scope = lifecycleScope,
            onPickFileRequest = { provenancePickerLauncher.launch(arrayOf("*/*")) },
            onNavigateBack = {
                if (navigationController.previousScreen == ScreenState.TOOLS) {
                    navigationController.showToolsScreen()
                } else {
                    navigationController.showHomeScreen()
                }
            }
        ).apply { init() }
    }

    private fun setupListeners() {
        // Theme & Appearance actions
        binding.btnToggleTheme.setOnClickListener { toggleTheme() }
        binding.btnToolToggleTheme.setOnClickListener { toggleTheme() }
        binding.btnSettings.setOnClickListener { openSettingsOverlay() }
        binding.btnToolSettings.setOnClickListener { openSettingsOverlay() }

        // Material 3 Expressive Motion: tactile touch bounce on all primary interactive views
        val interactiveBounceViews = listOf(
            binding.btnToggleTheme,
            binding.btnToolToggleTheme,
            binding.btnSettings,
            binding.btnToolSettings,
            binding.cardToolAi,
            binding.cardToolVideo,
            binding.cardToolImage,
            binding.cardToolFolder,
            binding.cardToolImageStudio,
            binding.cardToolVideoStudio,
            binding.cardToolImageUpscaler,
            binding.cardToolMarkdownViewer,
            binding.btnCheckUpdates,
            binding.btnRepairApp,
            binding.btnLinkGithub,
            binding.btnLinkDocs,
            binding.btnLinkChangelog,
            binding.btnLinkAbout,
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

        // Home Dashboard Tool Cards - DISTINCT CLEANER WORKFLOWS
        binding.cardToolAi.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardToolAi
            openTool(ToolMode.AI_BUNDLE)
        }

        binding.cardToolVideo.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardToolVideo
            openTool(ToolMode.VIDEO_CLEANER) // Dedicated Video Sanitizer
        }

        binding.cardToolImage.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardToolImage
            openTool(ToolMode.IMAGE_CLEANER) // Dedicated Image Cleaner
        }

        binding.cardToolFolder.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardToolFolder
            openTool(ToolMode.FOLDER_SCANNER)
        }

        // Image Studio & Video Studio Dashboard Cards - DISTINCT STUDIO WORKFLOWS
        binding.cardToolImageStudio.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardToolImageStudio
            openImageStudio()
        }

        binding.cardToolVideoStudio.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardToolVideoStudio
            openVideoStudio()
        }

        binding.cardToolImageUpscaler.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardToolImageUpscaler
            openImageUpscaler()
        }

        binding.cardToolQr.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardToolQr
            openQrStudio()
        }

        binding.cardHeroUpscaler.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardHeroUpscaler
            openImageUpscaler()
        }

        binding.cardHomeDocScanner.setOnClickListener {
            com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.cardHomeDocScanner
            openDocumentScanner()
        }

        // Tools Catalogue Workspace Card Clicks (All 13 Canonical Workspaces)
        binding.layoutToolsCatalogue.cardToolImageStudio.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolImageStudio; openImageStudio() }
        binding.layoutToolsCatalogue.cardToolVideoStudio.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolVideoStudio; openVideoStudio() }
        binding.layoutToolsCatalogue.cardToolQrStudio.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolQrStudio; openQrStudio() }
        binding.layoutToolsCatalogue.cardToolDocScanner.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolDocScanner; openDocumentScanner() }
        binding.layoutToolsCatalogue.cardToolBgRemover.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolBgRemover; openBackgroundRemover() }
        binding.layoutToolsCatalogue.cardToolAiUpscaler.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolAiUpscaler; openImageUpscaler() }
        binding.layoutToolsCatalogue.cardToolPrivacyScrubber.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolPrivacyScrubber; openTool(ToolMode.IMAGE_CLEANER) }
        binding.layoutToolsCatalogue.cardToolVideoCleaner.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolVideoCleaner; openTool(ToolMode.VIDEO_CLEANER) }
        binding.layoutToolsCatalogue.cardToolImageQuality.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolImageQuality; openImageQuality() }
        binding.layoutToolsCatalogue.cardToolFolderAnalyzer.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolFolderAnalyzer; openTool(ToolMode.FOLDER_SCANNER) }
        binding.layoutToolsCatalogue.cardToolAiBundler.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolAiBundler; openTool(ToolMode.AI_BUNDLE) }
        binding.layoutToolsCatalogue.cardToolMarkdownStudio.setOnClickListener {
            navigationController.showMarkdownViewerScreen()
            markdownViewerController.createNewDocument()
        }
        binding.layoutToolsCatalogue.cardToolProvenance.setOnClickListener { com.veilframe.app.ui.motion.NavigationMotionController.nextOriginView = binding.layoutToolsCatalogue.cardToolProvenance; openProvenance() }

        // Library Explore Button & Filters
        binding.layoutLibrary.btnLibraryExploreTools.setOnClickListener {
            navigationController.showToolsScreen()
        }
        binding.layoutLibrary.chipGroupLibraryFilter.setOnCheckedStateChangeListener { _, _ ->
            refreshLibrary()
        }

        // Search & Category Filters in Tools Catalogue
        setupToolsCatalogueFiltering()

        // In-App Updates & Repair Button
        binding.btnCheckUpdates.setOnClickListener {
            appUpdateManager.checkForUpdates(isUserInitiated = true)
        }
        binding.btnRepairApp.setOnClickListener {
            appUpdateManager.repairApp()
        }

        // External Links
        binding.btnLinkGithub.setOnClickListener { openWebUrl("https://github.com/sahir247/VeilFrame") }
        binding.btnLinkDocs.setOnClickListener { openWebUrl("https://github.com/sahir247/VeilFrame#readme") }
        binding.btnLinkChangelog.setOnClickListener { openWebUrl("https://github.com/sahir247/VeilFrame/blob/main/RELEASE_NOTES.md") }
        binding.btnLinkAbout.setOnClickListener { showAboutDialog() }

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

        // Markdown Viewer Dashboard Card (Open Existing or Create New from Zero)
        binding.cardToolMarkdownViewer.setOnClickListener {
            val options = arrayOf<CharSequence>("Open Markdown File...", "Create New Markdown (From Zero)")
            MaterialAlertDialogBuilder(this)
                .setTitle("Markdown Viewer & Editor")
                .setItems(options) { _, which ->
                    if (which == 0) {
                        markdownPickerLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                    } else {
                        navigationController.showMarkdownViewerScreen()
                        markdownViewerController.createNewDocument()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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

    private fun setupToolsCatalogueFiltering() {
        val cards = listOf(
            WorkspaceRoute.IMAGE_STUDIO to binding.layoutToolsCatalogue.cardToolImageStudio,
            WorkspaceRoute.VIDEO_STUDIO to binding.layoutToolsCatalogue.cardToolVideoStudio,
            WorkspaceRoute.QR_STUDIO to binding.layoutToolsCatalogue.cardToolQrStudio,
            WorkspaceRoute.DOCUMENT_SCANNER to binding.layoutToolsCatalogue.cardToolDocScanner,
            WorkspaceRoute.BACKGROUND_REMOVER to binding.layoutToolsCatalogue.cardToolBgRemover,
            WorkspaceRoute.IMAGE_UPSCALER to binding.layoutToolsCatalogue.cardToolAiUpscaler,
            WorkspaceRoute.IMAGE_CLEANER to binding.layoutToolsCatalogue.cardToolPrivacyScrubber,
            WorkspaceRoute.VIDEO_CLEANER to binding.layoutToolsCatalogue.cardToolVideoCleaner,
            WorkspaceRoute.IMAGE_QUALITY to binding.layoutToolsCatalogue.cardToolImageQuality,
            WorkspaceRoute.FOLDER_SCANNER to binding.layoutToolsCatalogue.cardToolFolderAnalyzer,
            WorkspaceRoute.AI_BUNDLE to binding.layoutToolsCatalogue.cardToolAiBundler,
            WorkspaceRoute.MARKDOWN_STUDIO to binding.layoutToolsCatalogue.cardToolMarkdownStudio,
            WorkspaceRoute.PROVENANCE to binding.layoutToolsCatalogue.cardToolProvenance
        )

        fun applyFilter() {
            val query = binding.layoutToolsCatalogue.etToolSearch.text?.toString().orEmpty().trim()
            val checkedChipId = binding.layoutToolsCatalogue.chipGroupToolCategories.checkedChipId

            val matchingRoutes = if (query.isEmpty()) {
                WorkspaceRoute.values().toList()
            } else {
                WorkspaceRoute.search(query)
            }.toSet()

            val selectedCategory = when (checkedChipId) {
                R.id.chipCategoryCreate -> WorkspaceCategory.CREATE_EDIT
                R.id.chipCategoryPrivacy -> WorkspaceCategory.PRIVACY
                R.id.chipCategoryAnalyze -> WorkspaceCategory.ANALYZE
                R.id.chipCategoryDev -> WorkspaceCategory.DEVELOPER
                else -> null
            }

            var visibleCount = 0
            cards.forEach { (route, card) ->
                val matchesQuery = route in matchingRoutes
                val matchesCat = (selectedCategory == null || route.category == selectedCategory)
                val visible = matchesQuery && matchesCat
                card.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) visibleCount++
            }

            binding.layoutToolsCatalogue.containerEmptySearch.visibility =
                if (visibleCount == 0) View.VISIBLE else View.GONE
        }

        binding.layoutToolsCatalogue.etToolSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.layoutToolsCatalogue.chipGroupToolCategories.setOnCheckedStateChangeListener { _, _ ->
            applyFilter()
        }
    }

    private fun showDocumentSourcePicker() {
        SharedSourcePickerSheet.newInstance(
            title = "Document Scanner Source",
            mediaType = SharedSourcePickerSheet.MediaType.DOCUMENT_ONLY
        ) { source ->
            when (source) {
                SharedSourcePickerSheet.SourceType.CAMERA -> {
                    Toast.makeText(this, "Document Scanner Camera (Beta)", Toast.LENGTH_SHORT).show()
                }
                SharedSourcePickerSheet.SourceType.FILES,
                SharedSourcePickerSheet.SourceType.PHOTOS -> {
                    imgStudioPickerLauncher.launch("image/*")
                }
            }
        }.show(supportFragmentManager, SharedSourcePickerSheet.TAG)
    }

    private fun showBackgroundRemoverSourcePicker() {
        SharedSourcePickerSheet.newInstance(
            title = "Background Remover Source",
            mediaType = SharedSourcePickerSheet.MediaType.IMAGE_ONLY
        ) { source ->
            when (source) {
                SharedSourcePickerSheet.SourceType.CAMERA -> {
                    Toast.makeText(this, "Camera capture for background removal (Alpha)", Toast.LENGTH_SHORT).show()
                }
                SharedSourcePickerSheet.SourceType.PHOTOS,
                SharedSourcePickerSheet.SourceType.FILES -> {
                    imgStudioPickerLauncher.launch("image/*")
                }
            }
        }.show(supportFragmentManager, SharedSourcePickerSheet.TAG)
    }

    private fun showImageQualitySourcePicker() {
        SharedSourcePickerSheet.newInstance(
            title = "Quality Inspector Source",
            mediaType = SharedSourcePickerSheet.MediaType.IMAGE_ONLY
        ) { _ ->
            imgStudioPickerLauncher.launch("image/*")
        }.show(supportFragmentManager, SharedSourcePickerSheet.TAG)
    }

    /**
     * Hides [cardCleanerActionDock] when the user scrolls to within ~48dp of the bottom of
     * [scrollTool], and shows it again when scrolling back toward the top.
     * Uses a simple alpha + translationY animation consistent with the rest of the Motion system.
     */
    private fun setupToolScrollDockBehavior() {
        binding.scrollTool.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { sv, _, scrollY, _, _ ->
                val child = sv.getChildAt(0) ?: return@OnScrollChangeListener
                // 120px ≈ 48dp — dock starts hiding when this close to the bottom
                val atBottom = scrollY >= child.height - sv.height - 120
                val dock = binding.cardCleanerActionDock
                if (dock.visibility != android.view.View.VISIBLE) return@OnScrollChangeListener
                if (atBottom) {
                    // Slide + fade out
                    dock.animate()
                        .translationY(dock.height.toFloat() + 32f)
                        .alpha(0f)
                        .setDuration(220)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction { dock.visibility = android.view.View.INVISIBLE }
                        .start()
                } else {
                    // Slide + fade in
                    dock.visibility = android.view.View.VISIBLE
                    dock.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(250)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
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

    private fun openTool(toolMode: ToolMode) {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.configureToolUI(toolMode)
        toolSessionManager.restoreToolState(toolSessionManager.currentState)
        navigationController.showToolScreen()
        consoleLogController.log("[UI] Opened ${toolSessionManager.getToolTitle(toolMode)} workspace.")
    }

    private fun openImageStudio() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.currentToolMode = ToolMode.IMAGE_COMPRESSOR
        navigationController.showImageStudioScreen()
        consoleLogController.log("[UI] Opened Image Studio workspace.")
    }

    private fun openVideoStudio() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.currentToolMode = ToolMode.VIDEO_COMPRESSOR
        navigationController.showVideoStudioScreen()
        consoleLogController.log("[UI] Opened Video Studio workspace.")
    }

    fun openImageUpscaler() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.currentToolMode = ToolMode.IMAGE_UPSCALER
        navigationController.showImageUpscalerScreen()
        consoleLogController.log("[UI] Opened Image Upscaler workspace.")
    }

    fun openQrStudio() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
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
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showMarkdownViewerScreen()
        markdownViewerController.loadMarkdown(uri, title)
        consoleLogController.log("[MARKDOWN] Opened viewer: ${title ?: uri.lastPathSegment}")
    }

    fun openMarkdownViewer(file: File, title: String? = null) {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showMarkdownViewerScreen()
        markdownViewerController.loadMarkdown(file, title)
        consoleLogController.log("[MARKDOWN] Opened viewer: ${file.name}")
    }

    fun openDocumentScanner() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showDocumentScannerScreen()
        consoleLogController.log("[UI] Opened Document Scanner workspace.")
    }

    fun openBackgroundRemover() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showBackgroundRemoverScreen()
        consoleLogController.log("[UI] Opened Background Remover workspace.")
    }

    fun openImageQuality() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showImageQualityScreen()
        consoleLogController.log("[UI] Opened Image Quality Forensics workspace.")
    }

    fun openProvenance() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        navigationController.showProvenanceScreen()
        consoleLogController.log("[UI] Opened Provenance & Verify workspace.")
    }

    private fun refreshLibrary() {
        lifecycleScope.launch(Dispatchers.IO) {
            val exportDirs = listOf(
                File(filesDir, "exports"),
                File(cacheDir, "exports"),
                File(cacheDir, "studio_exports"),
                File(cacheDir, "scanned_docs"),
                File(filesDir, "cv/exports")
            )
            val allFiles = exportDirs
                .filter { it.exists() && it.isDirectory }
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile && it.length() > 0 }
                .sortedByDescending { it.lastModified() }

            val savedSessions = com.veilframe.app.document.DocumentSession.listSavedSessions(this@MainActivity)
            val checkedFilterId = binding.layoutLibrary.chipGroupLibraryFilter.checkedChipId
            val filteredFiles = when (checkedFilterId) {
                R.id.chipLibraryImages -> allFiles.filter { f ->
                    val ext = f.extension.lowercase()
                    ext in listOf("jpg", "jpeg", "png", "webp", "bmp")
                }
                R.id.chipLibraryVideos -> allFiles.filter { f ->
                    val ext = f.extension.lowercase()
                    ext in listOf("mp4", "mkv", "webm", "mov", "avi")
                }
                R.id.chipLibraryDocs -> allFiles.filter { f ->
                    val ext = f.extension.lowercase()
                    ext in listOf("pdf", "md", "txt", "json", "aibundle")
                }
                R.id.chipLibraryQr -> allFiles.filter { f ->
                    f.name.lowercase().contains("qr") || f.extension.lowercase() == "svg"
                }
                else -> allFiles
            }

            withContext(Dispatchers.Main) {
                val density = resources.displayMetrics.density
                val margin12 = (12 * density).toInt()

                // Render active document scanner sessions
                if (savedSessions.isNotEmpty()) {
                    binding.layoutLibrary.containerLibrarySessionsSection.visibility = View.VISIBLE
                    binding.layoutLibrary.containerLibrarySessions.removeAllViews()
                    for (session in savedSessions) {
                        val card = com.google.android.material.card.MaterialCardView(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = margin12
                            }
                            radius = 16 * density
                            cardElevation = 1 * density
                            setCardBackgroundColor(getColor(R.color.vf_surface))
                            strokeColor = getColor(R.color.vf_surface_variant)
                            strokeWidth = (1 * density).toInt()
                        }

                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            setPadding(margin12, margin12, margin12, margin12)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        val icon = ImageView(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply {
                                marginEnd = margin12
                            }
                            setImageResource(R.drawable.ic_camera)
                            setColorFilter(getColor(R.color.vf_primary))
                        }

                        val infoCol = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val nameText = TextView(this@MainActivity).apply {
                            text = session.title
                            setTextColor(getColor(R.color.vf_text_primary))
                            textSize = 13f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }

                        val metaText = TextView(this@MainActivity).apply {
                            val timeAgo = android.text.format.DateUtils.getRelativeTimeSpanString(session.lastModifiedAt)
                            text = "${session.pageCount} pages • Edited $timeAgo"
                            setTextColor(getColor(R.color.vf_text_secondary))
                            textSize = 11f
                        }

                        infoCol.addView(nameText)
                        infoCol.addView(metaText)

                        val btnContinue = com.google.android.material.button.MaterialButton(
                            this@MainActivity,
                            null,
                            com.google.android.material.R.attr.borderlessButtonStyle
                        ).apply {
                            text = "Continue"
                            textSize = 12f
                            setTextColor(getColor(R.color.vf_primary))
                            setOnClickListener {
                                openDocumentScanner()
                                docScannerController.resumeSession(session.sessionId)
                            }
                        }

                        val btnDelete = com.google.android.material.button.MaterialButton(
                            this@MainActivity,
                            null,
                            com.google.android.material.R.attr.borderlessButtonStyle
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
                            setIconResource(R.drawable.ic_action_clear)
                            iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.vf_text_secondary))
                            setPadding(0, 0, 0, 0)
                            setOnClickListener {
                                com.veilframe.app.document.DocumentSession.deleteSession(this@MainActivity, session.sessionId)
                                refreshLibrary()
                            }
                        }

                        row.addView(icon)
                        row.addView(infoCol)
                        row.addView(btnContinue)
                        row.addView(btnDelete)
                        card.addView(row)
                        binding.layoutLibrary.containerLibrarySessions.addView(card)
                    }
                } else {
                    binding.layoutLibrary.containerLibrarySessionsSection.visibility = View.GONE
                }

                val hasAnyContent = savedSessions.isNotEmpty() || filteredFiles.isNotEmpty()
                if (!hasAnyContent) {
                    binding.layoutLibrary.containerLibraryEmpty.visibility = View.VISIBLE
                    binding.layoutLibrary.containerLibraryItems.visibility = View.GONE
                } else {
                    binding.layoutLibrary.containerLibraryEmpty.visibility = View.GONE
                    binding.layoutLibrary.containerLibraryItems.visibility = if (filteredFiles.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.layoutLibrary.containerLibraryItems.removeAllViews()

                    for (file in filteredFiles) {
                        val card = com.google.android.material.card.MaterialCardView(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = margin12
                            }
                            radius = 16 * density
                            cardElevation = 1 * density
                            setCardBackgroundColor(getColor(R.color.vf_surface))
                            strokeColor = getColor(R.color.vf_surface_variant)
                            strokeWidth = (1 * density).toInt()
                            isClickable = true
                            isFocusable = true
                        }

                        val row = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            setPadding(margin12, margin12, margin12, margin12)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }

                        val icon = ImageView(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply {
                                marginEnd = margin12
                            }
                            val iconRes = when (file.extension.lowercase()) {
                                "jpg", "jpeg", "png", "webp", "bmp" -> R.drawable.ic_tab_image
                                "mp4", "mkv", "webm", "mov" -> R.drawable.ic_tab_video
                                "pdf", "md", "txt" -> R.drawable.ic_toc
                                else -> if (file.name.contains("qr", ignoreCase = true)) R.drawable.ic_tool_qr else R.drawable.ic_check_circle
                            }
                            setImageResource(iconRes)
                            setColorFilter(getColor(R.color.vf_primary))
                        }

                        val infoCol = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }

                        val nameText = TextView(this@MainActivity).apply {
                            text = file.name
                            setTextColor(getColor(R.color.vf_text_primary))
                            textSize = 13f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }

                        val metaText = TextView(this@MainActivity).apply {
                            val sizeStr = safStorageManager.formatBytes(file.length())
                            val dateStr = android.text.format.DateFormat.format("MMM d, yyyy HH:mm", file.lastModified())
                            text = "$sizeStr • $dateStr"
                            setTextColor(getColor(R.color.vf_text_secondary))
                            textSize = 11f
                        }

                        infoCol.addView(nameText)
                        infoCol.addView(metaText)

                        val btnShare = com.google.android.material.button.MaterialButton(
                            this@MainActivity,
                            null,
                            com.google.android.material.R.attr.borderlessButtonStyle
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                            setIconResource(R.drawable.ic_action_share)
                            iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.vf_primary))
                            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
                            setPadding(0, 0, 0, 0)
                            setOnClickListener {
                                val mime = safStorageManager.getExportMimeType(file)
                                shareStudioFile(file, mime)
                            }
                        }

                        val btnDelete = com.google.android.material.button.MaterialButton(
                            this@MainActivity,
                            null,
                            com.google.android.material.R.attr.borderlessButtonStyle
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt())
                            setIconResource(R.drawable.ic_action_clear)
                            iconTint = android.content.res.ColorStateList.valueOf(getColor(R.color.vf_text_secondary))
                            setPadding(0, 0, 0, 0)
                            setOnClickListener {
                                file.delete()
                                refreshLibrary()
                            }
                        }

                        card.setOnClickListener {
                            val mime = safStorageManager.getExportMimeType(file)
                            shareStudioFile(file, mime)
                        }

                        row.addView(icon)
                        row.addView(infoCol)
                        row.addView(btnShare)
                        row.addView(btnDelete)
                        card.addView(row)
                        binding.layoutLibrary.containerLibraryItems.addView(card)
                    }
                }
            }
        }
    }

    private fun scheduleBackClearWork() {
        backClearTimerJob?.cancel()
        backClearTimerJob = null
        // Immediately release heavy video/audio resources upon returning to Home to prevent phantom frame retention
        if (::videoStudioController.isInitialized) {
            videoStudioController.clear()
        }
        if (::videoPlayerController.isInitialized) {
            videoPlayerController.clearMedia()
        }
        if (::imageStudioController.isInitialized) {
            imageStudioController.clear()
        }
        toolSessionManager.clearCurrentTool()
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

    private fun handleSingleFileSelected(uri: Uri) {
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

    private fun shareStudioFile(file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Media Artifact"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleTheme() {
        val current = com.veilframe.app.settings.ThemeSettingsManager.getThemeMode(this)
        val next = when (current) {
            com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.DARK -> com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.LIGHT
            com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.LIGHT -> com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.DARK
            com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.AMOLED -> com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.LIGHT
            com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.SYSTEM -> {
                val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                if (isDark) com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.LIGHT else com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.DARK
            }
            else -> com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.DARK
        }
        com.veilframe.app.settings.ThemeSettingsManager.setThemeMode(this, next)
        // Toolbar toggle: immediate recreate (not inside settings panel)
        com.veilframe.app.settings.ThemeSettingsManager.consumePendingRecreate()
        recreate()
    }

    private fun openSettingsOverlay() {
        if (isFinishing || isDestroyed) return
        // Reset so only changes made in THIS session trigger a recreate on close
        com.veilframe.app.settings.ThemeSettingsManager.consumePendingRecreate()
        val panelBinding = binding.layoutSettingsPanel

        val rootInsets = ViewCompat.getRootWindowInsets(binding.rootCoordinator)
        val sTop = rootInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val nBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        if (sTop > 0 || nBottom > 0) {
            binding.containerSettings.setPadding(0, sTop, 0, nBottom)
        }

        // 1. Theme mode selection
        when (com.veilframe.app.settings.ThemeSettingsManager.getThemeMode(this)) {
            com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.SYSTEM -> panelBinding.rbThemeSystem.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.LIGHT -> panelBinding.rbThemeLight.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.DARK -> panelBinding.rbThemeDark.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.AMOLED -> panelBinding.rbThemeAmoled.isChecked = true
        }

        panelBinding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbThemeLight -> com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.LIGHT
                R.id.rbThemeDark -> com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.DARK
                R.id.rbThemeAmoled -> com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.AMOLED
                else -> com.veilframe.app.settings.ThemeSettingsManager.ThemeMode.SYSTEM
            }
            com.veilframe.app.settings.ThemeSettingsManager.setThemeMode(this, mode)
        }

        // 2. Dynamic color toggle with live palette chip disabling
        fun updatePaletteChipsEnabled(dynamicActive: Boolean) {
            panelBinding.chipGroupAccentPalette.isEnabled = !dynamicActive
            panelBinding.chipPaletteMonochrome.isEnabled = !dynamicActive
            panelBinding.chipPaletteForestSage.isEnabled = !dynamicActive
            panelBinding.chipPaletteDeepOcean.isEnabled = !dynamicActive
            panelBinding.chipPaletteWarmAmber.isEnabled = !dynamicActive
            panelBinding.chipPaletteCyberViolet.isEnabled = !dynamicActive
            panelBinding.chipGroupAccentPalette.alpha = if (dynamicActive) 0.38f else 1.0f
            panelBinding.tvDynamicColorNotice.visibility = if (dynamicActive) View.VISIBLE else View.GONE
        }

        val isDynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dynamicInitial = com.veilframe.app.settings.ThemeSettingsManager.isDynamicColorEnabled(this) && isDynamicSupported
        panelBinding.switchDynamicColor.isEnabled = isDynamicSupported
        panelBinding.switchDynamicColor.isChecked = dynamicInitial
        updatePaletteChipsEnabled(dynamicInitial)

        panelBinding.switchDynamicColor.setOnCheckedChangeListener { _, isChecked ->
            com.veilframe.app.settings.ThemeSettingsManager.setDynamicColorEnabled(this, isChecked)
            updatePaletteChipsEnabled(isChecked)
        }

        // 3. Custom accent palette
        when (com.veilframe.app.settings.ThemeSettingsManager.getAccentPalette(this)) {
            com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.MONOCHROME -> panelBinding.chipPaletteMonochrome.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.SAGE -> panelBinding.chipPaletteForestSage.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.OCEAN -> panelBinding.chipPaletteDeepOcean.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.AMBER -> panelBinding.chipPaletteWarmAmber.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.VIOLET -> panelBinding.chipPaletteCyberViolet.isChecked = true
        }

        panelBinding.chipGroupAccentPalette.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val palette = when (checkedIds[0]) {
                    R.id.chipPaletteMonochrome -> com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.MONOCHROME
                    R.id.chipPaletteForestSage -> com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.SAGE
                    R.id.chipPaletteDeepOcean -> com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.OCEAN
                    R.id.chipPaletteWarmAmber -> com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.AMBER
                    R.id.chipPaletteCyberViolet -> com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.VIOLET
                    else -> com.veilframe.app.settings.ThemeSettingsManager.AccentPalette.MONOCHROME
                }
                com.veilframe.app.settings.ThemeSettingsManager.setAccentPalette(this, palette)
            }
        }

        // 4. Typography style
        when (com.veilframe.app.settings.ThemeSettingsManager.getTypographyStyle(this)) {
            com.veilframe.app.settings.ThemeSettingsManager.TypographyStyle.DEFAULT -> panelBinding.rbTypoSans.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.TypographyStyle.MONOSPACE -> panelBinding.rbTypoMonospace.isChecked = true
            com.veilframe.app.settings.ThemeSettingsManager.TypographyStyle.SERIF -> panelBinding.rbTypoSerif.isChecked = true
        }

        panelBinding.rgTypography.setOnCheckedChangeListener { _, checkedId ->
            val typo = when (checkedId) {
                R.id.rbTypoMonospace -> com.veilframe.app.settings.ThemeSettingsManager.TypographyStyle.MONOSPACE
                R.id.rbTypoSerif -> com.veilframe.app.settings.ThemeSettingsManager.TypographyStyle.SERIF
                else -> com.veilframe.app.settings.ThemeSettingsManager.TypographyStyle.DEFAULT
            }
            com.veilframe.app.settings.ThemeSettingsManager.setTypographyStyle(this, typo)
            com.veilframe.app.settings.ThemeSettingsManager.applyActivityTheme(this)
        }

        // 5. Hardware diagnostics live telemetry
        val actManager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo().also { actManager?.getMemoryInfo(it) }
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        panelBinding.tvSettingsHardwareCores.text = "CPU Cores: $cpuCores (Active HW Threads)"
        panelBinding.tvSettingsHardwareMemory.text = "RAM Headroom: ~$availMb MB (Total: $totalMb MB)"
        panelBinding.tvSettingsHardwareNnapi.text = "Engine: VeilFrame SOTA ONNX"
        panelBinding.tvSettingsHardwareAcceleration.text = "Acceleration: Multi-Threaded CPU (ONNX Runtime)"

        // 6. Close and dismissal bindings
        panelBinding.btnSettingsClose.setOnClickListener { closeSettingsOverlay() }
        panelBinding.btnSettingsDone.setOnClickListener { closeSettingsOverlay() }
        binding.scrimSettings.setOnClickListener { closeSettingsOverlay() }

        // 7. Left-to-right swipe-to-dismiss gesture
        com.veilframe.app.ui.motion.NavigationMotionController.attachSwipeToDismiss(
            settingsContainer = binding.containerSettings,
            scrimView = binding.scrimSettings
        ) {
            closeSettingsOverlay()
        }

        // 8. Open slide-over animation with M3 Expressive physics
        com.veilframe.app.ui.motion.NavigationMotionController.openSettings(
            settingsContainer = binding.containerSettings,
            scrimView = binding.scrimSettings
        )
    }

    private fun closeSettingsOverlay() {
        com.veilframe.app.ui.motion.NavigationMotionController.closeSettings(
            settingsContainer = binding.containerSettings,
            scrimView = binding.scrimSettings
        )
        // Apply any pending theme/palette/typography changes as a single recreate()
        // AFTER the close animation (~300ms), so the panel is fully dismissed first.
        if (com.veilframe.app.settings.ThemeSettingsManager.consumePendingRecreate()) {
            binding.rootCoordinator.postDelayed({ recreate() }, 320L)
        }
    }

    private fun openWebUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("About VeilFrame")
            .setMessage(
                """
                VeilFrame v2.3.0
                Privacy Forensics & AI Bundler
                
                • Local Processing: 100% on-device execution
                • Privacy First: Heuristic secret & credential masking
                • Forensic Analysis: PRNU defense, bitstream repacking & SHA-256
                • Multi-Format Export: .aibundle, HTML, JSON, Markdown, CSV, and media
                • In-App Updates: Monotonic versionCode & SHA-256 verification
                
                Engine Runtime: Native Kotlin + Media3 + FFmpegKit Full
                Open Source (Apache 2.0 / MIT)
                """.trimIndent()
            )
            .setPositiveButton("Close", null)
            .show()
    }
}
