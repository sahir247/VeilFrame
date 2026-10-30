package com.veilframe.app.ui.intake

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.veilframe.app.MainActivity
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
import com.veilframe.app.databinding.*
import com.veilframe.app.databinding.DialogPdfPasswordBinding
import com.veilframe.app.databinding.DialogPdfQuickToolPickerBinding
import com.veilframe.app.databinding.ItemPdfQuickToolOptionBinding
import com.veilframe.app.markdown.MarkdownDocxExporter
import com.veilframe.app.markdown.MarkdownTheme
import com.veilframe.app.pdf.PdfInitialCheck
import com.veilframe.app.pdf.PdfQuickToolController
import com.veilframe.app.pdf.PdfUnlockEngine
import com.veilframe.app.tools.ToolMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/**
 * Coordinator for Universal File Intake on VeilFrame's Home Dashboard.
 * Handles instant mounting, intelligent MIME type detection, cryptographic signature
 * inspection, password-protected PDF unlocking, quick tool picker modal, and markdown conversion.
 */
class HomeFileIntakeCoordinator(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding
) {
    var currentHomeIntakeUri: Uri? = null
        private set
    var currentHomeIntakePdfBytes: ByteArray? = null
        private set
    var currentHomeIntakePdfPassword: String? = null
        private set
    private var activeIntakeJob: Job? = null

    fun handleHomeFileIntake(uri: Uri) {
        activeIntakeJob?.cancel()
        currentHomeIntakeUri = uri
        currentHomeIntakePdfBytes = null
        currentHomeIntakePdfPassword = null
        try {
            activity.safStorageManager.persistReadPermission(uri)
        } catch (_: Exception) {}
        var mimeType = activity.contentResolver.getType(uri) ?: ""
        val displayName = activity.safStorageManager.getDisplayName(uri)
        val sizeBytes = activity.safStorageManager.queryFileSize(uri)
        val sizeStr = activity.safStorageManager.formatBytes(sizeBytes)

        val candidateName = (displayName + " " + (uri.lastPathSegment ?: "") + " " + (uri.path ?: "")).lowercase(Locale.ROOT)
        if (mimeType.isEmpty()) {
            val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString()).ifEmpty {
                candidateName.substringAfterLast('.', "")
            }
            if (ext.isNotEmpty()) {
                mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.ROOT)) ?: ""
            }
        }

        binding.layoutHomeIntakePickers.visibility = View.GONE
        binding.layoutHomeIntakeMounted.visibility = View.VISIBLE
        binding.tvHomeIntakeFileName.text = displayName
        binding.tvHomeIntakeMeta.text = if (sizeBytes > 0) "$sizeStr • ${mimeType.ifEmpty { "File" }}" else mimeType.ifEmpty { "Selected File" }

        val isImage = mimeType.startsWith("image/") ||
                candidateName.contains(".jpg") || candidateName.contains(".jpeg") || candidateName.contains(".png") ||
                candidateName.contains(".webp") || candidateName.contains(".heic") || candidateName.contains(".heif") ||
                candidateName.contains(".avif") || candidateName.contains(".bmp") || candidateName.contains(".gif")
        val isVideo = mimeType.startsWith("video/") ||
                candidateName.contains(".mp4") || candidateName.contains(".mkv") || candidateName.contains(".mov") ||
                candidateName.contains(".avi") || candidateName.contains(".webm") || candidateName.contains(".3gp")
        val isMarkdown = mimeType == "text/markdown" || mimeType == "text/x-markdown" ||
                candidateName.endsWith(".md") || candidateName.endsWith(".markdown") || candidateName.contains(".md ")
        val isPdf = mimeType == "application/pdf" || candidateName.contains(".pdf")
        val isDoc = mimeType.contains("word") || mimeType.contains("officedocument") ||
                mimeType.contains("excel") || mimeType.contains("spreadsheet") ||
                mimeType.contains("powerpoint") || mimeType.contains("presentation") ||
                mimeType.contains("opendocument") || mimeType == "text/plain" ||
                candidateName.endsWith(".docx") || candidateName.endsWith(".doc") ||
                candidateName.endsWith(".xlsx") || candidateName.endsWith(".xls") ||
                candidateName.endsWith(".pptx") || candidateName.endsWith(".ppt") ||
                candidateName.endsWith(".odt") || candidateName.endsWith(".rtf") ||
                candidateName.endsWith(".txt")

        if (isImage) {
            binding.ivHomeIntakeIcon.setImageResource(R.drawable.ic_tab_image)
            binding.btnSuggestEngine1.apply {
                text = "Open in Image Studio"
                setIconResource(R.drawable.ic_tab_image)
                setOnClickListener {
                    activity.openImageStudio()
                    activity.imageStudioController.handleImagesSelected(listOf(uri))
                }
            }
            binding.btnSuggestEngine2.apply {
                text = "AI Upscale"
                setIconResource(R.drawable.ic_resize)
                setOnClickListener {
                    activity.openImageUpscaler()
                    activity.workspaceHost.getOrInflateImageUpscaler()
                    activity.imageUpscalerController.handleImageSelected(uri)
                }
            }
            binding.btnSuggestEngine3.apply {
                text = "BG Remover"
                setIconResource(R.drawable.ic_eraser)
                setOnClickListener {
                    activity.openBackgroundRemover()
                    activity.workspaceHost.getOrInflateBackgroundRemover()
                    activity.backgroundRemoverController.handleImageSelected(uri)
                }
            }
        } else if (isVideo) {
            binding.ivHomeIntakeIcon.setImageResource(R.drawable.ic_tab_video)
            binding.btnSuggestEngine1.apply {
                text = "Open in Video Studio"
                setIconResource(R.drawable.ic_tab_video)
                setOnClickListener {
                    activity.openVideoStudio()
                    activity.videoStudioController.handleVideosSelected(listOf(uri))
                }
            }
            binding.btnSuggestEngine2.apply {
                text = "Remove Metadata"
                setIconResource(R.drawable.ic_shield_video)
                setOnClickListener {
                    activity.openTool(ToolMode.VIDEO_CLEANER)
                    activity.handleSingleFileSelected(uri)
                }
            }
            binding.btnSuggestEngine3.apply {
                text = "Motion Lab"
                setIconResource(R.drawable.ic_speed)
                setOnClickListener {
                    activity.openMotionLab()
                    activity.workspaceHost.getOrInflateMotionLab()
                    activity.motionLabController.setSource(uri)
                }
            }
        } else if (isMarkdown) {
            binding.ivHomeIntakeIcon.setImageResource(R.drawable.ic_tab_bundle)
            binding.tvHomeIntakeMeta.text = if (sizeBytes > 0) "$sizeStr • Markdown Document" else "Markdown Document"
            binding.btnSuggestEngine1.apply {
                text = "View Markdown"
                setIconResource(R.drawable.ic_tab_bundle)
                setOnClickListener {
                    activity.openMarkdownViewer(uri, displayName)
                }
            }
            binding.btnSuggestEngine2.apply {
                text = "Save as PDF"
                setIconResource(R.drawable.ic_pdf_file)
                setOnClickListener {
                    showMarkdownPdfThemePopup(uri, displayName)
                }
            }
            binding.btnSuggestEngine3.apply {
                text = "Save as DOCX"
                setIconResource(R.drawable.ic_file_download)
                setOnClickListener {
                    exportMarkdownToDocx(uri, displayName)
                }
            }
        } else if (isPdf) {
            binding.ivHomeIntakeIcon.setImageResource(R.drawable.ic_pdf_file)
            binding.tvHomeIntakeMeta.text = if (sizeBytes > 0) "$sizeStr • Analyzing PDF..." else "Analyzing PDF..."

            // Preliminary fallback options while async analysis runs
            applyPdfRecommendations(uri, displayName, sizeStr, hasSignatures = false, isEncrypted = false, password = null, bytes = null)

            activeIntakeJob = activity.lifecycleScope.launch(Dispatchers.IO) {
                val bytes = try {
                    activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (_: Exception) {
                    null
                }

                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.tvHomeIntakeMeta.text = if (sizeBytes > 0) "$sizeStr • PDF Document" else "PDF Document"
                    }
                    return@launch
                }

                currentHomeIntakePdfBytes = bytes
                val check = PdfUnlockEngine.checkEncryption(bytes)
                val isEncrypted = check is PdfInitialCheck.PasswordRequired

                if (isEncrypted) {
                    withContext(Dispatchers.Main) {
                        binding.tvHomeIntakeMeta.text = if (sizeBytes > 0) "$sizeStr • Protected PDF" else "Protected PDF"
                        applyPdfRecommendations(uri, displayName, sizeStr, hasSignatures = false, isEncrypted = true, password = null, bytes = bytes)
                        showPdfPasswordDialog(uri, displayName, sizeStr, bytes)
                    }
                } else {
                    var hasSignatures = false
                    try {
                        com.tom_roush.pdfbox.pdmodel.PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
                            val sigs = doc.signatureDictionaries
                            hasSignatures = !sigs.isNullOrEmpty()
                        }
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        applyPdfRecommendations(uri, displayName, sizeStr, hasSignatures = hasSignatures, isEncrypted = false, password = null, bytes = bytes)
                    }
                }
            }
        } else if (isDoc) {
            binding.ivHomeIntakeIcon.setImageResource(R.drawable.ic_pdf_file)
            binding.tvHomeIntakeMeta.text = if (sizeBytes > 0) "$sizeStr • Office Document" else "Office Document"
            binding.btnSuggestEngine1.apply {
                text = "Doc to PDF"
                setIconResource(R.drawable.ic_pdf_file)
                setOnClickListener {
                    activity.pdfSuiteCoordinator.openQuickTool(com.veilframe.app.pdf.PdfQuickToolController.QuickMode.DOC_TO_PDF)
                    activity.workspaceHost.getOrInflatePdfQuickTool()
                    activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
                }
            }
            binding.btnSuggestEngine2.apply {
                text = "Provenance"
                setIconResource(R.drawable.ic_certificate)
                setOnClickListener {
                    activity.openProvenance()
                    activity.workspaceHost.getOrInflateProvenance()
                    activity.provenanceController.handleFileSelected(uri)
                }
            }
            binding.btnSuggestEngine3.apply {
                text = "Code Bundler"
                setIconResource(R.drawable.ic_tab_bundle)
                setOnClickListener {
                    activity.openTool(ToolMode.AI_BUNDLE)
                    activity.handleSingleFileSelected(uri)
                }
            }
        } else {
            binding.ivHomeIntakeIcon.setImageResource(R.drawable.ic_certificate)
            binding.btnSuggestEngine1.apply {
                text = "Provenance"
                setIconResource(R.drawable.ic_certificate)
                setOnClickListener {
                    activity.openProvenance()
                    activity.workspaceHost.getOrInflateProvenance()
                    activity.provenanceController.handleFileSelected(uri)
                }
            }
            binding.btnSuggestEngine2.apply {
                text = "Code Bundler"
                setIconResource(R.drawable.ic_tab_bundle)
                setOnClickListener {
                    activity.openTool(ToolMode.AI_BUNDLE)
                    activity.handleSingleFileSelected(uri)
                }
            }
            binding.btnSuggestEngine3.apply {
                text = "Scan Folder"
                setIconResource(R.drawable.ic_tab_folder)
                setOnClickListener {
                    activity.openTool(ToolMode.FOLDER_SCANNER)
                }
            }
        }
    }

    fun showPdfPasswordDialog(
        uri: Uri,
        displayName: String,
        sizeStr: String,
        bytes: ByteArray
    ) {
        val dialogBinding = DialogPdfPasswordBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .create()
        dialog.setCancelable(true)

        dialogBinding.tvPasswordDialogTitle.text = "Protected PDF Document"
        dialogBinding.tvPasswordDialogSubtitle.text = "Cryptographic Verification & Quick Tools"
        dialogBinding.tvPasswordDialogPrompt.text = "This document is password protected. Enter the document password to verify cryptographic signatures and access PDF tools."
        dialogBinding.btnPasswordUnlock.text = "Verify & Unlock"

        dialogBinding.btnPasswordDialogClose.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.btnPasswordCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnPasswordUnlock.setOnClickListener {
            val pwd = dialogBinding.etPdfPassword.text?.toString().orEmpty()
            dialogBinding.tvPasswordError.visibility = View.GONE

            activity.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    com.tom_roush.pdfbox.pdmodel.PDDocument.load(ByteArrayInputStream(bytes), pwd).use { doc ->
                        val sigs = doc.signatureDictionaries
                        val hasSigs = !sigs.isNullOrEmpty()
                        withContext(Dispatchers.Main) {
                            currentHomeIntakePdfPassword = pwd
                            dialog.dismiss()
                            applyPdfRecommendations(
                                uri = uri,
                                displayName = displayName,
                                sizeStr = sizeStr,
                                hasSignatures = hasSigs,
                                isEncrypted = true,
                                password = pwd,
                                bytes = bytes
                            )
                        }
                    }
                } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                    withContext(Dispatchers.Main) {
                        dialogBinding.tvPasswordError.text = "Incorrect password. Please try again."
                        dialogBinding.tvPasswordError.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        dialogBinding.tvPasswordError.text = "Failed to load document: ${e.message}"
                        dialogBinding.tvPasswordError.visibility = View.VISIBLE
                    }
                }
            }
        }

        dialog.show()
    }

    fun applyPdfRecommendations(
        uri: Uri,
        displayName: String,
        sizeStr: String,
        hasSignatures: Boolean,
        isEncrypted: Boolean,
        password: String?,
        bytes: ByteArray?
    ) {
        if (hasSignatures) {
            binding.tvHomeIntakeMeta.text = if (isEncrypted) "$sizeStr • Unlocked • Digitally Signed" else "$sizeStr • Digitally Signed PDF"
            binding.btnSuggestEngine1.apply {
                text = "Verify Signature"
                setIconResource(R.drawable.ic_signature_valid)
                setOnClickListener {
                    clearHomeFileIntake()
                    activity.openPdfSignature()
                    activity.workspaceHost.getOrInflatePdfSignature()
                    if (bytes != null) {
                        activity.pdfSignatureController.loadPdfBytes(bytes, displayName, password ?: "")
                    } else {
                        activity.pdfSignatureController.handlePdfSelected(uri)
                    }
                }
            }
            binding.btnSuggestEngine2.apply {
                text = "PDF Quick Tool"
                setIconResource(R.drawable.ic_pdf_file)
                setOnClickListener {
                    showPdfQuickToolPopup(uri, displayName, password, bytes)
                }
            }
            binding.btnSuggestEngine3.apply {
                text = "Extract Text"
                setIconResource(R.drawable.ic_tab_bundle)
                setOnClickListener {
                    clearHomeFileIntake()
                    activity.pdfSuiteCoordinator.openExtractText()
                    activity.workspaceHost.getOrInflatePdfExtractText()
                    activity.pdfSuiteCoordinator.extractTextController.handlePdfSelected(uri)
                }
            }
        } else if (isEncrypted && password.isNullOrEmpty()) {
            binding.tvHomeIntakeMeta.text = "$sizeStr • Protected PDF (Locked)"
            binding.btnSuggestEngine1.apply {
                text = "Unlock PDF"
                setIconResource(R.drawable.ic_lock_open)
                setOnClickListener {
                    if (bytes != null) {
                        showPdfPasswordDialog(uri, displayName, sizeStr, bytes)
                    } else {
                        clearHomeFileIntake()
                        activity.openPdfUnlock()
                        activity.workspaceHost.getOrInflatePdfUnlock()
                        activity.pdfUnlockController.handlePdfSelected(uri)
                    }
                }
            }
            binding.btnSuggestEngine2.apply {
                text = "PDF Quick Tool"
                setIconResource(R.drawable.ic_pdf_file)
                setOnClickListener {
                    showPdfQuickToolPopup(uri, displayName, null, bytes)
                }
            }
            binding.btnSuggestEngine3.apply {
                text = "Verify Signature"
                setIconResource(R.drawable.ic_signature_valid)
                setOnClickListener {
                    clearHomeFileIntake()
                    activity.openPdfSignature()
                    activity.workspaceHost.getOrInflatePdfSignature()
                    if (bytes != null) {
                        activity.pdfSignatureController.loadPdfBytes(bytes, displayName, "")
                    } else {
                        activity.pdfSignatureController.handlePdfSelected(uri)
                    }
                }
            }
        } else {
            binding.tvHomeIntakeMeta.text = if (isEncrypted) "$sizeStr • Unlocked PDF" else "$sizeStr • PDF Document"
            binding.btnSuggestEngine1.apply {
                text = "PDF Quick Tool"
                setIconResource(R.drawable.ic_pdf_file)
                setOnClickListener {
                    showPdfQuickToolPopup(uri, displayName, password, bytes)
                }
            }
            binding.btnSuggestEngine2.apply {
                text = "Extract Text"
                setIconResource(R.drawable.ic_tab_bundle)
                setOnClickListener {
                    activity.pdfSuiteCoordinator.openExtractText()
                    activity.workspaceHost.getOrInflatePdfExtractText()
                    activity.pdfSuiteCoordinator.extractTextController.handlePdfSelected(uri)
                }
            }
            binding.btnSuggestEngine3.apply {
                text = if (isEncrypted) "Unlock PDF" else "Verify Signature"
                setIconResource(if (isEncrypted) R.drawable.ic_lock_open else R.drawable.ic_signature_valid)
                setOnClickListener {
                    if (isEncrypted) {
                        activity.openPdfUnlock()
                        activity.workspaceHost.getOrInflatePdfUnlock()
                        activity.pdfUnlockController.handlePdfSelected(uri)
                    } else {
                        activity.openPdfSignature()
                        activity.workspaceHost.getOrInflatePdfSignature()
                        if (bytes != null) {
                            activity.pdfSignatureController.loadPdfBytes(bytes, displayName, "")
                        } else {
                            activity.pdfSignatureController.handlePdfSelected(uri)
                        }
                    }
                }
            }
        }
    }

    fun showPdfQuickToolPopup(
        uri: Uri,
        displayName: String,
        password: String?,
        bytes: ByteArray?
    ) {
        val dialogBinding = DialogPdfQuickToolPickerBinding.inflate(activity.layoutInflater)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogBinding.root)
            .create()
        dialog.setCancelable(true)

        dialogBinding.tvPdfToolPickerSubtitle.text = "Select a tool to apply to $displayName"
        dialogBinding.btnPdfToolPickerClose.setOnClickListener { dialog.dismiss() }

        data class ToolOption(
            val title: String,
            val subtitle: String,
            val iconRes: Int,
            val onSelect: () -> Unit
        )

        val tools = listOf(
            ToolOption(
                title = "Organize & Split",
                subtitle = "Extract pages, split chapters, or reorder sheets",
                iconRes = R.drawable.ic_crop
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.SPLIT)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Merge Documents",
                subtitle = "Combine multiple PDF documents into one",
                iconRes = R.drawable.ic_tab_bundle
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.MERGE)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Compress PDF",
                subtitle = "Dual-engine smart size reduction",
                iconRes = R.drawable.ic_compress
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.COMPRESS)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Rotate Pages",
                subtitle = "Orientation fix (90°, 180°, 270° clockwise)",
                iconRes = R.drawable.ic_rotate
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.ROTATE)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Reorder Pages",
                subtitle = "Rearrange page sequence order with drag & drop",
                iconRes = R.drawable.ic_drag_handle
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.REORDER)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Delete Pages",
                subtitle = "Purge unwanted sheets from document",
                iconRes = R.drawable.ic_delete
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.DELETE)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Watermark",
                subtitle = "Add text or image watermarks with angle & opacity",
                iconRes = R.drawable.ic_color_filter
            ) {
                activity.pdfSuiteCoordinator.openWatermark()
                activity.workspaceHost.getOrInflatePdfWatermark()
                activity.pdfSuiteCoordinator.watermarkController.onPdfSelected(uri)
            },
            ToolOption(
                title = "Lock & Encrypt",
                subtitle = "Password protection & 256-bit AES permission encryption",
                iconRes = R.drawable.ic_lock
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.LOCK)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Unlock / Decrypt",
                subtitle = "Remove document protection passwords offline",
                iconRes = R.drawable.ic_lock_open
            ) {
                activity.openPdfUnlock()
                activity.workspaceHost.getOrInflatePdfUnlock()
                activity.pdfUnlockController.handlePdfSelected(uri)
            },
            ToolOption(
                title = "Sign PDF",
                subtitle = "Touch Bézier canvas signature stamping",
                iconRes = R.drawable.ic_sign_pdf
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.SIGN)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Fill Forms",
                subtitle = "Interactive AcroForm values editing",
                iconRes = R.drawable.ic_fill_form
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.FILL_FORMS)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Images to PDF",
                subtitle = "Compile camera photos & images into PDF",
                iconRes = R.drawable.ic_tab_image
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.IMAGE_TO_PDF)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "PDF to Images",
                subtitle = "Batch export pages to WebP, PNG, or JPG",
                iconRes = R.drawable.ic_tab_image
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.PDF_TO_IMAGE)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Sanitize Metadata",
                subtitle = "Audit & scrub hidden XMP, author & creator metadata",
                iconRes = R.drawable.ic_exif
            ) {
                activity.pdfSuiteCoordinator.openQuickTool(PdfQuickToolController.QuickMode.METADATA)
                activity.workspaceHost.getOrInflatePdfQuickTool()
                activity.pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))
            },
            ToolOption(
                title = "Extract Text",
                subtitle = "Extract searchable plain text from pages",
                iconRes = R.drawable.ic_text
            ) {
                activity.pdfSuiteCoordinator.openExtractText()
                activity.workspaceHost.getOrInflatePdfExtractText()
                activity.pdfSuiteCoordinator.extractTextController.handlePdfSelected(uri)
            },
            ToolOption(
                title = "Verify Signature",
                subtitle = "Verify cryptographic signatures and certificate chain",
                iconRes = R.drawable.ic_signature_valid
            ) {
                activity.openPdfSignature()
                activity.workspaceHost.getOrInflatePdfSignature()
                if (bytes != null) {
                    activity.pdfSignatureController.loadPdfBytes(bytes, displayName, password ?: "")
                } else {
                    activity.pdfSignatureController.handlePdfSelected(uri)
                }
            }
        )

        for (tool in tools) {
            val itemBinding = ItemPdfQuickToolOptionBinding.inflate(activity.layoutInflater, dialogBinding.containerPdfToolsList, false)
            itemBinding.ivToolOptionIcon.setImageResource(tool.iconRes)
            itemBinding.tvToolOptionTitle.text = tool.title
            itemBinding.tvToolOptionSubtitle.text = tool.subtitle
            itemBinding.cardToolOption.setOnClickListener {
                dialog.dismiss()
                clearHomeFileIntake()
                tool.onSelect()
            }
            dialogBinding.containerPdfToolsList.addView(itemBinding.root)
        }

        dialog.show()
    }

    fun showMarkdownPdfThemePopup(uri: Uri, displayName: String) {
        val themeOptions = arrayOf(
            "Dark Theme PDF",
            "Light Theme (White Background) PDF"
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle("Select PDF Appearance")
            .setItems(themeOptions) { dialog, which ->
                dialog.dismiss()
                val targetTheme = if (which == 0) MarkdownTheme.DARK else MarkdownTheme.LIGHT
                activity.openMarkdownViewer(uri, displayName)
                activity.markdownViewerController.exportToPdfWithTheme(targetTheme)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun exportMarkdownToDocx(uri: Uri, displayName: String) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = activity.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader(Charsets.UTF_8).readText()
                } ?: ""
                val baseName = displayName.substringBeforeLast(".")
                val docxFile = File(activity.cacheDir, "$baseName.docx")
                MarkdownDocxExporter.exportToDocx(content, docxFile, baseName)
                withContext(Dispatchers.Main) {
                    val shareUri = androidx.core.content.FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.provider",
                        docxFile
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        putExtra(Intent.EXTRA_SUBJECT, docxFile.name)
                        clipData = android.content.ClipData.newUri(activity.contentResolver, docxFile.name, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(intent, "Save or Share DOCX"))
                    Toast.makeText(activity, "DOCX created: ${docxFile.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Failed to export DOCX: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun clearHomeFileIntake() {
        activeIntakeJob?.cancel()
        currentHomeIntakeUri = null
        currentHomeIntakePdfBytes = null
        currentHomeIntakePdfPassword = null
        binding.layoutHomeIntakeMounted.visibility = View.GONE
        binding.layoutHomeIntakePickers.visibility = View.VISIBLE
    }
}
