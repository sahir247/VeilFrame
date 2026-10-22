package com.veilframe.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.util.Store
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Offline engine for full cryptographic validation of PDF digital signatures,
 * PKIX certificate path building against bundled and pinned trust anchors,
 * structural revision checks, and Adobe Acrobat DC DigSig visual checkmark rectification.
 */
class PdfSignatureEngine(private val context: Context) {

    private val trustStore = TrustedCertificateStore(context)

    /**
     * Inspects all digital signatures, certificate chains, byte ranges,
     * and structural integrity in the given PDF.
     */
    suspend fun inspectPdf(
        pdfBytes: ByteArray,
        password: String = ""
    ): Result<PdfInspectionReport> = withContext(Dispatchers.IO) {
        try {
            val doc = if (password.isNotEmpty()) {
                PDDocument.load(ByteArrayInputStream(pdfBytes), password)
            } else {
                PDDocument.load(ByteArrayInputStream(pdfBytes))
            }

            doc.use { loadedDoc ->
                val sigList = mutableListOf<PdfSignatureInfo>()
                val signatures: List<PDSignature>? = loadedDoc.signatureDictionaries
                var previewPageIndex = 0

                // Locate preview page containing primary signature widget
                val acroForm: PDAcroForm? = loadedDoc.documentCatalog.acroForm
                if (acroForm != null) {
                    for (field in acroForm.fields) {
                        if (field is PDSignatureField) {
                            for (widget in field.widgets) {
                                val wPage = widget.page
                                if (wPage != null) {
                                    val pIdx = loadedDoc.pages.indexOf(wPage)
                                    if (pIdx >= 0) {
                                        previewPageIndex = pIdx
                                        break
                                    }
                                }
                            }
                        }
                        if (previewPageIndex != 0) break
                    }
                }

                val bundledRoots = try { BundledTrustAnchors.loadTrustAnchors(context) } catch (e: Exception) { emptyList() }
                val bundledIntermediates = BundledTrustAnchors.loadBundledIntermediates(context)
                val userRoots = trustStore.trustedCertificateAuthorities

                val allAnchors = mutableListOf<X509Certificate>().apply {
                    addAll(bundledRoots)
                    addAll(userRoots)
                }

                if (signatures != null) {
                    for (sig in signatures) {
                        val range = sig.byteRange
                        val byteRangePlausible = checkByteRange(range, pdfBytes)
                        val signedRevisionEnd = if (byteRangePlausible && range != null && range.size == 4) {
                            range[2].toLong() + range[3].toLong()
                        } else {
                            0L
                        }
                        val coversWholeDocument = signedRevisionEnd == pdfBytes.size.toLong()

                        // Conservative revision & shadow attack verification
                        val revisionReport = PdfSignatureValidation.inspect(pdfBytes, sig, range, password)

                        var isCryptoValid = false
                        var signerCert: X509Certificate? = null
                        var fingerprint: String? = null
                        var pathResult: CertificatePathValidator.Result? = null

                        if (byteRangePlausible && range != null) {
                            try {
                                val certBytes = try { sig.getContents(pdfBytes) } catch (e: Exception) { null } ?: sig.contents
                                val signedContent = try { sig.getSignedContent(pdfBytes) } catch (e: Exception) { null }

                                if (certBytes != null && certBytes.isNotEmpty() && signedContent != null && signedContent.isNotEmpty()) {
                                    val asn1In = ASN1InputStream(certBytes)
                                    val asn1Obj = asn1In.readObject()
                                    val contentInfo = ContentInfo.getInstance(asn1Obj)
                                    val cms = CMSSignedData(CMSProcessableByteArray(signedContent), contentInfo)
                                    val certStore: Store<X509CertificateHolder> = cms.certificates
                                    val embeddedCerts = mutableListOf<X509Certificate>()
                                    val converter = JcaX509CertificateConverter().setProvider("BC")

                                    val allHolders = certStore.getMatches(null)
                                    for (holder in allHolders) {
                                        try {
                                            embeddedCerts.add(converter.getCertificate(holder))
                                        } catch (ignored: Exception) {}
                                    }

                                    val cmsSigners = cms.signerInfos.signers
                                    for (cmsSigner in cmsSigners) {
                                        for (holder in allHolders) {
                                            try {
                                                val cert = converter.getCertificate(holder)
                                                val verifier = JcaSimpleSignerInfoVerifierBuilder()
                                                    .setProvider("BC")
                                                    .build(cert)
                                                if (cmsSigner.verify(verifier)) {
                                                    isCryptoValid = true
                                                    signerCert = cert
                                                    fingerprint = TrustedCertificateStore.fingerprint(cert)
                                                    pathResult = CertificatePathValidator.validate(
                                                        cert,
                                                        embeddedCerts,
                                                        allAnchors,
                                                        bundledIntermediates
                                                    )
                                                    break
                                                }
                                            } catch (ignored: Exception) {}
                                        }
                                        if (isCryptoValid) break
                                    }
                                }
                            } catch (e: Exception) {
                                isCryptoValid = false
                            }
                        }

                        val signerName = extractCommonName(signerCert?.subjectX500Principal?.name)
                            ?: sig.name
                            ?: "Unknown Signer"

                        val signingDate = formatSigningDate(sig.signDate?.time)

                        val isPinned = signerCert != null && trustStore.isTrusted(signerCert)
                        val isPathTrusted = pathResult != null && (
                            pathResult.status == CertificatePathValidator.Status.TRUSTED_SYSTEM_PATH ||
                            pathResult.status == CertificatePathValidator.Status.TRUSTED_APP_PATH
                        )

                        sigList.add(
                            PdfSignatureInfo(
                                signature = sig,
                                signerName = signerName,
                                signingDate = signingDate,
                                location = sig.location,
                                reason = sig.reason,
                                contactInfo = sig.contactInfo,
                                isCryptoValid = isCryptoValid,
                                isByteRangePlausible = byteRangePlausible,
                                coversWholeDocument = coversWholeDocument,
                                signedRevisionEnd = signedRevisionEnd,
                                isPathTrusted = isPathTrusted,
                                isPinned = isPinned,
                                trustStatus = pathResult?.status,
                                certificate = signerCert,
                                fingerprintSha256 = fingerprint,
                                revisionReport = revisionReport
                            )
                        )
                    }
                }

                // Render page preview at 150 DPI
                var previewBmp: Bitmap? = null
                if (loadedDoc.numberOfPages > 0) {
                    try {
                        val renderer = PDFRenderer(loadedDoc)
                        previewBmp = renderer.renderImageWithDPI(previewPageIndex, 150f)
                    } catch (e: Exception) {
                        // ignore rendering errors
                    }
                }

                val isTampered = sigList.any { !it.isCryptoValid || !it.isByteRangePlausible || (it.revisionReport?.hasFailedChecks() == true) }
                val isFullyVerified = sigList.isNotEmpty() && sigList.all { it.isFullyVerified }
                val untrustedSigners = sigList.filter { it.isCryptoValid && !it.isPathTrusted && !it.isPinned }

                val title = loadedDoc.documentInformation?.title
                val fileSizeFormatted = formatFileSize(pdfBytes.size.toLong())

                Result.success(
                    PdfInspectionReport(
                        signatures = sigList,
                        pageCount = loadedDoc.numberOfPages,
                        previewBitmap = previewBmp,
                        isEncrypted = loadedDoc.isEncrypted,
                        isTampered = isTampered,
                        isFullyVerified = isFullyVerified,
                        untrustedSigners = untrustedSigners,
                        documentTitle = title,
                        fileSizeFormatted = fileSizeFormatted
                    )
                )
            }
        } catch (e: InvalidPasswordException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rectifies the PDF signature appearances with the authentic Adobe Acrobat DC
     * 15w-stroke green checkmark and typographic layout, and flattens/saves to bytes.
     */
    suspend fun rectifyAndSave(
        pdfBytes: ByteArray,
        password: String = ""
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val result = PdfStamperEngine.processPdf(pdfBytes, password)
            Result.success(result.rectifiedPdf)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun trustSignerCertificate(cert: X509Certificate, note: String = "User trusted via VeilFrame") {
        try {
            trustStore.trust(cert, true, note)
        } catch (ignored: Exception) {}
    }

    private fun checkByteRange(range: IntArray?, pdfBytes: ByteArray): Boolean {
        if (range == null || range.size != 4) return false
        val r0 = range[0].toLong()
        val r1 = range[1].toLong()
        val r2 = range[2].toLong()
        val r3 = range[3].toLong()
        if (r0 != 0L || r1 <= 0L || r2 <= 0L || r3 <= 0L) return false
        if (r0 + r1 > r2) return false
        val totalLength = r2 + r3
        return totalLength <= pdfBytes.size.toLong()
    }

    private fun extractCommonName(dn: String?): String? {
        if (dn == null) return null
        val parts = dn.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("CN=", ignoreCase = true)) {
                return trimmed.substring(3).trim()
            }
        }
        return null
    }

    private fun formatSigningDate(date: Date?): String {
        if (date == null) return "Unknown date"
        val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm:ss z", Locale.US)
        return sdf.format(date)
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val index = Math.min(units.size - 1, digitGroups)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, index.toDouble()), units[index])
    }
}
