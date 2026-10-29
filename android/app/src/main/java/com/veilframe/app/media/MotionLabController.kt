package com.veilframe.app.media

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import android.view.LayoutInflater
import com.veilframe.app.databinding.DialogModelRequiredBinding
import com.veilframe.app.ui.motion.MorphDialogController
import com.veilframe.app.media.rife.RifeModelManager
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
    }

    enum class MotionMethod {
        RIFE,
        DIS,
        FARNEBACK
    }

    private var sourceUri: Uri? = null
    val currentSourceUri: Uri? get() = sourceUri
    private var videoDurationMs: Long = 0L
    private var multiplier = 2
    private var isTarget60fps = true
    private var method = MotionMethod.RIFE
    private var job: Job? = null
    private var activeJobId: UUID? = null
    override val isProcessing: Boolean get() = activeJobId != null || job?.isActive == true
    override val supportsBackgroundExecution: Boolean get() = true
    @Volatile private var cancelRequested = false
    private var outputFile: File? = null

    init {
        wire()
    }

    private fun wire() {
        binding.btnMotionLabBack.setOnClickListener { onNavigateBack() }
        binding.btnMotionLabPick.setOnClickListener { if (job?.isActive != true) onPickVideoRequest() }
        binding.btnMotionLabRun.setOnClickListener {
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
            cancelRequested = true
            job?.cancel()
            FFmpegKit.cancel()
            setStatus("Cancelling…")
        }
        binding.chipMotion60fps.setOnClickListener {
            isTarget60fps = true
            multiplier = 2
        }
        binding.chipMotion2x.setOnClickListener {
            isTarget60fps = false
            multiplier = 2
        }
        binding.chipMotion4x.setOnClickListener {
            isTarget60fps = false
            multiplier = 4
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
        }
        binding.chipMotionFast.setOnClickListener {
            method = MotionMethod.DIS
            syncRateChipsForMethod()
        }
        binding.chipMotionQuality.setOnClickListener {
            method = MotionMethod.FARNEBACK
            syncRateChipsForMethod()
        }
        syncRateChipsForMethod()
        binding.btnMotionLabTrim.setOnClickListener { openTrimDialog() }
        binding.btnMotionLabSave.setOnClickListener { saveToGallery() }
        // Insets: applied centrally by MainActivity's single listener (Phase-1 contract).
    }


    override fun hasPickedMedia(): Boolean = sourceUri != null
    override fun hasUnsavedWork(): Boolean = sourceUri != null || outputFile != null

    override fun discardAndReleaseAllResources() {
        cancel()
        reset()
        MotionLabForegroundService.stop(activity)
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
        cancelRequested = true
        activeJobId?.let { id ->
            VeilFrameProcessingService.cancelProcessing(activity, id)
            activeJobId = null
        }
        rifeDownloadJob?.cancel()
        job?.cancel()
        FFmpegKit.cancel()
        MotionLabForegroundService.stop(activity)
        scope.launch(Dispatchers.Main) {
            binding.tvMotionLabStatus.text = "Cancelled"
            binding.btnMotionLabRun.isEnabled = sourceUri != null
        }
    }

    fun setSource(uri: Uri) {
        if (job?.isActive == true || activeJobId != null) return
        sourceUri = uri
        outputFile = null
        binding.cardMotionLabResult.visibility = View.GONE
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(activity, uri)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val captureFps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            runCatching { retriever.release() }

            videoDurationMs = durationMs
            val name = queryDisplayName(uri) ?: "video"
            val durationS = durationMs / 1000
            val fpsLabel = if (captureFps != null) " · ~${"%.1f".format(captureFps)} fps" else ""

            binding.btnMotionLabTrim.visibility = View.VISIBLE
            if (durationS > MAX_DURATION_S) {
                binding.tvMotionLabSource.text = "$name — ${durationS}s exceeds limit (${MAX_DURATION_S}s). Please trim a segment using button below."
                binding.btnMotionLabRun.isEnabled = false
            } else {
                binding.tvMotionLabSource.text = "$name · ${durationS}s$fpsLabel"
                binding.btnMotionLabRun.isEnabled = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "setSource failed", e)
            binding.tvMotionLabSource.text = "Could not read that video: ${e.message}"
            binding.btnMotionLabRun.isEnabled = false
            binding.btnMotionLabTrim.visibility = View.GONE
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        activity.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) { null }

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
        val initialEndSec = durSec.coerceAtMost(MAX_DURATION_S.toFloat())
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
            val targetLen = MAX_DURATION_S.toFloat().coerceAtMost(durSec)
            val midStart = ((durSec - targetLen) / 2f).coerceAtLeast(0f)
            val midEnd = (midStart + targetLen).coerceAtMost(durSec)
            dialogBinding.rangeSliderTrim.values = listOf(midStart, midEnd)
            val seekMs = (midStart * 1000).toLong()
            trimPlayer.seekTo(seekMs)
            dialogBinding.tvTrimPlayheadPosition.text = formatDuration(seekMs)
        }
        dialogBinding.chipTrimFull.setOnClickListener {
            val e = MAX_DURATION_S.toFloat().coerceAtMost(durSec)
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
            if (clipDurSec > MAX_DURATION_S) {
                Toast.makeText(activity, "Selected clip (${clipDurSec.toInt()}s) exceeds limit of ${MAX_DURATION_S}s", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
        binding.containerMotionLabProgress.visibility = View.VISIBLE
        binding.progressMotionLab.isIndeterminate = true
        binding.tvMotionLabStatus.text = "Trimming clip via FFmpeg…"
        binding.btnMotionLabRun.isEnabled = false

        scope.launch(Dispatchers.IO) {
            try {
                // Ensure local input file for FFmpeg fast seeking
                val localInputFile = if (inputUri.scheme == "file") {
                    File(inputUri.path ?: "")
                } else {
                    val temp = File(activity.cacheDir, "motion_temp_in_${System.currentTimeMillis()}.mp4")
                    activity.contentResolver.openInputStream(inputUri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    temp
                }

                if (!localInputFile.exists() || localInputFile.length() == 0L) {
                    throw IllegalStateException("Failed to read source video for trimming")
                }

                val trimmedFile = File(activity.cacheDir, "motion_clip_${System.currentTimeMillis()}.mp4")
                val startStr = String.format(Locale.US, "%.3f", startSec)
                val durStr = String.format(Locale.US, "%.3f", durationSec)

                // 1. Attempt ultra-fast stream copy
                val copyArgs = arrayOf(
                    "-y",
                    "-ss", startStr,
                    "-i", localInputFile.absolutePath,
                    "-t", durStr,
                    "-c", "copy",
                    "-avoid_negative_ts", "make_zero",
                    trimmedFile.absolutePath
                )
                val copySession = FFmpegKit.executeWithArguments(copyArgs)
                val copySuccess = ReturnCode.isSuccess(copySession.returnCode) && trimmedFile.exists() && trimmedFile.length() > 0

                val finalSuccess = if (copySuccess) {
                    true
                } else {
                    // 2. Fallback to ultrafast libx264 re-encode
                    trimmedFile.delete()
                    val encArgs = arrayOf(
                        "-y",
                        "-ss", startStr,
                        "-i", localInputFile.absolutePath,
                        "-t", durStr,
                        "-c:v", "libx264",
                        "-preset", "ultrafast",
                        "-crf", "18",
                        "-c:a", "copy",
                        trimmedFile.absolutePath
                    )
                    val encSession = FFmpegKit.executeWithArguments(encArgs)
                    ReturnCode.isSuccess(encSession.returnCode) && trimmedFile.exists() && trimmedFile.length() > 0
                }

                withContext(Dispatchers.Main) {
                    binding.containerMotionLabProgress.visibility = View.GONE
                    if (finalSuccess) {
                        setSource(Uri.fromFile(trimmedFile))
                        Toast.makeText(activity, "Clip trimmed (${String.format(Locale.US, "%.1f", durationSec)}s)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Trim failed. Please try another segment.", Toast.LENGTH_LONG).show()
                        binding.btnMotionLabRun.isEnabled = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "executeTrim error", e)
                withContext(Dispatchers.Main) {
                    binding.containerMotionLabProgress.visibility = View.GONE
                    Toast.makeText(activity, "Trim error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private var rifeDownloadJob: Job? = null

    private fun showRifeDownloadDialog(onModelReady: () -> Unit) {
        val rifeManager = RifeModelManager(activity)
        if (rifeManager.isModelReady()) {
            onModelReady()
            return
        }

        val dialogBinding = DialogModelRequiredBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.tvModelReqTitle.text = "RIFE v4 AI Model Required"
        dialogBinding.tvModelReqMessage.text =
            "RIFE v4 AI Frame Interpolation uses neural weights (~10.3 MB) to synthesize smooth 60 FPS video. Would you like to download it now to proceed?"

        dialogBinding.btnModelReqDownload.text = "Download RIFE v4 (~10 MB)"
        dialogBinding.btnModelReqUseLanczos.text = "Use Fast Optical Flow (DIS) Instead"

        dialogBinding.btnModelReqClose.setOnClickListener {
            rifeDownloadJob?.cancel()
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, binding.btnMotionLabRun)
        }

        dialogBinding.btnModelReqCancel.setOnClickListener {
            rifeDownloadJob?.cancel()
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, binding.btnMotionLabRun)
        }

        dialogBinding.btnModelReqUseLanczos.setOnClickListener {
            rifeDownloadJob?.cancel()
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, binding.btnMotionLabRun)
            method = MotionMethod.DIS
            binding.chipMotionFast.isChecked = true
            syncRateChipsForMethod()
            onModelReady()
        }

        dialogBinding.btnModelReqDownload.setOnClickListener {
            dialogBinding.progressModelReq.visibility = View.VISIBLE
            dialogBinding.progressModelReq.isIndeterminate = true
            dialogBinding.tvModelReqProgressStatus.visibility = View.VISIBLE
            dialogBinding.tvModelReqProgressStatus.setTextColor(activity.getColor(R.color.vf_accent_blue))
            dialogBinding.tvModelReqProgressStatus.text = "Connecting to download server…"
            dialogBinding.btnModelReqDownload.isEnabled = false
            dialogBinding.btnModelReqUseLanczos.isEnabled = false

            rifeDownloadJob = scope.launch(Dispatchers.IO) {
                val result = rifeManager.ensureModelDownloaded { pct, msg ->
                    scope.launch(Dispatchers.Main) {
                        if (dialog.isShowing) {
                            dialogBinding.progressModelReq.isIndeterminate = false
                            dialogBinding.progressModelReq.progress = pct
                            dialogBinding.tvModelReqProgressStatus.text = msg
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) return@withContext
                    if (result.isSuccess) {
                        Toast.makeText(activity, "RIFE v4 model ready!", Toast.LENGTH_SHORT).show()
                        MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, binding.btnMotionLabRun)
                        onModelReady()
                    } else {
                        val err = result.exceptionOrNull()?.message ?: "Download failed"
                        dialogBinding.progressModelReq.visibility = View.GONE
                        dialogBinding.tvModelReqProgressStatus.visibility = View.VISIBLE
                        dialogBinding.tvModelReqProgressStatus.text = "Download failed: $err"
                        dialogBinding.tvModelReqProgressStatus.setTextColor(activity.getColor(R.color.vf_accent_red))
                        dialogBinding.btnModelReqDownload.isEnabled = true
                        dialogBinding.btnModelReqDownload.text = "Retry Download"
                        dialogBinding.btnModelReqUseLanczos.isEnabled = true
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            rifeDownloadJob?.cancel()
        }

        MorphDialogController.showWithMorph(dialog, dialogBinding.root, binding.btnMotionLabRun)
    }

    private fun runInterpolation() {
        val uri = sourceUri ?: return
        if (isProcessing) return
        cancelRequested = false

        try {
            CvRuntime.requireAvailable()
        } catch (e: Exception) {
            binding.tvMotionLabStatus.text = "CV Runtime unavailable: ${e.message}"
            binding.btnMotionLabRun.isEnabled = true
            Toast.makeText(activity, "CV Runtime unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        binding.containerMotionLabProgress.visibility = View.VISIBLE
        binding.cardMotionLabResult.visibility = View.GONE
        binding.btnMotionLabRun.isEnabled = false
        binding.progressMotionLab.isIndeterminate = true
        binding.tvMotionLabStatus.text = "Starting Motion Lab in foreground service…"

        val jobId = UUID.randomUUID()
        activeJobId = jobId

        val exportDir = File(activity.filesDir, "exports").apply { mkdirs() }
        val resultFile = File(exportDir, "motionlab_${System.currentTimeMillis()}.mp4")
        outputFile = null

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
                    activeJobId = null
                    val out = File(outputPath)
                    outputFile = out
                    scope.launch(Dispatchers.Main) {
                        binding.containerMotionLabProgress.visibility = View.GONE
                        binding.cardMotionLabResult.visibility = View.VISIBLE
                        val mb = out.length() / (1024.0 * 1024.0)
                        val targetFpsStr = if (isTarget60fps) "60 fps" else "${multiplier}x fps"
                        binding.tvMotionLabResultInfo.text =
                            "Interpolation complete · $targetFpsStr · ${String.format(Locale.US, "%.1f", mb)} MB · saved to Library exports"
                        binding.btnMotionLabRun.isEnabled = true
                    }
                }
            }

            override fun onFailed(id: UUID, error: String) {
                if (id == jobId) {
                    activeJobId = null
                    scope.launch(Dispatchers.Main) {
                        binding.tvMotionLabStatus.text = "Failed: $error"
                        binding.btnMotionLabRun.isEnabled = sourceUri != null
                        Toast.makeText(activity, "Motion Lab: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

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

    private fun saveToGallery() {
        val out = outputFile ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val resolver = activity.contentResolver
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
                resolver.openOutputStream(uri)?.use { os -> out.inputStream().use { it.copyTo(os) } }
                    ?: throw IllegalStateException("openOutputStream returned null")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Saved to Gallery (Movies/VeilFrame)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gallery save failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun reset() {
        cancelRequested = true
        activeJobId?.let { id ->
            VeilFrameProcessingService.cancelProcessing(activity, id)
            activeJobId = null
        }
        rifeDownloadJob?.cancel()
        job?.cancel()
        sourceUri = null
        outputFile = null
        isTarget60fps = true
        multiplier = 2
        method = MotionMethod.RIFE
        binding.chipMotion60fps.isChecked = true
        binding.chipMotionRife.isChecked = true
        syncRateChipsForMethod()
        binding.containerMotionLabProgress.visibility = View.GONE
        binding.cardMotionLabResult.visibility = View.GONE
        binding.btnMotionLabRun.isEnabled = false
        binding.btnMotionLabTrim.visibility = View.GONE
        binding.tvMotionLabSource.text = "No video selected"
    }

    private fun syncRateChipsForMethod() {
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
