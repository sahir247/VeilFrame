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
import com.veilframe.app.databinding.LayoutMotionLabBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private companion object {
        const val TAG = "VeilFrame.MotionLab"
        const val MAX_DURATION_S = 30
        const val MAX_FRAMES = 900
    }

    enum class MotionMethod {
        RIFE,
        DIS,
        FARNEBACK
    }

    private var sourceUri: Uri? = null
    val currentSourceUri: Uri? get() = sourceUri
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
        binding.btnMotionLabRun.setOnClickListener { runInterpolation() }
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
            binding.chipMotion60fps.isChecked = true
            isTarget60fps = true
            multiplier = 2
        }
        binding.chipMotionFast.setOnClickListener { method = MotionMethod.DIS }
        binding.chipMotionQuality.setOnClickListener { method = MotionMethod.FARNEBACK }
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
        if (isProcessing) {
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
        job?.cancel()
        FFmpegKit.cancel()
        MotionLabForegroundService.stop(activity)
        scope.launch(Dispatchers.Main) {
            binding.tvMotionLabStatus.text = "Cancelled"
            binding.btnMotionLabRun.isEnabled = sourceUri != null
        }
    }

    fun setSource(uri: Uri) {
        if (job?.isActive == true) return
        sourceUri = uri
        outputFile = null
        binding.cardMotionLabResult.visibility = View.GONE
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(activity, uri)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val captureFps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            runCatching { retriever.release() } // release() is API 29+; minSdk 26

            val name = queryDisplayName(uri) ?: "video"
            val durationS = durationMs / 1000
            if (durationS > MAX_DURATION_S) {
                binding.tvMotionLabSource.text = "$name — ${durationS}s: too long (Alpha limit ${MAX_DURATION_S}s)"
                binding.btnMotionLabRun.isEnabled = false
                return
            }
            val fpsLabel = if (captureFps != null) " · ~${"%.1f".format(captureFps)} fps" else ""
            binding.tvMotionLabSource.text = "$name · ${durationS}s$fpsLabel"
            binding.btnMotionLabRun.isEnabled = true
        } catch (e: Exception) {
            Log.e(TAG, "setSource failed", e)
            binding.tvMotionLabSource.text = "Could not read that video: ${e.message}"
            binding.btnMotionLabRun.isEnabled = false
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
        MotionLabForegroundService.updateProgress(activity, "Synthesising frames ($done/$total)", pct)
        scope.launch(Dispatchers.Main) {
            binding.progressMotionLab.isIndeterminate = false
            if (total > 0) {
                binding.progressMotionLab.setProgressCompat(pct, true)
            }
        }
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
        job?.cancel()
        sourceUri = null
        outputFile = null
        isTarget60fps = true
        multiplier = 2
        method = MotionMethod.RIFE
        binding.chipMotion60fps.isChecked = true
        binding.chipMotionRife.isChecked = true
        binding.containerMotionLabProgress.visibility = View.GONE
        binding.cardMotionLabResult.visibility = View.GONE
        binding.btnMotionLabRun.isEnabled = false
        binding.tvMotionLabSource.text = "No video selected"
    }
}
