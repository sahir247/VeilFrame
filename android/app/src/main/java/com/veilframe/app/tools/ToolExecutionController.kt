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
        executionJob?.cancel()
        executionJob = null
        val state = sessionManager.currentState
        sessionManager.updateJobState(JobState.CANCELLED, "Processing cancelled by user.")
        onLog("[USER] Execution cancelled by user.")
        binding.progressIndicator.isIndeterminate = false
        sessionManager.updatePrimaryActionDock(state)
    }

    fun executeSelectedMode() {
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

        binding.btnExecute.text = "Cancel processing"
        binding.btnExecute.setIconResource(R.drawable.ic_action_clear)
        binding.btnExportResult.isEnabled = false
        binding.btnShareResult.isEnabled = false
        binding.cardResultSummary.visibility = View.GONE
        binding.btnResultPreview.visibility = View.GONE
        binding.progressIndicator.visibility = View.VISIBLE
        binding.progressIndicator.isIndeterminate = true
        binding.tvProgressDetails.text = ""

        executionJob = scope.launch {
            when (sessionManager.currentToolMode) {
                ToolMode.AI_BUNDLE -> runAiBundle(uri)
                ToolMode.VIDEO_CLEANER -> runVideoSanitization(uri)
                ToolMode.IMAGE_CLEANER -> runImageSanitization(uri)
                ToolMode.FOLDER_SCANNER -> runFolderScan(uri)
                ToolMode.IMAGE_COMPRESSOR -> getImageStudioController()?.execute()
                ToolMode.VIDEO_COMPRESSOR -> getVideoStudioController()?.execute()
                ToolMode.IMAGE_UPSCALER -> { /* Handled within ImageUpscalerController */ }
            }
            sessionManager.updatePrimaryActionDock(sessionManager.currentState)
        }
    }

    private suspend fun runAiBundle(uri: Uri) {
        val state = sessionManager.currentState
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

        withContext(Dispatchers.Main) {
            sessionManager.updateJobState(JobState.PROCESSING, "Packaging AI context bundle...")
            onLog("[AI] Packaging context directly from target: ${state.selectedPathDisplay} (Budget: ${if (tokenBudget == 0L) "Unlimited" else "$tokenBudget tokens"})...")
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(activity.cacheDir, "AIBundle_$timeStamp.$formatKey")

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
            maxDepth = maxDepth
        )

        val bundleResult = withContext(Dispatchers.IO) {
            if (state.isFolderSelected) {
                AiBundleEngine.buildBundleFromSaf(
                    context = activity,
                    treeUri = uri,
                    outputFile = outputFile,
                    config = bundleConfig,
                    onProgress = { currentFile, included ->
                        activity.runOnUiThread {
                            binding.tvStatusText.text = "Bundled $included files ($currentFile)..."
                        }
                    }
                )
            } else {
                val name = safStorageManager.getDisplayName(uri)
                val raw = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val content = if (bundleConfig.maskSecrets) AiBundleEngine.maskSecrets(raw) else raw
                val finalDocument = when (formatKey.lowercase(Locale.US)) {
                    "json" -> """
                        {
                            "project": "${name.replace("\\", "\\\\")}",
                            "generated": "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}",
                            "included_files": 1,
                            "estimated_tokens": ${content.length / 4},
                            "files": {
                                "${name.replace("\\", "\\\\")}": ${org.json.JSONObject.quote(content)}
                            }
                        }
                    """.trimIndent()
                    "aibundle" -> {
                        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                        "@VEILFRAME_BUNDLE\nversion=1\nproject=$name\ntimestamp=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\ntotal_tokens=${content.length / 4}\nincluded_files=1\n\n@FILES\n@FILE id=\"F001\" path=\"$name\" language=\"$ext\"\n<<<\n$content\n>>>\n\n@END\n"
                    }
                    else -> "# Context: $name\n\n```\n$content\n```"
                }
                outputFile.writeText(finalDocument, Charsets.UTF_8)
                AiBundleEngine.BundleResult(
                    totalFiles = 1,
                    includedFiles = 1,
                    totalTokens = (content.length / 4).toLong(),
                    outputPath = outputFile.absolutePath
                )
            }
        }

        state.lastGeneratedFile = outputFile
        state.progressPercent = 100
        state.progressDetailsText = "100% complete"

        withContext(Dispatchers.Main) {
            sessionManager.updateJobState(JobState.COMPLETE, "Bundle ready: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
            binding.progressIndicator.isIndeterminate = false
            binding.progressIndicator.progress = 100
            binding.tvProgressDetails.text = "100% complete"

            binding.cardResultSummary.visibility = View.VISIBLE
            binding.tvResultTitle.text = outputFile.name
            binding.tvResultDetails.text = "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • ${bundleResult.includedFiles} files included"
            val isMd = outputFile.extension.equals("md", ignoreCase = true) || outputFile.extension.equals("markdown", ignoreCase = true)
            binding.btnResultPreview.visibility = if (isMd) View.VISIBLE else View.GONE

            onLog("[OK] Package generated: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
            onLog("[AI] Tokens: ~${bundleResult.totalTokens} | Included files: ${bundleResult.includedFiles} | Format: $formatKey")
            onLog("[EXPORT] Output ready for saving or sharing.")
            binding.btnExecute.isEnabled = true
            binding.btnExportResult.isEnabled = true
            binding.btnShareResult.isEnabled = true
        }
    }

    private suspend fun runVideoSanitization(uri: Uri) {
        val state = sessionManager.currentState
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
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backend = AndroidMediaBackend(activity)

        if (state.isFolderSelected) {
            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PREPARING, "Extracting video files from folder...")
                onLog("[BATCH] Scanning folder for video files: ${state.selectedPathDisplay}...")
            }

            val stagingDir = File(activity.cacheDir, "batch_video_staging_$timeStamp")
            val outputBatchDir = File(activity.cacheDir, "batch_video_sanitized_$timeStamp").apply { mkdirs() }
            safStorageManager.materializeTargetIntoDir(uri, true, stagingDir)
            val inspectedCount = stagingDir.walkTopDown().count { it.isFile }

            val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "avi", "flv", "m4v", "wmv", "3gp")
            val candidateFiles = stagingDir.walkTopDown().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in videoExtensions }.toList()

            if (candidateFiles.isEmpty()) {
                stagingDir.deleteRecursively()
                outputBatchDir.deleteRecursively()
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
            candidateFiles.forEachIndexed { idx, inputFile ->
                val targetExt = when (formatIndex) {
                    1 -> "mp4"
                    2 -> "mkv"
                    3 -> "webm"
                    else -> inputFile.extension.lowercase(Locale.ROOT).ifEmpty { "mp4" }
                }
                val outputFile = File(outputBatchDir, "${inputFile.nameWithoutExtension}_sanitized.$targetExt")

                withContext(Dispatchers.Main) {
                    val percent = ((idx * 100) / candidateFiles.size)
                    binding.progressIndicator.isIndeterminate = false
                    binding.progressIndicator.progress = percent
                    binding.tvProgressDetails.text = "Sanitizing ${idx + 1}/${candidateFiles.size}: ${inputFile.name}"
                    onLog("[RUN] [${idx + 1}/${candidateFiles.size}] Sanitizing ${inputFile.name} -> $targetExt (noise: $noiseLevel)")
                }

                val ok = withContext(Dispatchers.IO) {
                    backend.cleanVideo(
                        inputFile.absolutePath,
                        outputFile.absolutePath,
                        noiseLevel = noiseLevel,
                        scrubAudio = scrubAudio,
                        crf = crf,
                        stripExif = stripExif,
                        reencodeBitstream = reencodeBitstream
                    )
                }
                if (ok && outputFile.exists() && outputFile.length() > 0) {
                    successCount++
                }
            }

            stagingDir.deleteRecursively()

            if (successCount == 0) {
                outputBatchDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.FAILED, "Batch video sanitization failed.")
                    onLog("[ERR] Failed to sanitize any video files in the batch.")
                    sessionManager.updatePrimaryActionDock(state)
                }
                return
            }

            val finalFile: File
            if (successCount == 1) {
                val singleFile = outputBatchDir.listFiles()?.firstOrNull() ?: outputBatchDir
                finalFile = File(activity.cacheDir, singleFile.name)
                singleFile.copyTo(finalFile, overwrite = true)
                outputBatchDir.deleteRecursively()
            } else {
                finalFile = File(activity.cacheDir, "VeilFrame_Batch_Videos_$timeStamp.zip")
                safStorageManager.zipDirectory(outputBatchDir, finalFile)
                outputBatchDir.deleteRecursively()
            }

            state.lastGeneratedFile = finalFile
            state.progressPercent = 100
            state.progressDetailsText = "100% complete"

            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.COMPLETE, "Batch ready: ${finalFile.name} (${safStorageManager.formatBytes(finalFile.length())})")
                binding.progressIndicator.isIndeterminate = false
                binding.progressIndicator.progress = 100
                binding.tvProgressDetails.text = "100% complete"

                binding.cardResultSummary.visibility = View.VISIBLE
                binding.tvResultTitle.text = finalFile.name
                binding.tvResultDetails.text = "${safStorageManager.formatBytes(finalFile.length())} • ${finalFile.extension.uppercase()} • $successCount videos sanitized"

                onLog("[OK] Batch completed: $successCount/${candidateFiles.size} videos sanitized successfully.")
                onLog("[EXPORT] Output ready for saving or sharing: ${finalFile.name}")
                sessionManager.updatePrimaryActionDock(state)
                binding.btnExportResult.isEnabled = true
                binding.btnShareResult.isEnabled = true
            }
        } else {
            // Single video file
            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PREPARING, "Staging video file...")
                onLog("[PREP] Staging video: ${state.selectedPathDisplay}...")
            }

            val sourceExt = state.selectedPathDisplay.substringAfterLast('.', "mp4").lowercase(Locale.ROOT)
            val targetExt = when (formatIndex) {
                1 -> "mp4"
                2 -> "mkv"
                3 -> "webm"
                else -> sourceExt.ifEmpty { "mp4" }
            }

            val tempInput = File(activity.cacheDir, "input_video_${System.currentTimeMillis()}.$sourceExt")
            val successCopy = safStorageManager.copyUriToFile(uri, tempInput)
            if (!successCopy) {
                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.FAILED, "Failed to copy input video.")
                    onLog("[ERR] Failed to copy input video from storage.")
                    sessionManager.updatePrimaryActionDock(state)
                }
                return
            }

            val outputFile = File(activity.cacheDir, "Sanitized_$timeStamp.$targetExt")

            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PROCESSING, "Sanitizing video stream (noise: $noiseLevel)...")
                onLog("[RUN] Applying sensor fingerprint defense ($noiseLevel) & stripping metadata...")
            }

            val processed = withContext(Dispatchers.IO) {
                backend.cleanVideo(
                    tempInput.absolutePath,
                    outputFile.absolutePath,
                    noiseLevel = noiseLevel,
                    scrubAudio = scrubAudio,
                    crf = crf,
                    stripExif = stripExif,
                    reencodeBitstream = reencodeBitstream
                )
            }

            tempInput.delete()

            if (processed && outputFile.exists() && outputFile.length() > 0) {
                state.lastGeneratedFile = outputFile
                state.progressPercent = 100
                state.progressDetailsText = "100% complete"

                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.COMPLETE, "Video ready: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
                    binding.progressIndicator.isIndeterminate = false
                    binding.progressIndicator.progress = 100
                    binding.tvProgressDetails.text = "100% complete"

                    binding.cardResultSummary.visibility = View.VISIBLE
                    binding.tvResultTitle.text = outputFile.name
                    binding.tvResultDetails.text = "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • Scrubbed and verified"

                    onLog("[OK] Video sanitized successfully: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
                    onLog("[EXPORT] Output ready for saving or sharing.")
                    sessionManager.updatePrimaryActionDock(state)
                    binding.btnExportResult.isEnabled = true
                    binding.btnShareResult.isEnabled = true
                }
            } else {
                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.FAILED, "Video sanitization failed.")
                    binding.progressIndicator.isIndeterminate = false
                    binding.cardResultSummary.visibility = View.GONE
                    onLog("[ERR] Video engine reported failure.")
                    sessionManager.updatePrimaryActionDock(state)
                }
            }
        }
    }

    private suspend fun runImageSanitization(uri: Uri) {
        val state = sessionManager.currentState
        val formatIndex = sessionManager.getSelectedFormatIndex()
        val quality = when (sessionManager.getSelectedOptionIndex()) {
            0 -> 95
            1 -> 90
            2 -> 85
            else -> state.sliderValue.toInt().coerceIn(50, 100)
        }
        val stripExif = binding.switchOption1.isChecked
        val stripThumbnails = binding.switchOption2.isChecked
        val sanitizeIcc = binding.switchOption3.isChecked
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backend = AndroidMediaBackend(activity)

        if (state.isFolderSelected) {
            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PREPARING, "Extracting images from folder...")
                onLog("[BATCH] Scanning folder for images: ${state.selectedPathDisplay}...")
            }

            val stagingDir = File(activity.cacheDir, "batch_img_staging_$timeStamp")
            val outputBatchDir = File(activity.cacheDir, "batch_img_sanitized_$timeStamp").apply { mkdirs() }
            safStorageManager.materializeTargetIntoDir(uri, true, stagingDir)
            val inspectedCount = stagingDir.walkTopDown().count { it.isFile }

            val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif", "tiff", "tif", "gif")
            val candidateFiles = stagingDir.walkTopDown().filter { it.isFile && it.extension.lowercase(Locale.ROOT) in imageExtensions }.toList()

            if (candidateFiles.isEmpty()) {
                stagingDir.deleteRecursively()
                outputBatchDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.FAILED, "No supported image files found in folder.")
                    onLog("[WARN] No image files (.jpg, .png, .webp, .bmp, .heic, etc.) found in selected folder ($inspectedCount files inspected).")
                    sessionManager.updatePrimaryActionDock(state)
                }
                return
            }

            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PROCESSING, "Scrubbing ${candidateFiles.size} images (Quality: $quality%)...")
                onLog("[BATCH] Found ${candidateFiles.size} images with mixed formats. Beginning batch scrubbing...")
            }

            var successCount = 0
            candidateFiles.forEachIndexed { idx, inputFile ->
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
                val outputFile = File(outputBatchDir, "${inputFile.nameWithoutExtension}_cleaned.$targetExt")

                withContext(Dispatchers.Main) {
                    val percent = ((idx * 100) / candidateFiles.size)
                    binding.progressIndicator.isIndeterminate = false
                    binding.progressIndicator.progress = percent
                    binding.tvProgressDetails.text = "Scrubbing ${idx + 1}/${candidateFiles.size}: ${inputFile.name}"
                    onLog("[RUN] [${idx + 1}/${candidateFiles.size}] Scrubbing ${inputFile.name} -> $targetExt")
                }

                val ok = withContext(Dispatchers.IO) {
                    backend.cleanImage(
                        inputFile.absolutePath,
                        outputFile.absolutePath,
                        stripExif = stripExif,
                        quality = quality,
                        targetExtension = targetExt,
                        stripThumbnails = stripThumbnails,
                        sanitizeIcc = sanitizeIcc
                    )
                }
                if (ok && outputFile.exists() && outputFile.length() > 0) {
                    successCount++
                }
            }

            stagingDir.deleteRecursively()

            if (successCount == 0) {
                outputBatchDir.deleteRecursively()
                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.FAILED, "Batch image sanitization failed.")
                    onLog("[ERR] Failed to sanitize any image files in the batch.")
                    sessionManager.updatePrimaryActionDock(state)
                }
                return
            }

            val finalFile: File
            if (successCount == 1) {
                val singleFile = outputBatchDir.listFiles()?.firstOrNull() ?: outputBatchDir
                finalFile = File(activity.cacheDir, singleFile.name)
                singleFile.copyTo(finalFile, overwrite = true)
                outputBatchDir.deleteRecursively()
            } else {
                finalFile = File(activity.cacheDir, "VeilFrame_Batch_Images_$timeStamp.zip")
                safStorageManager.zipDirectory(outputBatchDir, finalFile)
                outputBatchDir.deleteRecursively()
            }

            state.lastGeneratedFile = finalFile
            state.progressPercent = 100
            state.progressDetailsText = "100% complete"

            withContext(Dispatchers.Main) {
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
        } else {
            // Single image file
            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PREPARING, "Staging image...")
                onLog("[PREP] Staging image: ${state.selectedPathDisplay}...")
            }

            val sourceExt = state.selectedPathDisplay.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
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

            val tempInput = File(activity.cacheDir, "input_img_${System.currentTimeMillis()}.$sourceExt")
            val successCopy = safStorageManager.copyUriToFile(uri, tempInput)
            if (!successCopy) {
                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.FAILED, "Failed to copy input image.")
                    onLog("[ERR] Failed to copy input image from storage.")
                    sessionManager.updatePrimaryActionDock(state)
                }
                return
            }

            val outputFile = File(activity.cacheDir, "Cleaned_$timeStamp.$targetExt")

            withContext(Dispatchers.Main) {
                sessionManager.updateJobState(JobState.PROCESSING, "Scrubbing EXIF & re-encoding clean pixels (Quality: $quality%)...")
                onLog("[RUN] Scrubbing metadata & stripping embedded thumbnails...")
            }

            val processed = withContext(Dispatchers.IO) {
                backend.cleanImage(
                    tempInput.absolutePath,
                    outputFile.absolutePath,
                    stripExif = stripExif,
                    quality = quality,
                    targetExtension = targetExt,
                    stripThumbnails = stripThumbnails,
                    sanitizeIcc = sanitizeIcc
                )
            }

            tempInput.delete()

            if (processed && outputFile.exists() && outputFile.length() > 0) {
                state.lastGeneratedFile = outputFile
                state.progressPercent = 100
                state.progressDetailsText = "100% complete"

                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.COMPLETE, "Image clean: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
                    binding.progressIndicator.isIndeterminate = false
                    binding.progressIndicator.progress = 100
                    binding.tvProgressDetails.text = "100% complete"

                    binding.cardResultSummary.visibility = View.VISIBLE
                    binding.tvResultTitle.text = outputFile.name
                    binding.tvResultDetails.text = "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • Metadata stripped"

                    onLog("[OK] Image scrubbed successfully: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
                    onLog("[EXPORT] Output ready for saving or sharing.")
                    sessionManager.updatePrimaryActionDock(state)
                    binding.btnExportResult.isEnabled = true
                    binding.btnShareResult.isEnabled = true
                }
            } else {
                withContext(Dispatchers.Main) {
                    sessionManager.updateJobState(JobState.FAILED, "Image sanitization failed.")
                    binding.progressIndicator.isIndeterminate = false
                    binding.cardResultSummary.visibility = View.GONE
                    onLog("[ERR] Image cleaner reported failure.")
                    sessionManager.updatePrimaryActionDock(state)
                }
            }
        }
    }

    private suspend fun runFolderScan(uri: Uri) {
        val state = sessionManager.currentState
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

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(activity.cacheDir, "Scan_Report_$timeStamp.$formatExt")

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
                            binding.tvStatusText.text = "Scanned $count files ($currentFile)..."
                        }
                    }
                )
                FolderScannerEngine.exportReport(res, outputFile, formatExt)
                res
            } else {
                val name = safStorageManager.getDisplayName(uri)
                val size = activity.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
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

        val scanSummary = "${scanResult.totalFiles} files analyzed."
        state.lastGeneratedFile = outputFile
        state.progressPercent = 100
        state.progressDetailsText = "100% complete"

        withContext(Dispatchers.Main) {
            sessionManager.updateJobState(JobState.COMPLETE, "Audit ready: ${outputFile.name} (${safStorageManager.formatBytes(outputFile.length())})")
            binding.progressIndicator.isIndeterminate = false
            binding.progressIndicator.progress = 100
            binding.tvProgressDetails.text = "100% complete"

            binding.cardResultSummary.visibility = View.VISIBLE
            binding.tvResultTitle.text = outputFile.name
            binding.tvResultDetails.text = "${safStorageManager.formatBytes(outputFile.length())} • ${outputFile.extension.uppercase()} • $scanSummary"
            val isMd = outputFile.extension.equals("md", ignoreCase = true) || outputFile.extension.equals("markdown", ignoreCase = true)
            binding.btnResultPreview.visibility = if (isMd) View.VISIBLE else View.GONE

            onLog("[OK] Audit report created: ${outputFile.name} ($scanSummary)")
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
    }

    fun shareLastResult() {
        val file = sessionManager.currentState.lastGeneratedFile
        if (file == null || !file.exists()) {
            Toast.makeText(activity, "No output artifact to share", Toast.LENGTH_SHORT).show()
            return
        }

        try {
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
        } catch (e: Exception) {
            Toast.makeText(activity, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
