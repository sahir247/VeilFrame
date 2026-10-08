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
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Motion Lab — optical-flow frame interpolation as a first-class tool (v2.3.0).
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
) {

    private companion object {
        const val TAG = "VeilFrame.MotionLab"
        const val MAX_DURATION_S = 30
        const val MAX_FRAMES = 900
    }

    private var sourceUri: Uri? = null
    val currentSourceUri: Uri? get() = sourceUri
    private var multiplier = 2
    private var qualityMode = false
    private var job: Job? = null
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
        binding.chipMotion2x.setOnClickListener { multiplier = 2 }
        binding.chipMotion4x.setOnClickListener { multiplier = 4 }
        binding.chipMotionFast.setOnClickListener { qualityMode = false }
        binding.chipMotionQuality.setOnClickListener { qualityMode = true }
        binding.btnMotionLabSave.setOnClickListener { saveToGallery() }
        // Insets: applied centrally by MainActivity's single listener (Phase-1 contract).
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
            val fps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
            runCatching { retriever.release() } // release() is API 29+; minSdk 26

            val name = queryDisplayName(uri) ?: "video"
            val durationS = durationMs / 1000
            if (durationS > MAX_DURATION_S) {
                binding.tvMotionLabSource.text = "$name — ${durationS}s: too long (Alpha limit ${MAX_DURATION_S}s)"
                binding.btnMotionLabRun.isEnabled = false
                return
            }
            binding.tvMotionLabSource.text = "$name · ${durationS}s · ~${"%.1f".format(fps)} fps"
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
        scope.launch(Dispatchers.Main) {
            binding.progressMotionLab.isIndeterminate = false
            if (total > 0) {
                binding.progressMotionLab.setProgressCompat((done * 100 / total).coerceIn(0, 100), true)
            }
        }
    }

    private fun runInterpolation() {
        val uri = sourceUri ?: return
        if (job?.isActive == true) return
        cancelRequested = false
        binding.containerMotionLabProgress.visibility = View.VISIBLE
        binding.cardMotionLabResult.visibility = View.GONE
        binding.btnMotionLabRun.isEnabled = false

        job = scope.launch(Dispatchers.IO) {
            val workDir = File(activity.cacheDir, "motionlab/${System.currentTimeMillis()}")
            val framesDir = File(workDir, "frames").apply { mkdirs() }
            val outDir = File(workDir, "out").apply { mkdirs() }
            val inFile = File(workDir, "in.mp4")
            try {
                setStatus("Copying source…")
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    inFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open the selected video")

                com.veilframe.app.cv.core.CvRuntime.requireAvailable()

                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(inFile.absolutePath)
                val fps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 30f
                runCatching { retriever.release() } // release() is API 29+; minSdk 26

                setStatus("Extracting frames…")
                var session = FFmpegKit.execute("-y -i ${inFile.absolutePath} -t $MAX_DURATION_S ${framesDir.absolutePath}/f_%05d.png")
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    throw IllegalStateException("Frame extraction failed (ffmpeg rc=${session.returnCode})")
                }
                if (cancelRequested) throw InterruptedException("Cancelled")

                val frames = framesDir.listFiles { f -> f.name.startsWith("f_") && f.name.endsWith(".png") }
                    ?.sortedBy { it.name } ?: emptyList()
                if (frames.size < 2) throw IllegalStateException("Need at least 2 frames (got ${frames.size})")
                if (frames.size > MAX_FRAMES) throw IllegalStateException("${frames.size} frames exceeds the Alpha limit of $MAX_FRAMES")

                setStatus("Synthesising motion frames (${if (qualityMode) "Farnebäck" else "DIS"})…")
                val estimator = com.veilframe.app.cv.motion.FlowEstimator(
                    algorithm = if (qualityMode) {
                        com.veilframe.app.cv.motion.FlowEstimator.Algorithm.FARNEBACK
                    } else {
                        com.veilframe.app.cv.motion.FlowEstimator.Algorithm.DIS
                    }
                )
                val synthOptions = com.veilframe.app.cv.motion.FrameSynthesizer.Options(estimator = estimator)

                var outIdx = 0
                var prev = Imgcodecs.imread(frames[0].absolutePath, Imgcodecs.IMREAD_COLOR)
                if (prev.empty()) throw IllegalStateException("Frame 0 unreadable")
                Imgcodecs.imwrite("${outDir.absolutePath}/o_%05d.png".format(outIdx++), prev)

                for (i in 1 until frames.size) {
                    if (cancelRequested) { prev.release(); throw InterruptedException("Cancelled") }
                    val cur = Imgcodecs.imread(frames[i].absolutePath, Imgcodecs.IMREAD_COLOR)
                    if (cur.empty()) { prev.release(); throw IllegalStateException("Frame $i unreadable") }
                    if (cur.size() != prev.size()) {
                        prev.release(); cur.release()
                        throw IllegalStateException("Frame size changed mid-video — cannot interpolate mixed resolutions")
                    }
                    for (k in 1 until multiplier) {
                        val t = k.toDouble() / multiplier
                        val result = com.veilframe.app.cv.motion.FrameSynthesizer.synthesize(prev, cur, t, synthOptions)
                        Imgcodecs.imwrite("${outDir.absolutePath}/o_%05d.png".format(outIdx++), result.frame)
                        result.frame.release()
                        result.confidence.release()
                        result.fallbackMask.release()
                        if (cancelRequested) { prev.release(); cur.release(); throw InterruptedException("Cancelled") }
                    }
                    prev.release()
                    prev = cur
                    Imgcodecs.imwrite("${outDir.absolutePath}/o_%05d.png".format(outIdx++), prev)
                    setProgress(i, frames.size - 1)
                }
                prev.release()

                setStatus("Encoding ${"%.2f".format(fps * multiplier)} fps video…")
                val exportDir = File(activity.filesDir, "exports").apply { mkdirs() }
                val resultFile = File(exportDir, "motionlab_${System.currentTimeMillis()}.mp4")
                session = FFmpegKit.execute(
                    "-y -framerate ${"%.3f".format(fps * multiplier)} -i ${outDir.absolutePath}/o_%05d.png " +
                        "-i ${inFile.absolutePath} -map 0:v -map 1:a? -c:v libx264 -preset veryfast " +
                        "-crf 21 -pix_fmt yuv420p -c:a copy -shortest ${resultFile.absolutePath}"
                )
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    throw IllegalStateException("Encoding failed (ffmpeg rc=${session.returnCode})")
                }
                if (!resultFile.exists() || resultFile.length() == 0L) {
                    throw IllegalStateException("Encoder produced no output")
                }

                outputFile = resultFile
                val mb = resultFile.length() / (1024.0 * 1024.0)
                withContext(Dispatchers.Main) {
                    binding.containerMotionLabProgress.visibility = View.GONE
                    binding.cardMotionLabResult.visibility = View.VISIBLE
                    binding.tvMotionLabResultInfo.text =
                        "${frames.size} → $outIdx frames · ${"%.1f".format(fps)} → ${"%.1f".format(fps * multiplier)} fps · ${"%.1f".format(mb)} MB · saved to Library exports"
                    binding.btnMotionLabRun.isEnabled = true
                }
            } catch (e: InterruptedException) {
                withContext(Dispatchers.Main) {
                    binding.tvMotionLabStatus.text = "Cancelled"
                    binding.btnMotionLabRun.isEnabled = sourceUri != null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Interpolation failed", e)
                withContext(Dispatchers.Main) {
                    binding.tvMotionLabStatus.text = "Failed: ${e.message}"
                    binding.btnMotionLabRun.isEnabled = sourceUri != null
                    Toast.makeText(activity, "Motion Lab: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                workDir.deleteRecursively()
            }
        }
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
        job?.cancel()
        sourceUri = null
        outputFile = null
        binding.containerMotionLabProgress.visibility = View.GONE
        binding.cardMotionLabResult.visibility = View.GONE
        binding.btnMotionLabRun.isEnabled = false
        binding.tvMotionLabSource.text = "No video selected"
    }
}
