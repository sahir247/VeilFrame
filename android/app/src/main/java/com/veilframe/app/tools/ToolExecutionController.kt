package com.veilframe.app.tools

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.databinding.*
import com.veilframe.app.media.AndroidMediaBackend
import com.veilframe.app.media.ImageStudioController
import com.veilframe.app.media.VideoStudioController
import com.veilframe.app.storage.SafStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Controller managing asynchronous execution of dedicated tool workflows:
 * AI Bundler, Video Sanitizer (PRNU mitigation & EXIF strip), Image Sanitizer (EXIF scrub & clean re-encode),
 * and Folder Scanner (SAF audit & secret detection).
 */
class ToolExecutionController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val sessionManager: ToolSessionManager,
    private val safStorageManager: SafStorageManager,
    private val scope: CoroutineScope,
    private val onOpenFilePicker: () -> Unit,
    private val onOpenFolderPicker: () -> Unit,
    private val onLog: (String) -> Unit,
    private val getImageStudioController: () -> ImageStudioController?,
    private val getVideoStudioController: () -> VideoStudioController?
) : com.veilframe.app.navigation.ToolWorkStateContract {

    private var executionJob: Job? = null
    private var executionGeneration = 0L

    private fun ownsExecution(generation: Long, mode: ToolMode, owner: ToolTargetOwner): Boolean =
        generation == executionGeneration && !activity.isFinishing && !activity.isDestroyed &&
            sessionManager.currentToolMode == mode && owner.owns(sessionManager.currentState)

    val isExecuting: Boolean
        get() = executionJob?.isActive == true || sessionManager.currentState.jobState in listOf(
            JobState.PREPARING,
            JobState.SCANNING,
            JobState.PROCESSING,
            JobState.FINALIZING
        )

    override fun hasPickedMedia(): Boolean = sessionManager.currentState.selectedUri != null
    override fun hasUnsavedWork(): Boolean = sessionManager.currentState.selectedUri != null || sessionManager.currentState.jobState == JobState.COMPLETE
    override val isProcessing: Boolean
        get() = isExecuting
    override val supportsBackgroundExecution: Boolean
        get() = false

    override fun discardAndReleaseAllResources() {
        cancelExecution()
        sessionManager.clearSelectedTarget(logMessage = false)
    }

    fun cancelExecution() {
        executionGeneration++
        executionJob?.cancel()
        executionJob = null
        val state = sessionManager.currentState
        sessionManager.updateJobState(JobState.CANCELLED, "Processing cancelled by user.")
        onLog("[USER] Execution cancelled by user.")
        binding.progressIndicator.isIndeterminate = false
        sessionManager.updatePrimaryActionDock(state)
    }

    fun executeSelectedMode() = executeSelectedModeInternal(null)

    private fun executeSelectedModeInternal(excludedPaths: Set<String>?) {
        val state = sessionManager.currentState
        if (state.jobState in listOf(JobState.PREPARING, JobState.SCANNING, JobState.PROCESSING, JobState.FINALIZING)) {
            cancelExecution()
            return
        }

        if (state.jobState == JobState.COMPLETE) {
            sessionManager.updateJobState(JobState.IDLE, "Ready for execution.")
            sessionManager.updatePrimaryActionDock(state)
            return
        }

        val uri = state.selectedUri
        if (uri == null) {
            // AI Bundle and Folder Scanner work on directories — open folder picker
            if (sessionManager.currentToolMode in listOf(ToolMode.AI_BUNDLE, ToolMode.FOLDER_SCANNER)) {
                onOpenFolderPicker()
            } else {
                onOpenFilePicker()
            }
            return
        }

        if (sessionManager.currentToolMode == ToolMode.AI_BUNDLE && state.isFolderSelected && excludedPaths == null) {
            showBundleExclusions(state, uri)
            return
        }

        binding.btnExecute.text = "Cancel processing"
        binding.btnExecute.setIconResource(R.drawable.ic_action_clear)
        binding.btnExportResult.isEnabled = false
        binding.btnShareResult.isEnabled = false
        binding.cardResultSummary.visibility = View.GONE
        binding.btnResultPreview.visibility = View.GONE
        binding.progressIndicator.visibility = View.VISIBLE
        binding.progressIndicator.isIndeterminate = true
        binding.tvProgressDetails.text = ""

        val mode = sessionManager.currentToolMode
        val owner = ToolTargetOwner(state)
        val generation = ++executionGeneration
        if (mode == ToolMode.AI_BUNDLE) sessionManager.updateJobState(JobState.PREPARING, "Preparing AI bundle…")
        executionJob = scope.launch {
            if (!ownsExecution(generation, mode, owner)) return@launch
            when (mode) {
                ToolMode.AI_BUNDLE -> {
                    try {
                        runAiBundle(uri, state, excludedPaths.orEmpty()) {
                            ownsExecution(generation, mode, owner)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (!ownsExecution(generation, mode, owner)) return@launch
                        sessionManager.updateJobState(JobState.FAILED, "Bundle failed: ${failure.message}")
                        binding.progressIndicator.isIndeterminate = false
                        Toast.makeText(activity, "Bundle generation failed: ${failure.message}", Toast.LENGTH_LONG).show()
                    }
                }
                ToolMode.VIDEO_CLEANER, ToolMode.IMAGE_CLEANER, ToolMode.FOLDER_SCANNER -> {
                    try {
                        when (mode) {
                            ToolMode.VIDEO_CLEANER -> runVideoSanitization(uri, state) { ownsExecution(generation, mode, owner) }
                            ToolMode.IMAGE_CLEANER -> runImageSanitization(uri, state) { ownsExecution(generation, mode, owner) }
                            else -> runFolderScan(uri, state) { ownsExecution(generation, mode, owner) }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (!ownsExecution(generation, mode, owner)) return@launch
                        sessionManager.updateJobState(JobState.FAILED, "Processing failed: ${failure.message}")
                        binding.progressIndicator.isIndeterminate = false
                        Toast.makeText(activity, "Processing failed: ${failure.message}", Toast.LENGTH_LONG).show()
                    }
                }
                ToolMode.IMAGE_COMPRESSOR -> getImageStudioController()?.execute()
                ToolMode.VIDEO_COMPRESSOR -> getVideoStudioController()?.execute()
                ToolMode.IMAGE_UPSCALER -> { /* Handled within ImageUpscalerController */ }
            }
            if (ownsExecution(generation, mode, owner)) sessionManager.updatePrimaryActionDock(state)
        }
    }

    private fun showBundleExclusions(state: ToolSessionState, uri: Uri) {
        val owner = ToolTargetOwner(state)
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        container.addView(android.widget.TextView(activity).apply {
            text = "Optional: exclude individual files before their contents are read. Enter exact, case-sensitive paths relative to the selected folder, one per line (not wildcards). Unmatched paths block generation. Excluded names can still appear in the omission report."
        })
        val input = android.widget.EditText(activity).apply {
            hint = "src/private.kt\nconfig/local.json"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            minLines = 4
            maxLines = 8
            filters = arrayOf(android.text.InputFilter.LengthFilter(128 * 1024))
        }
        container.addView(input)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Exclude files from AI bundle")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Generate", null)
            .create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (activity.isFinishing || activity.isDestroyed || sessionManager.currentToolMode != ToolMode.AI_BUNDLE ||
                !owner.owns(sessionManager.currentState) || state.selectedUri != uri || !state.isFolderSelected) {
                dialog.dismiss()
                return@setOnClickListener
            }
            val exclusions = try {
                AiBundleEngine.parseExcludedPaths(input.text.toString())
            } catch (failure: IllegalArgumentException) {
                input.error = failure.message
                return@setOnClickListener
            }
            dialog.dismiss()
            executeSelectedModeInternal(exclusions)
        }
    }

    private suspend fun runAiBundle(uri: Uri, state: ToolSessionState, excludedPaths: Set<String>, owns: () -> Boolean) {
        if (!owns()) return
        val folder = state.isFolderSelected
        val sourceName = state.selectedPathDisplay
        val tokenIndex = sessionManager.getSelectedOptionIndex()
        val tokenBudget = when (tokenIndex) {
            0 -> 32_000L
            1 -> 64_000L
            2 -> 128_000L
            3 -> 200_000L
            4 -> 0L // Unlimited
            else -> 64_000L
        }

        val formatIndex = sessionManager.getSelectedFormatIndex()
        val formatKey = when (formatIndex) {
            0 -> "aibundle"
            1 -> "md"
            else -> "json"
        }

        sessionManager.updateJobState(JobState.PROCESSING, "Packaging AI context bundle...")
        onLog("[AI] Packaging context directly from target: $sourceName (Budget: ${if (tokenBudget == 0L) "32 MiB byte cap" else "~$tokenBudget estimated tokens"})...")

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(activity.cacheDir, "AIBundle_${timeStamp}_${java.util.UUID.randomUUID()}.$formatKey")

        val maxDepth = if (binding.switchBundleFullDepth.isChecked) {
            null
        } else {
            binding.etBundleCustomDepth.text.toString().toIntOrNull() ?: 3
        }

        val bundleConfig = AiBundleEngine.BundleConfig(
            tokenBudget = tokenBudget,
            outputFormat = formatKey,
            maskSecrets = binding.switchOption1.isChecked,
            excludeTests = binding.switchOption2.isChecked,
            compressManifests = binding.switchOption3.isChecked,
            maxDepth = maxDepth,
            excludedRelativePaths = excludedPaths
        )

        var published = false
        try {
        val bundleResult = withContext(Dispatchers.IO) {
            if (folder) {
                AiBundleEngine.buildBundleFromSaf(
                    context = activity,
                    treeUri = uri,
                    outputFile = outputFile,
                    config = bundleConfig,
                    onProgress = { currentFile, included ->
                        activity.runOnUiThread {
                            if (owns() && state.jobState == JobState.PROCESSING) {
                                binding.tvStatusText.text = "Bundled $included files ($currentFile)..."
                            }
                        }
                    }
                )
            } else {
                AiBundleEngine.buildSingleFileBundle(
                    activity, uri, sourceName, outputFile, bundleConfig
                )
            }
        }

        withContext(Dispatchers.Main) {
            if (!owns()) return@withContext
            state.lastGeneratedFile = outputFile
            state.progressPercent = 100
            state.progressDetailsText = "100% complete"
            published = true
            sessionManager.updateJobState(JobState.COMPLETE, "Bundle ready: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
            binding.progressIndicator.isIndeterminate = false
            binding.progressIndicator.progress = 100
            binding.tvProgressDetails.text = "100% complete"

            binding.cardResultSummary.visibility = View.VISIBLE
            binding.tvResultTitle.text = outputFile.name
            binding.tvResultDetails.text = "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • ${bundleResult.includedFiles}/${bundleResult.totalFiles} evaluated files included • ${bundleResult.omittedEntries} omitted entries (see report)"
            val isMd = outputFile.extension.equals("md", ignoreCase = true) || outputFile.extension.equals("markdown", ignoreCase = true)
            binding.btnResultPreview.visibility = if (isMd) View.VISIBLE else View.GONE

            onLog("[OK] Package generated: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
            onLog("[AI] Tokens: ~${bundleResult.totalTokens} | Included files: ${bundleResult.includedFiles} | Format: $formatKey")
            onLog(if (bundleConfig.maskSecrets) "[PRIVACY] Heuristic masking applied; review for missed credentials before sharing." else "[PRIVACY] Secret masking disabled; review all content before sharing.")
            onLog("[EXPORT] Output ready for saving or sharing.")
            binding.btnExecute.isEnabled = true
            binding.btnExportResult.isEnabled = true
            binding.btnShareResult.isEnabled = true
        }
        } finally {
            // withContext can cancel after IO has atomically published the file, before Main sees it.
            if (!published) outputFile.delete()
        }
    }

    private suspend fun runVideoSanitization(uri: Uri, state: ToolSessionState, owns: () -> Boolean) {
        if (!owns()) return
        val noiseIndex = sessionManager.getSelectedOptionIndex()
        val noiseLevel = when (noiseIndex) {
            0 -> "low"
            1 -> "medium"
            2 -> "high"
            else -> "none"
        }
        val crf = state.sliderValue.toInt().takeIf { it in 15..40 } ?: when (noiseLevel) {
            "low" -> 20
            "medium" -> 22
            "high" -> 23
            else -> 24
        }
        val stripExif = binding.switchOption1.isChecked
        val scrubAudio = binding.switchOption2.isChecked
        val reencodeBitstream = binding.switchOption3.isChecked

        val formatIndex = sessionManager.getSelectedFormatIndex()
        val backend = AndroidMediaBackend(activity)

        if (state.isFolderSelected) {
            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PREPARING, "Extracting video files from folder...")
                onLog("[BATCH] Scanning folder for video files: ${state.selectedPathDisplay}...")
            }

            val batchRoot = java.nio.file.Files.createTempDirectory(activity.cacheDir.toPath(), "batch_video_").toFile()
            val stagingDir = File(batchRoot, "inputs")
            val outputBatchDir = File(batchRoot, "outputs")
            var finalArtifact: File? = null
            var published = false
            try {
                check(outputBatchDir.mkdir())
                safStorageManager.materializeTargetIntoDir(uri, true, stagingDir)
                val inspectedCount = withContext(Dispatchers.IO) { stagingDir.walkTopDown().count { it.isFile } }

                val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "avi", "flv", "m4v", "wmv", "3gp")
                val candidateFiles = withContext(Dispatchers.IO) {
                    stagingDir.walkTopDown().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in videoExtensions }.toList().sortedBy { it.relativeTo(stagingDir).invariantSeparatorsPath }
                }
                withContext(Dispatchers.IO) { BatchOutputPaths.prepare(stagingDir, outputBatchDir, candidateFiles) }

                if (candidateFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        sessionManager.updateJobState(JobState.FAILED, "No supported video files found in folder.")
                        onLog("[WARN] No video files (.mp4, .mkv, .webm, .mov, etc.) found in selected folder ($inspectedCount files inspected).")
                        sessionManager.updatePrimaryActionDock(state)
                    }
                    return
                }

                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.PROCESSING, "Sanitizing ${candidateFiles.size} videos (noise: $noiseLevel)...")
                    onLog("[BATCH] Found ${candidateFiles.size} videos with mixed formats. Beginning sanitization...")
                }

                var successCount = 0
                var metadataOnlyFallbackCount = 0
                candidateFiles.forEachIndexed { idx, inputFile ->
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val targetExt = when (formatIndex) {
                        1 -> "mp4"
                        2 -> "mkv"
                        3 -> "webm"
                        else -> inputFile.extension.lowercase(Locale.ROOT).ifEmpty { "mp4" }
                    }
                    val outputFile = BatchOutputPaths.outputFor(stagingDir, outputBatchDir, inputFile, idx, "sanitized", targetExt)

                    withContext(Dispatchers.Main) {
                        val percent = ((idx * 100) / candidateFiles.size)
                        binding.progressIndicator.isIndeterminate = false
                        binding.progressIndicator.progress = percent
                        binding.tvProgressDetails.text = "Sanitizing ${idx + 1}/${candidateFiles.size}: ${inputFile.name}"
                        onLog("[RUN] [${idx + 1}/${candidateFiles.size}] Sanitizing ${inputFile.name} -> $targetExt (noise: $noiseLevel)")
                    }

                    val ok = withContext(Dispatchers.IO) {
                        backend.cleanVideoDetailed(
                            inputFile.absolutePath,
                            outputFile.absolutePath,
                            noiseLevel = noiseLevel,
                            scrubAudio = scrubAudio,
                            crf = crf,
                            stripExif = stripExif,
                            reencodeBitstream = reencodeBitstream
                        )
                    }
                    if (ok.isSuccess && outputFile.exists() && outputFile.length() > 0) {
                        successCount++
                        if (ok.status == com.veilframe.app.privacy.VideoSanitizationStatus.METADATA_ONLY_FALLBACK) {
                            metadataOnlyFallbackCount++
                        }
                    } else {
                        // FFmpeg may leave a partial file after both the primary and fallback passes fail.
                        outputFile.delete()
                    }
                }

                if (successCount == 0) {
                    withContext(Dispatchers.Main) {
                        sessionManager.updateJobState(JobState.FAILED, "Batch video sanitization failed.")
                        onLog("[ERR] Failed to sanitize any video files in the batch.")
                        sessionManager.updatePrimaryActionDock(state)
                    }
                    return
                }

                val finalFile = withContext(Dispatchers.IO) {
                val cc = kotlin.coroutines.coroutineContext
                val actualOutputs = outputBatchDir.walkTopDown().filter { it.isFile }.toList()
                check(actualOutputs.size == successCount) { "Batch output count does not match successful encodes" }
                val completedFile = if (actualOutputs.size == 1) {
                    val singleFile = actualOutputs.single()
                    File.createTempFile("VeilFrame_Videos_", ".${singleFile.extension}", activity.cacheDir).also {
                        finalArtifact = it
                        singleFile.inputStream().buffered().use { input ->
                            it.outputStream().buffered().use { output ->
                                SafStorageManager.copyChecked(input, output) { cc.ensureActive() }
                            }
                        }
                    }
                } else {
                    File.createTempFile("VeilFrame_Batch_Videos_", ".zip", activity.cacheDir).also {
                        finalArtifact = it
                        safStorageManager.zipDirectory(outputBatchDir, it) { cc.ensureActive() }
                    }
                }
                check(completedFile.isFile && completedFile.length() > 0) { "Batch artifact was not created" }
                completedFile
                }

                withContext(Dispatchers.Main) {
                    if (!owns()) return@withContext
                    state.lastGeneratedFile = finalFile
                    state.progressPercent = 100
                    state.progressDetailsText = "100% complete"
                    published = true
                    sessionManager.updateJobState(JobState.COMPLETE, "Batch ready: ${finalFile.name} (${safStorageManager.formatBytes(finalFile.length())})")
                    binding.progressIndicator.isIndeterminate = false
                    binding.progressIndicator.progress = 100
                    binding.tvProgressDetails.text = "100% complete"

                    binding.cardResultSummary.visibility = View.VISIBLE
                    binding.tvResultTitle.text = finalFile.name
                    binding.tvResultDetails.text = "${safStorageManager.formatBytes(finalFile.length())} • ${finalFile.extension.uppercase()} • $successCount videos processed; $metadataOnlyFallbackCount metadata-only fallback(s)"

                    onLog("[OK] Batch completed: $successCount/${candidateFiles.size} videos processed; $metadataOnlyFallbackCount used metadata-only fallback (no temporal noise filter).")
                    onLog("[EXPORT] Output ready for saving or sharing: ${finalFile.name}")
                    sessionManager.updatePrimaryActionDock(state)
                    binding.btnExportResult.isEnabled = true
                    binding.btnShareResult.isEnabled = true
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    batchRoot.deleteRecursively()
                    if (!published) finalArtifact?.delete()
                }
            }
        } else {
            // Single video file
            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PREPARING, "Staging video file...")
                onLog("[PREP] Staging video: ${state.selectedPathDisplay}...")
            }

            val sourceExt = state.selectedPathDisplay.substringAfterLast('.', "mp4").lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "mp4"
            val targetExt = when (formatIndex) {
                1 -> "mp4"
                2 -> "mkv"
                3 -> "webm"
                else -> sourceExt.ifEmpty { "mp4" }
            }

            var inputArtifact: File? = null
            var outputArtifact: File? = null
            var published = false
            try {
                val tempInput = withContext(Dispatchers.IO) {
                    File.createTempFile("input_video_", ".$sourceExt", activity.cacheDir).also { inputArtifact = it }
                }
                val successCopy = safStorageManager.copyUriToFile(uri, tempInput)
                if (!successCopy) {
                    withContext(Dispatchers.Main) {
                        if (!owns()) return@withContext
                        sessionManager.updateJobState(JobState.FAILED, "Failed to copy input video.")
                        onLog("[ERR] Failed to copy input video from storage.")
                        sessionManager.updatePrimaryActionDock(state)
                    }
                    return
                }

                val outputFile = withContext(Dispatchers.IO) {
                    File.createTempFile("Sanitized_", ".$targetExt", activity.cacheDir).also { outputArtifact = it }
                }

                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.PROCESSING, "Sanitizing video stream (noise: $noiseLevel)...")
                    onLog("[RUN] Applying requested video processing ($noiseLevel) and metadata stripping...")
                }

                val processed = withContext(Dispatchers.IO) {
                    backend.cleanVideoDetailed(
                        tempInput.absolutePath,
                        outputFile.absolutePath,
                        noiseLevel = noiseLevel,
                        scrubAudio = scrubAudio,
                        crf = crf,
                        stripExif = stripExif,
                        reencodeBitstream = reencodeBitstream
                    )
                }

                if (processed.isSuccess && outputFile.exists() && outputFile.length() > 0) {
                    withContext(Dispatchers.Main) {
                        if (!owns()) return@withContext
                        state.lastGeneratedFile = outputFile
                        state.progressPercent = 100
                        state.progressDetailsText = "100% complete"
                        published = true
                        sessionManager.updateJobState(JobState.COMPLETE, if (processed.status == com.veilframe.app.privacy.VideoSanitizationStatus.METADATA_ONLY_FALLBACK) "Metadata-only fallback: ${outputFile.name}" else "Video processed: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
                        binding.progressIndicator.isIndeterminate = false
                        binding.progressIndicator.progress = 100
                        binding.tvProgressDetails.text = "100% complete"

                        binding.cardResultSummary.visibility = View.VISIBLE
                        binding.tvResultTitle.text = outputFile.name
                        binding.tvResultDetails.text = if (processed.status == com.veilframe.app.privacy.VideoSanitizationStatus.METADATA_ONLY_FALLBACK) "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • Metadata-only fallback; temporal noise filter not applied" else "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • Video transcoded; camera PRNU removal not independently verified"

                        onLog(if (processed.status == com.veilframe.app.privacy.VideoSanitizationStatus.METADATA_ONLY_FALLBACK) "[WARN] Metadata-only fallback completed; requested temporal noise filter was not applied." else "[OK] Video transcode completed; camera PRNU removal was not independently verified.")
                        onLog("[EXPORT] Output ready for saving or sharing.")
                        sessionManager.updatePrimaryActionDock(state)
                        binding.btnExportResult.isEnabled = true
                        binding.btnShareResult.isEnabled = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!owns()) return@withContext
                        sessionManager.updateJobState(JobState.FAILED, "Video sanitization failed.")
                        binding.progressIndicator.isIndeterminate = false
                        binding.cardResultSummary.visibility = View.GONE
                        onLog("[ERR] Video engine reported failure.")
                        sessionManager.updatePrimaryActionDock(state)
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    inputArtifact?.delete()
                    if (!published) outputArtifact?.delete()
                }
            }
        }
    }

    private suspend fun runImageSanitization(uri: Uri, state: ToolSessionState, owns: () -> Boolean) {
        if (!owns()) return
        val formatIndex = sessionManager.getSelectedFormatIndex()
        val optionIndex = sessionManager.getSelectedOptionIndex()
        val defenseName = when (optionIndex) {
            0 -> "stealth"
            1 -> "standard"
            2 -> "high suppression"
            else -> "metadata"
        }
        val quality = state.sliderValue.toInt().coerceIn(50, 100)
        val stripExif = binding.switchOption1.isChecked
        val prnuDefense = binding.switchOption2.isChecked
        val cleanReencode = binding.switchOption3.isChecked
        val effectiveDefense = if (!prnuDefense && optionIndex != 3) "metadata" else defenseName
        val backend = AndroidMediaBackend(activity)

        if (state.isFolderSelected) {
            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PREPARING, "Extracting images from folder...")
                onLog("[BATCH] Scanning folder for images: ${state.selectedPathDisplay}...")
            }

            val batchRoot = java.nio.file.Files.createTempDirectory(activity.cacheDir.toPath(), "batch_img_").toFile()
            val stagingDir = File(batchRoot, "inputs")
            val outputBatchDir = File(batchRoot, "outputs")
            var finalArtifact: File? = null
            var published = false
            try {
                check(outputBatchDir.mkdir())
                safStorageManager.materializeTargetIntoDir(uri, true, stagingDir)
                val inspectedCount = withContext(Dispatchers.IO) { stagingDir.walkTopDown().count { it.isFile } }

                val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif", "tiff", "tif", "gif")
                val candidateFiles = withContext(Dispatchers.IO) {
                    stagingDir.walkTopDown().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in imageExtensions }.toList().sortedBy { it.relativeTo(stagingDir).invariantSeparatorsPath }
                }
                withContext(Dispatchers.IO) { BatchOutputPaths.prepare(stagingDir, outputBatchDir, candidateFiles) }

                if (candidateFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        sessionManager.updateJobState(JobState.FAILED, "No supported image files found in folder.")
                        onLog("[WARN] No image files (.jpg, .png, .webp, .bmp, .heic, etc.) found in selected folder ($inspectedCount files inspected).")
                        sessionManager.updatePrimaryActionDock(state)
                    }
                    return
                }

                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.PROCESSING, "Purifying ${candidateFiles.size} images (Mode: ${effectiveDefense.uppercase()}, Quality: $quality%)...")
                    onLog("[BATCH] Found ${candidateFiles.size} images. Beginning forensic purification (Defense: ${effectiveDefense.uppercase()})...")
                }

                var successCount = 0
                candidateFiles.forEachIndexed { idx, inputFile ->
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val targetExt = when (formatIndex) {
                        1 -> "jpg"
                        2 -> "png"
                        3 -> "webp"
                        4 -> "bmp"
                        5 -> "tiff"
                        6 -> "gif"
                        7 -> "heif"
                        else -> inputFile.extension.lowercase(Locale.ROOT).ifEmpty { "jpg" }
                    }
                    val outputFile = BatchOutputPaths.outputFor(stagingDir, outputBatchDir, inputFile, idx, "cleaned", targetExt)

                    withContext(Dispatchers.Main) {
                        val percent = ((idx * 100) / candidateFiles.size)
                        binding.progressIndicator.isIndeterminate = false
                        binding.progressIndicator.progress = percent
                        binding.tvProgressDetails.text = "Purifying ${idx + 1}/${candidateFiles.size}: ${inputFile.name}"
                        onLog("[RUN] [${idx + 1}/${candidateFiles.size}] Purifying ${inputFile.name} -> $targetExt")
                    }

                    val ok = withContext(Dispatchers.IO) {
                        if (effectiveDefense != "metadata") {
                            val res = backend.cleanImageWithDefenseDetailed(
                                inputFile.absolutePath,
                                outputFile.absolutePath,
                                defenseMode = effectiveDefense,
                                stripExif = stripExif,
                                quality = quality,
                                targetExtension = targetExt,
                                onMilestone = { milestone ->
                                    activity.runOnUiThread { if (owns()) onLog(milestone) }
                                }
                            )
                            if (!res.isAuditPassed && res.isSuccess) {
                                activity.runOnUiThread {
                                    if (!owns()) return@runOnUiThread
                                    val heuristic = if (res.residualHeuristicPassed) {
                                        " Single-image residual heuristic crossed its threshold."
                                    } else {
                                        " Single-image residual heuristic did not establish suppression."
                                    }
                                    onLog(
                                        "[PRNU] Output file was created, but camera PRNU removal was NOT verified: no independent multi-image camera reference is used." +
                                            heuristic +
                                            " Residual sPCE=${res.measuredPce}; max strip=${res.measuredMaxStrip}."
                                    )
                                }
                            }
                            res.isSuccess
                        } else {
                            backend.cleanImage(
                                inputFile.absolutePath,
                                outputFile.absolutePath,
                                stripExif = stripExif,
                                quality = quality,
                                targetExtension = targetExt,
                                stripThumbnails = true,
                                sanitizeIcc = true
                            )
                        }
                    }
                    if (ok && outputFile.isFile && outputFile.length() > 0) {
                        successCount++
                    } else {
                        // Failed encoders may leave an original or partially written image.
                        check(!outputFile.exists() || outputFile.delete()) { "Could not remove failed image artifact" }
                    }
                }

                if (successCount == 0) {
                    withContext(Dispatchers.Main) {
                        sessionManager.updateJobState(JobState.FAILED, "Batch image sanitization failed.")
                        onLog("[ERR] Failed to sanitize any image files in the batch.")
                        sessionManager.updatePrimaryActionDock(state)
                    }
                    return
                }

                val finalFile = withContext(Dispatchers.IO) {
                val cc = kotlin.coroutines.coroutineContext
                val actualOutputs = outputBatchDir.walkTopDown().filter { it.isFile }.toList()
                check(actualOutputs.size == successCount) { "Batch output count does not match successful encodes" }
                val completedFile = if (actualOutputs.size == 1) {
                    val singleFile = actualOutputs.single()
                    File.createTempFile("VeilFrame_Images_", ".${singleFile.extension}", activity.cacheDir).also {
                        finalArtifact = it
                        singleFile.inputStream().buffered().use { input ->
                            it.outputStream().buffered().use { output ->
                                SafStorageManager.copyChecked(input, output) { cc.ensureActive() }
                            }
                        }
                    }
                } else {
                    File.createTempFile("VeilFrame_Batch_Images_", ".zip", activity.cacheDir).also {
                        finalArtifact = it
                        safStorageManager.zipDirectory(outputBatchDir, it) { cc.ensureActive() }
                    }
                }
                check(completedFile.isFile && completedFile.length() > 0) { "Batch artifact was not created" }
                completedFile
                }

                withContext(Dispatchers.Main) {
                    if (!owns()) return@withContext
                    state.lastGeneratedFile = finalFile
                    state.progressPercent = 100
                    state.progressDetailsText = "100% complete"
                    published = true
                    sessionManager.updateJobState(JobState.COMPLETE, "Batch ready: ${finalFile.name} (${safStorageManager.formatBytes(finalFile.length())})")
                    binding.progressIndicator.isIndeterminate = false
                    binding.progressIndicator.progress = 100
                    binding.tvProgressDetails.text = "100% complete"

                    binding.cardResultSummary.visibility = View.VISIBLE
                    binding.tvResultTitle.text = finalFile.name
                    binding.tvResultDetails.text = "${safStorageManager.formatBytes(finalFile.length())} • ${finalFile.extension.uppercase()} • $successCount images scrubbed"

                    onLog("[OK] Batch completed: $successCount/${candidateFiles.size} images scrubbed successfully.")
                    onLog("[EXPORT] Output ready for saving or sharing: ${finalFile.name}")
                    sessionManager.updatePrimaryActionDock(state)
                    binding.btnExportResult.isEnabled = true
                    binding.btnShareResult.isEnabled = true
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    batchRoot.deleteRecursively()
                    if (!published) finalArtifact?.delete()
                }
            }
        } else {
            // Single image file
            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PREPARING, "Staging image...")
                onLog("[PREP] Staging image: ${state.selectedPathDisplay}...")
            }

            val sourceExt = state.selectedPathDisplay.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
                .takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "jpg"
            val targetExt = when (formatIndex) {
                1 -> "jpg"
                2 -> "png"
                3 -> "webp"
                4 -> "bmp"
                5 -> "tiff"
                6 -> "gif"
                7 -> "heif"
                else -> sourceExt.ifEmpty { "jpg" }
            }

            var inputArtifact: File? = null
            var outputArtifact: File? = null
            var published = false
            try {
                val tempInput = withContext(Dispatchers.IO) {
                    File.createTempFile("input_img_", ".$sourceExt", activity.cacheDir).also { inputArtifact = it }
                }
                val successCopy = safStorageManager.copyUriToFile(uri, tempInput)
                if (!successCopy) {
                    withContext(Dispatchers.Main) {
                        if (!owns()) return@withContext
                        sessionManager.updateJobState(JobState.FAILED, "Failed to copy input image.")
                        onLog("[ERR] Failed to copy input image from storage.")
                        sessionManager.updatePrimaryActionDock(state)
                    }
                    return
                }

                val outputFile = withContext(Dispatchers.IO) {
                    File.createTempFile("Cleaned_", ".$targetExt", activity.cacheDir).also { outputArtifact = it }
                }

                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.PROCESSING, "Purifying image forensic traces (Mode: ${effectiveDefense.uppercase()}, Quality: $quality%)...")
                    onLog("[RUN] Initiating forensic purification (Defense: ${effectiveDefense.uppercase()})...")
                }

                val processed = withContext(Dispatchers.IO) {
                    if (effectiveDefense != "metadata") {
                        val res = backend.cleanImageWithDefenseDetailed(
                            tempInput.absolutePath,
                            outputFile.absolutePath,
                            defenseMode = effectiveDefense,
                            stripExif = stripExif,
                            quality = quality,
                            targetExtension = targetExt,
                            onMilestone = { milestone ->
                                activity.runOnUiThread { if (owns()) onLog(milestone) }
                            }
                        )
                        if (!res.isAuditPassed && res.isSuccess) {
                            activity.runOnUiThread {
                                if (!owns()) return@runOnUiThread
                                if (!res.isConclusive) {
                                    onLog("[AUDIT] Note: Low-energy baseline; empirical sensor audit inconclusive.")
                                } else {
                                    onLog("[AUDIT] Note: Output written, but audit verification fell outside threshold (|sPCE|=${res.measuredPce}).")
                                }
                            }
                        }
                        res.isSuccess
                    } else {
                        backend.cleanImage(
                            tempInput.absolutePath,
                            outputFile.absolutePath,
                            stripExif = stripExif,
                            quality = quality,
                            targetExtension = targetExt,
                            stripThumbnails = true,
                            sanitizeIcc = true
                        )
                    }
                }

                if (processed && outputFile.exists() && outputFile.length() > 0) {
                    withContext(Dispatchers.Main) {
                        if (!owns()) return@withContext
                        state.lastGeneratedFile = outputFile
                        state.progressPercent = 100
                        state.progressDetailsText = "100% complete"
                        published = true
                        sessionManager.updateJobState(JobState.COMPLETE, "Image purified: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
                        binding.progressIndicator.isIndeterminate = false
                        binding.progressIndicator.progress = 100
                        binding.tvProgressDetails.text = "100% complete"

                        binding.cardResultSummary.visibility = View.VISIBLE
                        binding.tvResultTitle.text = outputFile.name
                        binding.tvResultDetails.text = "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • Image processed; camera PRNU not independently verified"

                        onLog("[OK] Image processing completed: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
                        onLog("[PRNU] Camera sensor fingerprint removal was not independently verified.")
                        onLog("[EXPORT] Output ready for saving or sharing.")
                        sessionManager.updatePrimaryActionDock(state)
                        binding.btnExportResult.isEnabled = true
                        binding.btnShareResult.isEnabled = true
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!owns()) return@withContext
                        sessionManager.updateJobState(JobState.FAILED, "Image sanitization failed.")
                        binding.progressIndicator.isIndeterminate = false
                        binding.cardResultSummary.visibility = View.GONE
                        onLog("[ERR] Image cleaner reported failure.")
                        sessionManager.updatePrimaryActionDock(state)
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    inputArtifact?.delete()
                    if (!published) outputArtifact?.delete()
                }
            }
        }
    }

    private suspend fun runFolderScan(uri: Uri, state: ToolSessionState, owns: () -> Boolean) {
        if (!owns()) return
        val formatIndex = sessionManager.getSelectedFormatIndex()
        val formatExt = when (formatIndex) {
            0 -> "html"
            1 -> "json"
            2 -> "md"
            3 -> "csv"
            4 -> "txt"
            5 -> "zip"
            else -> "html"
        }

        val profileIndex = sessionManager.getSelectedOptionIndex()
        val profileName = when (profileIndex) {
            0 -> "Quick Audit"
            1 -> "Deep Forensic"
            else -> "Duplicate Hunt"
        }

        val recursive = binding.switchOption1.isChecked
        val computeHashes = binding.switchOption2.isChecked || profileIndex == 2
        val detectSecrets = binding.switchOption3.isChecked

        withContext(Dispatchers.Main) {
            sessionManager.updateJobState(JobState.SCANNING, "Auditing directory stream...")
            onLog("[SCAN] Scanning target directly: ${state.selectedPathDisplay} (Mode: $profileName)...")
        }

        var artifact: File? = null
        var published = false
        try {
            val outputFile = withContext(Dispatchers.IO) {
                File.createTempFile("Scan_Report_", ".$formatExt", activity.cacheDir).also { artifact = it }
            }

            val scanConfig = FolderScannerEngine.ScanConfig(
                recursive = recursive,
                calculateHashes = computeHashes,
                detectSecrets = detectSecrets
            )

            val scanResult = withContext(Dispatchers.IO) {
                if (state.isFolderSelected) {
                    val res = FolderScannerEngine.scanSafTree(
                        context = activity,
                        treeUri = uri,
                        config = scanConfig,
                        onProgress = { currentFile, count ->
                            activity.runOnUiThread {
                                if (owns() && state.jobState == JobState.SCANNING)
                                    binding.tvStatusText.text = "Scanned $count files ($currentFile)..."
                            }
                        }
                    )
                    FolderScannerEngine.exportReport(res, outputFile, formatExt)
                    res
                } else {
                    val name = safStorageManager.getDisplayName(uri)
                    val size = activity.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                    val record = FolderScannerEngine.ScannedFileRecord(
                        relativePath = name,
                        name = name,
                        sizeBytes = size,
                        sha256 = null,
                        detectedSecrets = emptyList()
                    )
                    val res = FolderScannerEngine.ScanResult(
                        rootDisplayName = name,
                        timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                        totalFiles = 1,
                        totalBytes = size,
                        totalSecretsFound = 0,
                        files = listOf(record)
                    )
                    FolderScannerEngine.exportReport(res, outputFile, formatExt)
                    res
                }
            }

            if (scanResult.status == FolderScannerEngine.ScanStatus.FAILED) {
                throw java.io.IOException(scanResult.statusReason ?: "Folder audit failed")
            }
            val partial = scanResult.status == FolderScannerEngine.ScanStatus.PARTIAL
            val scanSummary = "${scanResult.totalFiles} files analyzed • " + if (partial) "PARTIAL audit" else "Complete audit"
            withContext(Dispatchers.Main) {
                if (!owns()) return@withContext
                state.lastGeneratedFile = outputFile
                state.progressPercent = 100
                state.progressDetailsText = if (partial) "Partial audit; see report limitations" else "100% complete"
                published = true
                sessionManager.updateJobState(JobState.COMPLETE, "${if (partial) "Partial audit report" else "Audit ready"}: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
                binding.progressIndicator.isIndeterminate = false
                binding.progressIndicator.progress = 100
                binding.tvProgressDetails.text = state.progressDetailsText

                binding.cardResultSummary.visibility = View.VISIBLE
                binding.tvResultTitle.text = outputFile.name
                binding.tvResultDetails.text = "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • $scanSummary"
                val isMd = outputFile.extension.equals("md", ignoreCase = true) || outputFile.extension.equals("markdown", ignoreCase = true)
                binding.btnResultPreview.visibility = if (isMd) View.VISIBLE else View.GONE

                onLog("[${if (partial) "WARN" else "OK"}] Audit report created: ${outputFile.name} ($scanSummary)")
                if (partial) onLog("[WARN] ${scanResult.statusReason ?: "Some checks could not be completed"}")
                if (scanResult.totalSecretsFound > 0) {
                    onLog("[WARN] Detected ${scanResult.totalSecretsFound} secret(s) flagged in report.")
                }
                if (scanResult.duplicateGroups.isNotEmpty()) {
                    onLog("[DUP] Found ${scanResult.duplicateGroups.size} duplicate file groups based on SHA-256 hashing.")
                }
                onLog("[EXPORT] Output ready for saving or sharing.")
                sessionManager.updatePrimaryActionDock(state)
                binding.btnExportResult.isEnabled = true
                binding.btnShareResult.isEnabled = true
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { if (!published) artifact?.delete() }
        }
    }

    fun shareLastResult(file: File? = sessionManager.currentState.lastGeneratedFile): Boolean {
        if (file == null || !file.isFile) {
            Toast.makeText(activity, "No output artifact to share", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val mimeType = safStorageManager.getMimeTypeForFile(file)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(activity.contentResolver, "Share Output Artifact", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share Output Artifact").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        } catch (e: Exception) {
            Toast.makeText(activity, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
