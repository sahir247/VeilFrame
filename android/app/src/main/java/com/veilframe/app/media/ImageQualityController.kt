package com.veilframe.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.cv.analysis.ImageQualityAnalyzer
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.databinding.LayoutImageQualityBinding
import com.veilframe.app.ui.motion.VeilFrameInteraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat

/**
 * Controller orchestrating the Image Quality Forensics workspace:
 * - Image selection and display (working-resolution decode, memory-budgeted)
 * - Sharpness (Laplacian variance), sensor noise (sigma), and exposure analysis
 * - Diagnostics reporting and export
 *
 * Reliability contract (CV plan A3 — honest failures):
 * this workspace NEVER fabricates metrics. When analysis fails, the score shows
 * "—", the reason is stated, and export stays disabled. When the source exceeds
 * the working-resolution cap, the report discloses the sampled resolution the
 * metrics were computed at.
 */
class ImageQualityController(
    private val activity: AppCompatActivity,
    private val binding: LayoutImageQualityBinding,
    private val scope: CoroutineScope,
    private val onPickImageRequest: () -> Unit,
    private val onShareReportRequest: (String) -> Unit,
    private val onNavigateBack: () -> Unit
) {
    private var selectedBitmap: Bitmap? = null
    private var lastReportSummary: String = ""

    /** Original source dimensions (before working-resolution sampling). */
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0

    private companion object {
        const val TAG = "VeilFrame.ImageQuality"

        /** Metrics are computed at ≤16 MP working resolution (bounded Mat + bitmap ≈128 MB). */
        const val WORKING_PIXEL_CAP = 16_000_000
    }

    fun init() {
        binding.toolbarImageQuality.setNavigationOnClickListener {
            onNavigateBack()
        }

        binding.cardQualityPreview.setOnClickListener {
            onPickImageRequest()
        }

        binding.btnQualityPickImage.setOnClickListener {
            onPickImageRequest()
        }

        binding.btnQualityAnalyze.setOnClickListener {
            runDiagnostics()
        }

        binding.btnQualityExportReport.setOnClickListener {
            if (lastReportSummary.isNotEmpty()) {
                onShareReportRequest(lastReportSummary)
            } else {
                Toast.makeText(activity, "Run diagnostics first", Toast.LENGTH_SHORT).show()
            }
        }

        VeilFrameInteraction.bindWorkspace(binding.root)
        updateUi()
    }

    fun handleImageSelected(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            var sampled: Bitmap? = null
            var origW = 0
            var origH = 0
            try {
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    origW = bounds.outWidth
                    origH = bounds.outHeight
                }

                if (origW > 0 && origH > 0) {
                    // Power-of-two sampling down to the working-resolution cap.
                    // Downsampled metrics are DISCLOSED in the report — never
                    // presented as full-resolution forensics.
                    var sample = 1
                    while ((origW.toLong() / sample) * (origH.toLong() / sample) > WORKING_PIXEL_CAP) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    sampled = activity.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream, null, opts)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decode failed", e)
                sampled = null
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "Decode OOM", oom)
                sampled = null
            }

            withContext(Dispatchers.Main) {
                if (sampled != null) {
                    selectedBitmap = sampled
                    sourceWidth = origW
                    sourceHeight = origH
                    lastReportSummary = ""
                    updateUi()
                    runDiagnostics()
                } else {
                    Toast.makeText(activity, "Could not open image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun runDiagnostics() {
        val bmp = selectedBitmap ?: return
        Toast.makeText(activity, "Analyzing image forensics...", Toast.LENGTH_SHORT).show()

        // A2: governed execution — INTERACTIVE lane + memory admission.
        val estimate = CvRuntime.estimateBytes(bmp.width, bmp.height, 3)
        scope.launch {
            val job = CvRuntime.engine.submit(
                name = "image-quality-analysis",
                priority = com.veilframe.app.cv.core.CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
            ) { ctx ->
                CvRuntime.requireAvailable()
                ctx.ensureActive()
                val mat = BitmapBridge.toMat(bmp)
                try {
                    ImageQualityAnalyzer.analyze(mat)
                } finally {
                    mat.release() // CV-4: was leaked on every failure path
                }
            }

            val sampledNote = if (bmp.width != sourceWidth || bmp.height != sourceHeight) {
                "\nWorking Resolution: ${bmp.width} x ${bmp.height} (sampled from source — metrics reflect the working resolution)"
            } else {
                ""
            }

            job.await().fold(
                onOk = { report ->
                    val overallScore = report.overall
                    val scoreLabel = when {
                        overallScore >= 80 -> "High Quality"
                        overallScore >= 60 -> "Moderate Quality"
                        else -> "Low Quality"
                    }
                    val sScore = report.components.sharpness
                    val nScore = report.components.noise
                    val eScore = report.components.exposure
                    val sharpnessText = "Sharpness: $sScore / 100 (${if (sScore >= 75) "Crisp" else if (sScore >= 50) "Acceptable" else "Soft"})"
                    val noiseText = "Sensor Noise: $nScore / 100 (${if (nScore >= 75) "Clean / Low Noise" else if (nScore >= 50) "Moderate Grain" else "Noisy"})"
                    val exposureText = "Exposure: $eScore / 100 (${if (eScore >= 75) "Balanced Dynamic Range" else if (eScore >= 50) "Minor Clipping" else "Under/Over-exposed"})"

                    lastReportSummary = """
                        VeilFrame Image Quality Diagnostic Report
                        ─────────────────────────────────────────
                        Resolution: $sourceWidth x $sourceHeight
                        Overall Score: $overallScore / 100 ($scoreLabel)
                        $sharpnessText
                        $noiseText
                        $exposureText$sampledNote
                    """.trimIndent()

                    binding.containerQualityMetrics.visibility = View.VISIBLE
                    binding.tvQualityScoreValue.text = overallScore.toString()
                    binding.tvQualityScoreLabel.text = scoreLabel
                    binding.tvQualitySharpness.text = sharpnessText
                    binding.tvQualityNoise.text = noiseText
                    binding.tvQualityExposure.text = exposureText
                    binding.btnQualityExportReport.isEnabled = true
                },
                onErr = { err ->
                    // CV-3 honest failure: dash score, reason shown, export stays disabled.
                    Log.e(TAG, "Quality analysis failed [${err.code}]", err.cause)
                    lastReportSummary = """
                        VeilFrame Image Quality Diagnostic Report
                        ─────────────────────────────────────────
                        Resolution: $sourceWidth x $sourceHeight
                        Status: ANALYSIS FAILED — [${err.code}] ${err.message}
                        No metrics were produced. VeilFrame never fabricates quality scores.
                    """.trimIndent()

                    binding.containerQualityMetrics.visibility = View.VISIBLE
                    binding.tvQualityScoreValue.text = "—"
                    binding.tvQualityScoreLabel.text = "Analysis unavailable"
                    binding.tvQualitySharpness.text = "Sharpness: — (analysis failed)"
                    binding.tvQualityNoise.text = "Sensor Noise: — (analysis failed)"
                    binding.tvQualityExposure.text = "Exposure: — (analysis failed)"
                    binding.btnQualityExportReport.isEnabled = false
                    Toast.makeText(activity, "Analysis failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun updateUi() {
        if (selectedBitmap == null) {
            binding.containerQualityEmpty.visibility = View.VISIBLE
            binding.ivQualityPreview.visibility = View.GONE
            binding.containerQualityMetrics.visibility = View.GONE
            binding.btnQualityAnalyze.isEnabled = false
            binding.btnQualityExportReport.isEnabled = false
        } else {
            binding.containerQualityEmpty.visibility = View.GONE
            binding.ivQualityPreview.visibility = View.VISIBLE
            binding.ivQualityPreview.setImageBitmap(selectedBitmap)
            binding.btnQualityAnalyze.isEnabled = true
        }
    }
}
