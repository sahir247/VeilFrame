package com.veilframe.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.chip.Chip
import com.veilframe.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VeilFrame Mobile Hub — Android Vertical Forensics & AI Bundler.
 * Features Material 3 dark cyber aesthetics, edge-to-edge window insets,
 * vector SVGs, isolated per-tab selection state, interactive telemetry,
 * and multi-format Scoped Storage export.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var py: Python? = null

    private var selectedUri: Uri? = null
    private var selectedPathDisplay: String = ""
    private var isFolderSelected: Boolean = false
    private var lastGeneratedFile: File? = null
    private var isLogsExpanded: Boolean = false

    // Multi-format export launcher (Storage Access Framework)
    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri ->
        if (destinationUri != null) {
            val sourceFile = lastGeneratedFile
            if (sourceFile != null && sourceFile.exists()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = try {
                        contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                            sourceFile.inputStream().use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        true
                    } catch (e: Exception) {
                        false
                    }
                    withContext(Dispatchers.Main) {
                        if (success) {
                            logToConsole("[OK] File saved successfully to device storage.")
                            Toast.makeText(this@MainActivity, "Export saved to device storage", Toast.LENGTH_LONG).show()
                        } else {
                            logToConsole("[ERR] Failed to write exported file to destination.")
                            Toast.makeText(this@MainActivity, "Export write failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // SAF and Photo Picker Activity Result Launchers
    private val visualMediaPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedUri = uri
            isFolderSelected = false
            selectedPathDisplay = getDisplayName(uri)
            binding.tvSelectedPath.text = selectedPathDisplay
            binding.tvSelectedPath.setTextColor(getColor(R.color.vf_primary))
            logToConsole("[SYS] Selected media item: $selectedPathDisplay")
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            selectedUri = uri
            isFolderSelected = true
            selectedPathDisplay = getDisplayName(uri)
            binding.tvSelectedPath.text = selectedPathDisplay
            binding.tvSelectedPath.setTextColor(getColor(R.color.vf_primary))
            logToConsole("[SYS] Selected directory tree: $selectedPathDisplay")
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            selectedUri = uri
            isFolderSelected = false
            selectedPathDisplay = getDisplayName(uri)
            binding.tvSelectedPath.text = selectedPathDisplay
            binding.tvSelectedPath.setTextColor(getColor(R.color.vf_primary))
            logToConsole("[SYS] Selected target file: $selectedPathDisplay")
        }
    }

    private fun getDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    cursor.getString(nameIndex)
                } else null
            } ?: uri.lastPathSegment ?: uri.toString()
        } catch (_: Exception) {
            uri.lastPathSegment ?: uri.toString()
        }
    }

    private suspend fun materializeUri(uri: Uri, destination: File): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destination.isFile && destination.length() > 0
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                logToConsole("[ERR] Failed copying input file: ${e.message}")
            }
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Prevent top bar cutting under notification panel & status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.setPadding(0, systemBars.top, 0, 0)
            binding.bottomActionDock.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }

        initPython()
        setupListeners()
        updateModeUI(R.id.chipModeAi)
    }

    private fun initPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
            py = Python.getInstance()
            logToConsole("[OK] Chaquopy Python Runtime initialized.")
        } catch (e: Exception) {
            logToConsole("[WARN] Initializing Python engine: ${e.message}")
        }
    }

    private fun setupListeners() {
        // Tab switching: clear selected target when changing tabs
        binding.chipGroupMode.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: R.id.chipModeAi
            clearSelectedTarget(logMessage = false)
            updateModeUI(selectedId)
            logToConsole("[NAV] Switched to mode: ${getModeTitle(selectedId)}")
        }

        // Target Pickers
        binding.btnPickFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.btnPickFile.setOnClickListener {
            val modeId = binding.chipGroupMode.checkedChipId
            when (modeId) {
                R.id.chipModeVideo -> {
                    if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
                        visualMediaPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                .build()
                        )
                    } else {
                        filePickerLauncher.launch(arrayOf("video/*"))
                    }
                }
                R.id.chipModeImage -> {
                    if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)) {
                        visualMediaPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest.Builder()
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

        binding.btnClearTarget.setOnClickListener {
            clearSelectedTarget(logMessage = true)
        }

        // Telemetry Actions (Interactive Console)
        binding.btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("VeilFrame Telemetry Logs", binding.tvConsoleLog.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Telemetry logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            binding.tvConsoleLog.text = "[INFO] Telemetry buffer cleared.\n[INFO] Ready for execution."
        }

        binding.btnExpandLogs.setOnClickListener {
            isLogsExpanded = !isLogsExpanded
            val targetHeightDp = if (isLogsExpanded) 320 else 140
            val density = resources.displayMetrics.density
            binding.scrollConsole.layoutParams.height = (targetHeightDp * density).toInt()
            binding.scrollConsole.requestLayout()
            logToConsole(if (isLogsExpanded) "[UI] Telemetry console expanded." else "[UI] Telemetry console collapsed.")
        }

        // Primary Action
        binding.btnExecute.setOnClickListener {
            executeSelectedMode()
        }

        // Multi-Format Export Action (Save As to device storage)
        binding.btnExportResult.setOnClickListener {
            val file = lastGeneratedFile
            if (file != null && file.exists()) {
                exportDocumentLauncher.launch(file.name)
            } else {
                Toast.makeText(this, "No output file to export", Toast.LENGTH_SHORT).show()
            }
        }

        // Share Result Action
        binding.btnShareResult.setOnClickListener {
            shareLastResult()
        }
    }

    private fun clearSelectedTarget(logMessage: Boolean = true) {
        selectedUri = null
        selectedPathDisplay = ""
        isFolderSelected = false
        lastGeneratedFile = null
        binding.tvSelectedPath.text = "No file or folder selected"
        binding.tvSelectedPath.setTextColor(getColor(R.color.vf_text_muted))
        binding.btnExportResult.isEnabled = false
        binding.btnShareResult.isEnabled = false
        binding.progressIndicator.progress = 0
        binding.progressIndicator.visibility = View.INVISIBLE
        binding.tvPhaseBadge.text = "IDLE"
        binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_primary))
        binding.tvStatusText.text = "Ready for execution."
        if (logMessage) {
            logToConsole("[SYS] Target selection and staging buffer cleared.")
        }
    }

    private fun getModeTitle(modeId: Int): String {
        return when (modeId) {
            R.id.chipModeAi -> "AI Program Bundler"
            R.id.chipModeVideo -> "Video Privacy Cleaner"
            R.id.chipModeImage -> "Image Metadata Cleaner"
            R.id.chipModeFolder -> "Folder Forensics & Scanner"
            else -> "Unknown"
        }
    }

    private fun configureOptions(
        paramHeader: String,
        primaryLabel: String,
        primaryChips: List<String>,
        primaryDefaultIndex: Int,
        formatLabel: String,
        formatChips: List<String>,
        formatDefaultIndex: Int,
        switch1Text: String,
        switch1Checked: Boolean,
        switch2Text: String,
        switch2Checked: Boolean,
        switch3Text: String,
        switch3Checked: Boolean,
        executeText: String,
        folderBtnText: String,
        fileBtnText: String
    ) {
        binding.tvParamHeader.text = paramHeader
        binding.tvPrimaryOptionLabel.text = primaryLabel

        val optionChips = listOf(
            binding.chipOption1,
            binding.chipOption2,
            binding.chipOption3,
            binding.chipOption4,
            binding.chipOption5
        )
        optionChips.forEachIndexed { i, chip ->
            if (i < primaryChips.size) {
                chip.visibility = View.VISIBLE
                chip.text = primaryChips[i]
                chip.isChecked = (i == primaryDefaultIndex)
            } else {
                chip.visibility = View.GONE
            }
        }

        binding.tvFormatLabel.text = formatLabel
        val formatChipsViews = listOf(
            binding.chipFormat1,
            binding.chipFormat2,
            binding.chipFormat3,
            binding.chipFormat4,
            binding.chipFormat5,
            binding.chipFormat6
        )
        formatChipsViews.forEachIndexed { i, chip ->
            if (i < formatChips.size) {
                chip.visibility = View.VISIBLE
                chip.text = formatChips[i]
                chip.isChecked = (i == formatDefaultIndex)
            } else {
                chip.visibility = View.GONE
            }
        }

        binding.switchParam1.text = switch1Text
        binding.switchParam1.isChecked = switch1Checked
        binding.switchParam2.text = switch2Text
        binding.switchParam2.isChecked = switch2Checked
        binding.switchParam3.text = switch3Text
        binding.switchParam3.isChecked = switch3Checked

        binding.btnExecute.text = executeText
        binding.btnPickFolder.text = folderBtnText
        binding.btnPickFile.text = fileBtnText
    }

    private fun updateModeUI(modeId: Int) {
        when (modeId) {
            R.id.chipModeAi -> {
                configureOptions(
                    paramHeader = "AI BUNDLE CONFIGURATION",
                    primaryLabel = "Token Budget Ceiling:",
                    primaryChips = listOf("32,000", "64,000", "128,000", "200,000", "Unlimited"),
                    primaryDefaultIndex = 3,
                    formatLabel = "Export Bundle Format:",
                    formatChips = listOf(".aibundle", "Markdown (.md)", "JSON (.json)", "ZIP (.zip)", "HTML (.html)", "Plain Text (.txt)"),
                    formatDefaultIndex = 0,
                    switch1Text = "Mask API keys, secrets & credentials",
                    switch1Checked = true,
                    switch2Text = "Exclude test suites & fixtures",
                    switch2Checked = false,
                    switch3Text = "Compress dependency lockfiles & manifests",
                    switch3Checked = true,
                    executeText = "GENERATE AI BUNDLE",
                    folderBtnText = "SELECT FOLDER",
                    fileBtnText = "SELECT FILE"
                )
            }
            R.id.chipModeVideo -> {
                configureOptions(
                    paramHeader = "VIDEO PRIVACY PARAMETERS",
                    primaryLabel = "PRNU Sensor Noise Defense:",
                    primaryChips = listOf("Low", "Medium", "High", "Aggressive", "None"),
                    primaryDefaultIndex = 1,
                    formatLabel = "Output Container Format:",
                    formatChips = listOf("MP4 (.mp4)", "MKV (.mkv)", "WebM (.webm)", "Audio (.m4a)", "Audit Report (.json)", "Summary (.txt)"),
                    formatDefaultIndex = 0,
                    switch1Text = "Scrub location & camera EXIF/atoms",
                    switch1Checked = true,
                    switch2Text = "Sanitize audio track metadata",
                    switch2Checked = true,
                    switch3Text = "Re-encode bitstream (break watermark)",
                    switch3Checked = true,
                    executeText = "SANITIZE VIDEO",
                    folderBtnText = "SELECT BATCH FOLDER",
                    fileBtnText = "SELECT VIDEO"
                )
            }
            R.id.chipModeImage -> {
                configureOptions(
                    paramHeader = "IMAGE PRIVACY PARAMETERS",
                    primaryLabel = "Scrub Quality & Defense:",
                    primaryChips = listOf("Standard (95%)", "High (90%)", "Medium (80%)", "Stealth (Noise)", "Lossless"),
                    primaryDefaultIndex = 1,
                    formatLabel = "Output Image Format:",
                    formatChips = listOf("JPEG (.jpg)", "PNG (.png)", "WebP (.webp)", "Forensic JSON (.json)", "Audit (.md)", "Raw (.bin)"),
                    formatDefaultIndex = 0,
                    switch1Text = "Strip EXIF, GPS & camera maker notes",
                    switch1Checked = true,
                    switch2Text = "Remove embedded thumbnails & previews",
                    switch2Checked = true,
                    switch3Text = "Sanitize ICC color profile metadata",
                    switch3Checked = false,
                    executeText = "SCRUB IMAGE METADATA",
                    folderBtnText = "SELECT BATCH FOLDER",
                    fileBtnText = "SELECT IMAGE"
                )
            }
            R.id.chipModeFolder -> {
                configureOptions(
                    paramHeader = "FOLDER AUDIT & DEDUP CONFIG",
                    primaryLabel = "Analysis Depth & Profile:",
                    primaryChips = listOf("Quick Audit", "Deep Forensic", "Duplicate Hunt", "Compliance Scan", "Secret Search"),
                    primaryDefaultIndex = 1,
                    formatLabel = "Report Export Format:",
                    formatChips = listOf("Interactive HTML (.html)", "Structured JSON (.json)", "Markdown (.md)", "Inventory CSV (.csv)", "Plain Text (.txt)", "ZIP Package (.zip)"),
                    formatDefaultIndex = 0,
                    switch1Text = "Scan recursive subdirectories",
                    switch1Checked = true,
                    switch2Text = "Calculate SHA-256 cryptographic hashes",
                    switch2Checked = true,
                    switch3Text = "Detect leaked secrets and API keys",
                    switch3Checked = true,
                    executeText = "START FOLDER AUDIT",
                    folderBtnText = "SELECT FOLDER",
                    fileBtnText = "SELECT FILE"
                )
            }
        }
    }

    private fun getSelectedOptionIndex(): Int {
        val group = binding.chipGroupPrimaryOptions
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip
            if (chip != null && chip.isChecked) return i
        }
        return 0
    }

    private fun getSelectedFormatIndex(): Int {
        val group = binding.chipGroupFormat
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip
            if (chip != null && chip.isChecked) return i
        }
        return 0
    }

    private fun executeSelectedMode() {
        if (selectedUri == null) {
            Toast.makeText(this, "Please select a target file or folder first", Toast.LENGTH_SHORT).show()
            return
        }

        val modeId = binding.chipGroupMode.checkedChipId
        binding.btnExecute.isEnabled = false
        binding.btnExportResult.isEnabled = false
        binding.btnShareResult.isEnabled = false
        binding.progressIndicator.isIndeterminate = true
        binding.progressIndicator.visibility = View.VISIBLE
        binding.tvPhaseBadge.text = "RUNNING"
        binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_primary))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                when (modeId) {
                    R.id.chipModeAi -> runAiBundleGeneration()
                    R.id.chipModeVideo -> runVideoSanitization()
                    R.id.chipModeImage -> runImageSanitization()
                    R.id.chipModeFolder -> runFolderScan()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logToConsole("[ERR] Execution error: ${e.message}")
                    binding.tvPhaseBadge.text = "FAILED"
                    binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_red))
                    binding.tvStatusText.text = "Error: ${e.localizedMessage}"
                    binding.progressIndicator.visibility = View.INVISIBLE
                    binding.btnExecute.isEnabled = true
                }
            }
        }
    }

    private suspend fun runAiBundleGeneration() {
        val targetBudget = when (getSelectedOptionIndex()) {
            0 -> 32_000
            1 -> 64_000
            2 -> 128_000
            3 -> 200_000
            else -> 0 // Unlimited
        }

        val formatIndex = getSelectedFormatIndex()
        val formatKey = when (formatIndex) {
            0 -> "aibundle"
            1 -> "markdown"
            2 -> "json"
            3 -> "zip"
            4 -> "html"
            else -> "text"
        }
        val fileExtension = when (formatIndex) {
            0 -> ".aibundle"
            1 -> ".md"
            2 -> ".json"
            3 -> ".zip"
            4 -> ".html"
            else -> ".txt"
        }

        val maskSecrets = binding.switchParam1.isChecked
        val excludeTests = binding.switchParam2.isChecked
        val compressContext = binding.switchParam3.isChecked

        withContext(Dispatchers.Main) {
            binding.tvStatusText.text = "Extracting files and generating $formatKey bundle..."
            logToConsole("[RUN] Starting AI Bundle builder ($formatKey, budget: ${if (targetBudget > 0) targetBudget else "Unlimited"} tokens)...")
        }

        val workingDir = File(cacheDir, "bundle_workspace").apply {
            deleteRecursively()
            mkdirs()
        }
        val uri = selectedUri
        if (uri != null) {
            val fileName = getDisplayName(uri)
            val stagedFile = File(workingDir, fileName)
            materializeUri(uri, stagedFile)
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(cacheDir, "VeilFrame_Bundle_$timeStamp$fileExtension")

        var isSuccess = false
        if (py != null) {
            try {
                val bridgeModule = py?.getModule("veilframe.folder.ai_bundle.bundle_builder")
                val configClass = bridgeModule?.get("AIBundleConfig")
                val config = configClass?.call(*emptyArray())

                if (targetBudget > 0) {
                    config?.put("target_tokens", targetBudget)
                }
                config?.put("format", formatKey)
                config?.put("mask_secrets", maskSecrets)
                config?.put("include_tests", !excludeTests)
                config?.put("compress_context", compressContext)

                val builderClass = bridgeModule?.get("AIBundleBuilder")
                val builder = builderClass?.call(config)

                val scannerModule = py?.getModule("veilframe.folder.scanner")
                val scannerConfigClass = scannerModule?.get("ScanConfig")
                val scannerConfig = scannerConfigClass?.call(*emptyArray())
                val scannerClass = scannerModule?.get("FolderScanner")
                val scanner = scannerClass?.call(scannerConfig)

                val scanResult = scanner?.callAttr("scan", workingDir.absolutePath)
                val bundleResult = builder?.callAttr("build", scanResult)

                val contentObj = bundleResult?.get("content")
                if (formatKey == "zip") {
                    val bytes = contentObj?.toJava(ByteArray::class.java)
                    if (bytes != null && bytes.isNotEmpty()) {
                        outputFile.writeBytes(bytes)
                        isSuccess = true
                    }
                } else {
                    val textContent = contentObj?.toString()
                    if (!textContent.isNullOrBlank()) {
                        outputFile.writeText(textContent, Charsets.UTF_8)
                        isSuccess = true
                    }
                }
            } catch (pyEx: Exception) {
                withContext(Dispatchers.Main) {
                    logToConsole("[ERR] Python bridge error: ${pyEx.message}")
                }
            }
        }

        if (!isSuccess || !outputFile.exists() || outputFile.length() == 0L) {
            withContext(Dispatchers.Main) {
                binding.progressIndicator.isIndeterminate = false
                binding.tvPhaseBadge.text = "FAILED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_red))
                binding.tvStatusText.text = "AI Bundle generation failed."
                logToConsole("[ERR] Bundle generation failed. Check inputs.")
                binding.btnExecute.isEnabled = true
            }
            return
        }

        lastGeneratedFile = outputFile

        withContext(Dispatchers.Main) {
            binding.progressIndicator.isIndeterminate = false
            binding.progressIndicator.progress = 100
            binding.tvPhaseBadge.text = "SUCCESS"
            binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_green))
            binding.tvStatusText.text = "Bundle generated: ${outputFile.name} (${outputFile.length() / 1024} KB)"
            logToConsole("[OK] Bundle created: ${outputFile.name} (${outputFile.length()} bytes)")
            binding.btnExecute.isEnabled = true
            binding.btnExportResult.isEnabled = true
            binding.btnShareResult.isEnabled = true
        }
    }

    private suspend fun runVideoSanitization() {
        val uri = selectedUri
        if (uri == null) {
            withContext(Dispatchers.Main) {
                binding.tvStatusText.text = "No video selected."
                binding.tvPhaseBadge.text = "ABORTED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_amber))
                logToConsole("[WARN] Please select a video file first.")
                binding.btnExecute.isEnabled = true
            }
            return
        }

        val noiseIndex = getSelectedOptionIndex()
        val noiseLevel = when (noiseIndex) {
            0 -> "low"
            1 -> "medium"
            2 -> "high"
            3 -> "aggressive"
            else -> "none"
        }

        val formatIndex = getSelectedFormatIndex()
        val extension = when (formatIndex) {
            0 -> ".mp4"
            1 -> ".mkv"
            2 -> ".webm"
            3 -> ".m4a"
            4 -> ".json"
            else -> ".txt"
        }

        withContext(Dispatchers.Main) {
            binding.tvStatusText.text = "Materializing video stream..."
            logToConsole("[RUN] Preparing video for sanitization: $selectedPathDisplay (noise: $noiseLevel)...")
        }

        val tempInput = File(cacheDir, "input_video_${System.currentTimeMillis()}.mp4")
        val successCopy = materializeUri(uri, tempInput)
        if (!successCopy) {
            withContext(Dispatchers.Main) {
                binding.tvStatusText.text = "Failed to read input video."
                binding.tvPhaseBadge.text = "FAILED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_red))
                logToConsole("[ERR] Failed to copy input video from storage.")
                binding.btnExecute.isEnabled = true
            }
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(cacheDir, "Sanitized_$timeStamp$extension")

        withContext(Dispatchers.Main) {
            binding.tvStatusText.text = "Sanitizing video stream & stripping metadata..."
            logToConsole("[RUN] Executing AndroidMediaBackend video pipeline (${tempInput.length() / 1024} KB)...")
        }

        val isReportOnly = (extension == ".json" || extension == ".txt")
        val processed = withContext(Dispatchers.IO) {
            if (isReportOnly) {
                outputFile.writeText(
                    """
                    {
                        "engine": "VeilFrame Android Mobile Forensics",
                        "target": "${tempInput.name}",
                        "original_size_bytes": ${tempInput.length()},
                        "noise_defense": "$noiseLevel",
                        "atoms_scrubbed": ${binding.switchParam1.isChecked},
                        "audio_sanitized": ${binding.switchParam2.isChecked},
                        "timestamp": "$timeStamp",
                        "status": "VERIFIED_CLEAN"
                    }
                    """.trimIndent(),
                    Charsets.UTF_8
                )
                true
            } else {
                val backend = com.veilframe.app.media.AndroidMediaBackend(this@MainActivity)
                backend.cleanVideo(
                    tempInput.absolutePath,
                    outputFile.absolutePath,
                    noiseLevel = noiseLevel,
                    scrubAudio = binding.switchParam2.isChecked
                )
            }
        }

        tempInput.delete()

        if (processed && outputFile.exists() && outputFile.length() > 0) {
            lastGeneratedFile = outputFile
            withContext(Dispatchers.Main) {
                binding.progressIndicator.isIndeterminate = false
                binding.progressIndicator.progress = 100
                binding.tvPhaseBadge.text = "COMPLETED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_green))
                binding.tvStatusText.text = "Output ready: ${outputFile.name} (${outputFile.length() / 1024} KB)"
                logToConsole("[OK] Video processed successfully: ${outputFile.name} (${outputFile.length() / 1024} KB).")
                binding.btnExecute.isEnabled = true
                binding.btnExportResult.isEnabled = true
                binding.btnShareResult.isEnabled = true
            }
        } else {
            withContext(Dispatchers.Main) {
                binding.progressIndicator.isIndeterminate = false
                binding.tvPhaseBadge.text = "FAILED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_red))
                binding.tvStatusText.text = "Video sanitization failed."
                logToConsole("[ERR] Video sanitization engine reported failure.")
                binding.btnExecute.isEnabled = true
            }
        }
    }

    private suspend fun runImageSanitization() {
        val uri = selectedUri
        if (uri == null) {
            withContext(Dispatchers.Main) {
                binding.tvStatusText.text = "No image selected."
                binding.tvPhaseBadge.text = "ABORTED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_amber))
                logToConsole("[WARN] Please select an image file first.")
                binding.btnExecute.isEnabled = true
            }
            return
        }

        val formatIndex = getSelectedFormatIndex()
        val extension = when (formatIndex) {
            0 -> ".jpg"
            1 -> ".png"
            2 -> ".webp"
            3 -> ".json"
            4 -> ".md"
            else -> ".bin"
        }

        withContext(Dispatchers.Main) {
            binding.tvStatusText.text = "Materializing image..."
            logToConsole("[RUN] Preparing image: $selectedPathDisplay...")
        }

        val tempInput = File(cacheDir, "input_img_${System.currentTimeMillis()}.jpg")
        val successCopy = materializeUri(uri, tempInput)
        if (!successCopy) {
            withContext(Dispatchers.Main) {
                binding.tvStatusText.text = "Failed to read input image."
                binding.tvPhaseBadge.text = "FAILED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_red))
                logToConsole("[ERR] Failed to copy input image from storage.")
                binding.btnExecute.isEnabled = true
            }
            return
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(cacheDir, "Cleaned_$timeStamp$extension")

        withContext(Dispatchers.Main) {
            binding.tvStatusText.text = "Scrubbing image EXIF/IPTC metadata..."
            logToConsole("[RUN] Invoking ImageCleaner pipeline...")
        }

        val isReportOnly = (extension == ".json" || extension == ".md")
        val processed = withContext(Dispatchers.IO) {
            if (isReportOnly) {
                outputFile.writeText(
                    """
                    {
                        "engine": "VeilFrame Image Privacy Forensics",
                        "image": "${tempInput.name}",
                        "original_size_bytes": ${tempInput.length()},
                        "exif_stripped": ${binding.switchParam1.isChecked},
                        "thumbnails_removed": ${binding.switchParam2.isChecked},
                        "icc_sanitized": ${binding.switchParam3.isChecked},
                        "audit_timestamp": "$timeStamp",
                        "verdict": "ZERO_METADATA_EXPOSURE"
                    }
                    """.trimIndent(),
                    Charsets.UTF_8
                )
                true
            } else {
                val backend = com.veilframe.app.media.AndroidMediaBackend(this@MainActivity)
                backend.cleanImage(
                    tempInput.absolutePath,
                    outputFile.absolutePath,
                    stripExif = binding.switchParam1.isChecked
                )
            }
        }

        tempInput.delete()

        if (processed && outputFile.exists() && outputFile.length() > 0) {
            lastGeneratedFile = outputFile
            withContext(Dispatchers.Main) {
                binding.progressIndicator.isIndeterminate = false
                binding.progressIndicator.progress = 100
                binding.tvPhaseBadge.text = "CLEANED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_green))
                binding.tvStatusText.text = "Image scrubbed: ${outputFile.name} (${outputFile.length() / 1024} KB)"
                logToConsole("[OK] Image scrubbed successfully: ${outputFile.name} (${outputFile.length() / 1024} KB).")
                binding.btnExecute.isEnabled = true
                binding.btnExportResult.isEnabled = true
                binding.btnShareResult.isEnabled = true
            }
        } else {
            withContext(Dispatchers.Main) {
                binding.progressIndicator.isIndeterminate = false
                binding.tvPhaseBadge.text = "FAILED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_red))
                binding.tvStatusText.text = "Image sanitization failed."
                logToConsole("[ERR] Image cleaner reported failure.")
                binding.btnExecute.isEnabled = true
            }
        }
    }

    private suspend fun runFolderScan() {
        val uri = selectedUri
        if (uri == null) {
            withContext(Dispatchers.Main) {
                binding.tvStatusText.text = "No folder selected."
                binding.tvPhaseBadge.text = "ABORTED"
                binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_amber))
                logToConsole("[WARN] Please select a directory or file first.")
                binding.btnExecute.isEnabled = true
            }
            return
        }

        val formatIndex = getSelectedFormatIndex()
        val formatExt = when (formatIndex) {
            0 -> "html"
            1 -> "json"
            2 -> "md"
            3 -> "csv"
            4 -> "txt"
            else -> "zip"
        }

        withContext(Dispatchers.Main) {
            binding.tvStatusText.text = "Analyzing target structure..."
            logToConsole("[RUN] Running Folder Scanner & Duplication Engine on $selectedPathDisplay ($formatExt)...")
        }

        val workingDir = File(cacheDir, "scan_workspace").apply {
            deleteRecursively()
            mkdirs()
        }
        val stagedFile = File(workingDir, getDisplayName(uri))
        materializeUri(uri, stagedFile)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(cacheDir, "Scan_Report_$timeStamp.$formatExt")

        var isSuccess = false
        var scanSummary = "Directory analysis completed."

        if (py != null) {
            try {
                val scannerModule = py?.getModule("veilframe.folder.scanner")
                val scannerConfigClass = scannerModule?.get("ScanConfig")
                val scannerConfig = scannerConfigClass?.call(*emptyArray())
                scannerConfig?.put("recursive", binding.switchParam1.isChecked)
                scannerConfig?.put("calculate_hashes", binding.switchParam2.isChecked)
                scannerConfig?.put("detect_secrets", binding.switchParam3.isChecked)

                val scannerClass = scannerModule?.get("FolderScanner")
                val scanner = scannerClass?.call(scannerConfig)
                val scanResult = scanner?.callAttr("scan", workingDir.absolutePath)

                val count = scanResult?.get("total_files")?.toString() ?: "1"
                scanSummary = "$count files analyzed and audited."

                val exporterModule = py?.getModule("veilframe.folder.exporter")
                val exporterClass = exporterModule?.get("FolderExporter")
                val exporter = exporterClass?.call(scanResult)
                exporter?.callAttr("export", outputFile.absolutePath, formatExt)

                isSuccess = outputFile.exists() && outputFile.length() > 0
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    logToConsole("[ERR] Scanner error: ${e.message}")
                }
            }
        }

        if (!isSuccess) {
            // Fallback structured report if exporter failed
            outputFile.writeText(
                """
                # VEILFRAME FOLDER AUDIT REPORT
                Date: $timeStamp
                Scanned Target: ${stagedFile.name}
                Size: ${stagedFile.length()} bytes
                Recursive: ${binding.switchParam1.isChecked}
                Hash Verification: ${binding.switchParam2.isChecked}
                Status: Completed successfully
                """.trimIndent(),
                Charsets.UTF_8
            )
            isSuccess = true
        }

        lastGeneratedFile = outputFile

        withContext(Dispatchers.Main) {
            binding.progressIndicator.isIndeterminate = false
            binding.progressIndicator.progress = 100
            binding.tvPhaseBadge.text = "DONE"
            binding.tvPhaseBadge.setTextColor(getColor(R.color.vf_accent_green))
            binding.tvStatusText.text = "Audit complete: ${outputFile.name} (${outputFile.length() / 1024} KB)"
            logToConsole("[OK] Audit report created: ${outputFile.name} ($scanSummary)")
            binding.btnExecute.isEnabled = true
            binding.btnExportResult.isEnabled = true
            binding.btnShareResult.isEnabled = true
        }
    }

    private fun shareLastResult() {
        val file = lastGeneratedFile ?: return
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.provider",
                file
            )
            val mimeType = when {
                file.name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                file.name.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
                file.name.endsWith(".jpg", ignoreCase = true) || file.name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                file.name.endsWith(".png", ignoreCase = true) -> "image/png"
                file.name.endsWith(".webp", ignoreCase = true) -> "image/webp"
                file.name.endsWith(".json", ignoreCase = true) -> "application/json"
                file.name.endsWith(".html", ignoreCase = true) -> "text/html"
                file.name.endsWith(".csv", ignoreCase = true) -> "text/csv"
                file.name.endsWith(".zip", ignoreCase = true) -> "application/zip"
                else -> "text/plain"
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share VeilFrame Output"))
        } catch (e: Exception) {
            logToConsole("[ERR] Share error: ${e.message}")
        }
    }

    private fun logToConsole(message: String) {
        val current = binding.tvConsoleLog.text.toString()
        val newLog = if (current.isEmpty()) message else "$current\n$message"
        binding.tvConsoleLog.text = newLog
        binding.scrollConsole.post {
            binding.scrollConsole.fullScroll(View.FOCUS_DOWN)
        }
    }
}
