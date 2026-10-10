package com.veilframe.app.media

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.cv.core.CvPriority
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.segmentation.rembg.CelBackgroundRemover
import com.veilframe.app.cv.segmentation.rembg.OnnxSessionManager
import com.veilframe.app.cv.segmentation.rembg.RembgExportFormat
import com.veilframe.app.cv.segmentation.rembg.RembgImageUtils
import com.veilframe.app.cv.segmentation.rembg.RembgModel
import com.veilframe.app.cv.segmentation.rembg.RembgModelDownloadManager
import com.veilframe.app.cv.segmentation.rembg.RembgModelRepository
import com.veilframe.app.databinding.DialogRembgModelManagerBinding
import com.veilframe.app.databinding.LayoutBackgroundRemoverBinding
import com.veilframe.app.ui.motion.MorphDialogController
import com.veilframe.app.ui.motion.VeilFrameInteraction
import com.veilframe.app.ui.views.BeforeAfterSplitView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Controller managing the on-device AI Background Remover workspace:
 * - 1:1 adoption of Cel-Android neural background removal pipeline
 * - SOTA ONNX models: BiRefNet Lite, ISNet General, U2Net Human
 * - On-demand model download & management (no models bundled with APK)
 * - M3 Expressive Model Manager dialog with download, delete, redownload, storage accounting
 * - Edge post-processing: tighten edges (1px alpha erosion), feather edges (1px blur), trim transparent
 * - Interactive Before / After Split comparison slider with synchronized pan & zoom
 * - Transparent PNG & White-backdrop JPG export to Gallery
 */
class BackgroundRemoverController(
    private val activity: AppCompatActivity,
    private val binding: LayoutBackgroundRemoverBinding,
    private val scope: CoroutineScope,
    private val onPickImageRequest: () -> Unit,
    private val onExportPngRequest: (File) -> Unit,
    private val onNavigateBack: () -> Unit
) {
    private var sourceBytes: ByteArray? = null
    private var sourceBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private var sourceFileName: String = "image"

    /** Selected AI model (defaults to recommended BiRefNet Lite) */
    private var currentModel: RembgModel = RembgModel.DEFAULT

    /** Last error message if processing failed */
    private var lastFailure: String? = null

    /** Model storage and execution subsystems */
    private val repository = RembgModelRepository(activity)
    private val downloadManager = RembgModelDownloadManager(activity, repository)
    private val sessionManager = OnnxSessionManager(repository)
    private val remover = CelBackgroundRemover(sessionManager)

    private companion object {
        const val TAG = "VeilFrame.BgRemover"
    }

    fun init() {
        binding.toolbarBgRemover.setNavigationOnClickListener {
            onNavigateBack()
        }

        binding.btnBgModelManager.setOnClickListener {
            showModelManagerDialog(binding.btnBgModelManager)
        }

        binding.btnBgChangeModel.setOnClickListener {
            showModelManagerDialog(binding.btnBgChangeModel)
        }

        binding.btnBgPickImage.setOnClickListener {
            onPickImageRequest()
        }

        binding.btnBgChangeImage.setOnClickListener {
            onPickImageRequest()
        }

        binding.btnBgExecuteRemoval.setOnClickListener {
            executeRemoval()
        }

        binding.btnBgResetCompare.setOnClickListener {
            binding.splitViewBgCompare.resetViewAnimated()
        }

        binding.btnBgSavePng.setOnClickListener {
            exportImage()
        }

        binding.chipGroupBgBackdrop.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when {
                checkedIds.contains(binding.chipBackdropWhite.id) ->
                    BeforeAfterSplitView.BackgroundMode.PURE_WHITE
                checkedIds.contains(binding.chipBackdropBlack.id) ->
                    BeforeAfterSplitView.BackgroundMode.PURE_BLACK
                else ->
                    BeforeAfterSplitView.BackgroundMode.TRANSPARENT_CHECKERBOARD
            }
            binding.splitViewBgCompare.backgroundMode = mode
        }

        // Re-execute when edge options change if an image is loaded and already processed
        binding.switchTightenEdges.setOnCheckedChangeListener { _, _ ->
            if (sourceBytes != null && resultBitmap != null) {
                executeRemoval()
            }
        }
        binding.switchFeatherEdges.setOnCheckedChangeListener { _, _ ->
            if (sourceBytes != null && resultBitmap != null) {
                executeRemoval()
            }
        }
        binding.switchTrimTransparent.setOnCheckedChangeListener { _, _ ->
            if (sourceBytes != null && resultBitmap != null) {
                executeRemoval()
            }
        }

        VeilFrameInteraction.bindWorkspace(binding.root)
        updateActiveModelBadge()
        updateUi()
    }

    fun cleanup() {
        try {
            sessionManager.closeAll()
            downloadManager.cancelDownload()
        } catch (ignored: Throwable) {}
    }

    fun handleImageSelected(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val bytes = try {
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed reading image stream", e)
                null
            }

            if (bytes == null || bytes.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Could not open selected image", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val decoded = try {
                RembgImageUtils.decodeOrientedBitmap(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Decoding oriented bitmap failed", e)
                null
            }

            withContext(Dispatchers.Main) {
                if (decoded != null) {
                    sourceBytes = bytes
                    sourceBitmap = decoded
                    resultBitmap = null
                    lastFailure = null
                    sourceFileName = uri.lastPathSegment?.substringAfterLast('/') ?: "photo"
                    updateUi()

                    // Automatically begin removal if model is installed; otherwise open manager
                    if (repository.isModelReady(currentModel)) {
                        executeRemoval()
                    } else {
                        Toast.makeText(
                            activity,
                            "AI model ${currentModel.displayName} needs to be downloaded first",
                            Toast.LENGTH_SHORT
                        ).show()
                        showModelManagerDialog(binding.cardBgActiveModel)
                    }
                } else {
                    Toast.makeText(activity, "Could not decode image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun executeRemoval() {
        val bytes = sourceBytes ?: return
        if (!repository.isModelReady(currentModel)) {
            Toast.makeText(
                activity,
                "Model ${currentModel.displayName} is not downloaded yet",
                Toast.LENGTH_SHORT
            ).show()
            showModelManagerDialog(binding.cardBgActiveModel)
            return
        }

        binding.cardBgProcessingOverlay.visibility = View.VISIBLE
        binding.progressBgInference.isIndeterminate = false
        binding.progressBgInference.progress = 10
        binding.tvBgProgressPercent.text = "10%"
        binding.tvBgProgressMessage.text = "Loading ${currentModel.displayName}…"

        val trim = binding.switchTrimTransparent.isChecked
        val tighten = binding.switchTightenEdges.isChecked
        val feather = binding.switchFeatherEdges.isChecked

        // Governed execution: INTERACTIVE lane + memory admission check via CvRuntime.
        val srcBmp = sourceBitmap
        val w = srcBmp?.width ?: 1024
        val h = srcBmp?.height ?: 1024
        val estimate = CvRuntime.estimateBytes(w, h, 4)

        scope.launch {
            val job = CvRuntime.engine.submit(
                name = "background-removal",
                priority = CvPriority.INTERACTIVE,
                memoryEstimate = estimate,
                timeoutMs = 60_000L,
            ) { ctx ->
                // Native gate + cooperative cancellation checks
                CvRuntime.requireAvailable()
                ctx.ensureActive()

                kotlinx.coroutines.runBlocking {
                    remover.removeBackground(
                        imageBytes = bytes,
                        model = currentModel,
                        trim = trim,
                        tightenEdges = tighten,
                        featherEdges = feather,
                        onProgress = { progress ->
                            ctx.ensureActive()
                            ctx.reportProgress(progress.percent / 100f)
                            withContext(Dispatchers.Main) {
                                binding.progressBgInference.progress = progress.percent
                                binding.tvBgProgressPercent.text = "${progress.percent}%"
                                binding.tvBgProgressMessage.text = progress.message
                            }
                        }
                    )
                }
            }

            val outcome = job.await()
            withContext(Dispatchers.Main) {
                binding.cardBgProcessingOverlay.visibility = View.GONE
                outcome.fold(
                    onOk = { result ->
                        resultBitmap = result.cutoutBitmap
                        lastFailure = null
                        updateUi()

                        if (result.warnings.isNotEmpty()) {
                            Toast.makeText(activity, result.warnings.first(), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onErr = { err ->
                        Log.e(TAG, "Background removal failed: [${err.code}] ${err.message}")
                        lastFailure = err.message
                        Toast.makeText(
                            activity,
                            "Removal failed: ${err.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        updateUi()
                    }
                )
            }
        }
    }

    fun showModelManagerDialog(originView: View? = null) {
        val dialogBinding = DialogRembgModelManagerBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.btnModelManagerClose.setOnClickListener {
            MorphDialogController.dismissWithMorph(dialog, dialogBinding.root, originView)
        }

        fun refreshCards() {
            val usedBytes = repository.getTotalStorageUsedBytes()
            val availBytes = repository.getAvailableStorageBytes()
            val usedMb = String.format(Locale.US, "%.1f MB", usedBytes / (1024.0 * 1024.0))
            val availGb = String.format(Locale.US, "%.1f GB", availBytes / (1024.0 * 1024.0 * 1024.0))
            dialogBinding.tvModelStorageSummary.text = "Models Storage: $usedMb used • $availGb available"

            val readyCount = repository.listReadyModels().size
            dialogBinding.tvModelCountSummary.text = "$readyCount of 3 AI Models Installed"

            // Card 1: BiRefNet Lite
            val isBiRefNetReady = repository.isModelReady(RembgModel.BIREFNET_GENERAL_LITE)
            if (isBiRefNetReady) {
                dialogBinding.tvModelStatusBiRefNet.text = "Installed"
                dialogBinding.tvModelStatusBiRefNet.setTextColor(activity.getColor(R.color.vf_accent_green))
                dialogBinding.btnDownloadModelBiRefNet.visibility = View.GONE
                dialogBinding.btnDeleteModelBiRefNet.visibility = View.VISIBLE
                dialogBinding.btnRedownloadModelBiRefNet.visibility = View.VISIBLE
                dialogBinding.btnSelectModelBiRefNet.visibility = View.VISIBLE
                if (currentModel == RembgModel.BIREFNET_GENERAL_LITE) {
                    dialogBinding.btnSelectModelBiRefNet.text = "Active"
                    dialogBinding.btnSelectModelBiRefNet.isEnabled = false
                } else {
                    dialogBinding.btnSelectModelBiRefNet.text = "Select"
                    dialogBinding.btnSelectModelBiRefNet.isEnabled = true
                }
            } else {
                dialogBinding.tvModelStatusBiRefNet.text = "Not Installed"
                dialogBinding.tvModelStatusBiRefNet.setTextColor(activity.getColor(R.color.vf_text_muted))
                dialogBinding.btnDownloadModelBiRefNet.visibility = View.VISIBLE
                dialogBinding.btnDeleteModelBiRefNet.visibility = View.GONE
                dialogBinding.btnRedownloadModelBiRefNet.visibility = View.GONE
                dialogBinding.btnSelectModelBiRefNet.visibility = View.GONE
            }

            // Card 2: ISNet General
            val isIsNetReady = repository.isModelReady(RembgModel.ISNET_GENERAL)
            if (isIsNetReady) {
                dialogBinding.tvModelStatusIsNet.text = "Installed"
                dialogBinding.tvModelStatusIsNet.setTextColor(activity.getColor(R.color.vf_accent_green))
                dialogBinding.btnDownloadModelIsNet.visibility = View.GONE
                dialogBinding.btnDeleteModelIsNet.visibility = View.VISIBLE
                dialogBinding.btnRedownloadModelIsNet.visibility = View.VISIBLE
                dialogBinding.btnSelectModelIsNet.visibility = View.VISIBLE
                if (currentModel == RembgModel.ISNET_GENERAL) {
                    dialogBinding.btnSelectModelIsNet.text = "Active"
                    dialogBinding.btnSelectModelIsNet.isEnabled = false
                } else {
                    dialogBinding.btnSelectModelIsNet.text = "Select"
                    dialogBinding.btnSelectModelIsNet.isEnabled = true
                }
            } else {
                dialogBinding.tvModelStatusIsNet.text = "Not Installed"
                dialogBinding.tvModelStatusIsNet.setTextColor(activity.getColor(R.color.vf_text_muted))
                dialogBinding.btnDownloadModelIsNet.visibility = View.VISIBLE
                dialogBinding.btnDeleteModelIsNet.visibility = View.GONE
                dialogBinding.btnRedownloadModelIsNet.visibility = View.GONE
                dialogBinding.btnSelectModelIsNet.visibility = View.GONE
            }

            // Card 3: U2Net Human
            val isU2NetReady = repository.isModelReady(RembgModel.U2NET_HUMAN)
            if (isU2NetReady) {
                dialogBinding.tvModelStatusU2Net.text = "Installed"
                dialogBinding.tvModelStatusU2Net.setTextColor(activity.getColor(R.color.vf_accent_green))
                dialogBinding.btnDownloadModelU2Net.visibility = View.GONE
                dialogBinding.btnDeleteModelU2Net.visibility = View.VISIBLE
                dialogBinding.btnRedownloadModelU2Net.visibility = View.VISIBLE
                dialogBinding.btnSelectModelU2Net.visibility = View.VISIBLE
                if (currentModel == RembgModel.U2NET_HUMAN) {
                    dialogBinding.btnSelectModelU2Net.text = "Active"
                    dialogBinding.btnSelectModelU2Net.isEnabled = false
                } else {
                    dialogBinding.btnSelectModelU2Net.text = "Select"
                    dialogBinding.btnSelectModelU2Net.isEnabled = true
                }
            } else {
                dialogBinding.tvModelStatusU2Net.text = "Not Installed"
                dialogBinding.tvModelStatusU2Net.setTextColor(activity.getColor(R.color.vf_text_muted))
                dialogBinding.btnDownloadModelU2Net.visibility = View.VISIBLE
                dialogBinding.btnDeleteModelU2Net.visibility = View.GONE
                dialogBinding.btnRedownloadModelU2Net.visibility = View.GONE
                dialogBinding.btnSelectModelU2Net.visibility = View.GONE
            }
        }

        fun download(model: RembgModel, progressView: com.google.android.material.progressindicator.LinearProgressIndicator, triggerButton: View) {
            progressView.visibility = View.VISIBLE
            progressView.isIndeterminate = true
            triggerButton.isEnabled = false

            scope.launch {
                val result = downloadManager.downloadModel(model, object : RembgModelDownloadManager.DownloadListener {
                    override fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) {
                        scope.launch(Dispatchers.Main) {
                            if (totalBytes > 0) {
                                progressView.isIndeterminate = false
                                progressView.progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            }
                        }
                    }

                    override fun onSuccess(model: RembgModel, destinationFile: File) {
                        scope.launch(Dispatchers.Main) {
                            progressView.visibility = View.GONE
                            triggerButton.isEnabled = true
                            refreshCards()
                            updateActiveModelBadge()
                            Toast.makeText(activity, "${model.displayName} ready!", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onError(error: String) {
                        scope.launch(Dispatchers.Main) {
                            progressView.visibility = View.GONE
                            triggerButton.isEnabled = true
                            Toast.makeText(activity, "Download failed: $error", Toast.LENGTH_LONG).show()
                        }
                    }
                })

                if (result.isSuccess) {
                    currentModel = model
                    updateActiveModelBadge()
                }
            }
        }

        // BiRefNet Lite listeners
        dialogBinding.btnDownloadModelBiRefNet.setOnClickListener {
            download(RembgModel.BIREFNET_GENERAL_LITE, dialogBinding.progressModelBiRefNet, dialogBinding.btnDownloadModelBiRefNet)
        }
        dialogBinding.btnRedownloadModelBiRefNet.setOnClickListener {
            repository.deleteModel(RembgModel.BIREFNET_GENERAL_LITE)
            sessionManager.closeSession(RembgModel.BIREFNET_GENERAL_LITE)
            download(RembgModel.BIREFNET_GENERAL_LITE, dialogBinding.progressModelBiRefNet, dialogBinding.btnRedownloadModelBiRefNet)
        }
        dialogBinding.btnDeleteModelBiRefNet.setOnClickListener {
            repository.deleteModel(RembgModel.BIREFNET_GENERAL_LITE)
            sessionManager.closeSession(RembgModel.BIREFNET_GENERAL_LITE)
            refreshCards()
            updateActiveModelBadge()
        }
        dialogBinding.btnSelectModelBiRefNet.setOnClickListener {
            currentModel = RembgModel.BIREFNET_GENERAL_LITE
            refreshCards()
            updateActiveModelBadge()
            if (sourceBytes != null) executeRemoval()
        }

        // ISNet General listeners
        dialogBinding.btnDownloadModelIsNet.setOnClickListener {
            download(RembgModel.ISNET_GENERAL, dialogBinding.progressModelIsNet, dialogBinding.btnDownloadModelIsNet)
        }
        dialogBinding.btnRedownloadModelIsNet.setOnClickListener {
            repository.deleteModel(RembgModel.ISNET_GENERAL)
            sessionManager.closeSession(RembgModel.ISNET_GENERAL)
            download(RembgModel.ISNET_GENERAL, dialogBinding.progressModelIsNet, dialogBinding.btnRedownloadModelIsNet)
        }
        dialogBinding.btnDeleteModelIsNet.setOnClickListener {
            repository.deleteModel(RembgModel.ISNET_GENERAL)
            sessionManager.closeSession(RembgModel.ISNET_GENERAL)
            refreshCards()
            updateActiveModelBadge()
        }
        dialogBinding.btnSelectModelIsNet.setOnClickListener {
            currentModel = RembgModel.ISNET_GENERAL
            refreshCards()
            updateActiveModelBadge()
            if (sourceBytes != null) executeRemoval()
        }

        // U2Net Human listeners
        dialogBinding.btnDownloadModelU2Net.setOnClickListener {
            download(RembgModel.U2NET_HUMAN, dialogBinding.progressModelU2Net, dialogBinding.btnDownloadModelU2Net)
        }
        dialogBinding.btnRedownloadModelU2Net.setOnClickListener {
            repository.deleteModel(RembgModel.U2NET_HUMAN)
            sessionManager.closeSession(RembgModel.U2NET_HUMAN)
            download(RembgModel.U2NET_HUMAN, dialogBinding.progressModelU2Net, dialogBinding.btnRedownloadModelU2Net)
        }
        dialogBinding.btnDeleteModelU2Net.setOnClickListener {
            repository.deleteModel(RembgModel.U2NET_HUMAN)
            sessionManager.closeSession(RembgModel.U2NET_HUMAN)
            refreshCards()
            updateActiveModelBadge()
        }
        dialogBinding.btnSelectModelU2Net.setOnClickListener {
            currentModel = RembgModel.U2NET_HUMAN
            refreshCards()
            updateActiveModelBadge()
            if (sourceBytes != null) executeRemoval()
        }

        refreshCards()
        MorphDialogController.showWithMorph(dialog, dialogBinding.root, originView)
    }

    private fun updateActiveModelBadge() {
        binding.tvBgCurrentModelName.text = currentModel.displayName
        val isReady = repository.isModelReady(currentModel)
        if (isReady) {
            binding.tvBgModelBadge.text = "Ready"
            binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_accent_green))
            binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_pass)
        } else {
            binding.tvBgModelBadge.text = "Download Needed"
            binding.tvBgModelBadge.setTextColor(activity.getColor(R.color.vf_accent_amber))
            binding.tvBgModelBadge.setBackgroundResource(R.drawable.bg_badge_warn)
        }
    }

    private fun exportImage() {
        val bmp = resultBitmap ?: run {
            Toast.makeText(
                activity,
                if (lastFailure != null) "No cutout to save — removal failed ($lastFailure)"
                else "No cutout to save yet",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val isJpg = binding.chipFormatJpg.isChecked
        scope.launch(Dispatchers.IO) {
            val exportDir = File(activity.filesDir, "exports").apply { mkdirs() }
            val ext = if (isJpg) "jpg" else "png"
            val mimeType = if (isJpg) "image/jpeg" else "image/png"
            val outFile = File(exportDir, "${sourceFileName}_BGREMOVED_${System.currentTimeMillis()}.$ext")

            val finalExportBmp = if (isJpg) {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(flat)
                val bgColor = when (binding.splitViewBgCompare.backgroundMode) {
                    BeforeAfterSplitView.BackgroundMode.PURE_BLACK -> Color.BLACK
                    else -> Color.WHITE
                }
                canvas.drawColor(bgColor)
                canvas.drawBitmap(bmp, 0f, 0f, null)
                flat
            } else {
                when (binding.splitViewBgCompare.backgroundMode) {
                    BeforeAfterSplitView.BackgroundMode.PURE_WHITE -> {
                        val comp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(comp)
                        canvas.drawColor(Color.WHITE)
                        canvas.drawBitmap(bmp, 0f, 0f, null)
                        comp
                    }
                    BeforeAfterSplitView.BackgroundMode.PURE_BLACK -> {
                        val comp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(comp)
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(bmp, 0f, 0f, null)
                        comp
                    }
                    else -> bmp
                }
            }

            try {
                FileOutputStream(outFile).use { fos ->
                    if (isJpg) {
                        finalExportBmp.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                    } else {
                        finalExportBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }

                // MediaStore gallery save
                val resolver = activity.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, outFile.name)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VeilFrame")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val savedUri = resolver.insert(collection, values)
                    ?: throw java.io.IOException("MediaStore insert refused")
                resolver.openOutputStream(savedUri)?.use { out ->
                    outFile.inputStream().use { inp -> inp.copyTo(out) }
                } ?: throw java.io.IOException("openOutputStream returned null")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(savedUri, values, null, null)
                }

                onExportPngRequest(outFile)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Saved to Gallery (Pictures/VeilFrame)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gallery save failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateUi() {
        updateActiveModelBadge()
        if (sourceBitmap == null) {
            binding.containerBgEmptyState.visibility = View.VISIBLE
            binding.splitViewBgCompare.visibility = View.GONE
            binding.cardBgActionDock.visibility = View.GONE
            binding.containerBgControls.visibility = View.GONE
            binding.btnBgResetCompare.visibility = View.GONE
            binding.btnBgSavePng.isEnabled = false
        } else {
            binding.containerBgEmptyState.visibility = View.GONE
            binding.splitViewBgCompare.visibility = View.VISIBLE
            binding.cardBgActionDock.visibility = View.VISIBLE
            binding.containerBgControls.visibility = View.VISIBLE
            binding.btnBgResetCompare.visibility = if (resultBitmap != null) View.VISIBLE else View.GONE
            binding.btnBgSavePng.isEnabled = (resultBitmap != null)

            val displayCutout = resultBitmap ?: sourceBitmap
            binding.splitViewBgCompare.setBitmaps(sourceBitmap, displayCutout)
        }
    }
}
