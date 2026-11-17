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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.provider.OpenableColumns
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
    private val onNavigateBack: () -> Unit,
    private val onOpenPdfStudio: (() -> Unit)? = null,
    private val onSavePdfFromDiskRequest: ((File, String) -> Unit)? = null
) : ToolWorkStateContract {

    private val taskTracker = PdfTaskTracker(scope)

    private var originalPdfBytes: ByteArray? = null
    private var originalPdfUri: Uri? = null
    private var unlockedPdfFile: File? = null
    private var originalPdfName: String = "document.pdf"
    private var unlockedResult: PdfUnlockResult? = null
    private var currentPreviewBitmap: Bitmap? = null

    private var isUnlocking: Boolean = false
    private var isSaved: Boolean = false
    private var activeJob: Job? = null

    override fun hasPickedMedia(): Boolean = originalPdfBytes != null || originalPdfUri != null

    override fun hasUnsavedWork(): Boolean = (unlockedResult != null || unlockedPdfFile != null) && !isSaved

    override val isProcessing: Boolean get() = isUnlocking

    override fun discardAndReleaseAllResources() {
        taskTracker.cancelAll()
        activeJob?.cancel()
        activeJob = null
        isUnlocking = false

        currentPreviewBitmap?.recycle()
        currentPreviewBitmap = null

        originalPdfBytes = null
        originalPdfUri = null
        unlockedResult = null
        unlockedPdfFile?.delete()
        unlockedPdfFile = null
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

        binding.btnPdfUnlockOpenHub.setOnClickListener {
            onOpenPdfStudio?.invoke()
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
        taskTracker.cancelAll()
        activeJob = null
        currentPreviewBitmap?.recycle()
        currentPreviewBitmap = null
        originalPdfBytes = null
        originalPdfUri = uri
        unlockedResult = null
        unlockedPdfFile?.delete()
        unlockedPdfFile = null
        isUnlocking = false
        isSaved = false
        binding.progressPdfUnlockPreview.visibility = View.VISIBLE
        binding.ivPdfUnlockPreview.setImageDrawable(null)
        val fileName = queryDisplayName(uri) ?: "encrypted_document.pdf"
        originalPdfName = fileName

        // Inspect the picker URI directly: no full-document ByteArray on import.
        taskTracker.launch(Dispatchers.IO) {
            val encrypted = try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    PdfUnlockEngine.checkEncryption(input)
                } ?: throw java.io.IOException("Could not open selected PDF")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (originalPdfUri == uri) {
                        binding.progressPdfUnlockPreview.visibility = View.GONE
                        Toast.makeText(activity, "Failed to inspect PDF: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (originalPdfUri != uri) return@withContext
                binding.progressPdfUnlockPreview.visibility = View.GONE
                if (encrypted) showPasswordDialogForUri(uri, fileName)
                else executeUnlockFromUri(uri, fileName, "")
            }
        }
    }

    fun loadPdfBytes(bytes: ByteArray, fileName: String) {
        originalPdfUri = null
        unlockedPdfFile?.delete()
        unlockedPdfFile = null
        originalPdfBytes = bytes
        originalPdfName = fileName
        unlockedResult = null
        isSaved = false

        binding.progressPdfUnlockPreview.visibility = View.VISIBLE
        taskTracker.launch(Dispatchers.IO) {
            val check = PdfUnlockEngine.checkEncryption(bytes)
            withContext(Dispatchers.Main) {
                binding.progressPdfUnlockPreview.visibility = View.GONE
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
        }
    }

    private fun showPasswordDialog(bytes: ByteArray, fileName: String) {
        showPasswordDialogForSource(fileName) { password -> executeUnlock(bytes, fileName, password) }
    }

    private fun showPasswordDialogForUri(uri: Uri, fileName: String) {
        showPasswordDialogForSource(fileName) { password -> executeUnlockFromUri(uri, fileName, password) }
    }

    private fun showPasswordDialogForSource(fileName: String, unlock: (String) -> Unit) {
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
            unlock(pwd)
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

        activeJob = taskTracker.launch {
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
     * Decrypt directly from the SAF URI to a private temporary file. Neither
     * original nor unlocked PDF is retained as a whole in the Java heap.
     * File and preview ownership are transferred only on full success.
     */
    private fun executeUnlockFromUri(uri: Uri, fileName: String, password: String) {
        activeJob?.cancel()
        unlockedResult = null
        unlockedPdfFile?.delete()
        unlockedPdfFile = null
        isUnlocking = true
        isSaved = false
        binding.cardPdfUnlockPick.visibility = View.GONE
        binding.containerPdfUnlockContent.visibility = View.VISIBLE
        binding.btnPdfUnlockReset.visibility = View.VISIBLE
        binding.progressPdfUnlockPreview.visibility = View.VISIBLE
        binding.tvPdfUnlockDocName.text = fileName

        activeJob = taskTracker.launch {
            var tempFile: File? = null
            var preview: Bitmap? = null
            var accepted = false
            try {
                val pages = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("veilframe_unlocked_", ".pdf", activity.cacheDir)
                    tempFile = file
                    val pageCount = activity.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().buffered().use { output ->
                            PdfUnlockEngine.unlockPdfStream(input, output, password).getOrThrow()
                        }
                    } ?: throw java.io.IOException("Cannot reopen the selected PDF")
                    if (!file.isFile || file.length() == 0L) {
                        throw java.io.IOException("Decrypted PDF output is empty")
                    }
                    preview = PdfUnlockEngine.renderPagePreview(file)
                    pageCount
                }
                val file = requireNotNull(tempFile)
                if (originalPdfUri != uri) return@launch
                unlockedPdfFile = file
                currentPreviewBitmap?.recycle()
                currentPreviewBitmap = preview
                accepted = true
                preview?.let { binding.ivPdfUnlockPreview.setImageBitmap(it) }
                binding.tvPdfUnlockPageCount.text = "$pages page${if (pages != 1) "s" else ""}"
                binding.tvUnlockSecurityDetails.text =
                    "Protection: Fully Stripped • Permissions: Unrestricted Access"

                val originalLength = withContext(Dispatchers.IO) {
                    try {
                        activity.contentResolver.query(
                            uri, arrayOf(OpenableColumns.SIZE), null, null, null
                        )?.use { cursor ->
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                                cursor.getLong(sizeIndex)
                            } else -1L
                        } ?: -1L
                    } catch (_: Exception) {
                        -1L
                    }
                }
                if (originalPdfUri == uri) {
                    val originalLabel = if (originalLength >= 0L) formatFileSize(originalLength) else "Unknown"
                    binding.tvUnlockSizeDetails.text =
                        "Original Encrypted Size: $originalLabel • Decrypted Size: ${formatFileSize(file.length())}"
                    playUnlockedAnimation()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (originalPdfUri == uri) {
                    if (e.message?.contains("password", ignoreCase = true) == true ||
                        e.message?.contains("decrypt", ignoreCase = true) == true) {
                        Toast.makeText(activity, "Incorrect password. Please try again.", Toast.LENGTH_SHORT).show()
                        showPasswordDialogForUri(uri, fileName)
                    } else {
                        Toast.makeText(activity, "Failed to unlock PDF: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                if (!accepted) {
                    tempFile?.delete()
                    preview?.recycle()
                }
                if (originalPdfUri == uri) {
                    isUnlocking = false
                    binding.progressPdfUnlockPreview.visibility = View.GONE
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
        taskTracker.launch(Dispatchers.IO) {
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
                    originalPdfName,
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
