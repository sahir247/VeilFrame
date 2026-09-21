package com.veilframe.app.tools

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.storage.SafStorageManager
import com.veilframe.app.ui.motion.ExpressiveMotion

/**
 * Manages per-tool session states, parameter configuration chips,
 * options switches, target card binding, and privacy impact summaries.
 */
class ToolSessionManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val safStorageManager: SafStorageManager,
    private val onLog: (String) -> Unit,
    private val onOpenFilePicker: () -> Unit
) {
    // Persistent tool session states with smart defaults
    val toolStates: MutableMap<ToolMode, ToolSessionState> = mutableMapOf(
        ToolMode.AI_BUNDLE to ToolSessionState(
            primaryOptionIndex = 1, // 64K
            formatOptionIndex = 0,  // .aibundle by default!
            switch1Checked = true,  // Mask secrets
            switch2Checked = true,  // Exclude tests
            switch3Checked = true   // Compress manifests
        ),
        ToolMode.VIDEO_CLEANER to ToolSessionState(
            primaryOptionIndex = 0, // Standard
            formatOptionIndex = 0,  // Original / Auto
            switch1Checked = true,  // Strip EXIF
            switch2Checked = false, // Audio stripping OFF by default!
            switch3Checked = true   // Re-encode bitstream
        ),
        ToolMode.IMAGE_CLEANER to ToolSessionState(
            primaryOptionIndex = 0, // Standard 95%
            formatOptionIndex = 0,  // Original / Auto
            switch1Checked = true,  // Strip EXIF
            switch2Checked = true,  // Remove thumbnails
            switch3Checked = false  // Sanitize ICC
        ),
        ToolMode.FOLDER_SCANNER to ToolSessionState(
            primaryOptionIndex = 0, // Quick Audit
            formatOptionIndex = 0,  // HTML
            switch1Checked = true,  // Recursive scan
            switch2Checked = false, // SHA-256 OFF by default
            switch3Checked = false  // Secret detection OFF in quick audit
        ),
        ToolMode.IMAGE_COMPRESSOR to ToolSessionState(
            primaryOptionIndex = 0,
            formatOptionIndex = 0
        ),
        ToolMode.VIDEO_COMPRESSOR to ToolSessionState(
            primaryOptionIndex = 0,
            formatOptionIndex = 0
        ),
        ToolMode.IMAGE_UPSCALER to ToolSessionState(
            primaryOptionIndex = 0,
            formatOptionIndex = 0
        )
    )

    var currentToolMode: ToolMode = ToolMode.AI_BUNDLE

    val currentState: ToolSessionState
        get() = toolStates.getOrPut(currentToolMode) { ToolSessionState() }

    fun init() {
        binding.chipGroupPrimaryOptions.setOnCheckedStateChangeListener { _, _ ->
            currentState.primaryOptionIndex = getSelectedOptionIndex()
            updatePrivacySummaryUI()
        }
        binding.chipGroupFormat.setOnCheckedStateChangeListener { _, _ ->
            currentState.formatOptionIndex = getSelectedFormatIndex()
            updatePrivacySummaryUI()
        }
        binding.switchOption1.setOnCheckedChangeListener { _, isChecked ->
            currentState.switch1Checked = isChecked
            updatePrivacySummaryUI()
        }
        binding.switchOption2.setOnCheckedChangeListener { _, isChecked ->
            currentState.switch2Checked = isChecked
            updatePrivacySummaryUI()
        }
        binding.switchOption3.setOnCheckedChangeListener { _, isChecked ->
            currentState.switch3Checked = isChecked
            updatePrivacySummaryUI()
        }
        binding.sliderToolIntensity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                binding.sliderToolIntensity.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            currentState.sliderValue = value
            if (currentToolMode == ToolMode.IMAGE_CLEANER) {
                binding.tvToolSliderValue.text = "${value.toInt()}%"
            } else if (currentToolMode == ToolMode.VIDEO_CLEANER) {
                binding.tvToolSliderValue.text = "CRF ${value.toInt()}"
            }
        }

        ExpressiveMotion.applyTouchBounce(binding.btnPickFolder)
        ExpressiveMotion.applyTouchBounce(binding.btnPickFile)
        ExpressiveMotion.applyTouchBounce(binding.btnChangeTarget)
        ExpressiveMotion.applyTouchBounce(binding.btnClearTarget)
        ExpressiveMotion.applyTouchBounce(binding.btnExecute)
        ExpressiveMotion.applyTouchBounce(binding.btnExportResult)
        ExpressiveMotion.applyTouchBounce(binding.btnShareResult)
        ExpressiveMotion.applyTouchBounce(binding.btnResultSave)
        ExpressiveMotion.applyTouchBounce(binding.btnResultShare)
    }

    fun saveCurrentToolState() {
        val state = currentState
        state.primaryOptionIndex = getSelectedOptionIndex()
        state.formatOptionIndex = getSelectedFormatIndex()
        state.switch1Checked = binding.switchOption1.isChecked
        state.switch2Checked = binding.switchOption2.isChecked
        state.switch3Checked = binding.switchOption3.isChecked
        state.sliderValue = binding.sliderToolIntensity.value
        state.consoleLogs = binding.tvConsoleLog.text.toString()

        val prefs = activity.getSharedPreferences("veilframe_tool_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("${currentToolMode.name}_primary", state.primaryOptionIndex)
            .putInt("${currentToolMode.name}_format", state.formatOptionIndex)
            .putBoolean("${currentToolMode.name}_switch1", state.switch1Checked)
            .putBoolean("${currentToolMode.name}_switch2", state.switch2Checked)
            .putBoolean("${currentToolMode.name}_switch3", state.switch3Checked)
            .putFloat("${currentToolMode.name}_slider", state.sliderValue)
            .apply()
    }

    fun restoreToolState(state: ToolSessionState) {
        val prefs = activity.getSharedPreferences("veilframe_tool_prefs", Context.MODE_PRIVATE)
        if (prefs.contains("${currentToolMode.name}_primary")) {
            state.primaryOptionIndex = prefs.getInt("${currentToolMode.name}_primary", state.primaryOptionIndex)
            state.formatOptionIndex = prefs.getInt("${currentToolMode.name}_format", state.formatOptionIndex)
            state.switch1Checked = prefs.getBoolean("${currentToolMode.name}_switch1", state.switch1Checked)
            state.switch2Checked = prefs.getBoolean("${currentToolMode.name}_switch2", state.switch2Checked)
            state.switch3Checked = prefs.getBoolean("${currentToolMode.name}_switch3", state.switch3Checked)
            state.sliderValue = prefs.getFloat("${currentToolMode.name}_slider", state.sliderValue)
        }

        // Restore Target Card (Empty vs Mounted state)
        updateTargetCardUI(state)

        // Restore Options
        selectChipByIndex(binding.chipGroupPrimaryOptions, state.primaryOptionIndex)
        selectChipByIndex(binding.chipGroupFormat, state.formatOptionIndex)
        binding.switchOption1.isChecked = state.switch1Checked
        binding.switchOption2.isChecked = state.switch2Checked
        binding.switchOption3.isChecked = state.switch3Checked

        if (currentToolMode == ToolMode.IMAGE_CLEANER) {
            val clamped = state.sliderValue.coerceIn(50f, 100f)
            binding.sliderToolIntensity.value = clamped
            binding.tvToolSliderValue.text = "${clamped.toInt()}%"
        } else if (currentToolMode == ToolMode.VIDEO_CLEANER) {
            val clamped = state.sliderValue.coerceIn(18f, 36f)
            binding.sliderToolIntensity.value = clamped
            binding.tvToolSliderValue.text = "CRF ${clamped.toInt()}"
        }

        // Restore Privacy Summary
        updatePrivacySummaryUI()

        // Restore Primary Action Dock
        updatePrimaryActionDock(state)

        // Restore Telemetry & JobState
        updateJobState(state.jobState, state.statusMessage)
        binding.tvConsoleLog.text = state.consoleLogs
        binding.progressIndicator.progress = state.progressPercent
        binding.tvProgressDetails.text = state.progressDetailsText

        // Restore Result Card & Dock
        if (state.jobState == JobState.COMPLETE && state.lastGeneratedFile != null && state.lastGeneratedFile!!.exists()) {
            val file = state.lastGeneratedFile!!
            binding.cardResultSummary.visibility = View.VISIBLE
            binding.tvResultTitle.text = file.name
            binding.tvResultDetails.text = "${safStorageManager.formatBytes(file.length())} • ${file.extension.uppercase()} • Output ready in local cache"
            binding.btnExportResult.isEnabled = true
            binding.btnShareResult.isEnabled = true
            val isMarkdown = file.extension.equals("md", ignoreCase = true) || file.extension.equals("markdown", ignoreCase = true)
            binding.btnResultPreview.visibility = if (isMarkdown) View.VISIBLE else View.GONE
        } else {
            binding.cardResultSummary.visibility = View.GONE
            binding.btnResultPreview.visibility = View.GONE
            binding.btnExportResult.isEnabled = false
            binding.btnShareResult.isEnabled = false
        }
    }

    fun selectChipByIndex(group: ChipGroup, index: Int) {
        if (index in 0 until group.childCount) {
            val chip = group.getChildAt(index) as? Chip
            chip?.isChecked = true
        }
    }

    fun getToolTitle(mode: ToolMode): String = when (mode) {
        ToolMode.AI_BUNDLE -> "AI BUNDLE"
        ToolMode.VIDEO_CLEANER -> "VIDEO CLEANER"
        ToolMode.IMAGE_CLEANER -> "IMAGE CLEANER"
        ToolMode.FOLDER_SCANNER -> "FOLDER SCANNER"
        ToolMode.IMAGE_COMPRESSOR -> "IMAGE STUDIO"
        ToolMode.VIDEO_COMPRESSOR -> "VIDEO STUDIO"
        ToolMode.IMAGE_UPSCALER -> "IMAGE UPSCALER"
    }

    fun getToolExecuteText(mode: ToolMode): String = when (mode) {
        ToolMode.AI_BUNDLE -> "GENERATE AI BUNDLE"
        ToolMode.VIDEO_CLEANER -> "SANITIZE VIDEO"
        ToolMode.IMAGE_CLEANER -> "SCRUB IMAGE METADATA"
        ToolMode.FOLDER_SCANNER -> "START FORENSIC AUDIT"
        ToolMode.IMAGE_COMPRESSOR -> "COMPRESS IMAGE"
        ToolMode.VIDEO_COMPRESSOR -> "COMPRESS VIDEO"
        ToolMode.IMAGE_UPSCALER -> "UPSCALE IMAGE"
    }

    fun configureToolUI(mode: ToolMode) {
        currentToolMode = mode
        when (mode) {
            ToolMode.AI_BUNDLE -> {
                binding.layoutToolSlider.visibility = View.GONE
                binding.tvToolTitle.text = "AI BUNDLE"
                binding.tvToolSubtitle.text = "Package source code into LLM-ready context bundles"
                binding.btnPickFolder.text = "Project Folder"
                binding.btnPickFile.text = "Single File"

                configureOptions(
                    paramHeader = "AI BUNDLE CONFIGURATION",
                    primaryLabel = "Token Budget",
                    primaryDesc = "Target context window limit for LLM prompt ingestion",
                    primaryChips = listOf("32K", "64K", "128K", "200K", "Unlimited"),
                    primaryDefaultIndex = 1,
                    formatLabel = "Bundle Format",
                    formatDesc = "Output archive extension and structured packaging",
                    formatChips = listOf(".aibundle", "Markdown (.md)", "JSON (.json)"),
                    formatDefaultIndex = 0,
                    switch1Title = "Mask Leaked Secrets & API Keys",
                    switch1Desc = "Redact passwords, AWS/OpenAI keys, and sensitive tokens",
                    switch1Checked = true,
                    switch2Title = "Exclude Test Suites & Fixtures",
                    switch2Desc = "Omit test suites, mocks, and heavy fixtures from context",
                    switch2Checked = true,
                    switch3Title = "Compress Dependency Manifests",
                    switch3Desc = "Condense package-lock, poetry.lock, and cargo.lock files",
                    switch3Checked = true,
                    executeText = "GENERATE AI BUNDLE"
                )
            }
            ToolMode.VIDEO_CLEANER -> {
                binding.tvToolTitle.text = "VIDEO CLEANER"
                binding.tvToolSubtitle.text = "Remove forensic identifiers & camera sensor noise"
                binding.btnPickFolder.text = "Batch Folder"
                binding.btnPickFile.text = "Single Video"

                binding.layoutToolSlider.visibility = View.VISIBLE
                binding.tvToolSliderTitle.text = "CRF COMPRESSION FACTOR (LOWER = HIGHER QUALITY)"
                binding.sliderToolIntensity.valueFrom = 18f
                binding.sliderToolIntensity.valueTo = 36f
                binding.sliderToolIntensity.stepSize = 1f
                val clamped = currentState.sliderValue.coerceIn(18f, 36f)
                val initialVal = if (currentState.sliderValue < 18f || currentState.sliderValue > 36f) 23f else clamped
                currentState.sliderValue = initialVal
                binding.sliderToolIntensity.value = initialVal
                binding.tvToolSliderValue.text = "CRF ${initialVal.toInt()}"

                configureOptions(
                    paramHeader = "VIDEO PRIVACY PARAMETERS",
                    primaryLabel = "Sensor Fingerprint Protection",
                    primaryDesc = "Mitigate camera sensor pattern noise (PRNU forensic defense)",
                    primaryChips = listOf("Standard", "High", "Stealth", "None"),
                    primaryDefaultIndex = 0,
                    formatLabel = "Container Format",
                    formatDesc = "Output video container encoding",
                    formatChips = listOf("Original / Auto", "MP4 (.mp4)", "MKV (.mkv)", "WebM (.webm)"),
                    formatDefaultIndex = 0,
                    switch1Title = "Strip Location & Camera EXIF",
                    switch1Desc = "Removes GPS coordinates, device serials, and timestamps",
                    switch1Checked = true,
                    switch2Title = "Sanitize Audio Metadata & Tags",
                    switch2Desc = "Audio stripping OFF by default (preserves original audio)",
                    switch2Checked = false,
                    switch3Title = "Re-encode Bitstream (Watermark Defense)",
                    switch3Desc = "Repacks video bitstream while preserving source original",
                    switch3Checked = true,
                    executeText = "SANITIZE VIDEO"
                )
            }
            ToolMode.IMAGE_CLEANER -> {
                binding.tvToolTitle.text = "IMAGE CLEANER"
                binding.tvToolSubtitle.text = "Strip metadata, camera maker notes, and trace artifacts"
                binding.btnPickFolder.text = "Batch Folder"
                binding.btnPickFile.text = "Single Image"

                binding.layoutToolSlider.visibility = View.VISIBLE
                binding.tvToolSliderTitle.text = "IMAGE FIDELITY & QUALITY"
                binding.sliderToolIntensity.valueFrom = 50f
                binding.sliderToolIntensity.valueTo = 100f
                binding.sliderToolIntensity.stepSize = 5f
                val clamped = currentState.sliderValue.coerceIn(50f, 100f)
                val initialVal = if (clamped < 50f) 95f else clamped
                currentState.sliderValue = initialVal
                binding.sliderToolIntensity.value = initialVal
                binding.tvToolSliderValue.text = "${initialVal.toInt()}%"

                configureOptions(
                    paramHeader = "IMAGE PRIVACY PARAMETERS",
                    primaryLabel = "Privacy / Quality Fidelity",
                    primaryDesc = "Compression ratio balance while scrubbing forensic traces",
                    primaryChips = listOf("Standard (95%)", "High (90%)", "Aggressive (85%)"),
                    primaryDefaultIndex = 0,
                    formatLabel = "Image Format",
                    formatDesc = "Output image encoding and color profile",
                    formatChips = listOf("Original / Auto", "JPEG (.jpg)", "PNG (.png)", "WebP (.webp)", "BMP (.bmp)", "TIFF (.tiff)", "GIF (.gif)", "HEIF (.heif)"),
                    formatDefaultIndex = 0,
                    switch1Title = "Strip EXIF, GPS & Camera Maker Notes",
                    switch1Desc = "Eliminates location, aperture, camera serials, and dates",
                    switch1Checked = true,
                    switch2Title = "Remove Embedded Thumbnails",
                    switch2Desc = "Purges uncompressed embedded preview thumbnails and caches",
                    switch2Checked = true,
                    switch3Title = "Sanitize ICC Color Profile Metadata",
                    switch3Desc = "Strips proprietary tags while preserving standard sRGB color",
                    switch3Checked = false,
                    executeText = "SCRUB IMAGE METADATA"
                )
            }
            ToolMode.FOLDER_SCANNER -> {
                binding.layoutToolSlider.visibility = View.GONE
                binding.tvToolTitle.text = "FOLDER SCANNER"
                binding.tvToolSubtitle.text = "Perform structural directory audits, secret scans & duplicate hunts"
                binding.btnPickFolder.text = "Select Folder"
                binding.btnPickFile.text = "Select File"

                configureOptions(
                    paramHeader = "FOLDER AUDIT PARAMETERS",
                    primaryLabel = "Scan Mode",
                    primaryDesc = "Task-driven forensic inspection and analysis mode",
                    primaryChips = listOf("Quick Audit", "Deep Forensic", "Duplicate Hunt"),
                    primaryDefaultIndex = 0,
                    formatLabel = "Report Format",
                    formatDesc = "Multi-format report export for forensic audit findings",
                    formatChips = listOf("HTML (.html)", "JSON (.json)", "Markdown (.md)", "CSV (.csv)", "TXT (.txt)", "ZIP (.zip)"),
                    formatDefaultIndex = 0,
                    switch1Title = "Scan Recursive Subdirectories",
                    switch1Desc = "Traverse all nested folders and subprojects",
                    switch1Checked = true,
                    switch2Title = "Calculate SHA-256 Hashing",
                    switch2Desc = "OFF by default (saves CPU & battery on large projects)",
                    switch2Checked = false,
                    switch3Title = "Detect Leaked Secrets & API Keys",
                    switch3Desc = "Scan bitstreams for high-entropy tokens and credentials",
                    switch3Checked = false,
                    executeText = "START FORENSIC AUDIT",
                    onPrimaryChipSelected = { selectedIndex ->
                        when (selectedIndex) {
                            0 -> { // Quick Audit
                                binding.switchOption1.isChecked = true
                                binding.switchOption2.isChecked = false
                                binding.switchOption3.isChecked = false
                            }
                            1 -> { // Deep Forensic
                                binding.switchOption1.isChecked = true
                                binding.switchOption2.isChecked = true
                                binding.switchOption3.isChecked = true
                            }
                            2 -> { // Duplicate Hunt
                                binding.switchOption1.isChecked = true
                                binding.switchOption2.isChecked = true
                                binding.switchOption3.isChecked = false
                            }
                        }
                    }
                )
            }
            ToolMode.IMAGE_COMPRESSOR,
            ToolMode.VIDEO_COMPRESSOR,
            ToolMode.IMAGE_UPSCALER -> {
                // Media Studio workspaces use dedicated layouts
            }
        }
    }

    private fun createChipBackgroundStateList(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                activity.getColor(R.color.vf_primary),
                activity.getColor(R.color.vf_surface_variant)
            )
        )
    }

    private fun createChipTextStateList(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                activity.getColor(R.color.vf_on_primary),
                activity.getColor(R.color.vf_text_primary)
            )
        )
    }

    private fun createChipStrokeStateList(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                activity.getColor(R.color.vf_primary),
                activity.getColor(R.color.vf_surface_stroke)
            )
        )
    }

    private fun createSwitchThumbStateList(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                activity.getColor(R.color.vf_primary),
                activity.getColor(R.color.vf_text_muted)
            )
        )
    }

    private fun createSwitchTrackStateList(): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            ),
            intArrayOf(
                activity.getColor(R.color.vf_primary_container),
                activity.getColor(R.color.vf_surface_variant)
            )
        )
    }

    fun configureOptions(
        paramHeader: String,
        primaryLabel: String,
        primaryDesc: String,
        primaryChips: List<String>,
        primaryDefaultIndex: Int,
        formatLabel: String,
        formatDesc: String,
        formatChips: List<String>,
        formatDefaultIndex: Int,
        switch1Title: String,
        switch1Desc: String,
        switch1Checked: Boolean,
        switch2Title: String,
        switch2Desc: String,
        switch2Checked: Boolean,
        switch3Title: String,
        switch3Desc: String,
        switch3Checked: Boolean,
        executeText: String,
        onPrimaryChipSelected: ((Int) -> Unit)? = null
    ) {
        binding.tvParamHeader.text = paramHeader
        binding.tvPrimaryOptionTitle.text = primaryLabel
        binding.tvPrimaryOptionDesc.text = primaryDesc

        binding.chipGroupPrimaryOptions.removeAllViews()
        primaryChips.forEachIndexed { index, title ->
            val chip = Chip(activity).apply {
                text = title
                isCheckable = true
                isChecked = (index == primaryDefaultIndex)
                chipBackgroundColor = createChipBackgroundStateList()
                setTextColor(createChipTextStateList())
                chipStrokeColor = createChipStrokeStateList()
                chipStrokeWidth = resources.displayMetrics.density * 1.5f
                isCheckedIconVisible = true
                checkedIconTint = createChipTextStateList()
                textSize = 12f
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(resources.displayMetrics.density * 18f)
                    .build()
                ExpressiveMotion.applyTouchBounce(this)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        onPrimaryChipSelected?.invoke(index)
                    }
                }
            }
            binding.chipGroupPrimaryOptions.addView(chip)
        }

        binding.tvFormatOptionTitle.text = formatLabel
        binding.tvFormatOptionDesc.text = formatDesc

        binding.chipGroupFormat.removeAllViews()
        formatChips.forEachIndexed { index, format ->
            val chip = Chip(activity).apply {
                text = format
                isCheckable = true
                isChecked = (index == formatDefaultIndex)
                chipBackgroundColor = createChipBackgroundStateList()
                setTextColor(createChipTextStateList())
                chipStrokeColor = createChipStrokeStateList()
                chipStrokeWidth = resources.displayMetrics.density * 1.5f
                isCheckedIconVisible = true
                checkedIconTint = createChipTextStateList()
                textSize = 12f
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(resources.displayMetrics.density * 18f)
                    .build()
                ExpressiveMotion.applyTouchBounce(this)
            }
            binding.chipGroupFormat.addView(chip)
        }

        binding.tvSwitch1Title.text = switch1Title
        binding.tvSwitch1Desc.text = switch1Desc
        binding.switchOption1.isChecked = switch1Checked
        binding.switchOption1.thumbTintList = createSwitchThumbStateList()
        binding.switchOption1.trackTintList = createSwitchTrackStateList()

        binding.tvSwitch2Title.text = switch2Title
        binding.tvSwitch2Desc.text = switch2Desc
        binding.switchOption2.isChecked = switch2Checked
        binding.switchOption2.thumbTintList = createSwitchThumbStateList()
        binding.switchOption2.trackTintList = createSwitchTrackStateList()

        binding.tvSwitch3Title.text = switch3Title
        binding.tvSwitch3Desc.text = switch3Desc
        binding.switchOption3.isChecked = switch3Checked
        binding.switchOption3.thumbTintList = createSwitchThumbStateList()
        binding.switchOption3.trackTintList = createSwitchTrackStateList()

        binding.btnExecute.text = executeText
    }

    fun clearSelectedTarget(logMessage: Boolean = true) {
        val state = currentState
        state.selectedUri = null
        state.selectedPathDisplay = "No file or folder selected"
        state.isFolderSelected = false
        state.targetFileCount = 0
        state.targetTotalBytes = 0L
        state.lastGeneratedFile = null

        updateTargetCardUI(state)
        updatePrivacySummaryUI()
        updatePrimaryActionDock(state)

        binding.cardResultSummary.visibility = View.GONE
        binding.btnExportResult.isEnabled = false
        binding.btnShareResult.isEnabled = false
        binding.progressIndicator.progress = 0
        binding.progressIndicator.visibility = View.INVISIBLE
        binding.tvProgressDetails.text = ""
        updateJobState(JobState.IDLE, "Ready for execution.")

        if (logMessage) {
            onLog("[TARGET] Target selection cleared.")
        }
    }

    fun clearCurrentTool() {
        clearSelectedTarget(logMessage = false)
    }

    fun updateTargetCardUI(state: ToolSessionState) {
        if (state.selectedUri != null) {
            binding.layoutTargetEmpty.visibility = View.GONE
            binding.layoutTargetMounted.visibility = View.VISIBLE
            binding.tvSelectedPath.text = state.selectedPathDisplay
            binding.tvSelectedPath.setTextColor(activity.getColor(R.color.vf_text_primary))
            binding.tvTargetDetails.text = if (state.isFolderSelected) {
                "${state.targetFileCount} files • ${safStorageManager.formatBytes(state.targetTotalBytes)} • Ready"
            } else {
                "${safStorageManager.formatBytes(state.targetTotalBytes)} • Ready"
            }
            binding.tvTargetDetails.setTextColor(activity.getColor(R.color.vf_accent_green))
        } else {
            binding.layoutTargetEmpty.visibility = View.VISIBLE
            binding.layoutTargetMounted.visibility = View.GONE
        }
    }

    fun updatePrimaryActionDock(state: ToolSessionState) {
        when (state.jobState) {
            JobState.PREPARING, JobState.SCANNING, JobState.PROCESSING, JobState.FINALIZING -> {
                binding.btnExecute.text = "CANCEL PROCESSING"
                binding.btnExecute.setIconResource(R.drawable.ic_action_clear)
                binding.btnExecute.isEnabled = true
                binding.btnExecute.alpha = 1.0f
                binding.btnExportResult.isEnabled = false
                binding.btnShareResult.isEnabled = false
            }
            JobState.COMPLETE -> {
                binding.btnExecute.text = "RUN ANOTHER TASK"
                binding.btnExecute.setIconResource(R.drawable.ic_action_play)
                binding.btnExecute.isEnabled = true
                binding.btnExecute.alpha = 1.0f
                val hasValidOutput = (state.lastGeneratedFile != null && state.lastGeneratedFile!!.exists())
                binding.btnExportResult.isEnabled = hasValidOutput
                binding.btnShareResult.isEnabled = hasValidOutput
            }
            else -> {
                binding.btnExportResult.isEnabled = false
                binding.btnShareResult.isEnabled = false
                if (state.selectedUri == null) {
                    binding.btnExecute.text = "SELECT TARGET TO BEGIN"
                    binding.btnExecute.setIconResource(R.drawable.ic_folder_pick)
                    binding.btnExecute.isEnabled = true
                    binding.btnExecute.alpha = 1.0f
                } else {
                    binding.btnExecute.text = getToolExecuteText(currentToolMode)
                    binding.btnExecute.setIconResource(R.drawable.ic_action_play)
                    binding.btnExecute.isEnabled = true
                    binding.btnExecute.alpha = 1.0f
                }
            }
        }
    }

    fun updatePrivacySummaryUI() {
        when (currentToolMode) {
            ToolMode.AI_BUNDLE -> {
                binding.tvPrivacyProfileBadge.text = "LLM PACKAGING"
                binding.tvPrivacyProfileBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                binding.tvPrivacyProfileBadge.setBackgroundResource(R.color.vf_status_pass_bg)
                binding.tvPrivacyImpact1.text = if (binding.switchOption1.isChecked) "• Sensitive passwords, OpenAI/AWS tokens & credentials masked" else "• Raw credentials unmasked (masking disabled)"
                binding.tvPrivacyImpact2.text = if (binding.switchOption2.isChecked) "• Test suites, mocks & fixtures excluded from context" else "• Full source directory included"
                binding.tvPrivacyImpact3.text = if (binding.switchOption3.isChecked) "• Dependency manifests compressed to reduce prompt tokens" else "• Manifest compression disabled"
            }
            ToolMode.VIDEO_CLEANER -> {
                val noiseLevel = when (getSelectedOptionIndex()) {
                    0 -> "Standard"
                    1 -> "High"
                    2 -> "Stealth"
                    else -> "None"
                }
                binding.tvPrivacyProfileBadge.text = "$noiseLevel DEFENSE".uppercase()
                binding.tvPrivacyProfileBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                binding.tvPrivacyProfileBadge.setBackgroundResource(R.color.vf_status_pass_bg)
                binding.tvPrivacyImpact1.text = if (binding.switchOption1.isChecked) "• Camera EXIF, GPS coordinates & device serials purged" else "• EXIF & GPS retained"
                binding.tvPrivacyImpact2.text = if (binding.switchOption2.isChecked) "• Audio stream stripped completely" else "• Audio stream preserved (metadata tags scrubbed)"
                binding.tvPrivacyImpact3.text = if (binding.switchOption3.isChecked) "• Bitstream repacked (PRNU sensor pattern noise mitigated)" else "• Stream remuxed without pixel alteration"
            }
            ToolMode.IMAGE_CLEANER -> {
                binding.tvPrivacyProfileBadge.text = "METADATA STRIP"
                binding.tvPrivacyProfileBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                binding.tvPrivacyProfileBadge.setBackgroundResource(R.color.vf_status_pass_bg)
                binding.tvPrivacyImpact1.text = if (binding.switchOption1.isChecked) "• EXIF, GPS location & camera maker notes scrubbed" else "• EXIF retained"
                binding.tvPrivacyImpact2.text = if (binding.switchOption2.isChecked) "• Embedded preview thumbnails & caches eliminated" else "• Thumbnails preserved"
                binding.tvPrivacyImpact3.text = if (binding.switchOption3.isChecked) "• ICC color profile sanitized to standard sRGB" else "• ICC profile preserved"
            }
            ToolMode.FOLDER_SCANNER -> {
                val mode = when (getSelectedOptionIndex()) {
                    0 -> "Quick Audit"
                    1 -> "Deep Forensic"
                    else -> "Duplicate Hunt"
                }
                binding.tvPrivacyProfileBadge.text = mode.uppercase()
                binding.tvPrivacyProfileBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                binding.tvPrivacyProfileBadge.setBackgroundResource(R.color.vf_status_pass_bg)
                binding.tvPrivacyImpact1.text = if (binding.switchOption1.isChecked) "• Recursive directory traversal across all subprojects" else "• Top-level directory only"
                binding.tvPrivacyImpact2.text = if (binding.switchOption2.isChecked) "• SHA-256 cryptographic hashing active" else "• SHA-256 calculation skipped (low CPU/battery)"
            }
            ToolMode.IMAGE_COMPRESSOR,
            ToolMode.VIDEO_COMPRESSOR,
            ToolMode.IMAGE_UPSCALER -> {
                binding.tvPrivacyProfileBadge.text = "MEDIA STUDIO"
                binding.tvPrivacyProfileBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                binding.tvPrivacyProfileBadge.setBackgroundResource(R.color.vf_status_pass_bg)
                binding.tvPrivacyImpact1.text = "• Visual optimization & size reduction"
                binding.tvPrivacyImpact2.text = "• Metadata scrubbing & privacy protection"
                binding.tvPrivacyImpact3.text = "• Target platform profile matching"
            }
        }
    }

    fun updateJobState(state: JobState, statusMessage: String = "") {
        val toolState = currentState
        toolState.jobState = state
        if (statusMessage.isNotEmpty()) {
            toolState.statusMessage = statusMessage
            binding.tvStatusText.text = statusMessage
        }

        binding.tvPhaseBadge.text = state.name
        binding.tvToolStatusBadge.text = state.name

        when (state) {
            JobState.IDLE -> {
                binding.tvPhaseBadge.setTextColor(activity.getColor(R.color.vf_secondary))
                binding.tvPhaseBadge.setBackgroundResource(R.color.vf_surface_variant)
                binding.tvToolStatusBadge.setTextColor(activity.getColor(R.color.vf_secondary))
            }
            JobState.PREPARING, JobState.SCANNING, JobState.PROCESSING, JobState.FINALIZING -> {
                binding.tvPhaseBadge.setTextColor(activity.getColor(R.color.vf_accent_amber))
                binding.tvPhaseBadge.setBackgroundResource(R.color.vf_status_warn_bg)
                binding.tvToolStatusBadge.setTextColor(activity.getColor(R.color.vf_accent_amber))
            }
            JobState.COMPLETE -> {
                binding.tvPhaseBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                binding.tvPhaseBadge.setBackgroundResource(R.color.vf_status_pass_bg)
                binding.tvToolStatusBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
                ExpressiveMotion.playJellyBounce(binding.btnExecute)
            }
            JobState.FAILED, JobState.CANCELLED -> {
                binding.tvPhaseBadge.setTextColor(activity.getColor(R.color.vf_accent_red))
                binding.tvPhaseBadge.setBackgroundResource(R.color.vf_status_fail_bg)
                binding.tvToolStatusBadge.setTextColor(activity.getColor(R.color.vf_accent_red))
            }
        }
    }

    fun getSelectedOptionIndex(): Int {
        val checkedId = binding.chipGroupPrimaryOptions.checkedChipId
        if (checkedId == View.NO_ID) return 0
        val chip = binding.chipGroupPrimaryOptions.findViewById<Chip>(checkedId)
        return binding.chipGroupPrimaryOptions.indexOfChild(chip).coerceAtLeast(0)
    }

    fun getSelectedFormatIndex(): Int {
        val checkedId = binding.chipGroupFormat.checkedChipId
        if (checkedId == View.NO_ID) return 0
        val chip = binding.chipGroupFormat.findViewById<Chip>(checkedId)
        return binding.chipGroupFormat.indexOfChild(chip).coerceAtLeast(0)
    }
}
