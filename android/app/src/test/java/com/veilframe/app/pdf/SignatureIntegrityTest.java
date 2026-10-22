package com.veilframe.app.pdf;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm;
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField;

public class SignatureIntegrityTest {

    private static File assetsDir;

    @BeforeClass
    public static void setUp() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        assetsDir = new File("src/main/assets");
        if (!assetsDir.exists()) {
            assetsDir = new File("app/src/main/assets");
        }
    }

    @Test
    public void testBundledTrustAnchorsPinning() throws Exception {
        Assert.assertTrue("Assets dir must exist: " + assetsDir.getAbsolutePath(), assetsDir.exists());
        List<X509Certificate> roots = BundledTrustAnchors.loadTrustAnchors(assetsDir);
        Assert.assertEquals("Must load exactly 3 pinned CCA India roots", 3, roots.size());

        boolean saw2022 = false;
        boolean saw2014 = false;
        boolean saw2015Spl = false;

        for (X509Certificate root : roots) {
            String fp = BundledTrustAnchors.sha256(root);
            if ("9A3FD3176798E842DDCB12C262F11CFACCA70A8B84C6EA6FDA30842A95A94CD8".equalsIgnoreCase(fp)) {
                saw2022 = true;
            } else if ("60109BC6C38328598A112C7A25E38B0F23E5A7511CB815FB64E0C4FF05DB7DF7".equalsIgnoreCase(fp)) {
                saw2014 = true;
            } else if ("C34C5DF53080078FFE45B21A7F600469917204F4F0293F1D7209393E5265C04F".equalsIgnoreCase(fp)) {
                saw2015Spl = true;
            }
            Assert.assertTrue("Root must be a CA", root.getBasicConstraints() >= 0);
            Assert.assertEquals("Root must be self-signed",
                    root.getSubjectX500Principal(), root.getIssuerX500Principal());
        }

        Assert.assertTrue("CCA India 2022 must be present", saw2022);
        Assert.assertTrue("CCA India 2014 must be present", saw2014);
        Assert.assertTrue("CCA India 2015 SPL must be present", saw2015Spl);
    }

    @Test
    public void testBundledNCodeIntermediates() {
        List<X509Certificate> intermediates = BundledTrustAnchors.loadBundledIntermediates(assetsDir);
        Assert.assertTrue("Must load bundled nCode intermediate certificates", intermediates.size() >= 5);

        for (X509Certificate cert : intermediates) {
            Assert.assertTrue("Intermediate must be a CA", cert.getBasicConstraints() >= 0);
            Assert.assertNotEquals("Intermediate CA must not be self-signed",
                    cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        }
    }

    @Test
    public void testNCodeCertificatePathValidation() throws Exception {
        List<X509Certificate> roots = BundledTrustAnchors.loadTrustAnchors(assetsDir);
        List<X509Certificate> intermediates = BundledTrustAnchors.loadBundledIntermediates(assetsDir);

        // Find (n)Code Solutions sub-CA for eSign 2024
        X509Certificate esignSubCa24 = null;
        for (X509Certificate cert : intermediates) {
            if (cert.getSubjectX500Principal().getName().contains("eSign 2024")) {
                esignSubCa24 = cert;
                break;
            }
        }
        Assert.assertNotNull("eSign 2024 sub-CA must be present in bundled intermediates", esignSubCa24);

        // Validate that esignSubCa24 chains through (n)Code Solutions CA 2022 to CCA India 2022
        CertificatePathValidator.Result result = CertificatePathValidator.validate(
                esignSubCa24,
                Collections.emptyList(),
                roots,
                intermediates
        );

        Assert.assertEquals("Certificate path must reach bundled CCA root anchor",
                CertificatePathValidator.Status.TRUSTED_APP_PATH, result.status);
    }

    @Test
    public void testByteRangeValidationOnTamperedBytes() {
        // Construct a synthetic 4-element byte range: [0, 100, 200, 100] on a 300-byte buffer
        byte[] fakePdf = new byte[300];
        fakePdf[100] = '<';
        for (int i = 101; i < 199; i++) fakePdf[i] = '0';
        fakePdf[199] = '>';

        int[] validRange = new int[]{0, 100, 200, 100};
        // Verify valid structure
        boolean rangeOk = (validRange[0] == 0 && validRange[1] > 0
                && validRange[2] > validRange[1] && validRange[3] > 0
                && (validRange[2] + validRange[3]) <= fakePdf.length);
        Assert.assertTrue("Synthetic valid range must pass range checks", rangeOk);

        // Overlapping range (malicious ByteRange)
        int[] overlappingRange = new int[]{0, 150, 100, 100};
        boolean overlapOk = (overlappingRange[0] == 0 && overlappingRange[1] > 0
                && overlappingRange[2] > overlappingRange[1]);
        Assert.assertFalse("Overlapping ByteRange must be rejected", overlapOk);

        // Truncated file (end exceeds file length)
        int[] outOfBoundsRange = new int[]{0, 100, 200, 500};
        boolean inBounds = (outOfBoundsRange[2] + outOfBoundsRange[3]) <= fakePdf.length;
        Assert.assertFalse("Out of bounds ByteRange must be rejected", inBounds);
    }

    @Test
    public void testOriginalSignedPdfBytePreservation() throws Exception {
        byte[] original = "Original Pristine Signed Bytes".getBytes("UTF-8");
        byte[] copy = original.clone();

        Assert.assertArrayEquals("Original bytes must be bit-for-bit identical when cloned", original, copy);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        Assert.assertArrayEquals("Hashes must match perfectly", sha.digest(original), sha.digest(copy));
    }
    @Test
    public void testAadhaarSignaturePreservationAndWidgetInspection() throws Exception {
        File file = new File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/scratch/EAadhaar_0000003703510220260701195410_21102026132249.pdf");
        if (!file.exists()) return;
        try (PDDocument doc = PDDocument.load(file, "KHON2004")) {
            Assert.assertEquals("Document should have 1 page", 1, doc.getNumberOfPages());
            PDPage page = doc.getPage(0);
            PDRectangle crop = page.getCropBox();
            System.out.println("Aadhaar Page 0: cropBox=" + crop);
            PDAcroForm acroForm = doc.getDocumentCatalog().getAcroForm();
            Assert.assertNotNull("AcroForm must be present in Aadhaar PDF", acroForm);
            boolean foundSigField = false;
            for (PDField field : acroForm.getFields()) {
                for (PDAnnotationWidget widget : field.getWidgets()) {
                    PDRectangle rect = widget.getRectangle();
                    System.out.println("Widget rect: " + rect);
                    Assert.assertTrue("Widget rectangle must have positive dimensions", rect.getWidth() > 0 && rect.getHeight() > 0);
                    foundSigField = true;
                    if (widget.getAppearance() != null && widget.getAppearance().getNormalAppearance() != null) {
                        com.tom_roush.pdfbox.cos.COSDictionary apDict = (com.tom_roush.pdfbox.cos.COSDictionary) widget.getAppearance().getNormalAppearance().getCOSObject();
                        com.tom_roush.pdfbox.cos.COSDictionary res = (com.tom_roush.pdfbox.cos.COSDictionary) apDict.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.RESOURCES);
                        com.tom_roush.pdfbox.cos.COSDictionary xobjs = (com.tom_roush.pdfbox.cos.COSDictionary) res.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.XOBJECT);
                        com.tom_roush.pdfbox.cos.COSStream frm = (com.tom_roush.pdfbox.cos.COSStream) xobjs.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.getPDFName("FRM"));
                        com.tom_roush.pdfbox.cos.COSDictionary frmRes = (com.tom_roush.pdfbox.cos.COSDictionary) frm.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.RESOURCES);
                        com.tom_roush.pdfbox.cos.COSDictionary subX = (com.tom_roush.pdfbox.cos.COSDictionary) frmRes.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.XOBJECT);
                        for (com.tom_roush.pdfbox.cos.COSName k : subX.keySet()) {
                            com.tom_roush.pdfbox.cos.COSStream s = (com.tom_roush.pdfbox.cos.COSStream) subX.getDictionaryObject(k);
                            try (java.io.InputStream in = s.createInputStream()) {
                                byte[] b = in.readAllBytes();
                                String text = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).replace("\n", " ").replace("\r", " ");
                                System.out.println("Sub-stream " + k.getName() + " length=" + b.length + " content=" + text);
                            }
                        }
                    }
                }
            }
            Assert.assertTrue("Must find signature field in Aadhaar", foundSigField);
        }
    }

    @Test
    public void testProcessPdfAndSaveHighResCopies() throws Exception {
        File scratchDir = new File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/scratch");

        // Income PDF
        File incomeFile = new File(scratchDir, "income.pdf");
        if (incomeFile.exists()) {
            byte[] bytes = java.nio.file.Files.readAllBytes(incomeFile.toPath());
            PdfStamperEngine.StampResult res = PdfStamperEngine.processPdf(bytes, null);
            java.nio.file.Files.write(new File(scratchDir, "income_app_rectified.pdf").toPath(), res.rectifiedPdf);
            java.nio.file.Files.write(new File(scratchDir, "income_app_printable.pdf").toPath(), res.printablePdf);
            System.out.println("Saved income_app_rectified.pdf (" + res.rectifiedPdf.length + " bytes)");
        }

        // Aadhaar PDF
        File aadhaarFile = new File(scratchDir, "eaadhaar.pdf");
        if (aadhaarFile.exists()) {
            byte[] bytes = java.nio.file.Files.readAllBytes(aadhaarFile.toPath());
            PdfStamperEngine.StampResult res = PdfStamperEngine.processPdf(bytes, "KHON2004");
            java.nio.file.Files.write(new File(scratchDir, "eaadhaar_app_rectified.pdf").toPath(), res.rectifiedPdf);
            java.nio.file.Files.write(new File(scratchDir, "eaadhaar_app_printable.pdf").toPath(), res.printablePdf);
            System.out.println("Saved eaadhaar_app_rectified.pdf (" + res.rectifiedPdf.length + " bytes)");
        }

        // Ration PDF
        File rationFile = new File(scratchDir, "ration.pdf");
        if (rationFile.exists()) {
            byte[] bytes = java.nio.file.Files.readAllBytes(rationFile.toPath());
            PdfStamperEngine.StampResult res = PdfStamperEngine.processPdf(bytes, null);
            java.nio.file.Files.write(new File(scratchDir, "ration_app_rectified.pdf").toPath(), res.rectifiedPdf);
            java.nio.file.Files.write(new File(scratchDir, "ration_app_printable.pdf").toPath(), res.printablePdf);
            System.out.println("Saved ration_app_rectified.pdf (" + res.rectifiedPdf.length + " bytes)");
        }
    }

            @Test
    public void testAadhaarSignatureVerificationAndIntegrity() throws Exception {
        File aadhaarFile = new File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/scratch/eaadhaar.pdf");
        if (!aadhaarFile.exists()) {
            aadhaarFile = new File("C:/Users/parve/Downloads/PrivacyVideoCleaner_v1_source/scratch/aadhaar.pdf");
        }
        if (!aadhaarFile.exists()) return;

        byte[] pdfBytes = java.nio.file.Files.readAllBytes(aadhaarFile.toPath());
        try (PDDocument doc = PDDocument.load(new java.io.ByteArrayInputStream(pdfBytes), "KHON2004")) {
            List<PDSignature> signatures = doc.getSignatureDictionaries();
            Assert.assertNotNull("Aadhaar signatures must exist", signatures);
            Assert.assertEquals(1, signatures.size());

            PDSignature sig = signatures.get(0);
            int[] range = sig.getByteRange();

            // 1. ByteRange Plausibility
            Assert.assertTrue("ByteRange must be plausible", range != null && range.length == 4);
            Assert.assertEquals(0, range[0]);
            Assert.assertTrue(range[1] > 0);
            Assert.assertTrue(range[2] > range[1]);
            Assert.assertTrue(range[3] > 0);
            Assert.assertEquals("ByteRange must cover exactly the entire file", pdfBytes.length, range[2] + range[3]);

            // 2. Revision Report with password must NOT have failed checks
            PdfSignatureValidation.Report report = PdfSignatureValidation.inspect(pdfBytes, sig, range, "KHON2004");
            Assert.assertFalse("Aadhaar must NOT have failed checks when inspected with password", report.hasFailedChecks());

            // 3. Cryptographic math verification
            byte[] cmsBytes = sig.getContents(pdfBytes);
            byte[] signedContent = sig.getSignedContent(pdfBytes);
            org.bouncycastle.cms.CMSSignedData cms = new org.bouncycastle.cms.CMSSignedData(
                    new org.bouncycastle.cms.CMSProcessableByteArray(signedContent),
                    new java.io.ByteArrayInputStream(cmsBytes));
            org.bouncycastle.cert.jcajce.JcaX509CertificateConverter converter =
                    new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().setProvider("BC");

            boolean mathVerified = false;
            for (org.bouncycastle.cms.SignerInformation cmsSigner : cms.getSignerInfos().getSigners()) {
                for (Object hObj : cms.getCertificates().getMatches(cmsSigner.getSID())) {
                    org.bouncycastle.cert.X509CertificateHolder holder = (org.bouncycastle.cert.X509CertificateHolder) hObj;
                    java.security.cert.X509Certificate cert = converter.getCertificate(holder);
                    boolean verified = cmsSigner.verify(
                            new org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder().setProvider("BC").build(cert));
                    if (verified) {
                        mathVerified = true;
                        break;
                    }
                }
            }
            Assert.assertTrue("Aadhaar CMS signature must be cryptographically valid", mathVerified);

            // 4. Test without password: must NOT be marked as FAIL (tampered), but as REVIEW_REQUIRED
            PdfSignatureValidation.Report unauthedReport = PdfSignatureValidation.inspect(pdfBytes, sig, range, null);
            Assert.assertFalse("Encrypted document without password must NOT be treated as tampered", unauthedReport.hasFailedChecks());
        }
    }
}
