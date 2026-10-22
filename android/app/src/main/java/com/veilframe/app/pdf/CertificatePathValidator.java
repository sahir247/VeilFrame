package com.veilframe.app.pdf;

import java.security.KeyStore;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Checks whether a signer certificate currently chains to the Android system CA store
 * or bundled/user-imported CA trust anchors (including CCA India and nCode Solutions PKI).
 *
 * Exact user pins are intentionally not added as CA trust anchors here. A signer pin is a
 * separate, leaf-certificate-only trust decision and must never make a sibling certificate
 * or issuer trusted. Revocation is disabled here on purpose; this class does not claim that
 * a certificate is not revoked.
 */
public final class CertificatePathValidator {
    public enum Status {
        TRUSTED_SYSTEM_PATH,
        TRUSTED_APP_PATH,
        UNTRUSTED,
        UNKNOWN
    }

    public static final class Result {
        public final Status status;
        public final String summary;

        public Result(Status status, String summary) {
            this.status = status;
            this.summary = summary;
        }
    }

    private CertificatePathValidator() { }

    public static Result validate(X509Certificate signer,
                                  Collection<X509Certificate> embeddedCertificates) {
        return validate(signer, embeddedCertificates, Collections.emptyList(), Collections.emptyList());
    }

    public static Result validate(X509Certificate signer,
                                  Collection<X509Certificate> embeddedCertificates,
                                  Collection<X509Certificate> appTrustAnchors) {
        return validate(signer, embeddedCertificates, appTrustAnchors, Collections.emptyList());
    }

    public static Result validate(X509Certificate signer,
                                  Collection<X509Certificate> embeddedCertificates,
                                  Collection<X509Certificate> appTrustAnchors,
                                  Collection<X509Certificate> intermediateCertificates) {
        return validate(signer, embeddedCertificates, appTrustAnchors, intermediateCertificates, null);
    }

    public static Result validate(X509Certificate signer,
                                  Collection<X509Certificate> embeddedCertificates,
                                  Collection<X509Certificate> appTrustAnchors,
                                  Collection<X509Certificate> intermediateCertificates,
                                  Date validationDate) {
        if (signer == null) {
            return new Result(Status.UNKNOWN, "Signer certificate is unavailable.");
        }

        try {
            Set<TrustAnchor> anchors = loadSystemTrustAnchors();
            Set<X509Certificate> appAnchors = new HashSet<>();
            if (appTrustAnchors != null) {
                for (X509Certificate certificate : appTrustAnchors) {
                    if (certificate != null) {
                        anchors.add(new TrustAnchor(certificate, null));
                        appAnchors.add(certificate);
                    }
                }
            }
            if (anchors.isEmpty()) {
                return new Result(Status.UNKNOWN, "No Android system or app-imported CA trust anchors are available.");
            }

            // Direct check if signer certificate is itself a trusted app anchor
            for (X509Certificate appAnchor : appAnchors) {
                if (sameCertificate(signer, appAnchor)) {
                    return new Result(Status.TRUSTED_APP_PATH,
                            "Signer certificate is explicitly trusted as an app trust anchor (" +
                                    signer.getSubjectX500Principal().getName() + ").");
                }
            }

            X509CertSelector target = new X509CertSelector();
            target.setCertificate(signer);
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, target);
            Date effectiveDate = validationDate != null ? validationDate : new Date();
            parameters.setDate(effectiveDate);
            // Revocation is reported separately; this builder does not claim a certificate is unrevoked.
            parameters.setRevocationEnabled(false);

            Collection<X509Certificate> candidates = new ArrayList<>();
            candidates.add(signer);
            if (embeddedCertificates != null) candidates.addAll(embeddedCertificates);
            if (appTrustAnchors != null) candidates.addAll(appTrustAnchors);
            if (intermediateCertificates != null) candidates.addAll(intermediateCertificates);
            parameters.addCertStore(CertStore.getInstance(
                    "Collection", new CollectionCertStoreParameters(candidates)));

            PKIXCertPathBuilderResult result = (PKIXCertPathBuilderResult)
                    CertPathBuilder.getInstance("PKIX").build(parameters);
            if (result.getTrustAnchor() == null || result.getTrustAnchor().getTrustedCert() == null) {
                return new Result(Status.UNKNOWN, "Path builder did not return a certificate trust anchor.");
            }
            X509Certificate anchor = result.getTrustAnchor().getTrustedCert();
            for (X509Certificate appAnchor : appAnchors) {
                if (sameCertificate(anchor, appAnchor)) {
                    return new Result(Status.TRUSTED_APP_PATH,
                            "Certificate path reaches a bundled or user-imported CA trust anchor in this app (" +
                                    anchor.getSubjectX500Principal().getName() + ").");
                }
            }
            return new Result(Status.TRUSTED_SYSTEM_PATH,
                    "Certificate path reaches an Android system trust anchor (" +
                            anchor.getSubjectX500Principal().getName() + ").");
        } catch (CertPathBuilderException e) {
            if (validationDate == null && signer.getNotAfter() != null && signer.getNotAfter().before(new Date())) {
                // Historical certificate: try validating at certificate validity window
                try {
                    return validate(signer, embeddedCertificates, appTrustAnchors, intermediateCertificates,
                            new Date(signer.getNotBefore().getTime() + 1000));
                } catch (Exception ignored) { }
            }
            return new Result(Status.UNTRUSTED,
                    "No valid path to an Android system, bundled, or user-imported CA trust anchor was found (" +
                            e.getMessage() + ").");
        } catch (Exception e) {
            return new Result(Status.UNKNOWN,
                    "Certificate path validation could not be completed (" +
                            e.getClass().getSimpleName() + ").");
        }
    }

    private static boolean sameCertificate(X509Certificate first, X509Certificate second) {
        try {
            return java.security.MessageDigest.isEqual(first.getEncoded(), second.getEncoded());
        } catch (Exception e) {
            return false;
        }
    }

    private static Set<TrustAnchor> loadSystemTrustAnchors() throws Exception {
        Set<TrustAnchor> anchors = new HashSet<>();
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);

        for (TrustManager manager : factory.getTrustManagers()) {
            if (!(manager instanceof X509TrustManager)) continue;
            X509Certificate[] accepted = ((X509TrustManager) manager).getAcceptedIssuers();
            if (accepted == null) continue;
            for (X509Certificate certificate : accepted) {
                if (certificate != null && certificate.getBasicConstraints() >= 0) {
                    anchors.add(new TrustAnchor(certificate, null));
                }
            }
        }
        return anchors;
    }
}
