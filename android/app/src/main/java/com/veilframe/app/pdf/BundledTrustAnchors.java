package com.veilframe.app.pdf;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only trust anchors and intermediate CA certificates shipped with the application.
 *
 * Includes the official root trust anchors for the Controller of Certifying Authorities (CCA)
 * of India, pinned by exact SHA-256 fingerprints to ensure integrity:
 * - CCA India 2022 Root CA (current document-signing root)
 * - CCA India 2014 Root CA (legacy document-signing root)
 * - CCA India 2015 SPL Root CA (special purpose document-signing root)
 *
 * Also includes bundled intermediate and Sub-CA certificates issued by nCode Solutions
 * (GNFC) and CCA India repository (https://www.ncodesolutions.com/repository.php)
 * to facilitate deterministic PKIX path building for Indian digital signatures (eSign, DSC).
 */
public final class BundledTrustAnchors {

    public static final Map<String, String> PINNED_ROOTS = new HashMap<>();

    static {
        PINNED_ROOTS.put("trust/cca-india-2022.pem",
                "9A3FD3176798E842DDCB12C262F11CFACCA70A8B84C6EA6FDA30842A95A94CD8");
        PINNED_ROOTS.put("trust/cca-india-2014.pem",
                "60109BC6C38328598A112C7A25E38B0F23E5A7511CB815FB64E0C4FF05DB7DF7");
        PINNED_ROOTS.put("trust/cca-india-2015-spl.pem",
                "C34C5DF53080078FFE45B21A7F600469917204F4F0293F1D7209393E5265C04F");
    }

    public static final String[] INTERMEDIATE_ASSETS = {
            "trust/intermediates/ncode-ca-2022.pem",
            "trust/intermediates/ncode-dsc-subca-2022.pem",
            "trust/intermediates/ncode-esign-subca-2024.pem",
            "trust/intermediates/ncode-ca-2014-1.pem",
            "trust/intermediates/ncode-esign-subca-2019.pem",
            "trust/intermediates/ncode-esign-subca-2019-1.pem",
            "trust/intermediates/ncode-ca-spl-2015.pem"
    };

    private BundledTrustAnchors() { }

    /**
     * Backwards-compatible load method returning pinned root trust anchors.
     */
    public static List<X509Certificate> load(Context context) throws Exception {
        return loadTrustAnchors(context);
    }

    /**
     * Loads and validates all pinned root CA trust anchors from application assets.
     */
    public static List<X509Certificate> loadTrustAnchors(Context context) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> anchors = new ArrayList<>();

        for (Map.Entry<String, String> entry : PINNED_ROOTS.entrySet()) {
            String assetPath = entry.getKey();
            String expectedFingerprint = entry.getValue();

            try (InputStream input = context.getAssets().open(assetPath)) {
                anchors.add(verifyAndLoadRoot(cf, input, assetPath, expectedFingerprint));
            }
        }

        return Collections.unmodifiableList(anchors);
    }

    /**
     * JVM/Test-compatible loader for root CA trust anchors.
     */
    public static List<X509Certificate> loadTrustAnchors(File assetsBaseDir) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> anchors = new ArrayList<>();

        for (Map.Entry<String, String> entry : PINNED_ROOTS.entrySet()) {
            String relativePath = entry.getKey().replace('/', File.separatorChar);
            File certFile = new File(assetsBaseDir, relativePath);
            String expectedFingerprint = entry.getValue();

            try (InputStream input = new FileInputStream(certFile)) {
                anchors.add(verifyAndLoadRoot(cf, input, certFile.getName(), expectedFingerprint));
            }
        }

        return Collections.unmodifiableList(anchors);
    }

    private static X509Certificate verifyAndLoadRoot(CertificateFactory cf, InputStream input,
                                                     String sourceName, String expectedFingerprint) throws Exception {
        X509Certificate cert = (X509Certificate) cf.generateCertificate(input);

        if (cert.getBasicConstraints() < 0) {
            throw new SecurityException("Bundled root is not a CA: " + sourceName);
        }
        boolean[] keyUsage = cert.getKeyUsage();
        if (keyUsage != null && (keyUsage.length <= 5 || !keyUsage[5])) {
            throw new SecurityException("Bundled root cannot sign certificates: " + sourceName);
        }
        String actualFingerprint = sha256(cert);
        if (!expectedFingerprint.equalsIgnoreCase(actualFingerprint)) {
            throw new SecurityException("Bundled root fingerprint mismatch for " + sourceName
                    + " expected: " + expectedFingerprint + " but got: " + actualFingerprint);
        }
        cert.verify(cert.getPublicKey());
        return cert;
    }

    /**
     * Loads intermediate and Sub-CA certificates (e.g. nCode Solutions CA 2022, eSign Sub-CA 2024,
     * DSC Sub-CA 2022) to enable complete PKIX path construction.
     */
    public static List<X509Certificate> loadBundledIntermediates(Context context) {
        List<X509Certificate> intermediates = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (String assetPath : INTERMEDIATE_ASSETS) {
                try (InputStream input = context.getAssets().open(assetPath)) {
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(input);
                    if (cert != null && cert.getBasicConstraints() >= 0) {
                        intermediates.add(cert);
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return Collections.unmodifiableList(intermediates);
    }

    /**
     * JVM/Test-compatible loader for intermediate certificates.
     */
    public static List<X509Certificate> loadBundledIntermediates(File assetsBaseDir) {
        List<X509Certificate> intermediates = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (String assetPath : INTERMEDIATE_ASSETS) {
                String relativePath = assetPath.replace('/', File.separatorChar);
                File certFile = new File(assetsBaseDir, relativePath);
                if (!certFile.exists()) continue;
                try (InputStream input = new FileInputStream(certFile)) {
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(input);
                    if (cert != null && cert.getBasicConstraints() >= 0) {
                        intermediates.add(cert);
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return Collections.unmodifiableList(intermediates);
    }

    public static String sha256(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        }
        return hex.toString();
    }
}
