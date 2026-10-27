package com.veilframe.app.pdf;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * App-private exact-certificate pin store. Pins are keyed by SHA-256(DER), never by subject,
 * issuer, public key, or CA name. The separate audit record records the explicit confirmation.
 */
public final class TrustedCertificateStore {
    private static final String PREFS = "trusted_signer_certificates_v1";
    private static final String AUDIT_PREFIX = "audit:";
    private static final String CA_PREFIX = "ca:";
    private static final int POLICY_VERSION = 2;
    private final SharedPreferences preferences;

    public static final class TrustMetadata {
        public final long trustedAtEpochMillis;
        public final boolean fingerprintVerified;
        public final int policyVersion;
        public final String purpose;
        public final String userNote;
        public final boolean legacyRecord;

        private TrustMetadata(long trustedAtEpochMillis, boolean fingerprintVerified,
                              int policyVersion, String purpose, String userNote,
                              boolean legacyRecord) {
            this.trustedAtEpochMillis = trustedAtEpochMillis;
            this.fingerprintVerified = fingerprintVerified;
            this.policyVersion = policyVersion;
            this.purpose = purpose;
            this.userNote = userNote;
            this.legacyRecord = legacyRecord;
        }
    }

    public TrustedCertificateStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder out = new StringBuilder(digest.length * 3 - 1);
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) out.append(':');
            out.append(String.format(java.util.Locale.ROOT, "%02X", digest[i] & 0xff));
        }
        return out.toString();
    }

    public boolean isTrusted(X509Certificate certificate) {
        try {
            String fp = fingerprint(certificate);
            String encoded = preferences.getString(fp, null);
            if (encoded == null) return false;
            byte[] stored = Base64.decode(encoded, Base64.NO_WRAP);
            return MessageDigest.isEqual(stored, certificate.getEncoded());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Kept for source compatibility. New trust operations should pass the explicit
     * fingerprint-confirmation value to the overload below.
     */
    public void trust(X509Certificate certificate) throws Exception {
        trust(certificate, true, null);
    }

    public void trust(X509Certificate certificate, boolean fingerprintVerified,
                      String userNote) throws Exception {
        if (certificate == null) throw new IllegalArgumentException("Certificate is required.");
        if (!fingerprintVerified) {
            throw new SecurityException("The independent fingerprint confirmation is required.");
        }

        String fp = fingerprint(certificate);
        String encoded = Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP);
        JSONObject audit = new JSONObject();
        audit.put("trustedAtEpochMillis", System.currentTimeMillis());
        audit.put("fingerprintVerified", true);
        audit.put("policyVersion", POLICY_VERSION);
        audit.put("purpose", "PDF digital-signature signer");
        if (userNote != null && !userNote.trim().isEmpty()) {
            audit.put("userNote", userNote.trim());
        }

        preferences.edit()
                .putString(fp, encoded)
                .putString(AUDIT_PREFIX + fp, audit.toString())
                .apply();
        if (!isTrusted(certificate)) {
            throw new IllegalStateException("Could not persist or verify the trust record.");
        }
    }

    public void remove(String fingerprint) {
        preferences.edit()
                .remove(fingerprint)
                .remove(AUDIT_PREFIX + fingerprint)
                .apply();
    }

    /** Store a CA certificate as an explicit app-local PKIX trust anchor. */
    public void trustCertificateAuthority(X509Certificate certificate) throws Exception {
        if (certificate == null || certificate.getBasicConstraints() < 0) {
            throw new SecurityException("A CA certificate is required.");
        }
        boolean[] usage = certificate.getKeyUsage();
        if (usage != null && (usage.length <= 5 || !usage[5])) {
            throw new SecurityException("CA key usage does not permit certificate signing.");
        }
        certificate.checkValidity(new java.util.Date());
        String fp = fingerprint(certificate);
        String encoded = Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP);
        preferences.edit().putString(CA_PREFIX + fp, encoded).apply();
        if (!isTrustedCertificateAuthority(certificate)) {
            throw new IllegalStateException("Could not persist or verify the CA trust anchor.");
        }
    }

    public boolean isTrustedCertificateAuthority(X509Certificate certificate) {
        try {
            String key = CA_PREFIX + fingerprint(certificate);
            String encoded = preferences.getString(key, null);
            if (encoded == null) return false;
            byte[] stored = Base64.decode(encoded, Base64.NO_WRAP);
            return MessageDigest.isEqual(stored, certificate.getEncoded());
        } catch (Exception e) {
            return false;
        }
    }

    public List<X509Certificate> getTrustedCertificateAuthorities() {
        List<X509Certificate> certificates = new ArrayList<>();
        try {
            java.security.cert.CertificateFactory factory =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.startsWith(CA_PREFIX) || !(entry.getValue() instanceof String)) continue;
                try {
                    byte[] der = Base64.decode((String) entry.getValue(), Base64.NO_WRAP);
                    X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                            new java.io.ByteArrayInputStream(der));
                    String expected = key.substring(CA_PREFIX.length());
                    if (expected.equals(fingerprint(certificate)) && certificate.getBasicConstraints() >= 0) {
                        certificates.add(certificate);
                    }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return certificates;
    }

    public void removeTrustedCertificateAuthority(String fingerprint) {
        if (fingerprint == null || fingerprint.trim().isEmpty()) return;
        preferences.edit().remove(CA_PREFIX + fingerprint).apply();
    }


    public List<String> listFingerprints() {
        List<String> items = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key != null && !key.startsWith(AUDIT_PREFIX) && !key.startsWith(CA_PREFIX)
                    && entry.getValue() instanceof String) {
                items.add(key);
            }
        }
        Collections.sort(items);
        return items;
    }

    public TrustMetadata getTrustMetadata(String fingerprint) {
        try {
            String raw = preferences.getString(AUDIT_PREFIX + fingerprint, null);
            if (raw == null) {
                // Backward compatibility for pins saved by the previous version. That version
                // only wrote a pin after the user checked the independent-fingerprint box, but
                // it did not retain the date or a separate audit record.
                return new TrustMetadata(0L, true, 0,
                        "PDF digital-signature signer (legacy pin; date not recorded)",
                        null, true);
            }
            JSONObject audit = new JSONObject(raw);
            return new TrustMetadata(
                    audit.optLong("trustedAtEpochMillis", 0L),
                    audit.optBoolean("fingerprintVerified", false),
                    audit.optInt("policyVersion", 0),
                    audit.optString("purpose", "PDF digital-signature signer"),
                    audit.has("userNote") ? audit.optString("userNote", null) : null,
                    false);
        } catch (Exception e) {
            return new TrustMetadata(0L, false, 0,
                    "Trust record metadata could not be read", null, true);
        }
    }

    public X509Certificate getCertificate(String fingerprint) {
        try {
            String encoded = preferences.getString(fingerprint, null);
            if (encoded == null) return null;
            byte[] der = Base64.decode(encoded, Base64.NO_WRAP);
            java.security.cert.CertificateFactory factory =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                    new java.io.ByteArrayInputStream(der));
            if (!fingerprint.equals(fingerprint(certificate))) return null;
            return certificate;
        } catch (Exception e) {
            return null;
        }
    }
}
