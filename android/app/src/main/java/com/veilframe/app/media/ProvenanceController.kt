package com.veilframe.app.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.veilframe.app.R
import com.veilframe.app.databinding.LayoutProvenanceBinding
import com.veilframe.app.storage.SafStorageManager
import com.veilframe.app.ui.motion.VeilFrameInteraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Controller orchestrating the Provenance & Verify workspace:
 * - Streaming SHA-256 hash computation over user artifacts
 * - Ed25519 signature validation and manifest forensics
 * - Clipboard copy actions and integrity status display
 */
class ProvenanceController(
    private val activity: AppCompatActivity,
    private val binding: LayoutProvenanceBinding,
    private val safStorageManager: SafStorageManager,
    private val scope: CoroutineScope,
    private val onPickFileRequest: () -> Unit,
    private val onNavigateBack: () -> Unit
) {
    private var currentSha256: String = ""
    var currentSourceUri: Uri? = null

    fun init() {
        binding.toolbarProvenance.setNavigationOnClickListener {
            onNavigateBack()
        }

        binding.btnProvenanceSelectFile.setOnClickListener {
            onPickFileRequest()
        }

        binding.btnProvenanceCopyHash.isEnabled = false
        binding.btnProvenanceCopyHash.setOnClickListener {
            if (currentSha256.isNotEmpty()) {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("SHA-256 Hash", currentSha256)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(activity, "SHA-256 copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        reset()
        VeilFrameInteraction.bindWorkspace(binding.root)
    }

    fun reset() {
        currentSha256 = ""
        currentSourceUri = null
        binding.btnProvenanceCopyHash.isEnabled = false
        binding.tvProvenanceFileName.text = "No file selected"
        binding.tvProvenanceFileSize.text = "Select any file or archive to compute hash and verify signature"
        binding.tvProvenanceSha256.text = "No artifact selected"
        binding.tvProvenanceEd25519Status.text = "No manifest audited"
        binding.tvProvenanceEd25519Details.text = "Awaiting artifact inspection"
        binding.ivProvenanceStatusIcon.setImageResource(R.drawable.ic_info_outline)
        binding.ivProvenanceStatusIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(activity, R.color.vf_text_secondary)
        )
        binding.tvProvenanceStatusTitle.text = "Awaiting Artifact"
        binding.tvProvenanceStatusDesc.text = "Select a file to perform cryptographic audit and signature verification"
    }

    fun handleFileSelected(uri: Uri) {
        currentSourceUri = uri
        val name = safStorageManager.getDisplayName(uri)
        val sizeBytes = safStorageManager.queryFileSize(uri)

        binding.tvProvenanceFileName.text = name
        binding.tvProvenanceFileSize.text = "Size: ${safStorageManager.formatBytes(sizeBytes)} • Computing hash..."
        binding.tvProvenanceSha256.text = "Computing SHA-256..."
        binding.btnProvenanceCopyHash.isEnabled = false
        binding.tvProvenanceEd25519Status.text = "Auditing manifest structure..."
        binding.tvProvenanceEd25519Details.text = "Scanning artifact headers for cryptographic envelope..."

        scope.launch(Dispatchers.IO) {
            val hash = try {
                val digest = MessageDigest.getInstance("SHA-256")
                activity.contentResolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } > 0) {
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                "Error computing checksum: ${e.message}"
            }

            val isSuccess = !hash.startsWith("Error")
            val isVeilFrameArchive = name.endsWith(".vfp", ignoreCase = true) ||
                name.endsWith(".zip", ignoreCase = true) ||
                name.endsWith(".aibundle", ignoreCase = true)

            var manifestFound = false
            var manifestHasSignature = false
            var manifestSignatureValid: Boolean? = null

            if (isVeilFrameArchive && isSuccess) {
                try {
                    activity.contentResolver.openInputStream(uri)?.use { stream ->
                        java.util.zip.ZipInputStream(stream).use { zis ->
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (entry.name.equals("manifest.json", ignoreCase = true) ||
                                    entry.name.endsWith("/manifest.json", ignoreCase = true)
                                ) {
                                    manifestFound = true
                                    val text = zis.bufferedReader().readText()
                                    val json = org.json.JSONObject(text)
                                    val sig = json.optString("signature", "")
                                    if (sig.isNotBlank()) {
                                        manifestHasSignature = true
                                        manifestSignatureValid = com.veilframe.app.updates.AppUpdateManager.verifyManifestSignature(json)
                                    }
                                    break
                                }
                                entry = zis.nextEntry
                            }
                        }
                    }
                } catch (e: Exception) {
                    manifestFound = false
                }
            }

            withContext(Dispatchers.Main) {
                if (isSuccess) {
                    currentSha256 = hash
                    binding.tvProvenanceSha256.text = hash
                    binding.btnProvenanceCopyHash.isEnabled = true
                    binding.tvProvenanceFileSize.text = "Size: ${safStorageManager.formatBytes(sizeBytes)}"
                    binding.ivProvenanceStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    binding.ivProvenanceStatusIcon.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(activity, R.color.vf_accent_green)
                    )
                    binding.tvProvenanceStatusTitle.text = "Bitstream SHA-256 Computed"
                    binding.tvProvenanceStatusDesc.text = "SHA-256 digest computed across bitstream. Copy hash to compare against trusted publisher checksum."

                    if (isVeilFrameArchive) {
                        if (manifestFound && manifestHasSignature) {
                            if (manifestSignatureValid == true) {
                                binding.tvProvenanceEd25519Status.text = "Ed25519 Signature Verified"
                                binding.tvProvenanceEd25519Details.text = "Manifest: manifest.json detected\nSignature: Valid Ed25519 digital signature verified against embedded public key."
                            } else {
                                binding.tvProvenanceEd25519Status.text = "Ed25519 Signature INVALID"
                                binding.tvProvenanceEd25519Details.text = "Manifest: manifest.json detected\nSignature: FAILED verification! Manifest may have been tampered with or signed by an unknown key."
                                binding.ivProvenanceStatusIcon.setImageResource(R.drawable.ic_info_outline)
                                binding.ivProvenanceStatusIcon.imageTintList = ColorStateList.valueOf(
                                    ContextCompat.getColor(activity, R.color.vf_accent_red)
                                )
                                binding.tvProvenanceStatusTitle.text = "Signature Verification Failed"
                            }
                        } else if (manifestFound) {
                            binding.tvProvenanceEd25519Status.text = "Unsigned Manifest Present"
                            binding.tvProvenanceEd25519Details.text = "Archive contains manifest.json, but no digital signature field is present.\nStatus: Unsigned Package"
                        } else {
                            binding.tvProvenanceEd25519Status.text = "Standard Archive (No Manifest)"
                            binding.tvProvenanceEd25519Details.text = "Inspected archive entries: No manifest.json or cryptographic signature envelope found in archive.\nStatus: Unsigned Archive"
                        }
                    } else {
                        binding.tvProvenanceEd25519Status.text = "Standalone Artifact (Unsigned)"
                        binding.tvProvenanceEd25519Details.text = "Raw media file: Standalone media does not carry an embedded provenance envelope.\nUse SHA-256 to verify file integrity against publisher records."
                    }
                } else {
                    binding.tvProvenanceSha256.text = hash
                    binding.btnProvenanceCopyHash.isEnabled = false
                    binding.ivProvenanceStatusIcon.setImageResource(R.drawable.ic_info_outline)
                    binding.ivProvenanceStatusIcon.imageTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(activity, R.color.vf_accent_red)
                    )
                    binding.tvProvenanceStatusTitle.text = "Audit Failed"
                    binding.tvProvenanceStatusDesc.text = hash
                    binding.tvProvenanceEd25519Status.text = "Audit Incomplete"
                    binding.tvProvenanceEd25519Details.text = "Cannot verify unreadable artifact"
                }
            }
        }
    }
}
