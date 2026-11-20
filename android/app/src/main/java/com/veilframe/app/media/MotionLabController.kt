package com.veilframe.app.media

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import android.view.LayoutInflater
import com.veilframe.app.databinding.DialogModelRequiredBinding
import com.veilframe.app.ui.motion.MorphDialogController
import com.veilframe.app.media.rife.RifeModelManager
import com.veilframe.app.media.rife.MotionModelSelectorDialog
import com.veilframe.app.media.rife.RifeModelRegistry
import com.veilframe.app.media.rife.RifeModelSpec
import com.veilframe.app.databinding.DialogVideoTrimBinding
import com.veilframe.app.databinding.LayoutMotionLabBinding
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.veilframe.app.VeilFrameProcessingService
import com.veilframe.app.cv.core.CvRuntime
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Motion Lab — optical-flow frame interpolation as a first-class tool (v2.2.9).
 *
 * Device feedback promoted the dormant cv/motion stack (built in the CV hardening
 * round, previously zero production consumers) into its own workspace:
 *
 *   video → FFmpegKit frame extraction → per-pair FlowEstimator + FrameSynthesizer
 *         (governed internally per ADR 0006) → interleaved frame sequence
 *         → libx264 re-encode at multiplier×fps (original audio copied)
 *         → filesDir/exports (Library-visible) + MediaStore gallery save.
 *
 * Honest limits (Alpha): inputs capped at [MAX_DURATION_S]s / [MAX_FRAMES] frames,
 * frames are processed pairwise with immediate Mat release (memory flat), and every
 * failure surfaces as a real message — no silent fallbacks.
 */
class MotionLabController(
    private val activity: Activity,
    private val binding: LayoutMotionLabBinding,
    private val scope: CoroutineScope,
    private val onPickVideoRequest: () -> Unit,
    private val onNavigateBack: () -> Unit,
) : com.veilframe.app.navigation.ToolWorkStateContract {

    companion object {
        const val TAG = "VeilFrame.MotionLab"
        const val MAX_DURATION_S = 60
        const val MAX_FRAMES = 1800
        const val WARN_DURATION_S = 30
    }

    enum class MotionMethod {
        RIFE,
        DIS,
        FARNEBACK
    }

    private var sourceUri: Uri? = null
    private var ownedTrimFile: File? = null
    private var sourceGeneration = 0L
    val currentSourceUri: Uri? get() = sourceUri
    private var videoDurationMs: Long = 0L
    private var multiplier = 2
    private var isTarget60fps = true
    private var method = MotionMethod.RIFE
    private var job: Job? = null
    private var activeJobId: UUID? = null
    private var isTrimming = false
    override val isProcessing: Boolean get() = activeJobId != null || job?.isActive == true || isTrimming
    override val supportsBackgroundExecution: Boolean get() = true
    @Volatile private var cancelRequested = false
    private var outputFile: File? = null
    private val outputLeases = ResourceLeases<File> { file ->
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Log.w(TAG, "Failed retiring motion output", it) }
    }

    private fun retireOutput() {
        val old = outputFile ?: return
        outputFile = null
        outputLeases.retire(old)
    }

    private fun adoptOutput(file: File) {
        if (outputFile == file) return // retain identity for existing readers
        retireOutput()
        outputFile = file
    }

    init {
        wire()
        syncWithActiveService()
    }

    private fun wire() {
        binding.btnMotionLabBack.setOnClickListener { onNavigateBack() }
        binding.btnMotionLabPick.setOnClickListener { if (!isProcessing) onPickVideoRequest() }
        binding.btnMotionLabRun.setOnClickListener {
            if (isProcessing) return@setOnClickListener

            if (outputFile != null && outputFile?.exists() == true) {
                saveToGallery()
                return@setOnClickListener
            }

            if (method == MotionMethod.RIFE) {
                val rifeManager = RifeModelManager(activity)
                if (!rifeManager.isModelReady()) {
                    showRifeDownloadDialog {
                        runInterpolation()
                    }
                    return@setOnClickListener
                }
            }
            runInterpolation()
        }
        binding.btnCancelMotionLab.setOnClickListener {
            cancel()
        }
        binding.btnMotionLabDiscard.setOnClickListener {
            discardCurrentWork()
        }
        binding.btnMotionLabSave.setOnClickListener {
            saveToGallery()
        }
        binding.chipMotion60fps.setOnClickListener {
            isTarget60fps = true
            multiplier = 2
            updateControlStates()
        }
        binding.chipMotion2x.setOnClickListener {
            isTarget60fps = false
            multiplier = 2
            updateControlStates()
        }
        binding.chipMotion4x.setOnClickListener {
            isTarget60fps = false
            multiplier = 4
            updateControlStates()
        }
        binding.chipMotionRife.setOnClickListener {
            method = MotionMethod.RIFE
            syncRateChipsForMethod()
            val rifeManager = RifeModelManager(activity)
            if (!rifeManager.isModelReady()) {
                showRifeDownloadDialog {
                    // Ready for interpolation
                }
            }
            updateControlStates()
        }
        binding.chipMotionFast.setOnClickListener {
            method = MotionMethod.DIS
            syncRateChipsForMethod()
            updateControlStates()
        }
        binding.chipMotionQuality.setOnClickListener {
            method = MotionMethod.FARNEBACK
            syncRateChipsForMethod()
            updateControlStates()
        }
        syncRateChipsForMethod()
        binding.btnMotionLabTrim.setOnClickListener { if (!isProcessing) openTrimDialog() }
        binding.btnMotionSwitchModel.setOnClickListener {
            if (!isProcessing) openModelSelectorDialog()
        }
        binding.cardMotionActiveModel.setOnClickListener {
            if (!isProcessing) openModelSelectorDialog()
        }
        updateControlStates()
        // Insets: applied centrally by MainActivity's single listener (Phase-1 contract).
    }

    override fun hasPickedMedia(): Boolean = sourceUri != null
    override fun hasUnsavedWork(): Boolean = sourceUri != null || outputFile != null

    fun discardCurrentWork() {
        retireOutput()
        binding.cardMotionLabResult.visibility = View.GONE
        updateControlStates()
        Toast.makeText(activity, "Work discarded", Toast.LENGTH_SHORT).show()
    }

    override fun discardAndReleaseAllResources() {
        cancel()
        retireOutput()
        reset()
        MotionLabForegroundService.stop(activity)
    }

    fun reset() {
        sourceGeneration++
        cancelRequested = true
        activeJobId?.let { id ->
            VeilFrameProcessingService.cancelProcessing(activity, id)
            activeJobId = null
        }
        rifeDownloadJob?.cancel()
        job?.cancel()
        isTrimming = false
        retireOutput()
        ownedTrimFile?.delete()
        ownedTrimFile = null
        sourceUri = null
        videoDurationMs = 0L
        isTarget60fps = true
        multiplier = 2
        method = MotionMethod.RIFE
        binding.chipMotion60fps.isChecked = true
        binding.chipMotionRife.isChecked = true
        syncRateChipsForMethod()
        binding.containerMotionLabProgress.visibility = View.GONE
        binding.cardMotionLabResult.visibility = View.GONE
        binding.btnMotionLabTrim.visibility = View.GONE
        binding.tvMotionLabSource.text = "No video selected"
        updateControlStates()
    }

    override fun moveToBackground() {
        // VeilFrameProcessingService is already running in foreground for activeJobId.
        // Only start MotionLabForegroundService if a local coroutine job is active without VeilFrameProcessingService.
        if (activeJobId == null && job?.isActive == true && !MotionLabForegroundService.isVeilFrameServiceRunning()) {
            val label = if (isTarget60fps) "Motion Lab (60 fps) in background" else "Motion Lab (${multiplier}x) in background"
            MotionLabForegroundService.start(activity, label)
        }
    }

    fun cancel() {
        sourceGeneration++
        cancelRequested = true
        activeJobId?.let { id ->
            VeilFrameProcessingService.cancelProcessing(activity, id)
            activeJobId = null
        }
        rifeDownloadJob?.cancel()
        job?.cancel()
        isTrimming = false
        // The trim coroutine cancels its own native session; the foreground
        // service likewise owns its native sessions. Never cancel globally.
        MotionLabForegroundService.stop(activity)
        retireOutput()
        scope.launch(Dispatchers.Main) {
            binding.containerMotionLabProgress.visibility = View.GONE
            binding.cardMotionLabResult.visibility = View.GONE
            binding.tvMotionLabStatus.text = "Cancelled"
            updateControlStates()
        }
    }

    private fun updateControlStates() {
        val busy = isProcessing
        val hasSource = sourceUri != null
        val hasResult = outputFile != null && outputFile?.exists() == true

        // 1. Source & Trim Controls
        binding.btnMotionLabPick.isEnabled = !busy
        binding.btnMotionLabPick.alpha = if (!busy) 1.0f else 0.4f

        binding.btnMotionLabTrim.isEnabled = !busy && hasSource
        binding.btnMotionLabTrim.alpha = if (!busy && hasSource) 1.0f else 0.4f

        // 2. Chips
        binding.chipMotionRife.isEnabled = !busy
        binding.chipMotionFast.isEnabled = !busy
        binding.chipMotionQuality.isEnabled = !busy
        binding.chipMotion60fps.isEnabled = !busy
        binding.chipMotion2x.isEnabled = !busy
        binding.chipMotion4x.isEnabled = !busy

        // 3. Dynamic Action Button
        when {
            busy -> {
                binding.btnMotionLabRun.isEnabled = false
                binding.btnMotionLabRun.alpha = 0.5f
                binding.btnMotionLabRun.text = "Processing…"
                binding.btnMotionLabRun.icon = null
            }
            hasResult -> {
                binding.btnMotionLabRun.isEnabled = true
                binding.btnMotionLabRun.alpha = 1.0f
                binding.btnMotionLabRun.text = "Save Video"
                binding.btnMotionLabRun.setIconResource(R.drawable.ic_action_save)
            }
            hasSource -> {
                binding.btnMotionLabRun.isEnabled = true
                binding.btnMotionLabRun.alpha = 1.0f
                binding.btnMotionLabRun.text = "Interpolate"
                binding.btnMotionLabRun.icon = null
            }
            else -> {
                binding.btnMotionLabRun.isEnabled = false
                binding.btnMotionLabRun.alpha = 0.4f
                binding.btnMotionLabRun.text = "Interpolate"
                binding.btnMotionLabRun.icon = null
            }
        }

        // 4. Active RIFE Model Card
        val isRife = method == MotionMethod.RIFE
        binding.cardMotionActiveModel.visibility = if (isRife) View.VISIBLE else View.GONE
        if (isRife) {
            val rifeManager = RifeModelManager(activity)
            val selected = rifeManager.getSelectedModel()
            binding.textMotionActiveModelName.text = selected.displayName
            val isDownloaded = rifeManager.isModelDownloaded(selected)
            val statusSuffix = if (isDownloaded) "Ready" else "Download Needed"
            binding.textMotionActiveModelDetails.text = "${selected.sizeFormatted} • ${selected.requiredPadding}px padding • $statusSuffix"
            binding.btnMotionSwitchModel.isEnabled = !busy
        }
    }

    fun setSource(uri: Uri) {
        if (job?.isActive == true || activeJobId != null || isTrimming) return
        sourceGeneration++
        try {
            val retriever = android.media.MediaMetadataRetriever()
            if (uri.scheme == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(activity, uri)
            }
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val captureFps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            runCatching { retriever.release() }

            videoDurationMs = durationMs
            val name = queryDisplayName(uri) ?: "video"
            val durationS = durationMs / 1000
            val fpsLabel = if (captureFps != null) " · ~${"%.1f".format(captureFps)} fps" else ""

            val prefs = activity.getSharedPreferences("veilframe_motion_lab", Context.MODE_PRIVATE)
            val skipWarning = prefs.getBoolean("skip_long_video_warning", false)

            if (durationS > WARN_DURATION_S && !skipWarning) {
                showLongVideoWarningDialog(uri, durationMs, durationS, fpsLabel, name)
            } else {
                applySourceInternal(uri, durationMs, durationS, fpsLabel, name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setSource failed", e)
            binding.tvMotionLabSource.text = "Could not read that video: ${e.message}"
            binding.btnMotionLabTrim.visibility = View.GONE
            updateControlStates()
        }
    }

    private fun showLongVideoWarningDialog(
        uri: Uri,
        durationMs: Long,
        durationS: Long,
        fpsLabel: String,
        name: String
    ) {
        val generation = sourceGeneration
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_motion_long_video_warning, null)
        val cbDoNotShow = dialogView.findViewById<MaterialCheckBox>(R.id.cbDoNotShowAgain)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvWarningMessage)
        tvMessage.text = "The selected video is ${durationS}s long. Processing videos longer than 30 seconds requires intense on-device neural synthesis, which may cause device lag and heating."

        MaterialAlertDialogBuilder(activity)
            .setTitle("Long Video Warning")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Proceed") { _, _ ->
                if (cbDoNotShow.isChecked) {
                    val prefs = activity.getSharedPreferences("veilframe_motion_lab", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("skip_long_video_warning", true).apply()
                }
                if (generation == sourceGeneration) applySourceInternal(uri, durationMs, durationS, fpsLabel, name)
            }
            .setNegativeButton("Cancel") { _, _ ->
                // User chose not to load long video
            }
            .show()
    }

    private fun applySourceInternal(
        uri: Uri,
        durationMs: Long,
        durationS: Long,
        fpsLabel: String,
        name: String
    ) {
        if (ownedTrimFile?.let { Uri.fromFile(it) } != uri) {
            ownedTrimFile?.delete()
            ownedTrimFile = null
        }
        sourceUri = uri
        retireOutput()
        videoDurationMs = durationMs
        binding.cardMotionLabResult.visibility = View.GONE
        binding.btnMotionLabTrim.visibility = View.VISIBLE
        binding.tvMotionLabSource.text = "$name · ${durationS}s$fpsLabel"
        updateControlStates()
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        if (uri.scheme == "file" && uri.path != null) {
            java.io.File(uri.path!!).name
        } else {
            activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment
        }
    } catch (_: Exception) { uri.lastPathSegment }

    private fun setStatus(text: String) {
        scope.launch(Dispatchers.Main) { binding.tvMotionLabStatus.text = text }
    }

    private fun setProgress(done: Int, total: Int) {
        val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
        if (activeJobId == null) {
            MotionLabForegroundService.updateProgress(activity, "Synthesising frames ($done/$total)", pct)
        }
        scope.launch(Dispatchers.Main) {
            binding.progressMotionLab.isIndeterminate = false
            if (total > 0) {
                binding.progressMotionLab.setProgressCompat(pct, true)
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val tenths = (ms % 1000) / 100
        return String.format(Locale.US, "%02d:%02d.%d", min, sec, tenths)
    }

    private fun updateRangeSliderSafely(
        slider: com.google.android.material.slider.RangeSlider,
        from: Float,
        to: Float,
        startVal: Float,
        endVal: Float
    ) {
        val safeTo = if (to <= from) from + 1f else to
        val safeStart = startVal.coerceIn(from, safeTo)
        val safeEnd = endVal.coerceIn(safeStart, safeTo)
        slider.valueFrom = from
        slider.valueTo = safeTo
        slider.values = listOf(safeStart, safeEnd)
    }

    private fun openTrimDialog() {
        val currentUri = sourceUri ?: return
        val dialogBinding = DialogVideoTrimBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity).setView(dialogBinding.root).create()

        val trimPlayer = ExoPlayer.Builder(activity).build()
        dialogBinding.trimPlayerView.player = trimPlayer
        val mediaItem = MediaItem.fromUri(currentUri)
        trimPlayer.setMediaItem(mediaItem)
        trimPlayer.prepare()

        val durSec = (videoDurationMs / 1000.0).toFloat().coerceAtLeast(1f)
        val initialEndSec = durSec.coerceAtMost(WARN_DURATION_S.toFloat())
        updateRangeSliderSafely(dialogBinding.rangeSliderTrim, 0f, durSec, 0f, initialEndSec)

        dialogBinding.tvTrimStartTime.text = "00:00.0"
        dialogBinding.tvTrimEndTime.text = formatDuration((initialEndSec * 1000).toLong())
        dialogBinding.tvTrimDuration.text = formatDuration((initialEndSec * 1000).toLong())
        dialogBinding.tvTrimPlayheadPosition.text = "00:00.0"

        var lastStartSec = 0f
        var lastEndSec = initialEndSec

        trimPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                dialogBinding.btnTrimPlayPause.setIconResource(
                    if (isPlaying) R.drawable.ic_action_pause else R.drawable.ic_action_play
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                dialogBinding.trimBufferingBar.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
        })

        // Live scrub seeking with visual preview
        dialogBinding.rangeSliderTrim.addOnChangeListener { slider, _, fromUser ->
            val sSec = slider.values[0]
            val eSec = slider.values[1]
            val sMs = (sSec * 1000).toLong()
            val eMs = (eSec * 1000).toLong()
            dialogBinding.tvTrimStartTime.text = formatDuration(sMs)
            dialogBinding.tvTrimEndTime.text = formatDuration(eMs)
            dialogBinding.tvTrimDuration.text = formatDuration((eMs - sMs).coerceAtLeast(0))

            if (fromUser) {
                if (Math.abs(sSec - lastStartSec) > 0.02f) {
                    lastStartSec = sSec
                    trimPlayer.pause()
                    trimPlayer.seekTo(sMs)
                    dialogBinding.tvTrimPlayheadPosition.text = formatDuration(sMs)
                } else if (Math.abs(eSec - lastEndSec) > 0.02f) {
                    lastEndSec = eSec
                    trimPlayer.pause()
                    trimPlayer.seekTo(eMs)
                    dialogBinding.tvTrimPlayheadPosition.text = formatDuration(eMs)
                }
            }
        }

        dialogBinding.btnTrimPlayPause.setOnClickListener {
            if (trimPlayer.isPlaying) {
                trimPlayer.pause()
            } else {
                val curPos = trimPlayer.currentPosition
                val sMs = (dialogBinding.rangeSliderTrim.values[0] * 1000).toLong()
                val eMs = (dialogBinding.rangeSliderTrim.values[1] * 1000).toLong()
                if (curPos < sMs || curPos >= eMs) {
                    trimPlayer.seekTo(sMs)
                }
                trimPlayer.play()
            }
        }

        val tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (trimPlayer.isPlaying) {
                    val curPos = trimPlayer.currentPosition
                    val sMs = (dialogBinding.rangeSliderTrim.values[0] * 1000).toLong()
                    val eMs = (dialogBinding.rangeSliderTrim.values[1] * 1000).toLong()
                    if (curPos >= eMs) {
                        trimPlayer.seekTo(sMs)
                    }
                    dialogBinding.tvTrimPlayheadPosition.text = formatDuration(curPos)
                }
                delay(100L)
            }
        }

        dialogBinding.chipTrimStory15.setOnClickListener {
            val e = 15f.coerceAtMost(durSec)
            dialogBinding.rangeSliderTrim.values = listOf(0f, e)
            trimPlayer.seekTo(0L)
            dialogBinding.tvTrimPlayheadPosition.text = formatDuration(0L)
        }
        dialogBinding.chipTrimStatus30.setOnClickListener {
            val e = 30f.coerceAtMost(durSec)
            dialogBinding.rangeSliderTrim.values = listOf(0f, e)
            trimPlayer.seekTo(0L)
            dialogBinding.tvTrimPlayheadPosition.text = formatDuration(0L)
        }
        dialogBinding.chipTrimMiddle.setOnClickListener {
            val targetLen = WARN_DURATION_S.toFloat().coerceAtMost(durSec)
            val midStart = ((durSec - targetLen) / 2f).coerceAtLeast(0f)
            val midEnd = (midStart + targetLen).coerceAtMost(durSec)
            dialogBinding.rangeSliderTrim.values = listOf(midStart, midEnd)
            val seekMs = (midStart * 1000).toLong()
            trimPlayer.seekTo(seekMs)
            dialogBinding.tvTrimPlayheadPosition.text = formatDuration(seekMs)
        }
        dialogBinding.chipTrimFull.setOnClickListener {
            val e = durSec
            dialogBinding.rangeSliderTrim.values = listOf(0f, e)
            trimPlayer.seekTo(0L)
            dialogBinding.tvTrimPlayheadPosition.text = formatDuration(0L)
        }

        fun cleanup() {
            tickerJob.cancel()
            trimPlayer.stop()
            trimPlayer.release()
            dialogBinding.trimPlayerView.player = null
        }

        dialog.setOnDismissListener { cleanup() }
        dialogBinding.btnTrimClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnTrimCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnTrimReset.setOnClickListener {
            dialogBinding.rangeSliderTrim.values = listOf(0f, initialEndSec)
            trimPlayer.seekTo(0L)
            dialogBinding.tvTrimPlayheadPosition.text = formatDuration(0L)
        }

        dialogBinding.btnTrimApply.setOnClickListener {
            val sSec = dialogBinding.rangeSliderTrim.values[0]
            val eSec = dialogBinding.rangeSliderTrim.values[1]
            val clipDurSec = eSec - sSec
            if (clipDurSec < 0.5f) {
                Toast.makeText(activity, "Clip too short (minimum 0.5s)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            executeTrim(currentUri, sSec, clipDurSec)
        }

        dialog.show()
    }

    private fun executeTrim(inputUri: Uri, startSec: Float, durationSec: Float) {
        if (isProcessing || sourceUri != inputUri) return
        val generation = ++sourceGeneration
        isTrimming = true
        binding.containerMotionLabProgress.visibility = View.VISIBLE
        binding.progressMotionLab.isIndeterminate = true
        binding.tvMotionLabStatus.text = "Trimming clip via FFmpeg…"
        updateControlStates()

        job = scope.launch(Dispatchers.IO) {
            var ownedInput: File? = null
            val trimmedFile = File(activity.cacheDir, "motion_clip_${UUID.randomUUID()}.mp4")
            var handedOff = false
            try {
                val localInputFile = if (inputUri.scheme == "file") {
                    File(inputUri.path ?: "")
                } else {
                    val temp = File(activity.cacheDir, "motion_temp_in_${UUID.randomUUID()}.mp4")
                    ownedInput = temp
                    activity.contentResolver.openInputStream(inputUri)?.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    } ?: error("Cannot open source video")
                    temp
                }
                check(localInputFile.isFile && localInputFile.length() > 0) { "Failed to read source video" }
                val startStr = String.format(Locale.US, "%.3f", startSec)
                val durStr = String.format(Locale.US, "%.3f", durationSec)
                // Decode and seek accurately; a stream copy can start at an earlier
                // keyframe and include footage outside the selected clip.
                val session = executeOwnedFfmpeg(arrayOf(
                    "-y", "-nostdin", "-ss", startStr, "-i", localInputFile.absolutePath,
                    "-t", durStr, "-map", "0:v:0", "-map", "0:a:0?",
                    "-c:v", "libx264", "-preset", "ultrafast", "-crf", "18",
                    "-c:a", "aac", "-movflags", "+faststart", trimmedFile.absolutePath
                ))
                check(ReturnCode.isSuccess(session.returnCode) && trimmedFile.isFile && trimmedFile.length() > 0) {
                    "Trim failed. Please try another segment."
                }
                val retriever = android.media.MediaMetadataRetriever()
                val clipDuration = try {
                    retriever.setDataSource(trimmedFile.absolutePath)
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.takeIf { it > 0L } ?: error("Trimmed video has no readable duration")
                } finally { retriever.release() }
                withContext(Dispatchers.Main) {
                    if (generation != sourceGeneration || sourceUri != inputUri) return@withContext
                    // setSource() rejects an active trim job; commit the validated
                    // result directly on the UI owner instead.
                    isTrimming = false
                    val previous = ownedTrimFile
                    ownedTrimFile = trimmedFile
                    applySourceInternal(Uri.fromFile(trimmedFile), clipDuration, clipDuration / 1000L,
                        "", trimmedFile.name)
                    handedOff = true
                    previous?.takeIf { it != trimmedFile }?.delete()
                    Toast.makeText(activity, "Clip trimmed (${String.format(Locale.US, "%.1f", clipDuration / 1000.0)}s)", Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "executeTrim error", e)
                withContext(Dispatchers.Main) {
                    if (generation == sourceGeneration) Toast.makeText(activity, "Trim error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                ownedInput?.delete()
                if (!handedOff) trimmedFile.delete()
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (generation == sourceGeneration) {
                        job = null
                        isTrimming = false
                        binding.containerMotionLabProgress.visibility = View.GONE
                        updateControlStates()
                    }
                }
            }
        }
    }

    private var rifeDownloadJob: Job? = null

    private fun openModelSelectorDialog(onModelReady: (() -> Unit)? = null) {
        val rifeManager = RifeModelManager(activity)
        val dialog = MotionModelSelectorDialog(activity, scope, rifeManager) { spec ->
            updateControlStates()
            Toast.makeText(activity, "Active model: ${spec.shortBadge}", Toast.LENGTH_SHORT).show()
            onModelReady?.invoke()
        }
        dialog.show()
    }

    private fun showRifeDownloadDialog(onModelReady: () -> Unit) {
        val rifeManager = RifeModelManager(activity)
        if (rifeManager.isModelReady()) {
            onModelReady()
            return
        }
        openModelSelectorDialog(onModelReady)
    }

    private fun runInterpolation() {
        val uri = sourceUri ?: return
        if (isProcessing) return
        cancelRequested = false

        try {
            CvRuntime.requireAvailable()
        } catch (e: Exception) {
            binding.tvMotionLabStatus.text = "CV Runtime unavailable: ${e.message}"
            updateControlStates()
            Toast.makeText(activity, "CV Runtime unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        binding.containerMotionLabProgress.visibility = View.VISIBLE
        binding.cardMotionLabResult.visibility = View.GONE
        binding.progressMotionLab.isIndeterminate = true
        binding.tvMotionLabStatus.text = "Starting Motion Lab in foreground service…"

        val jobId = UUID.randomUUID()
        activeJobId = jobId

        val exportDir = File(activity.filesDir, "exports").apply { mkdirs() }
        val resultFile = File(exportDir, "motionlab_$jobId.mp4")
        retireOutput()

        attachJobCallback(jobId)
        updateControlStates()

        VeilFrameProcessingService.startMotionLab(
            context = activity,
            inputUri = uri,
            outputPath = resultFile.absolutePath,
            method = method.name,
            multiplier = multiplier,
            target60fps = isTarget60fps,
            jobId = jobId
        )
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun saveToGallery() {
        val out = outputFile ?: return
        val releaseOutput = outputLeases.acquire(out)
        scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            val resolver = activity.contentResolver
            var insertedUri: Uri? = null
            var published = false
            try {
                val cc = kotlinx.coroutines.currentCoroutineContext()
                cc.ensureActive()
                val expected = out.length()
                check(expected > 0) { "Motion output unavailable" }
                val fileName = out.name
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VeilFrame")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("MediaStore insert refused")
                insertedUri = uri
                val stream = resolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("openOutputStream returned null")
                stream.use { os ->
                    out.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            cc.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            check(n > 0 && n.toLong() <= expected - copied) { "Motion output changed during copy" }
                            os.write(buffer, 0, n)
                            copied += n
                        }
                        check(copied == expected) { "Incomplete motion output copy" }
                        os.flush()
                    }
                }
                cc.ensureActive()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    check(resolver.update(uri, values, null, null) == 1) { "MediaStore publication failed" }
                }
                published = true
                withContext(Dispatchers.Main) {
                    if (!activity.isDestroyed && !activity.isFinishing && outputFile === out)
                        Toast.makeText(activity, "Saved to Gallery (Movies/VeilFrame)", Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Gallery save failed", e)
                withContext(Dispatchers.Main) {
                    if (!activity.isDestroyed && !activity.isFinishing && outputFile === out)
                        Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                try {
                    if (!published) insertedUri?.let { uri ->
                        runCatching { resolver.delete(uri, null, null) }
                            .onFailure { Log.w(TAG, "Could not remove incomplete gallery entry", it) }
                    }
                } finally { releaseOutput() }
            }
        }
    }

    fun syncWithActiveService() {
        val currentJob = VeilFrameProcessingService.activeJob
        if (currentJob != null && currentJob.type == com.veilframe.app.media.JobType.MOTION_LAB_RIFE) {
            when (currentJob.state) {
                com.veilframe.app.media.JobState.RUNNING -> {
                    activeJobId = currentJob.id
                    if (sourceUri == null) {
                        setSource(currentJob.inputUri)
                    }
                    binding.containerMotionLabProgress.visibility = View.VISIBLE
                    binding.cardMotionLabResult.visibility = View.GONE
                    val pct = currentJob.progressPercent
                    if (pct > 0) {
                        binding.progressMotionLab.isIndeterminate = false
                        binding.progressMotionLab.setProgressCompat(pct, true)
                        binding.tvMotionLabStatus.text = "Synthesising frames ($pct%)…"
                    } else {
                        binding.progressMotionLab.isIndeterminate = true
                        binding.tvMotionLabStatus.text = "Processing in background…"
                    }
                    attachJobCallback(currentJob.id)
                    updateControlStates()
                }
                com.veilframe.app.media.JobState.COMPLETED -> {
                    val outUri = currentJob.outputUri
                    val outFile = outUri?.path?.let { File(it) }
                    if (outFile != null && outFile.exists() && outFile.length() > 0L) {
                        adoptOutput(outFile)
                        if (sourceUri == null) {
                            setSource(currentJob.inputUri)
                        }
                        binding.containerMotionLabProgress.visibility = View.GONE
                        binding.cardMotionLabResult.visibility = View.VISIBLE
                        val mb = outFile.length() / (1024.0 * 1024.0)
                        binding.tvMotionLabResultInfo.text =
                            "Interpolation complete · ${String.format(Locale.US, "%.1f", mb)} MB · saved to Library exports"
                        updateControlStates()
                    }
                }
                com.veilframe.app.media.JobState.FAILED -> {
                    if (activeJobId == currentJob.id) {
                        activeJobId = null
                        binding.containerMotionLabProgress.visibility = View.GONE
                        binding.tvMotionLabStatus.text = "Failed: ${currentJob.errorMessage ?: "Processing failed"}"
                        updateControlStates()
                    }
                }
                else -> {}
            }
        }
    }

    private fun attachJobCallback(jobId: UUID) {
        MotionLabForegroundService.cancelHook = { cancel() }
        VeilFrameProcessingService.jobCallback = object : VeilFrameProcessingService.Companion.JobCallback {
            override fun onProgress(id: UUID, percent: Int, status: String) {
                if (id == jobId) {
                    scope.launch(Dispatchers.Main) {
                        binding.tvMotionLabStatus.text = status
                        binding.progressMotionLab.isIndeterminate = false
                        binding.progressMotionLab.setProgressCompat(percent, true)
                    }
                }
            }

            override fun onCompleted(id: UUID, outputPath: String) {
                if (id == jobId) {
                    val out = File(outputPath)
                    scope.launch(Dispatchers.Main) {
                        if (activeJobId != id || activity.isDestroyed || activity.isFinishing) return@launch
                        activeJobId = null
                        if (!out.isFile || out.length() <= 0L) {
                            binding.containerMotionLabProgress.visibility = View.GONE
                            binding.tvMotionLabStatus.text = "Motion output unavailable"
                            updateControlStates()
                            return@launch
                        }
                        adoptOutput(out)
                        binding.containerMotionLabProgress.visibility = View.GONE
                        binding.cardMotionLabResult.visibility = View.VISIBLE
                        val mb = out.length() / (1024.0 * 1024.0)
                        val targetFpsStr = if (isTarget60fps) "60 fps" else "${multiplier}x fps"
                        binding.tvMotionLabResultInfo.text =
                            "Interpolation complete · $targetFpsStr · ${String.format(Locale.US, "%.1f", mb)} MB · saved to Library exports"
                        updateControlStates()
                    }
                }
            }

            override fun onFailed(id: UUID, error: String) {
                if (id == jobId) {
                    activeJobId = null
                    scope.launch(Dispatchers.Main) {
                        binding.containerMotionLabProgress.visibility = View.GONE
                        binding.tvMotionLabStatus.text = "Failed: $error"
                        updateControlStates()
                        Toast.makeText(activity, "Motion Lab: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun syncRateChipsForMethod() {
        if (isProcessing) {
            binding.chipMotion60fps.isEnabled = false
            binding.chipMotion2x.isEnabled = false
            binding.chipMotion4x.isEnabled = false
            return
        }
        when (method) {
            MotionMethod.RIFE -> {
                // RIFE only supports 60 fps
                binding.chipMotion60fps.isEnabled = true
                binding.chipMotion60fps.alpha = 1.0f
                binding.chipMotion60fps.isChecked = true
                isTarget60fps = true
                multiplier = 2

                binding.chipMotion2x.isEnabled = false
                binding.chipMotion2x.alpha = 0.38f
                binding.chipMotion4x.isEnabled = false
                binding.chipMotion4x.alpha = 0.38f
            }
            MotionMethod.DIS, MotionMethod.FARNEBACK -> {
                // Fallbacks support 60 fps, 2x, and 4x
                binding.chipMotion60fps.isEnabled = true
                binding.chipMotion60fps.alpha = 1.0f
                binding.chipMotion2x.isEnabled = true
                binding.chipMotion2x.alpha = 1.0f
                binding.chipMotion4x.isEnabled = true
                binding.chipMotion4x.alpha = 1.0f
            }
        }
    }
}
