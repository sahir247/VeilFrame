package com.veilframe.app.pdf

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.veilframe.app.R
import com.veilframe.app.databinding.DialogPdfCertDetailsBinding
import com.veilframe.app.databinding.DialogPdfPasswordBinding
import com.veilframe.app.databinding.DialogPdfTrustManagerBinding
import com.veilframe.app.databinding.ItemPdfSignatureCardBinding
import com.veilframe.app.databinding.ItemPdfTrustEntryBinding
import com.veilframe.app.databinding.LayoutPdfSignatureBinding
import com.veilframe.app.navigation.ToolWorkStateContract
import com.veilframe.app.settings.ThemeSettingsManager
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.cert.X509Certificate

/**
 * Controller orchestrating PDF digital signature inspection, PKIX validation against
 * bundled CCA India roots and user pins, authentic Adobe DC 15w checkmark rectification,
 * trust management dialogs, and safe lifecycle cleanup.
 */
class PdfSignatureController(
    private val activity: AppCompatActivity,
    private val binding: LayoutPdfSignatureBinding,
    private val scope: CoroutineScope,
    private val onPickPdfRequest: () -> Unit,
    private val onSavePdfRequest: (ByteArray, String) -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onImportCaCertRequest: () -> Unit,
    private val onOpenPdfStudio: (() -> Unit)? = null
) : ToolWorkStateContract {

    private val engine = PdfSignatureEngine(activity)
    private val trustStore = TrustedCertificateStore(activity)

    private var currentPdfBytes: ByteArray? = null
    private var currentPdfName: String = "document.pdf"
    private var currentPassword: String = ""
    private var currentInspectionReport: PdfInspectionReport? = null
    private var currentPreviewBitmap: Bitmap? = null
    private var rectifiedPdfBytes: ByteArray? = null

    private var isWorking: Boolean = false
    private var isSaved: Boolean = false
    private var activeJob: Job? = null

    override fun hasPickedMedia(): Boolean = currentPdfBytes != null

    override fun hasUnsavedWork(): Boolean = currentInspectionReport != null && !isSaved

    override val isProcessing: Boolean get() = isWorking

    override fun discardAndReleaseAllResources() {
        activeJob?.cancel()
        activeJob = null
        isWorking = false

        currentPreviewBitmap?.recycle()
        currentPreviewBitmap = null

        currentPdfBytes = null
        rectifiedPdfBytes = null
        currentInspectionReport = null
        currentPassword = ""
        isSaved = false

        binding.ivPdfPreview.setImageDrawable(null)
        binding.containerSignaturesList.removeAllViews()
        binding.containerPdfSignatureContent.visibility = View.GONE
        binding.cardPdfSignaturePick.visibility = View.VISIBLE
        binding.btnPdfSignatureReset.visibility = View.GONE
        binding.progressPdfPreview.visibility = View.GONE
        binding.badgePreviewVerifiedTick.visibility = View.GONE
    }

    fun init() {
        binding.toolbarPdfSignature.setNavigationOnClickListener {
            onNavigateBack()
        }

        binding.btnPdfSignatureOpenHub.setOnClickListener {
            onOpenPdfStudio?.invoke()
        }

        binding.btnPdfTrustStore.setOnClickListener {
            showTrustManagerDialog()
        }

        binding.btnPdfSignatureReset.setOnClickListener {
            discardAndReleaseAllResources()
        }

        binding.btnPickSignedPdf.setOnClickListener {
            onPickPdfRequest()
        }

        binding.btnQuickTrustSigner.setOnClickListener {
            val untrusted = currentInspectionReport?.untrustedSigners?.firstOrNull()?.certificate
            if (untrusted != null) {
                trustSignerAndRevalidate(untrusted)
            }
        }

        binding.btnSaveValidatedPdf.setOnClickListener {
            saveValidatedPdf()
        }

        binding.btnShareValidatedPdf.setOnClickListener {
            shareValidatedPdf()
        }

        binding.btnPrintValidatedPdf.setOnClickListener {
            printValidatedPdf()
        }
    }

    fun handlePdfSelected(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val bytes = try {
                activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }

            val fileName = queryDisplayName(uri) ?: "signed_document.pdf"

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
        currentPdfBytes = bytes
        currentPdfName = fileName
        currentPassword = ""
        rectifiedPdfBytes = null
        isSaved = false

        // Check if password protected
        val check = PdfUnlockEngine.checkEncryption(bytes)
        if (check is PdfInitialCheck.PasswordRequired) {
            showPasswordDialog(bytes, fileName)
        } else {
            runInspection(bytes, fileName, "")
        }
    }

    private fun showPasswordDialog(bytes: ByteArray, fileName: String) {
        val dialogBinding = DialogPdfPasswordBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .create()
        dialog.setCancelable(true)

        dialogBinding.tvPasswordDialogTitle.text = "Protected Document"
        dialogBinding.tvPasswordDialogPrompt.text = "This document is password protected. Enter the password to verify its signatures."

        dialogBinding.btnPasswordDialogClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnPasswordCancel.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnPasswordUnlock.setOnClickListener {
            val pwd = dialogBinding.etPdfPassword.text?.toString().orEmpty()
            dialogBinding.tvPasswordError.visibility = View.GONE
            dialog.dismiss()
            runInspection(bytes, fileName, pwd)
        }

        dialog.show()
    }

    private fun runInspection(bytes: ByteArray, fileName: String, password: String) {
        activeJob?.cancel()
        isWorking = true
        currentPassword = password

        binding.cardPdfSignaturePick.visibility = View.GONE
        binding.containerPdfSignatureContent.visibility = View.VISIBLE
        binding.btnPdfSignatureReset.visibility = View.VISIBLE
        binding.progressPdfPreview.visibility = View.VISIBLE

        binding.tvPdfDocName.text = fileName
        binding.containerSignaturesList.removeAllViews()

        activeJob = scope.launch {
            val result = engine.inspectPdf(bytes, password)
            isWorking = false
            binding.progressPdfPreview.visibility = View.GONE

            result.onSuccess { report ->
                currentInspectionReport = report

                // Page count badge
                binding.tvPdfPageCount.text = "${report.pageCount} page${if (report.pageCount != 1) "s" else ""}"

                // Preview bitmap
                currentPreviewBitmap?.recycle()
                currentPreviewBitmap = report.previewBitmap
                if (report.previewBitmap != null) {
                    binding.ivPdfPreview.setImageBitmap(report.previewBitmap)
                }

                // Status Banner
                updateStatusBanner(report)

                // Signatures Breakdown
                populateSignaturesList(report)

            }.onFailure { err ->
                if (err is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                    Toast.makeText(activity, "Incorrect document password.", Toast.LENGTH_SHORT).show()
                    showPasswordDialog(bytes, fileName)
                } else {
                    Toast.makeText(activity, "Error inspecting signatures: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateStatusBanner(report: PdfInspectionReport) {
        if (report.signatures.isEmpty()) {
            binding.cardPdfStatusBanner.setCardBackgroundColor(activity.getColor(R.color.vf_surface))
            binding.ivPdfStatusIcon.setImageResource(R.drawable.ic_close)
            binding.ivPdfStatusIcon.setColorFilter(activity.getColor(R.color.vf_text_secondary))
            binding.tvPdfStatusTitle.text = "No Digital Signatures Found"
            binding.tvPdfStatusSubtitle.text = "This document does not contain any cryptographic PDF signature dictionaries."
            binding.cardUntrustedPrompt.visibility = View.GONE
            binding.badgePreviewVerifiedTick.visibility = View.GONE
            binding.btnSaveValidatedPdf.isEnabled = false
            return
        }

        binding.btnSaveValidatedPdf.isEnabled = true

        if (report.isTampered) {
            binding.cardPdfStatusBanner.setCardBackgroundColor(activity.getColor(R.color.vf_surface))
            binding.ivPdfStatusIcon.setImageResource(R.drawable.ic_close)
            binding.ivPdfStatusIcon.setColorFilter(activity.getColor(R.color.vf_accent_red))
            binding.tvPdfStatusTitle.text = "Signature Invalid / Altered"
            binding.tvPdfStatusSubtitle.text = "Document integrity verification failed. Signature byte range or CMS hash mismatch."
            binding.cardUntrustedPrompt.visibility = View.GONE
            binding.badgePreviewVerifiedTick.visibility = View.GONE
        } else if (report.isFullyVerified) {
            binding.cardPdfStatusBanner.setCardBackgroundColor(activity.getColor(R.color.vf_surface))
            binding.ivPdfStatusIcon.setImageResource(R.drawable.ic_signature_valid)
            val isMonochrome = ThemeSettingsManager.getAccentPalette(activity) == ThemeSettingsManager.AccentPalette.MONOCHROME
            if (isMonochrome) {
                val primaryColor = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, activity.getColor(R.color.vf_primary))
                binding.ivPdfStatusIcon.setColorFilter(primaryColor)
            } else {
                binding.ivPdfStatusIcon.clearColorFilter()
            }
            binding.tvPdfStatusTitle.text = "Signature Valid"
            binding.tvPdfStatusSubtitle.text = "All digital signatures are cryptographically valid and chained to a trusted root authority."
            binding.cardUntrustedPrompt.visibility = View.GONE

            // Visible tick in preview (authentic Adobe DC green checkmark badge)
            binding.badgePreviewVerifiedTick.visibility = View.VISIBLE
            val badgeColor = 0xFF008736.toInt()
            binding.badgePreviewVerifiedTick.backgroundTintList = android.content.res.ColorStateList.valueOf(badgeColor)
            binding.ivPreviewVerifiedIcon.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            binding.tvPreviewVerifiedLabel.setTextColor(0xFFFFFFFF.toInt())
            binding.tvPreviewVerifiedLabel.text = if (report.signatures.any { it.isPinned }) "Signature Valid • Pinned" else "Signature Valid"
        } else if (report.untrustedSigners.isNotEmpty()) {
            binding.cardPdfStatusBanner.setCardBackgroundColor(activity.getColor(R.color.vf_surface))
            binding.ivPdfStatusIcon.setImageResource(R.drawable.ic_signature_valid)
            binding.ivPdfStatusIcon.setColorFilter(activity.getColor(R.color.vf_accent_amber))
            binding.tvPdfStatusTitle.text = "Signature Valid (Untrusted Signer)"
            binding.tvPdfStatusSubtitle.text = "Signatures are mathematically valid, but the certificate chain does not anchor to a trusted root."
            binding.cardUntrustedPrompt.visibility = View.VISIBLE
            binding.badgePreviewVerifiedTick.visibility = View.GONE
        }
    }

    private fun populateSignaturesList(report: PdfInspectionReport) {
        binding.containerSignaturesList.removeAllViews()

        for (sig in report.signatures) {
            val itemBinding = ItemPdfSignatureCardBinding.inflate(
                LayoutInflater.from(activity),
                binding.containerSignaturesList,
                false
            )

            itemBinding.tvItemSignerName.text = sig.signerName
            itemBinding.tvItemDate.text = "Signing Date: ${sig.signingDate}"

            val details = StringBuilder()
            if (!sig.reason.isNullOrBlank()) details.append("Reason: ${sig.reason} • ")
            if (!sig.location.isNullOrBlank()) details.append("Location: ${sig.location} • ")
            details.append(if (sig.coversWholeDocument) "Whole Document Covered" else "Revision Restricted")
            itemBinding.tvItemDetails.text = details.toString()

            if (!sig.isCryptoValid || !sig.isByteRangePlausible) {
                itemBinding.ivItemStatusBadge.setImageResource(R.drawable.ic_close)
                itemBinding.ivItemStatusBadge.setColorFilter(activity.getColor(R.color.vf_accent_red))
                itemBinding.tvItemStatusText.text = "Invalid Signature (Corrupted or altered)"
                itemBinding.tvItemStatusText.setTextColor(activity.getColor(R.color.vf_accent_red))
            } else if (sig.isFullyVerified) {
                itemBinding.ivItemStatusBadge.setImageResource(R.drawable.ic_signature_valid)
                val isMonochrome = ThemeSettingsManager.getAccentPalette(activity) == ThemeSettingsManager.AccentPalette.MONOCHROME
                val successColor = if (isMonochrome) {
                    MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, activity.getColor(R.color.vf_primary))
                } else {
                    activity.getColor(R.color.vf_accent_green)
                }
                if (isMonochrome) {
                    itemBinding.ivItemStatusBadge.setColorFilter(successColor)
                } else {
                    itemBinding.ivItemStatusBadge.clearColorFilter()
                }
                val rootName = if (sig.isPinned) "User Pinned" else "CCA India Trusted"
                itemBinding.tvItemStatusText.text = "Valid Signature • $rootName"
                itemBinding.tvItemStatusText.setTextColor(successColor)
            } else {
                itemBinding.ivItemStatusBadge.setImageResource(R.drawable.ic_signature_valid)
                itemBinding.ivItemStatusBadge.setColorFilter(activity.getColor(R.color.vf_accent_amber))
                itemBinding.tvItemStatusText.text = "Valid Signature • Unknown Trust Authority"
                itemBinding.tvItemStatusText.setTextColor(activity.getColor(R.color.vf_accent_amber))

                if (sig.certificate != null) {
                    itemBinding.btnItemTrustSigner.visibility = View.VISIBLE
                    itemBinding.btnItemTrustSigner.setOnClickListener {
                        trustSignerAndRevalidate(sig.certificate)
                    }
                }
            }

            itemBinding.btnItemCertDetails.setOnClickListener {
                if (sig.certificate != null) {
                    showCertDetailsDialog(sig.certificate)
                } else {
                    Toast.makeText(activity, "Certificate details not embedded.", Toast.LENGTH_SHORT).show()
                }
            }

            binding.containerSignaturesList.addView(itemBinding.root)
        }
    }

    private fun trustSignerAndRevalidate(cert: X509Certificate) {
        try {
            engine.trustSignerCertificate(cert, "Trusted by user via VeilFrame")
            Toast.makeText(activity, "Certificate added to Trusted Store.", Toast.LENGTH_SHORT).show()
            currentPdfBytes?.let { bytes ->
                runInspection(bytes, currentPdfName, currentPassword)
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Failed to trust certificate: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCertDetailsDialog(cert: X509Certificate) {
        val dialogBinding = DialogPdfCertDetailsBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .create()
        dialog.setCancelable(true)

        dialogBinding.tvCertSubject.text = cert.subjectX500Principal.name
        dialogBinding.tvCertIssuer.text = cert.issuerX500Principal.name
        dialogBinding.tvCertValidity.text = "${cert.notBefore} to ${cert.notAfter}"
        dialogBinding.tvCertAlgorithm.text = "${cert.sigAlgName} (Serial: ${cert.serialNumber.toString(16).uppercase()})"

        val fp = try {
            TrustedCertificateStore.fingerprint(cert)
        } catch (e: Exception) {
            "Unavailable"
        }
        dialogBinding.tvCertFingerprint.text = fp

        dialogBinding.btnCertClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnCertDismiss.setOnClickListener { dialog.dismiss() }

        val isAlreadyTrusted = trustStore.isTrusted(cert)
        if (isAlreadyTrusted) {
            dialogBinding.btnTrustCertificate.text = "Already Trusted"
            dialogBinding.btnTrustCertificate.isEnabled = false
        } else {
            dialogBinding.btnTrustCertificate.setOnClickListener {
                dialog.dismiss()
                trustSignerAndRevalidate(cert)
            }
        }

        dialog.show()
    }

    private fun showTrustManagerDialog() {
        val dialogBinding = DialogPdfTrustManagerBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .create()
        dialog.setCancelable(true)

        dialogBinding.btnTrustManagerClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnTrustManagerDismiss.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnImportCaCert.setOnClickListener {
            dialog.dismiss()
            onImportCaCertRequest()
        }

        // Bundled roots
        val bundledRoots = try { BundledTrustAnchors.loadTrustAnchors(activity) } catch (e: Exception) { emptyList() }
        dialogBinding.containerBundledRoots.removeAllViews()

        for (root in bundledRoots) {
            val entryBinding = ItemPdfTrustEntryBinding.inflate(LayoutInflater.from(activity), dialogBinding.containerBundledRoots, false)
            entryBinding.tvTrustTitle.text = extractCn(root.subjectX500Principal.name) ?: root.subjectX500Principal.name
            entryBinding.tvTrustFingerprint.text = "SHA-256: " + try { TrustedCertificateStore.fingerprint(root) } catch (e: Exception) { "" }
            entryBinding.btnDeletePin.visibility = View.GONE
            dialogBinding.containerBundledRoots.addView(entryBinding.root)
        }

        // User Pins
        refreshUserPinsInDialog(dialogBinding)

        dialog.show()
    }

    private fun refreshUserPinsInDialog(dialogBinding: DialogPdfTrustManagerBinding) {
        val pins = trustStore.listFingerprints()
        dialogBinding.containerUserPins.removeAllViews()

        if (pins.isEmpty()) {
            val tv = TextView(activity).apply {
                text = "No user-trusted signer certificates pinned yet."
                setTextColor(activity.getColor(R.color.vf_text_secondary))
                textSize = 12f
            }
            dialogBinding.containerUserPins.addView(tv)
        } else {
            for (fp in pins) {
                val cert = trustStore.getCertificate(fp)
                val entryBinding = ItemPdfTrustEntryBinding.inflate(LayoutInflater.from(activity), dialogBinding.containerUserPins, false)
                entryBinding.tvTrustTitle.text = if (cert != null) (extractCn(cert.subjectX500Principal.name) ?: "Pinned Signer") else "Pinned Signer"
                entryBinding.tvTrustFingerprint.text = "SHA-256: $fp"
                entryBinding.btnDeletePin.visibility = View.VISIBLE
                entryBinding.btnDeletePin.setOnClickListener {
                    trustStore.remove(fp)
                    Toast.makeText(activity, "Pin removed.", Toast.LENGTH_SHORT).show()
                    refreshUserPinsInDialog(dialogBinding)
                    currentPdfBytes?.let { bytes -> runInspection(bytes, currentPdfName, currentPassword) }
                }
                dialogBinding.containerUserPins.addView(entryBinding.root)
            }
        }
    }

    fun handleCaCertImported(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val cert = activity.contentResolver.openInputStream(uri)?.use { stream ->
                    val factory = java.security.cert.CertificateFactory.getInstance("X.509")
                    factory.generateCertificate(stream) as? X509Certificate
                }
                if (cert != null) {
                    trustStore.trustCertificateAuthority(cert)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Root CA imported successfully.", Toast.LENGTH_SHORT).show()
                        currentPdfBytes?.let { bytes -> runInspection(bytes, currentPdfName, currentPassword) }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Invalid certificate file format.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveValidatedPdf() {
        val bytes = currentPdfBytes ?: return
        val outName = if (currentPdfName.endsWith(".pdf", ignoreCase = true)) {
            currentPdfName.substring(0, currentPdfName.length - 4) + "_validated.pdf"
        } else {
            "${currentPdfName}_validated.pdf"
        }

        binding.progressPdfPreview.visibility = View.VISIBLE
        isWorking = true

        scope.launch {
            val rectified = rectifiedPdfBytes ?: run {
                val res = engine.rectifyAndSave(bytes, currentPassword)
                res.getOrNull() ?: bytes
            }
            rectifiedPdfBytes = rectified
            isWorking = false
            binding.progressPdfPreview.visibility = View.GONE
            isSaved = true
            onSavePdfRequest(rectified, outName)
        }
    }

    private fun shareValidatedPdf() {
        val bytes = currentPdfBytes ?: return
        binding.progressPdfPreview.visibility = View.VISIBLE
        isWorking = true

        scope.launch {
            val rectified = rectifiedPdfBytes ?: run {
                val res = engine.rectifyAndSave(bytes, currentPassword)
                res.getOrNull() ?: bytes
            }
            rectifiedPdfBytes = rectified
            isWorking = false
            binding.progressPdfPreview.visibility = View.GONE

            withContext(Dispatchers.IO) {
                try {
                    val shareDir = File(activity.cacheDir, "shared_pdfs").apply { mkdirs() }
                    val shareFile = File(shareDir, "validated_$currentPdfName")
                    FileOutputStream(shareFile).use { it.write(rectified) }

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
                        activity.startActivity(Intent.createChooser(intent, "Share Validated PDF"))
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Failed to share PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun printValidatedPdf() {
        val bytes = rectifiedPdfBytes ?: currentPdfBytes ?: return
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager != null) {
            val isVerified = currentInspectionReport?.signatures?.any { it.isFullyVerified } == true
            val firstSig = currentInspectionReport?.signatures?.firstOrNull()
            printManager.print(
                "Validated PDF - $currentPdfName",
                PdfPrintDocumentAdapter(
                    activity,
                    bytes,
                    currentPassword,
                    currentPdfName ?: "Document",
                    isVerified,
                    firstSig?.signerName,
                    firstSig?.signingDate
                ),
                null
            )
        } else {
            Toast.makeText(activity, "Printing is not available on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractCn(dn: String?): String? {
        if (dn == null) return null
        for (part in dn.split(",")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("CN=", ignoreCase = true)) {
                return trimmed.substring(3).trim()
            }
        }
        return null
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
