package com.veilframe.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.cv.core.BitmapBridge
import com.veilframe.app.cv.segmentation.BackgroundRemover
import com.veilframe.app.databinding.LayoutBackgroundRemoverBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * Controller managing the dedicated Background Remover workspace:
 * - Image selection and display
 * - Subject segmentation and alpha mask compositing
 * - Transparent PNG export
 */
class BackgroundRemoverController(
    private val activity: AppCompatActivity,
    private val binding: LayoutBackgroundRemoverBinding,
    private val scope: CoroutineScope,
    private val onPickImageRequest: () -> Unit,
    private val onExportPngRequest: (File) -> Unit,
    private val onNavigateBack: () -> Unit
) {
    private var sourceBitmap: Bitmap? = null
    private var resultBitmap: Bitmap? = null

    fun init() {
        binding.toolbarBgRemover.setNavigationOnClickListener {
            onNavigateBack()
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

        binding.btnBgSavePng.setOnClickListener {
            exportPng()
        }

        updateUi()
    }

    fun handleImageSelected(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val bmp = try {
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                if (bmp != null) {
                    sourceBitmap = bmp
                    resultBitmap = null
                    updateUi()
                    executeRemoval()
                } else {
                    Toast.makeText(activity, "Could not open image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun executeRemoval() {
        val srcBmp = sourceBitmap ?: return
        Toast.makeText(activity, "Processing background removal...", Toast.LENGTH_SHORT).show()

        scope.launch(Dispatchers.IO) {
            var cutoutBmp: Bitmap? = null
            try {
                val srcMat = BitmapBridge.toMat(srcBmp)
                // Use adaptive luma thresholding segmenter fallback when neural model is local
                val segmenter = BackgroundRemover.ForegroundSegmenter { img ->
                    val gray = Mat()
                    Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
                    val mask = Mat()
                    Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                    gray.release()
                    mask
                }

                val removalResult = BackgroundRemover.removeBackground(srcMat, segmenter)
                cutoutBmp = BitmapBridge.toBitmap(removalResult.output)
                removalResult.output.release()
                removalResult.mask.release()
                srcMat.release()
            } catch (e: Throwable) {
                // Fallback: simple copy if native CV is not initialized
                cutoutBmp = srcBmp
            }

            withContext(Dispatchers.Main) {
                resultBitmap = cutoutBmp
                updateUi()
            }
        }
    }

    private fun exportPng() {
        val bmp = resultBitmap ?: sourceBitmap ?: run {
            Toast.makeText(activity, "No image to save", Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch(Dispatchers.IO) {
            val exportDir = File(activity.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(exportDir, "Cutout_${System.currentTimeMillis()}.png")
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            withContext(Dispatchers.Main) {
                onExportPngRequest(outFile)
            }
        }
    }

    private fun updateUi() {
        if (sourceBitmap == null) {
            binding.containerBgEmptyState.visibility = View.VISIBLE
            binding.ivBgResultPreview.visibility = View.GONE
            binding.cardBgActionDock.visibility = View.GONE
            binding.btnBgSavePng.isEnabled = false
        } else {
            binding.containerBgEmptyState.visibility = View.GONE
            binding.ivBgResultPreview.visibility = View.VISIBLE
            binding.cardBgActionDock.visibility = View.VISIBLE
            binding.btnBgSavePng.isEnabled = (resultBitmap != null)

            val displayBmp = resultBitmap ?: sourceBitmap
            binding.ivBgResultPreview.setImageBitmap(displayBmp)
        }
    }
}
