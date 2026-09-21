package com.veilframe.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.View
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
import com.veilframe.app.media.ImageStudioController
import com.veilframe.app.media.SafDestinationManager
import com.veilframe.app.media.VideoPlayerController
import com.veilframe.app.media.VideoStudioController
import com.veilframe.app.navigation.MainNavigationController
import com.veilframe.app.navigation.ScreenState
import com.veilframe.app.upscale.ui.ImageUpscalerController
import com.veilframe.app.storage.CreateDocumentWithMime
import com.veilframe.app.storage.SafStorageManager
import com.veilframe.app.tools.JobState
import com.veilframe.app.tools.ToolExecutionController
import com.veilframe.app.tools.ToolMode
import com.veilframe.app.tools.ToolSessionManager
import com.veilframe.app.ui.motion.ExpressiveMotion
import com.veilframe.app.ui.motion.MorphDialogController
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

        // System insets handling: status bar top inset for AppBarLayout + navigation bar bottom inset for action dock + settings panel
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            binding.appBarLayout.setPadding(0, statusBarTop, 0, 0)
            binding.containerSettings.setPadding(0, statusBarTop, 0, navBarBottom)
            val layoutParams = binding.cardCleanerActionDock.layoutParams as? android.view.ViewGroup.MarginLayoutParams
            if (layoutParams != null) {
                layoutParams.bottomMargin = navBarBottom + (16 * resources.displayMetrics.density).toInt()
                binding.cardCleanerActionDock.layoutParams = layoutParams
            }
            insets
        }

        initSubControllers()
        initStudioWorkspaces()
        setupListeners()

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
            }
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
            onLog = { msg -> consoleLogController.log(msg) },
            getImageStudioController = { if (::imageStudioController.isInitialized) imageStudioController else null },
            getVideoStudioController = { if (::videoStudioController.isInitialized) videoStudioController else null }
        )

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
        )

        imageStudioController.initWorkspace()
        videoStudioController.initWorkspace()
        imageUpscalerController.init()
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
            openTool(ToolMode.AI_BUNDLE)
        }

        binding.cardToolVideo.setOnClickListener {
            openTool(ToolMode.VIDEO_CLEANER) // Dedicated Video Sanitizer
        }

        binding.cardToolImage.setOnClickListener {
            openTool(ToolMode.IMAGE_CLEANER) // Dedicated Image Cleaner
        }

        binding.cardToolFolder.setOnClickListener {
            openTool(ToolMode.FOLDER_SCANNER)
        }

        // Image Studio & Video Studio Dashboard Cards - DISTINCT STUDIO WORKFLOWS
        binding.cardToolImageStudio.setOnClickListener {
            openImageStudio()
        }

        binding.cardToolVideoStudio.setOnClickListener {
            openVideoStudio()
        }

        binding.cardToolImageUpscaler.setOnClickListener {
            openImageUpscaler()
        }

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
    }

    private fun openSettingsOverlay() {
        if (isFinishing || isDestroyed) return
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
        val profile = com.veilframe.app.upscale.inference.DeviceCapabilityProfile.probe(this)
        val availMb = profile.availableMemoryBytes / (1024 * 1024)
        val totalMb = profile.totalMemoryBytes / (1024 * 1024)
        panelBinding.tvSettingsHardwareCores.text = "CPU Cores: ${profile.cpuCores} (Active HW Threads)"
        panelBinding.tvSettingsHardwareMemory.text = "RAM Headroom: ~${availMb} MB (Total: ${totalMb} MB)"
        panelBinding.tvSettingsHardwareNnapi.text = "NNAPI: ${if (profile.supportsNnapi) "Supported (NPU/GPU Accelerator)" else "Unsupported / CPU Fallback"}"
        panelBinding.tvSettingsHardwareAcceleration.text = "Acceleration: ${if (profile.supportsNnapiFp16) "NNAPI FP16 Relaxed + Multi-Threaded CPU" else "NNAPI Standard + CPU"}"

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
                VeilFrame v2.2.7
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
