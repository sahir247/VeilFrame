package com.veilframe.app.pdf

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.R
import com.veilframe.app.databinding.DialogPdfPasswordBinding
import com.veilframe.app.databinding.LayoutPdfUnlockBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Controller orchestrating offline PDF password removal, permission un-restriction,
 * smooth unlocked bounce/scale animation, preview rendering, and safe resource cleanup.
 */
class PdfUnlockController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfUnlockBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSavePdfRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit
) : ToolWorkStateContract {

    private var originalPdfBytes: ByteArray? = null
    private var originalPdfName: String = "document.pdf"
    private var unlockedResult: PdfUnlockResult? = null
    private var currentPreviewBitmap: Bitmap? = null

    private var isUnlocking: Boolean = false
    private var isSaved: Boolean = false
    private var activeJob: Job? = null

    override fun hasPickedMedia(): Boolean = originalPdfBytes != null

    override fun hasUnsavedWork(): Boolean = unlockedResult != null && !isSaved

    override val isProcessing: Boolean get() = isUnlocking

    override fun discardAndReleaseAllResources() {
        activeJob?.cancel()
        activeJob = null
        isUnlocking = false

        currentPreviewBitmap?.recycle()
        currentPreviewBitmap = null

        originalPdfBytes = null
        unlockedResult = null
        isSaved = false

        binding.ivPdfUnlockPreview.setImageDrawable(null)
        binding.containerPdfUnlockContent.visibility = View.GONE
        binding.cardPdfUnlockPick.visibility = View.VISIBLE
        binding.btnPdfUnlockReset.visibility = View.GONE
        binding.progressPdfUnlockPreview.visibility = View.GONE
    }

    fun init() {
        binding.toolbarPdfUnlock.setNavigationOnClickListener {
            onNavigateBack()
        }

        binding.btnPdfUnlockReset.setOnClickListener {
            discardAndReleaseAllResources()
        }

        binding.btnPickEncryptedPdf.setOnClickListener {
            onPickPdfRequest()
        }

        binding.btnSaveUnlockedPdf.setOnClickListener {
            saveUnlockedPdf()
        }

        binding.btnShareUnlockedPdf.setOnClickListener {
            shareUnlockedPdf()
        }

        binding.btnPrintUnlockedPdf.setOnClickListener {
            printUnlockedPdf()
        }
    }

    fun handlePdfSelected(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val bytes = try {
                activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }

            val fileName = queryDisplayName(uri) ?: "encrypted_document.pdf"

            withContext(Dispatchers.Main) {
                if (bytes == null || bytes.isEmpty()) {
                    Toast.makeText(activity, "Failed to read PDF file.", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                loadPdfBytes(bytes, fileName)
            }
        }
    }

    fun loadPdfBytes(bytes: ByteArray, fileName: String) {
        originalPdfBytes = bytes
        originalPdfName = fileName
        unlockedResult = null
        isSaved = false

        val check = PdfUnlockEngine.checkEncryption(bytes)
        when (check) {
            is PdfInitialCheck.PasswordRequired -> {
                showPasswordDialog(bytes, fileName)
            }
            is PdfInitialCheck.Clear -> {
                // Document not encrypted, decrypt directly with empty password
                executeUnlock(bytes, fileName, "")
            }
            is PdfInitialCheck.Failed -> {
                Toast.makeText(activity, check.error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPasswordDialog(bytes: ByteArray, fileName: String) {
        val dialogBinding = DialogPdfPasswordBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .create()
        dialog.setCancelable(true)

        dialogBinding.tvPasswordDialogTitle.text = "Unlock PDF Document"
        dialogBinding.tvPasswordDialogPrompt.text = "Enter the PDF password to remove all encryption and password restrictions."

        dialogBinding.btnPasswordDialogClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnPasswordCancel.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnPasswordUnlock.setOnClickListener {
            val pwd = dialogBinding.etPdfPassword.text?.toString().orEmpty()
            if (pwd.isEmpty()) {
                dialogBinding.tvPasswordError.text = "Please enter the document password."
                dialogBinding.tvPasswordError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            dialogBinding.tvPasswordError.visibility = View.GONE
            dialog.dismiss()
            executeUnlock(bytes, fileName, pwd)
        }

        dialog.show()
    }

    private fun executeUnlock(bytes: ByteArray, fileName: String, password: String) {
        activeJob?.cancel()
        isUnlocking = true

        binding.cardPdfUnlockPick.visibility = View.GONE
        binding.containerPdfUnlockContent.visibility = View.VISIBLE
        binding.btnPdfUnlockReset.visibility = View.VISIBLE
        binding.progressPdfUnlockPreview.visibility = View.VISIBLE

        binding.tvPdfUnlockDocName.text = fileName

        activeJob = scope.launch {
            val result = PdfUnlockEngine.unlockPdf(bytes, password)
            isUnlocking = false
            binding.progressPdfUnlockPreview.visibility = View.GONE

            result.onSuccess { res ->
                unlockedResult = res

                // Page count
                binding.tvPdfUnlockPageCount.text = "${res.pageCount} page${if (res.pageCount != 1) "s" else ""}"

                // Preview bitmap
                currentPreviewBitmap?.recycle()
                currentPreviewBitmap = res.previewBitmap
                if (res.previewBitmap != null) {
                    binding.ivPdfUnlockPreview.setImageBitmap(res.previewBitmap)
                }

                // Security and size stats
                binding.tvUnlockSecurityDetails.text = "Protection: Fully Stripped • Permissions: Unrestricted Access"
                val origKb = formatFileSize(res.originalSizeBytes)
                val unlKb = formatFileSize(res.unlockedSizeBytes)
                binding.tvUnlockSizeDetails.text = "Original Encrypted Size: $origKb • Decrypted Size: $unlKb"

                // Play smooth unlocked icon animation
                playUnlockedAnimation()

            }.onFailure { err ->
                if (err.message?.contains("password", ignoreCase = true) == true) {
                    Toast.makeText(activity, "Incorrect password. Please try again.", Toast.LENGTH_SHORT).show()
                    showPasswordDialog(bytes, fileName)
                } else {
                    Toast.makeText(activity, "Failed to decrypt PDF: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Plays a smooth bounce and scale animation on the unlocked lock icon.
     */
    private fun playUnlockedAnimation() {
        val icon = binding.ivUnlockedIcon
        icon.scaleX = 0.2f
        icon.scaleY = 0.2f
        icon.alpha = 0f
        icon.rotation = -30f

        val scaleX = ObjectAnimator.ofFloat(icon, "scaleX", 0.2f, 1.25f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(icon, "scaleY", 0.2f, 1.25f, 1.0f)
        val alpha = ObjectAnimator.ofFloat(icon, "alpha", 0f, 1.0f)
        val rotation = ObjectAnimator.ofFloat(icon, "rotation", -30f, 0f)

        val set = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha, rotation)
            duration = 650
            interpolator = OvershootInterpolator(1.8f)
        }
        set.start()
    }

    private fun saveUnlockedPdf() {
        val res = unlockedResult ?: return
        val outName = if (originalPdfName.endsWith(".pdf", ignoreCase = true)) {
            originalPdfName.substring(0, originalPdfName.length - 4) + "_unlocked.pdf"
        } else {
            "${originalPdfName}_unlocked.pdf"
        }

        isSaved = true
        onSavePdfRequest(res.unlockedBytes, outName)
    }

    private fun shareUnlockedPdf() {
        val res = unlockedResult ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val shareDir = File(activity.cacheDir, "shared_pdfs").apply { mkdirs() }
                val shareFile = File(shareDir, "unlocked_$originalPdfName")
                FileOutputStream(shareFile).use { it.write(res.unlockedBytes) }

                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.provider",
                    shareFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    activity.startActivity(Intent.createChooser(intent, "Share Unlocked PDF"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Failed to share PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun printUnlockedPdf() {
        val res = unlockedResult ?: return
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager != null) {
            printManager.print(
                "Unlocked PDF - $originalPdfName",
                PdfPrintDocumentAdapter(
                    activity,
                    res.unlockedBytes,
                    null,
                    originalPdfName ?: "Unlocked Document",
                    false,
                    null,
                    null
                ),
                null
            )
        } else {
            Toast.makeText(activity, "Printing is not available on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = Math.min(units.size - 1, digitGroups)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
