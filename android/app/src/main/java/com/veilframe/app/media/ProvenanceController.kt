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
import com.veilframe.app.navigation.ToolWorkStateContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
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
) : ToolWorkStateContract {
    private var currentSha256: String = ""
    var currentSourceUri: Uri? = null
        private set
    private var auditJob: Job? = null
    private var generation = 0L

    override fun hasPickedMedia(): Boolean = currentSourceUri != null
    override fun hasUnsavedWork(): Boolean = false
    override val isProcessing: Boolean get() = auditJob?.isActive == true
    override fun discardAndReleaseAllResources() = reset()

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
        generation++
        auditJob?.cancel()
        auditJob = null
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
        reset()
        val gen = generation
        currentSourceUri = uri
        val name = safStorageManager.getDisplayName(uri)
        val sizeBytes = safStorageManager.queryFileSize(uri)

        binding.tvProvenanceFileName.text = name
        binding.tvProvenanceFileSize.text = "Size: ${safStorageManager.formatBytes(sizeBytes)} • Computing hash..."
        binding.tvProvenanceSha256.text = "Computing SHA-256..."
        binding.btnProvenanceCopyHash.isEnabled = false
        binding.tvProvenanceEd25519Status.text = "Auditing manifest structure..."
        binding.tvProvenanceEd25519Details.text = "Scanning artifact headers for cryptographic envelope..."

        auditJob = scope.launch(Dispatchers.IO) {
            val hash = try {
                val stream = activity.contentResolver.openInputStream(uri)
                    ?: throw java.io.FileNotFoundException("Cannot open stream for URI: $uri")
                val digest = MessageDigest.getInstance("SHA-256")
                var totalBytesRead = 0L
                stream.use { s ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (s.read(buffer).also { read = it } > 0) {
                        ensureActive()
                        digest.update(buffer, 0, read)
                        totalBytesRead += read
                    }
                }
                if (totalBytesRead == 0L) {
                    throw java.io.IOException("Artifact is empty (0 bytes read)")
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
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

            var archiveInspectionError: String? = null
            if (isVeilFrameArchive && isSuccess) {
                try {
                    val input = activity.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Cannot reopen archive")
                    java.util.zip.ZipInputStream(input).use { zis ->
                        val buffer = ByteArray(8192)
                        var totalInflated = 0L
                        var entries = 0
                        while (true) {
                            ensureActive()
                            val entry = zis.nextEntry ?: break
                            if (++entries > 4096) throw java.io.IOException("Archive entry limit reached")
                            val isManifest = !entry.isDirectory && (entry.name.equals("manifest.json", ignoreCase = true) ||
                                entry.name.endsWith("/manifest.json", ignoreCase = true))
                            val manifest = if (isManifest) java.io.ByteArrayOutputStream() else null
                            while (true) {
                                ensureActive()
                                val read = zis.read(buffer)
                                if (read < 0) break
                                totalInflated += read
                                if (totalInflated > 32L * 1024 * 1024) throw java.io.IOException("Archive inspection byte limit reached")
                                if (manifest != null) {
                                    if (manifest.size() + read > 1024 * 1024) throw java.io.IOException("Manifest exceeds 1 MiB")
                                    manifest.write(buffer, 0, read)
                                }
                            }
                            if (manifest != null) {
                                manifestFound = true
                                val json = org.json.JSONObject(manifest.toString("UTF-8"))
                                manifestHasSignature = json.optString("signature", "").isNotBlank()
                                if (manifestHasSignature) {
                                    manifestSignatureValid = com.veilframe.app.updates.AppUpdateManager.verifyManifestSignature(json)
                                }
                                break
                            }
                        }
                        if (entries == 0) throw java.io.IOException("No readable ZIP entries")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    archiveInspectionError = e.message ?: "Archive inspection failed"
                }
            }

            withContext(Dispatchers.Main) {
                if (generation != gen || currentSourceUri != uri) return@withContext
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
                        if (archiveInspectionError != null) {
                            binding.tvProvenanceEd25519Status.text = "Archive Inspection Incomplete"
                            binding.tvProvenanceEd25519Details.text = archiveInspectionError
                        } else if (manifestFound && manifestHasSignature) {
                            if (manifestSignatureValid == true) {
                                binding.tvProvenanceEd25519Status.text = "Manifest Signature Valid (App-Pinned Key)"
                                binding.tvProvenanceEd25519Details.text = "Manifest: manifest.json detected\nSignature: valid against VeilFrame's app-pinned update key.\nArchive payload, signer identity beyond that key and media authenticity are not verified."
                            } else {
                                binding.tvProvenanceEd25519Status.text = "Manifest Signature Not Verified"
                                binding.tvProvenanceEd25519Details.text = "Manifest: manifest.json detected\nSignature was not verified against the app-pinned key. The key or algorithm may be unsupported, or the signature invalid."
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
                        binding.tvProvenanceEd25519Status.text = "Standalone Artifact (Not Inspected for Signatures)"
                        binding.tvProvenanceEd25519Details.text = "Only the file hash was computed. Embedded signatures or provenance formats were not inspected.\nCompare SHA-256 against a trusted publisher checksum."
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
