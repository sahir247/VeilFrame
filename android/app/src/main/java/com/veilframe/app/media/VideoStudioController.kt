package com.veilframe.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chaquo.python.Python
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.veilframe.app.R
import com.veilframe.app.databinding.DialogVideoAspectBinding
import com.veilframe.app.databinding.DialogVideoAudioBinding
import com.veilframe.app.databinding.DialogVideoPresetBinding
import com.veilframe.app.databinding.DialogVideoScaleBinding
import com.veilframe.app.databinding.DialogVideoSpeedBinding
import com.veilframe.app.databinding.DialogVideoTrimBinding
import com.veilframe.app.databinding.LayoutVideoStudioBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Controller managing the Mobile Video Studio workspace.
 * Coordinates between UI controls, edit models, and the dedicated VideoPlayerController.
 */
class VideoStudioController(
    private val activity: AppCompatActivity,
    private val binding: LayoutVideoStudioBinding,
    val playerController: VideoPlayerController,
    private val safManager: SafDestinationManager,
    private val scope: CoroutineScope,
    private val getPython: () -> Python?,
    private val onPickVideoRequest: () -> Unit,
    private val onPickFolderRequest: () -> Unit,
    private val onExportFileRequest: (File) -> Unit,
    private val onShareFileRequest: (File, String) -> Unit,
    private val onNavigateHome: () -> Unit
) {
    val editState = VideoEditState()
    val outputConfig = VideoOutputConfig()

    var selectedUri: Uri? = null
        private set
    var originalFile: File? = null
        private set
    var originalBytes: Long = 0L
        private set
    var lastResultFile: File? = null
        private set

    private var compressionJob: Job? = null
    private var isScrubbing: Boolean = false

    fun initWorkspace() {
        binding.btnVidStudioMenu.setOnClickListener { onNavigateHome() }
        binding.btnSelectVideo.setOnClickListener { onPickVideoRequest() }
        binding.btnVidClearAll.setOnClickListener { clear() }
        binding.btnVidRemoveFile.setOnClickListener { clear() }

        // SAF destination folder selection
        binding.tvVidDestinationPath.text = safManager.videoDestinationName
        binding.btnVidChangeDestination.setOnClickListener { onPickFolderRequest() }

        // Reset all edits button
        binding.btnVidResetAllEdits.setOnClickListener { resetEdits() }

        // Player transport controls
        binding.btnPlayerPlayPause.setOnClickListener { playerController.togglePlayPause() }
        binding.btnPlayerReplay.setOnClickListener { playerController.replay() }
        binding.btnPlayerMute.setOnClickListener { toggleMute() }

        // Wire player callbacks
        playerController.onPlaybackStateChange = { isPlaying ->
            binding.btnPlayerPlayPause.setIconResource(if (isPlaying) R.drawable.ic_action_pause else R.drawable.ic_action_play)
        }

        playerController.onErrorListener = { _, _ ->
            binding.playerBufferingBar.visibility = View.GONE
            Toast.makeText(activity, "Playback warning: video preview format partially supported", Toast.LENGTH_SHORT).show()
        }

        playerController.onProgressUpdate = { posMs ->
            if (!isScrubbing) {
                val sec = posMs / 1000f
                binding.playerScrubber.value = sec.coerceIn(binding.playerScrubber.valueFrom, binding.playerScrubber.valueTo)
                binding.tvPlayerPosition.text = formatDuration(posMs)
            }
        }

        // Timeline scrubber
        binding.playerScrubber.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isScrubbing = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                isScrubbing = false
                val seekMs = (slider.value * 1000).toLong()
                playerController.seekTo(seekMs)
                binding.tvPlayerPosition.text = formatDuration(seekMs)
            }
        })
        binding.playerScrubber.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val seekMs = (value * 1000).toLong()
                binding.tvPlayerPosition.text = formatDuration(seekMs)
            }
        }

        // Dual-thumb RangeSlider timeline trimmer
        binding.rangeSliderVidTrim.addOnChangeListener { slider, _, _ ->
            val startSec = slider.values[0]
            val endSec = slider.values[1]
            editState.trimStartMs = (startSec * 1000).toLong()
            editState.trimEndMs = (endSec * 1000).toLong()

            binding.tvVidTrimStartLabel.text = formatDuration(editState.trimStartMs)
            binding.tvVidTrimEndLabel.text = formatDuration(editState.trimEndMs)
            binding.tvVidTrimDurationLabel.text = "Trimmed: ${formatDuration(editState.trimmedDurationMs)}"

            playerController.setTrimBounds(editState.trimStartMs, editState.trimEndMs)
            refreshStats()
            updateEditSummary()
        }

        // Output Formats
        binding.chipGroupVidFormat.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = binding.chipGroupVidFormat.findViewById<Chip>(checkedIds[0])
                val text = chip?.text?.toString() ?: "MP4"
                if (text.contains("GIF", ignoreCase = true)) {
                    outputConfig.format = "GIF"
                    outputConfig.outputMode = VideoOutputMode.GIF
                } else {
                    outputConfig.outputMode = VideoOutputMode.VIDEO
                    outputConfig.format = when (text.take(3)) {
                        "MKV" -> "MKV"
                        "Web" -> "WebM"
                        else -> "MP4"
                    }
                }
                updateUiForOutputMode()
                updateOutputFilenameExtension()
                refreshStats()
            }
        }

        binding.chipGroupVidCodec.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = binding.chipGroupVidCodec.findViewById<Chip>(checkedIds[0])
                outputConfig.codec = if (chip?.text?.toString()?.contains("265") == true) "H.265" else "H.264"
                refreshStats()
            }
        }

        // In-place CRF quality slider
        binding.sliderVidQuality.addOnChangeListener { _, value, _ ->
            outputConfig.crf = value.toInt()
            binding.tvVidQualityValue.text = "CRF ${outputConfig.crf}"
            refreshStats()
        }

        // In-place resolution chips
        binding.chipGroupVidResolution.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = binding.chipGroupVidResolution.findViewById<Chip>(checkedIds[0])
                editState.scalePreset = when (chip?.id) {
                    R.id.chipVidRes1080p -> "1080p (Full HD)"
                    R.id.chipVidRes720p -> "720p (HD)"
                    R.id.chipVidRes480p -> "480p (SD Compact)"
                    R.id.chipVidRes360p -> "360p (Ultra Small)"
                    else -> "Original (No scaling)"
                }
                refreshStats()
                updateEditSummary()
            }
        }

        // In-place audio chips
        binding.chipGroupVidAudio.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = binding.chipGroupVidAudio.findViewById<Chip>(checkedIds[0])
                editState.audioMode = if (chip?.id == R.id.chipVidAudioRemove) AudioMode.MUTE else AudioMode.KEEP
                playerController.setMute(editState.audioMode == AudioMode.MUTE)
                binding.btnPlayerMute.setIconResource(if (editState.audioMode == AudioMode.MUTE) R.drawable.ic_audio_volume_off else R.drawable.ic_audio_volume)
                refreshStats()
                updateEditSummary()
            }
        }

        // Tool buttons
        binding.toolVidTrim.setOnClickListener { showTrimDialog() }
        binding.toolVidScale.setOnClickListener { showScaleDialog() }
        binding.toolVidPreset.setOnClickListener { showPresetDialog() }
        binding.toolVidSpeed.setOnClickListener { showSpeedDialog() }
        binding.toolVidAspect.setOnClickListener { showAspectDialog() }
        binding.toolVidAudio.setOnClickListener { showAudioDialog() }

        // Compression execution
        binding.btnVidExecute.setOnClickListener { handleExecute() }

        // Result save & share
        binding.btnVidSaveResult.setOnClickListener {
            val f = lastResultFile
            if (f != null && f.exists()) onExportFileRequest(f)
        }
        binding.btnVidShareResult.setOnClickListener {
            val f = lastResultFile
            if (f != null && f.exists()) onShareFileRequest(f, "video/*")
        }
    }

    fun onDestinationFolderSelected(uri: Uri) {
        safManager.takePersistablePermission(uri)
        safManager.videoDestinationUri = uri
        val name = safManager.getFolderDisplayName(uri)
        safManager.videoDestinationName = name
        binding.tvVidDestinationPath.text = name
    }

    private fun updateRangeSliderSafely(
        slider: com.google.android.material.slider.RangeSlider,
        from: Float,
        to: Float,
        start: Float,
        end: Float
    ) {
        val safeFrom = from
        val safeTo = if (to <= safeFrom) safeFrom + 1f else to
        val safeStart = start.coerceIn(safeFrom, safeTo)
        val safeEnd = end.coerceIn(safeStart, safeTo)

        try {
            val curMin = slider.valueFrom
            slider.values = listOf(curMin, curMin)
        } catch (_: Exception) {}

        slider.valueFrom = safeFrom
        slider.valueTo = safeTo
        slider.values = listOf(safeStart, safeEnd)
    }

    private fun updateSliderSafely(
        slider: com.google.android.material.slider.Slider,
        from: Float,
        to: Float,
        value: Float
    ) {
        val safeFrom = from
        val safeTo = if (to <= safeFrom) safeFrom + 1f else to
        val safeVal = value.coerceIn(safeFrom, safeTo)

        try {
            slider.value = slider.valueFrom
        } catch (_: Exception) {}

        slider.valueFrom = safeFrom
        slider.valueTo = safeTo
        slider.value = safeVal
    }

    fun handleVideoSelected(uri: Uri) {
        val mimeType = activity.contentResolver.getType(uri) ?: ""
        val displayName = getDisplayName(uri)
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)

        val supportedExts = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi", "3gp", "flv", "ts", "wmv")
        val isVideoMime = mimeType.startsWith("video/") || mimeType.contains("matroska") || mimeType == "application/octet-stream"

        if (!supportedExts.contains(ext) && !isVideoMime && mimeType.isNotEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Unsupported video format")
                .setMessage("Please select a supported video file (MP4, MOV, WEBM, MKV, AVI, etc.).")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        selectedUri = uri
        originalBytes = queryFileSize(uri)

        // Clear previous temporary studio cache files to prevent memory/storage leaks
        try {
            activity.cacheDir.listFiles()?.filter { it.name.startsWith("studio_input_") }?.forEach { it.delete() }
        } catch (_: Exception) {}

        scope.launch(Dispatchers.IO) {
            val cacheFile = File(activity.cacheDir, "studio_input_$displayName")
            try {
                copyUriToFile(uri, cacheFile)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Unable to access this video")
                        .setMessage("Please grant file access and try again.\n${e.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@launch
            }
            originalFile = cacheFile

            var duration = 0L
            var width = 0
            var height = 0
            var thumbFrame: Bitmap? = null

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(cacheFile.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                if (durStr != null) duration = durStr.toLong()
                val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                if (wStr != null) width = wStr.toInt()
                val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (hStr != null) height = hStr.toInt()
                thumbFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
            } catch (e: Exception) {
                Log.w("VeilFrame.VideoStudio", "MediaMetadataRetriever error: ${e.message}")
            }

            if (duration <= 0L && thumbFrame == null) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Unable to read this video")
                        .setMessage("The file may be corrupted or unsupported.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@launch
            }

            if (duration <= 0L) duration = 1000L
            if (width <= 0) width = 1280
            if (height <= 0) height = 720

            withContext(Dispatchers.Main) {
                editState.reset(duration)
                editState.originalWidth = width
                editState.originalHeight = height

                binding.layoutVidEmptyState.visibility = View.GONE
                binding.layoutVidSelectedState.visibility = View.VISIBLE
                binding.tvVidSelectedCount.text = "1 video selected"
                binding.tvVidFileName.text = displayName
                binding.tvVidFileDetails.text = "${formatBytes(originalBytes)} • ${formatDuration(duration)} • ${width}x${height}"
                binding.imgVidThumb.setImageBitmap(thumbFrame)
                binding.imgVidPreview.setImageBitmap(thumbFrame)
                binding.btnVidClearAll.isEnabled = true

                // Enable tools
                binding.toolVidTrim.isEnabled = true
                binding.toolVidScale.isEnabled = true
                binding.toolVidPreset.isEnabled = true
                binding.toolVidSpeed.isEnabled = true
                binding.toolVidAspect.isEnabled = true
                binding.toolVidAudio.isEnabled = true

                val durSec = (duration / 1000.0).toFloat().coerceAtLeast(1f)
                updateRangeSliderSafely(binding.rangeSliderVidTrim, 0f, durSec, 0f, durSec)
                updateSliderSafely(binding.playerScrubber, 0f, durSec, 0f)

                binding.tvVidTrimStartLabel.text = "00:00.0"
                binding.tvVidTrimEndLabel.text = formatDuration(duration)
                binding.tvVidTrimDurationLabel.text = "Trimmed: ${formatDuration(duration)}"
                binding.tvPlayerPosition.text = "00:00.0"
                binding.tvPlayerTotalDuration.text = formatDuration(duration)
                binding.tvPlayerSpeedBadge.text = "${editState.speed}×"

                binding.tvVidBeforeStats.text = "${formatBytes(originalBytes)} • ${formatDuration(duration)} • ${width}x${height}"

                val baseName = displayName.substringBeforeLast('.')
                binding.etVidOutputFilename.setText("compressed_${baseName}.${outputConfig.format.lowercase(Locale.US)}")

                binding.tvVidActualStats.visibility = View.GONE
                binding.layoutVidResultActions.visibility = View.GONE

                // Set data source on dedicated VideoPlayerController using seekable local cache file
                binding.playerBufferingBar.visibility = View.VISIBLE
                playerController.onPreparedListener = { durMs, w, h ->
                    binding.playerBufferingBar.visibility = View.GONE
                    binding.imgVidPreview.visibility = View.GONE
                }
                playerController.onErrorListener = { what, extra ->
                    binding.playerBufferingBar.visibility = View.GONE
                    Log.w("VeilFrame.VideoStudio", "MediaPlayer warning/error: what=$what extra=$extra")
                }
                playerController.setDataSource(Uri.fromFile(cacheFile))

                applyAspectRatioPreview()
                refreshStats()
                updateEditSummary()
                binding.btnVidExecute.text = "Compress"
            }
        }
    }

    fun applyAspectRatioPreview() {
        val viewport = binding.videoPlayerViewport
        val params = viewport.layoutParams as? FrameLayout.LayoutParams ?: return

        when (editState.aspect) {
            "9:16 (Reel / Shorts / TikTok)", "9:16" -> {
                params.width = FrameLayout.LayoutParams.WRAP_CONTENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.CENTER
            }
            "1:1 (Square Feed)", "1:1" -> {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.CENTER
            }
            else -> {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.CENTER
            }
        }
        viewport.layoutParams = params
    }

    private fun toggleMute() {
        val newMuted = !playerController.isMuted
        playerController.setMute(newMuted)
        editState.audioMode = if (newMuted) AudioMode.MUTE else AudioMode.KEEP
        binding.btnPlayerMute.setIconResource(if (newMuted) R.drawable.ic_audio_volume_off else R.drawable.ic_audio_volume)
        if (newMuted) {
            binding.chipVidAudioRemove.isChecked = true
        } else {
            binding.chipVidAudioKeep.isChecked = true
        }
        refreshStats()
        updateEditSummary()
        Toast.makeText(activity, if (newMuted) "Player muted" else "Player unmuted", Toast.LENGTH_SHORT).show()
    }

    fun updateUiForOutputMode() {
        val isGif = outputConfig.outputMode == VideoOutputMode.GIF
        if (isGif) {
            binding.chipGroupVidCodec.visibility = View.GONE
            binding.toolVidAudio.isEnabled = false
            binding.toolVidAudio.alpha = 0.4f
            binding.chipGroupVidAudio.visibility = View.GONE
            editState.audioMode = AudioMode.MUTE
        } else {
            binding.chipGroupVidCodec.visibility = View.VISIBLE
            binding.toolVidAudio.isEnabled = true
            binding.toolVidAudio.alpha = 1.0f
            binding.chipGroupVidAudio.visibility = View.VISIBLE
        }
    }

    fun updateOutputFilenameExtension() {
        val currentName = binding.etVidOutputFilename.text.toString().trim()
        if (currentName.isNotEmpty()) {
            val base = currentName.substringBeforeLast('.')
            val ext = if (outputConfig.outputMode == VideoOutputMode.GIF) "gif" else outputConfig.format.lowercase()
            binding.etVidOutputFilename.setText("${base}.${ext}")
        }
    }

    fun refreshStats() {
        val durationSec = (editState.trimmedDurationSeconds / editState.speed).coerceAtLeast(0.1)

        val estBytes = when (outputConfig.targetPreset) {
            "WhatsApp (16 MB)" -> (15.5 * 1024 * 1024).toLong()
            "Discord (25 MB)" -> (24.0 * 1024 * 1024).toLong()
            "Email Attachment (8 MB)" -> (7.8 * 1024 * 1024).toLong()
            else -> {
                val baseKbps = when (editState.scalePreset) {
                    "1080p (Full HD)" -> 3500
                    "720p (HD)" -> 1800
                    "480p (SD Compact)" -> 900
                    "360p (Ultra Small)" -> 500
                    else -> 2500
                }
                val crfFactor = Math.pow(0.92, (outputConfig.crf - 23).toDouble())
                val estKbps = (baseKbps * crfFactor).toLong().coerceIn(200, 8000)
                val totalBytes = ((estKbps * 1000 / 8) * durationSec).toLong()
                if (originalBytes > 0) totalBytes.coerceAtMost(originalBytes) else totalBytes
            }
        }

        val formatDesc = if (outputConfig.outputMode == VideoOutputMode.GIF) "GIF (Animated)" else outputConfig.format
        binding.tvVidAfterStats.text = "~${formatBytes(estBytes)} • ${formatDuration(editState.trimmedDurationMs)} • $formatDesc"
        updateEditSummary()
    }

    fun updateEditSummary() {
        val hasEdits = editState.hasEdits() || outputConfig.outputMode == VideoOutputMode.GIF
        if (!hasEdits) {
            binding.tvVidSummaryEmpty.visibility = View.VISIBLE
            binding.layoutVidSummaryDetails.visibility = View.GONE
        } else {
            binding.tvVidSummaryEmpty.visibility = View.GONE
            binding.layoutVidSummaryDetails.visibility = View.VISIBLE

            val hasTrim = editState.trimStartMs > 0L || (editState.durationMs > 0L && editState.trimEndMs < editState.durationMs)
            if (hasTrim) {
                binding.tvVidSummaryTrim.visibility = View.VISIBLE
                binding.tvVidSummaryTrim.text = "✓ Trim: ${formatDuration(editState.trimStartMs)} → ${formatDuration(editState.trimEndMs)} (${formatDuration(editState.trimmedDurationMs)})"
            } else {
                binding.tvVidSummaryTrim.visibility = View.GONE
            }

            if (editState.scalePreset != "Original (No scaling)" && editState.scalePreset != "Original") {
                binding.tvVidSummaryScale.visibility = View.VISIBLE
                binding.tvVidSummaryScale.text = "✓ Resolution: ${editState.scalePreset}"
            } else {
                binding.tvVidSummaryScale.visibility = View.GONE
            }

            if (editState.aspect != "Original") {
                binding.tvVidSummaryAspect.visibility = View.VISIBLE
                binding.tvVidSummaryAspect.text = "✓ Aspect: ${editState.aspect}"
            } else {
                binding.tvVidSummaryAspect.visibility = View.GONE
            }

            if (editState.speed != 1.0f) {
                binding.tvVidSummarySpeed.visibility = View.VISIBLE
                binding.tvVidSummarySpeed.text = "✓ Speed: ${editState.speed}×"
            } else {
                binding.tvVidSummarySpeed.visibility = View.GONE
            }

            if (outputConfig.outputMode == VideoOutputMode.GIF) {
                binding.tvVidSummaryAudio.visibility = View.VISIBLE
                binding.tvVidSummaryAudio.text = "✓ Mode: GIF Animation (Audio removed)"
            } else if (editState.audioMode != AudioMode.KEEP) {
                binding.tvVidSummaryAudio.visibility = View.VISIBLE
                binding.tvVidSummaryAudio.text = "✓ Audio: ${editState.audioMode}"
            } else {
                binding.tvVidSummaryAudio.visibility = View.GONE
            }
        }
    }

    fun resetEdits() {
        val totalDur = editState.durationMs
        editState.reset(totalDur)
        outputConfig.crf = 28
        outputConfig.targetPreset = "Auto (Balanced CRF 28)"
        outputConfig.format = "MP4"
        outputConfig.outputMode = VideoOutputMode.VIDEO
        outputConfig.codec = "H.264"

        val durSec = (totalDur / 1000.0).toFloat().coerceAtLeast(1f)
        updateRangeSliderSafely(binding.rangeSliderVidTrim, 0f, durSec, 0f, durSec)
        updateSliderSafely(binding.playerScrubber, 0f, durSec, 0f)
        binding.tvVidTrimStartLabel.text = "00:00.0"
        binding.tvVidTrimEndLabel.text = formatDuration(totalDur)
        binding.tvVidTrimDurationLabel.text = "Trimmed: ${formatDuration(totalDur)}"

        binding.sliderVidQuality.value = 28f
        binding.tvVidQualityValue.text = "CRF 28"
        binding.chipVidResOriginal.isChecked = true
        binding.chipVidAudioKeep.isChecked = true
        binding.chipVidMp4.isChecked = true
        binding.chipVidCodecH264.isChecked = true
        binding.tvPlayerSpeedBadge.text = "1.0×"

        playerController.setSpeed(1.0f)
        playerController.setMute(false)
        playerController.setTrimBounds(0L, totalDur)

        updateUiForOutputMode()
        updateOutputFilenameExtension()
        applyAspectRatioPreview()
        refreshStats()
        updateEditSummary()
        Toast.makeText(activity, "Video trim and compression parameters reset to original", Toast.LENGTH_SHORT).show()
    }

    fun execute() {
        handleExecute()
    }

    fun release() {
        compressionJob?.cancel()
        compressionJob = null
    }

    private fun handleExecute() {
        if (selectedUri == null) {
            onPickVideoRequest()
            return
        }

        if (compressionJob?.isActive == true) {
            compressionJob?.cancel()
            binding.layoutVidProgress.visibility = View.GONE
            binding.btnVidExecute.text = "Compress"
            Toast.makeText(activity, "Compression cancelled", Toast.LENGTH_SHORT).show()
            return
        }

        executeCompression()
    }

    private fun executeCompression() {
        val srcFile = originalFile ?: return
        val outDir = File(activity.cacheDir, "studio_output").apply { mkdirs() }
        val ext = if (outputConfig.outputMode == VideoOutputMode.GIF) "gif" else outputConfig.format.lowercase(Locale.US)
        val rawName = binding.etVidOutputFilename.text.toString().trim()
        val base = rawName.substringBeforeLast('.', rawName).ifBlank { "compressed_video" }
        val outFilename = "$base.$ext"
        val outFile = File(outDir, outFilename)

        if (outputConfig.codec.equals("copy", ignoreCase = true) && editState.hasVideoTransforms()) {
            Toast.makeText(activity, "Notice: Active transforms require re-encoding. Stream copy promoted to H.264.", Toast.LENGTH_SHORT).show()
        }

        binding.layoutVidProgress.visibility = View.VISIBLE
        binding.btnVidExecute.text = "Cancel"

        compressionJob = scope.launch(Dispatchers.IO) {
            try {
                val result = MediaProcessor.video.process(
                    srcFile = srcFile,
                    outFile = outFile,
                    editState = editState,
                    outputConfig = outputConfig,
                    py = getPython()
                )

                // Copy to SAF Destination Folder if chosen
                val destUri = safManager.videoDestinationUri
                if (result.success && destUri != null && outFile.exists()) {
                    val mime = when (outputConfig.format.uppercase()) {
                        "MKV" -> "video/x-matroska"
                        "WEBM" -> "video/webm"
                        "MOV" -> "video/quicktime"
                        "AVI" -> "video/x-msvideo"
                        "GIF" -> "image/gif"
                        else -> "video/mp4"
                    }
                    safManager.copyFileToDocumentTree(outFile, destUri, mime)
                }

                withContext(Dispatchers.Main) {
                    binding.layoutVidProgress.visibility = View.GONE
                    binding.btnVidExecute.text = "Compress again"
                    if (result.success && outFile.exists() && outFile.length() > 0L) {
                        lastResultFile = outFile
                        val durTrimmed = editState.trimmedDurationMs
                        binding.tvVidAfterStats.text = "${formatBytes(outFile.length())} • ${formatDuration(durTrimmed)} • ${outputConfig.format}"
                        val ratio = if (originalBytes > 0) {
                            (100.0 - (outFile.length().toDouble() / originalBytes.toDouble() * 100.0)).toInt().coerceIn(0, 99)
                        } else 0
                        binding.tvVidActualStats.visibility = View.VISIBLE
                        binding.tvVidActualStats.text = "Actual Output: ${formatBytes(outFile.length())} • ${formatDuration(durTrimmed)} (Saved: $ratio%)"
                        binding.layoutVidResultActions.visibility = View.VISIBLE
                        Toast.makeText(activity, "Video compressed successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Video compression failed: ${result.error ?: "Encoder error"}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.layoutVidProgress.visibility = View.GONE
                    binding.btnVidExecute.text = "Compress"
                    Toast.makeText(activity, "Video compression error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun clear() {
        selectedUri = null
        originalFile = null
        originalBytes = 0L
        lastResultFile = null
        compressionJob?.cancel()

        playerController.pause()
        binding.imgVidThumb.setImageDrawable(null)
        binding.imgVidPreview.setImageDrawable(null)
        binding.layoutVidEmptyState.visibility = View.VISIBLE
        binding.layoutVidSelectedState.visibility = View.GONE
        binding.btnVidClearAll.isEnabled = false

        binding.toolVidTrim.isEnabled = false
        binding.toolVidScale.isEnabled = false
        binding.toolVidPreset.isEnabled = false
        binding.toolVidSpeed.isEnabled = false
        binding.toolVidAspect.isEnabled = false
        binding.toolVidAudio.isEnabled = false

        binding.tvVidBeforeStats.text = "0 B • 0s • Original"
        binding.tvVidAfterStats.text = "~0 B (MP4)"
        binding.tvVidActualStats.visibility = View.GONE
        binding.layoutVidResultActions.visibility = View.GONE
        binding.btnVidExecute.text = "Select video"

        editState.reset()
        updateEditSummary()
    }

    // Tool Dialogs
    private fun showTrimDialog() {
        val dialogBinding = DialogVideoTrimBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()
        dialogBinding.imgTrimVideoThumbnail.setImageDrawable(binding.imgVidPreview.drawable)

        val durSec = (editState.durationMs / 1000.0).toFloat().coerceAtLeast(1f)
        val currentStartSec = (editState.trimStartMs / 1000.0).toFloat().coerceIn(0f, durSec)
        val currentEndSec = (editState.trimEndMs / 1000.0).toFloat().coerceIn(currentStartSec, durSec)
        updateRangeSliderSafely(dialogBinding.rangeSliderTrim, 0f, durSec, currentStartSec, currentEndSec)

        dialogBinding.tvTrimStartTime.text = formatDuration(editState.trimStartMs)
        dialogBinding.tvTrimEndTime.text = formatDuration(editState.trimEndMs)
        dialogBinding.tvTrimDuration.text = formatDuration(editState.trimmedDurationMs)

        dialogBinding.rangeSliderTrim.addOnChangeListener { slider, _, _ ->
            val sSec = slider.values[0]
            val eSec = slider.values[1]
            val sMs = (sSec * 1000).toLong()
            val eMs = (eSec * 1000).toLong()
            dialogBinding.tvTrimStartTime.text = formatDuration(sMs)
            dialogBinding.tvTrimEndTime.text = formatDuration(eMs)
            dialogBinding.tvTrimDuration.text = formatDuration((eMs - sMs).coerceAtLeast(0))
        }

        dialogBinding.chipTrimStory15.setOnClickListener {
            val e = 15f.coerceAtMost(durSec)
            dialogBinding.rangeSliderTrim.values = listOf(0f, e)
        }
        dialogBinding.chipTrimStatus30.setOnClickListener {
            val e = 30f.coerceAtMost(durSec)
            dialogBinding.rangeSliderTrim.values = listOf(0f, e)
        }
        dialogBinding.chipTrimMiddle.setOnClickListener {
            val midStart = durSec * 0.25f
            val midEnd = durSec * 0.75f
            dialogBinding.rangeSliderTrim.values = listOf(midStart, midEnd)
        }
        dialogBinding.chipTrimFull.setOnClickListener {
            dialogBinding.rangeSliderTrim.values = listOf(0f, durSec)
        }

        dialogBinding.btnTrimApply.setOnClickListener {
            val sSec = dialogBinding.rangeSliderTrim.values[0]
            val eSec = dialogBinding.rangeSliderTrim.values[1]
            editState.trimStartMs = (sSec * 1000).toLong()
            editState.trimEndMs = (eSec * 1000).toLong()

            updateRangeSliderSafely(binding.rangeSliderVidTrim, 0f, durSec, sSec, eSec)
            binding.tvVidTrimStartLabel.text = formatDuration(editState.trimStartMs)
            binding.tvVidTrimEndLabel.text = formatDuration(editState.trimEndMs)
            binding.tvVidTrimDurationLabel.text = "Trimmed: ${formatDuration(editState.trimmedDurationMs)}"

            playerController.setTrimBounds(editState.trimStartMs, editState.trimEndMs)
            refreshStats()
            updateEditSummary()
            dialog.dismiss()
        }

        dialogBinding.btnTrimReset.setOnClickListener {
            dialogBinding.rangeSliderTrim.values = listOf(0f, durSec)
            editState.trimStartMs = 0L
            editState.trimEndMs = editState.durationMs
            updateRangeSliderSafely(binding.rangeSliderVidTrim, 0f, durSec, 0f, durSec)
            binding.tvVidTrimStartLabel.text = "00:00.0"
            binding.tvVidTrimEndLabel.text = formatDuration(editState.durationMs)
            binding.tvVidTrimDurationLabel.text = "Trimmed: ${formatDuration(editState.durationMs)}"

            playerController.setTrimBounds(0L, editState.durationMs)
            refreshStats()
            updateEditSummary()
            dialog.dismiss()
        }

        dialogBinding.btnTrimCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnTrimClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showScaleDialog() {
        val dialogBinding = DialogVideoScaleBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftScale = editState.scalePreset

        when (draftScale) {
            "1080p (Full HD)" -> dialogBinding.chipScale1080p.isChecked = true
            "720p (HD)" -> dialogBinding.chipScale720p.isChecked = true
            "480p (SD Compact)" -> dialogBinding.chipScale480p.isChecked = true
            "360p (Ultra Small)" -> dialogBinding.chipScale360p.isChecked = true
            else -> dialogBinding.chipScaleOriginal.isChecked = true
        }

        dialogBinding.chipGroupVideoScale.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupVideoScale.findViewById<Chip>(checkedIds[0])
                draftScale = chip?.text?.toString() ?: "Original (No scaling)"
            }
        }

        dialogBinding.btnVideoScaleApply.setOnClickListener {
            editState.scalePreset = draftScale
            when (editState.scalePreset) {
                "1080p (Full HD)" -> binding.chipVidRes1080p.isChecked = true
                "720p (HD)" -> binding.chipVidRes720p.isChecked = true
                "480p (SD Compact)" -> binding.chipVidRes480p.isChecked = true
                "360p (Ultra Small)" -> binding.chipVidRes360p.isChecked = true
                else -> binding.chipVidResOriginal.isChecked = true
            }
            refreshStats()
            updateEditSummary()
            dialog.dismiss()
        }

        dialogBinding.btnVideoScaleReset.setOnClickListener {
            draftScale = "Original (No scaling)"
            dialogBinding.chipScaleOriginal.isChecked = true
        }

        dialogBinding.btnVideoScaleCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnVideoScaleClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showPresetDialog() {
        val dialogBinding = DialogVideoPresetBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftCrf = outputConfig.crf
        var draftTargetPreset = outputConfig.targetPreset
        var draftFormat = outputConfig.format
        var draftMode = outputConfig.outputMode
        var draftCodec = outputConfig.codec

        dialogBinding.sliderCrf.value = draftCrf.toFloat()
        dialogBinding.tvCrfValue.text = draftCrf.toString()

        when (draftTargetPreset) {
            "WhatsApp (16 MB)" -> dialogBinding.chipPresetWhatsapp.isChecked = true
            "Discord (25 MB)" -> dialogBinding.chipPresetDiscord.isChecked = true
            "Discord Nitro (50 MB)" -> dialogBinding.chipPresetNitro.isChecked = true
            "Email Attachment (8 MB)" -> dialogBinding.chipPresetEmail.isChecked = true
            "Web Stream (10 MB)" -> dialogBinding.chipPresetWeb.isChecked = true
            else -> dialogBinding.chipPresetAuto.isChecked = true
        }

        dialogBinding.chipGroupTargetSize.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupTargetSize.findViewById<Chip>(checkedIds[0])
                draftTargetPreset = chip?.text?.toString() ?: "Auto (Balanced CRF 28)"
            }
        }

        // Codec selection
        when {
            draftCodec.contains("265", ignoreCase = true) || draftCodec.contains("hevc", ignoreCase = true) -> dialogBinding.chipCodecH265.isChecked = true
            draftCodec.contains("vp9", ignoreCase = true) -> dialogBinding.chipCodecVp9.isChecked = true
            draftCodec.contains("av1", ignoreCase = true) -> dialogBinding.chipCodecAv1.isChecked = true
            draftCodec.contains("copy", ignoreCase = true) -> dialogBinding.chipCodecCopy.isChecked = true
            else -> dialogBinding.chipCodecH264.isChecked = true
        }

        dialogBinding.chipGroupVideoCodecDialog.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupVideoCodecDialog.findViewById<Chip>(checkedIds[0])
                draftCodec = when (chip?.id) {
                    dialogBinding.chipCodecH265.id -> "H.265"
                    dialogBinding.chipCodecVp9.id -> "VP9"
                    dialogBinding.chipCodecAv1.id -> "AV1"
                    dialogBinding.chipCodecCopy.id -> "Copy"
                    else -> "H.264"
                }
            }
        }

        // Container format selection
        val isCurrentGif = draftFormat.equals("GIF", ignoreCase = true)
        dialogBinding.chipGroupVideoCodecDialog.visibility = if (isCurrentGif) View.GONE else View.VISIBLE

        when (draftFormat.uppercase()) {
            "MOV" -> dialogBinding.chipContainerMov.isChecked = true
            "MKV" -> dialogBinding.chipContainerMkv.isChecked = true
            "WEBM" -> dialogBinding.chipContainerWebm.isChecked = true
            "AVI" -> dialogBinding.chipContainerAvi.isChecked = true
            "GIF" -> dialogBinding.chipContainerGif.isChecked = true
            else -> dialogBinding.chipContainerMp4.isChecked = true
        }

        dialogBinding.chipGroupVideoContainerDialog.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupVideoContainerDialog.findViewById<Chip>(checkedIds[0])
                val text = chip?.text?.toString() ?: "MP4"
                if (text.equals("GIF", ignoreCase = true)) {
                    draftFormat = "GIF"
                    draftMode = VideoOutputMode.GIF
                    dialogBinding.chipGroupVideoCodecDialog.visibility = View.GONE
                } else {
                    draftMode = VideoOutputMode.VIDEO
                    draftFormat = text
                    dialogBinding.chipGroupVideoCodecDialog.visibility = View.VISIBLE
                }
            }
        }

        dialogBinding.sliderCrf.addOnChangeListener { _, value, _ ->
            draftCrf = value.toInt()
            dialogBinding.tvCrfValue.text = draftCrf.toString()
        }

        dialogBinding.btnVideoPresetApply.setOnClickListener {
            outputConfig.crf = draftCrf
            outputConfig.targetPreset = draftTargetPreset
            outputConfig.format = draftFormat
            outputConfig.outputMode = draftMode
            outputConfig.codec = draftCodec

            binding.sliderVidQuality.value = outputConfig.crf.toFloat()
            binding.tvVidQualityValue.text = "CRF ${outputConfig.crf}"

            when (outputConfig.format.uppercase()) {
                "MKV" -> binding.chipVidMkv.isChecked = true
                "WEBM" -> binding.chipVidWebm.isChecked = true
                "GIF" -> binding.chipVidGif.isChecked = true
                else -> binding.chipVidMp4.isChecked = true
            }

            if (outputConfig.codec.equals("Copy", ignoreCase = true) && editState.hasVideoTransforms()) {
                Toast.makeText(activity, "Notice: Stream copy is incompatible with active transforms; H.264 will be used.", Toast.LENGTH_LONG).show()
            }

            updateUiForOutputMode()
            updateOutputFilenameExtension()
            refreshStats()
            updateEditSummary()
            dialog.dismiss()
        }

        dialogBinding.btnVideoPresetReset.setOnClickListener {
            draftTargetPreset = "Auto (Balanced CRF 28)"
            draftCrf = 28
            draftFormat = "MP4"
            draftMode = VideoOutputMode.VIDEO
            draftCodec = "H.264"
            dialogBinding.chipPresetAuto.isChecked = true
            dialogBinding.chipCodecH264.isChecked = true
            dialogBinding.chipContainerMp4.isChecked = true
            dialogBinding.sliderCrf.value = 28f
            dialogBinding.tvCrfValue.text = "28"
        }

        dialogBinding.btnVideoPresetCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnVideoPresetClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSpeedDialog() {
        val dialogBinding = DialogVideoSpeedBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftSpeed = editState.speed

        when (draftSpeed) {
            0.5f -> dialogBinding.chipSpeed05.isChecked = true
            0.75f -> dialogBinding.chipSpeed075.isChecked = true
            1.25f -> dialogBinding.chipSpeed125.isChecked = true
            1.5f -> dialogBinding.chipSpeed15.isChecked = true
            2.0f -> dialogBinding.chipSpeed20.isChecked = true
            else -> dialogBinding.chipSpeed10.isChecked = true
        }

        dialogBinding.chipGroupVideoSpeed.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupVideoSpeed.findViewById<Chip>(checkedIds[0])
                draftSpeed = when (chip?.id) {
                    dialogBinding.chipSpeed05.id -> 0.5f
                    dialogBinding.chipSpeed075.id -> 0.75f
                    dialogBinding.chipSpeed125.id -> 1.25f
                    dialogBinding.chipSpeed15.id -> 1.5f
                    dialogBinding.chipSpeed20.id -> 2.0f
                    else -> 1.0f
                }
            }
        }

        dialogBinding.btnVideoSpeedApply.setOnClickListener {
            editState.speed = draftSpeed
            binding.tvPlayerSpeedBadge.text = "${editState.speed}×"
            playerController.setSpeed(editState.speed)
            refreshStats()
            updateEditSummary()
            dialog.dismiss()
        }

        dialogBinding.btnVideoSpeedReset.setOnClickListener {
            draftSpeed = 1.0f
            dialogBinding.chipSpeed10.isChecked = true
        }

        dialogBinding.btnVideoSpeedCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnVideoSpeedClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showAspectDialog() {
        val dialogBinding = DialogVideoAspectBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftAspect = editState.aspect

        when (draftAspect) {
            "9:16 (Reel / Shorts / TikTok)" -> dialogBinding.chipAspect916.isChecked = true
            "1:1 (Square Feed)" -> dialogBinding.chipAspect11.isChecked = true
            "16:9 (Landscape YouTube)" -> dialogBinding.chipAspect169.isChecked = true
            else -> dialogBinding.chipAspectOrig.isChecked = true
        }

        dialogBinding.chipGroupVideoAspect.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupVideoAspect.findViewById<Chip>(checkedIds[0])
                draftAspect = chip?.text?.toString() ?: "Original"
            }
        }

        dialogBinding.btnVideoAspectApply.setOnClickListener {
            editState.aspect = draftAspect
            applyAspectRatioPreview()
            refreshStats()
            updateEditSummary()
            dialog.dismiss()
        }

        dialogBinding.btnVideoAspectReset.setOnClickListener {
            draftAspect = "Original"
            dialogBinding.chipAspectOrig.isChecked = true
        }

        dialogBinding.btnVideoAspectCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnVideoAspectClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showAudioDialog() {
        val dialogBinding = DialogVideoAudioBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        var draftAudioMode = editState.audioMode
        var draftChannels = editState.audioChannels
        var draftVolume = editState.audioVolume

        when (draftAudioMode) {
            AudioMode.MUTE -> dialogBinding.chipAudioMute.isChecked = true
            AudioMode.COMPRESS_AAC_128K -> dialogBinding.chipAudioAac128.isChecked = true
            AudioMode.VOICE_64K -> dialogBinding.chipAudioAac64.isChecked = true
            else -> dialogBinding.chipAudioKeep.isChecked = true
        }

        dialogBinding.chipGroupVideoAudio.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupVideoAudio.findViewById<Chip>(checkedIds[0])
                draftAudioMode = AudioMode.fromLabel(chip?.text?.toString() ?: "Keep")
            }
        }

        // Channels
        when (draftChannels.lowercase()) {
            "mono" -> dialogBinding.chipAudioChanMono.isChecked = true
            "stereo" -> dialogBinding.chipAudioChanStereo.isChecked = true
            else -> dialogBinding.chipAudioChanKeep.isChecked = true
        }

        dialogBinding.chipGroupAudioChannels.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = dialogBinding.chipGroupAudioChannels.findViewById<Chip>(checkedIds[0])
                draftChannels = when (chip?.id) {
                    dialogBinding.chipAudioChanMono.id -> "mono"
                    dialogBinding.chipAudioChanStereo.id -> "stereo"
                    else -> "keep"
                }
            }
        }

        // Volume
        dialogBinding.sliderAudioVolume.value = (draftVolume * 100f).coerceIn(0f, 200f)
        dialogBinding.tvAudioVolumeLabel.text = "${(draftVolume * 100).toInt()}%"
        dialogBinding.sliderAudioVolume.addOnChangeListener { _, value, _ ->
            draftVolume = value / 100f
            dialogBinding.tvAudioVolumeLabel.text = "${value.toInt()}%"
        }

        dialogBinding.btnVideoAudioApply.setOnClickListener {
            editState.audioMode = draftAudioMode
            editState.audioChannels = draftChannels
            editState.audioVolume = draftVolume

            val shouldMute = editState.audioMode == AudioMode.MUTE
            playerController.setMute(shouldMute)
            binding.btnPlayerMute.setIconResource(if (shouldMute) R.drawable.ic_audio_volume_off else R.drawable.ic_audio_volume)
            if (shouldMute) {
                binding.chipVidAudioRemove.isChecked = true
            } else {
                binding.chipVidAudioKeep.isChecked = true
            }
            refreshStats()
            updateEditSummary()
            dialog.dismiss()
        }

        dialogBinding.btnVideoAudioReset.setOnClickListener {
            draftAudioMode = AudioMode.KEEP
            draftVolume = 1.0f
            draftChannels = "keep"
            dialogBinding.chipAudioKeep.isChecked = true
            dialogBinding.chipAudioChanKeep.isChecked = true
            dialogBinding.sliderAudioVolume.value = 100f
            dialogBinding.tvAudioVolumeLabel.text = "100%"
        }

        dialogBinding.btnVideoAudioCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnVideoAudioClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // Helpers
    private fun queryFileSize(uri: Uri): Long {
        return try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun getDisplayName(uri: Uri): String {
        return try {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) cursor.getString(nameIndex) else "video.mp4"
            } ?: "video.mp4"
        } catch (_: Exception) { "video.mp4" }
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        activity.contentResolver.openInputStream(uri)?.use { inStream ->
            FileOutputStream(target).use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024.0 && unitIndex < units.size - 1) {
            size /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }

    private fun formatDuration(millis: Long): String {
        val totalSec = millis / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val msTenth = (millis % 1000) / 100
        return String.format(Locale.US, "%02d:%02d.%d", min, sec, msTenth)
    }
}
