package com.veilframe.app.pdf

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import java.security.cert.X509Certificate

/**
 * Detailed cryptographic and structural record of a single digital signature
 * within a verified PDF document.
 */
data class PdfSignatureInfo(
    val signature: PDSignature,
    val signerName: String,
    val signingDate: String,
    val location: String?,
    val reason: String?,
    val contactInfo: String?,
    val isCryptoValid: Boolean,
    val isByteRangePlausible: Boolean,
    val coversWholeDocument: Boolean,
    val signedRevisionEnd: Long,
    val isPathTrusted: Boolean,
    val isPinned: Boolean,
    val trustStatus: CertificatePathValidator.Status?,
    val certificate: X509Certificate?,
    val fingerprintSha256: String?,
    val revisionReport: PdfSignatureValidation.Report?
) {
    /** True if this signature's mathematics are sound and it roots in a trusted PKI anchor or exact pin */
    val isFullyVerified: Boolean
        get() = isCryptoValid && (isPathTrusted || isPinned) && isByteRangePlausible && (revisionReport?.hasFailedChecks() == false)
}

/**
 * Complete inspection report for an entire PDF document.
 */
data class PdfInspectionReport(
    val signatures: List<PdfSignatureInfo>,
    val pageCount: Int,
    val previewBitmap: Bitmap?,
    val isEncrypted: Boolean,
    val isTampered: Boolean,
    val isFullyVerified: Boolean,
    val untrustedSigners: List<PdfSignatureInfo>,
    val documentTitle: String?,
    val fileSizeFormatted: String
)

/**
 * Result of an offline PDF unlocking / decryption operation.
 */
data class PdfUnlockResult(
    val unlockedBytes: ByteArray,
    val pageCount: Int,
    val previewBitmap: Bitmap?,
    val originalSizeBytes: Long,
    val unlockedSizeBytes: Long,
    val documentTitle: String?
)

/**
 * Initial check result when loading a PDF file.
 */
sealed class PdfInitialCheck {
    data class Clear(val bytes: ByteArray, val pageCount: Int, val isEncrypted: Boolean) : PdfInitialCheck()
    data class PasswordRequired(val bytes: ByteArray) : PdfInitialCheck()
    data class Failed(val error: String) : PdfInitialCheck()
}
