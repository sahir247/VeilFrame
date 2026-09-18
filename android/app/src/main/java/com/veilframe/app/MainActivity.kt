package com.veilframe.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
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
import com.veilframe.app.media.ImageStudioController
import com.veilframe.app.media.SafDestinationManager
import com.veilframe.app.media.VideoPlayerController
import com.veilframe.app.media.VideoStudioController
import com.veilframe.app.navigation.MainNavigationController
import com.veilframe.app.navigation.ScreenState
import com.veilframe.app.storage.CreateDocumentWithMime
import com.veilframe.app.storage.SafStorageManager
import com.veilframe.app.tools.JobState
import com.veilframe.app.tools.ToolExecutionController
import com.veilframe.app.tools.ToolMode
import com.veilframe.app.tools.ToolSessionManager
import com.veilframe.app.updates.AppUpdateManager
import kotlinx.coroutines.Dispatchers
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
    private var pendingExportFile: File? = null
    private var isConsoleExpanded: Boolean = false

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
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            imageStudioController.handleImageSelected(uri)
        }
    }

    private val vidStudioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            videoStudioController.handleVideoSelected(uri)
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
        val prefs = getSharedPreferences("veilframe_prefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_theme", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore cached changelog if available
        val cachedTag = prefs.getString("cached_changelog_tag", null)
        val cachedChangelog = prefs.getString("cached_changelog", null)
        if (!cachedTag.isNullOrEmpty() && !cachedChangelog.isNullOrEmpty()) {
            binding.tvWhatsNewHeader.text = "WHAT'S NEW IN $cachedTag"
            binding.tvWhatsNewContent.text = cachedChangelog
        }

        // System insets handling: status bar top inset for AppBarLayout + navigation bar bottom inset for action dock
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            binding.appBarLayout.setPadding(0, statusBarTop, 0, 0)
            binding.bottomActionDock.setPadding(
                binding.bottomActionDock.paddingStart,
                binding.bottomActionDock.paddingTop,
                binding.bottomActionDock.paddingEnd,
                navBarBottom + 12
            )
            insets
        }

        initSubControllers()
        initStudioWorkspaces()
        setupListeners()

        consoleLogController.log("[SYS] Initialized VeilFrame 2.2.5 Native Core Runtime")
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
        if (action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } ?: intent.data

            if (uri != null) {
                safStorageManager.persistReadPermission(uri)
                if (type.startsWith("video/")) {
                    openVideoStudio()
                    videoStudioController.handleVideoSelected(uri)
                } else if (type.startsWith("image/")) {
                    openImageStudio()
                    imageStudioController.handleImageSelected(uri)
                }
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
            onHomeScreenEntered = {}
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
            onPickFolderRequest = { imgFolderPickerLauncher.launch(null) },
            onExportFileRequest = { file ->
                pendingExportFile = file
                val mime = safStorageManager.getExportMimeType(file)
                imgStudioExportLauncher.launch(file.name to mime)
            },
            onShareFileRequest = { file, mime ->
                shareStudioFile(file, mime)
            },
            onNavigateHome = { navigationController.showHomeScreen() }
        )

        videoStudioController = VideoStudioController(
            activity = this,
            binding = binding.layoutVideoStudio,
            playerController = videoPlayerController,
            safManager = safDestinationManager,
            scope = lifecycleScope,
            onPickVideoRequest = { vidStudioPickerLauncher.launch("video/*") },
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

        imageStudioController.initWorkspace()
        videoStudioController.initWorkspace()
    }

    private fun setupListeners() {
        // Theme toggle actions
        binding.btnToggleTheme.setOnClickListener { toggleTheme() }
        binding.btnToolToggleTheme.setOnClickListener { toggleTheme() }

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

        // In-App Updates Button
        binding.btnCheckUpdates.setOnClickListener {
            appUpdateManager.checkForUpdates(isUserInitiated = true)
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

        // Result Card Direct Actions
        binding.btnResultSave.setOnClickListener { launchExportCurrentArtifact() }
        binding.btnResultShare.setOnClickListener { toolExecutionController.shareLastResult() }

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
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.currentToolMode = ToolMode.IMAGE_COMPRESSOR
        navigationController.showImageStudioScreen()
        consoleLogController.log("[UI] Opened Image Studio workspace.")
    }

    private fun openVideoStudio() {
        pauseVideoPlayback()
        if (navigationController.currentScreen == ScreenState.TOOL) {
            toolSessionManager.saveCurrentToolState()
        }
        toolSessionManager.currentToolMode = ToolMode.VIDEO_COMPRESSOR
        navigationController.showVideoStudioScreen()
        consoleLogController.log("[UI] Opened Video Studio workspace.")
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
        val prefs = getSharedPreferences("veilframe_prefs", Context.MODE_PRIVATE)
        val currentNightMode = AppCompatDelegate.getDefaultNightMode()
        val isDark = if (currentNightMode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } else {
            currentNightMode == AppCompatDelegate.MODE_NIGHT_YES
        }
        val newDark = !isDark
        prefs.edit().putBoolean("dark_theme", newDark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
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
                VeilFrame v2.2.5
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
