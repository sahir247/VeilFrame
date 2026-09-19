package com.veilframe.app.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testCanonicalizeJsonForSigning_RecursiveDeterministicOrdering() {
        // Document with keys in random order and nested objects in reverse order
        val json1 = JSONObject().apply {
            put("versionName", "2.2.6")
            put("versionCode", 226)
            put("signature", "SIGNATURE_TO_BE_STRIPPED")
            put("apk", JSONObject().apply {
                put("size", 12345678L)
                put("sha256", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")
            })
            put("metadata", JSONObject().apply {
                put("z_key", "last")
                put("a_key", "first")
                put("nested_array", JSONArray().apply {
                    put(JSONObject().apply {
                        put("order", 2)
                        put("name", "beta")
                    })
                    put(JSONObject().apply {
                        put("name", "alpha")
                        put("order", 1)
                    })
                })
            })
        }

        // Document with identical data but inserted in completely different key order
        val json2 = JSONObject().apply {
            put("metadata", JSONObject().apply {
                put("a_key", "first")
                put("nested_array", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "beta")
                        put("order", 2)
                    })
                    put(JSONObject().apply {
                        put("order", 1)
                        put("name", "alpha")
                    })
                })
                put("z_key", "last")
            })
            put("apk", JSONObject().apply {
                put("sha256", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")
                put("size", 12345678L)
            })
            put("versionCode", 226)
            put("versionName", "2.2.6")
            put("signature", "DIFFERENT_SIGNATURE_STILL_STRIPPED")
        }

        val canonicalBytes1 = AppUpdateManager.canonicalizeJsonForSigning(json1)
        val canonicalBytes2 = AppUpdateManager.canonicalizeJsonForSigning(json2)

        val str1 = String(canonicalBytes1, Charsets.UTF_8)
        val str2 = String(canonicalBytes2, Charsets.UTF_8)

        // Must be byte-for-byte identical
        assertArrayEquals("Canonical JSON bytes must be strictly deterministic", canonicalBytes1, canonicalBytes2)
        assertEquals(str1, str2)

        // Must exclude top-level signature
        assertFalse("Must strip top-level signature", str1.contains("SIGNATURE_TO_BE_STRIPPED"))
        assertFalse("Must strip top-level signature", str1.contains("DIFFERENT_SIGNATURE_STILL_STRIPPED"))

        // Must preserve sorted nested structure
        val expected = "{\"apk\":{\"sha256\":\"abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789\",\"size\":12345678},\"metadata\":{\"a_key\":\"first\",\"nested_array\":[{\"name\":\"beta\",\"order\":2},{\"name\":\"alpha\",\"order\":1}],\"z_key\":\"last\"},\"versionCode\":226,\"versionName\":\"2.2.6\"}"
        assertEquals(expected, str1)
    }

    @Test
    fun testCanonicalizeJsonForSigning_PrimitivesAndNulls() {
        val json = JSONObject().apply {
            put("bool_val", true)
            put("null_val", JSONObject.NULL)
            put("double_val", 42.0)
            put("unicode_val", "VeilFrame 🛡️ 视频")
        }

        val canonicalStr = String(AppUpdateManager.canonicalizeJsonForSigning(json), Charsets.UTF_8)
        val expected = "{\"bool_val\":true,\"double_val\":42,\"null_val\":null,\"unicode_val\":\"VeilFrame 🛡️ 视频\"}"
        assertEquals(expected, canonicalStr)
    }

    @Test
    fun testIsAllowedUpdateUrl_RepositoryPinning() {
        // Valid official VeilFrame repository URLs
        assertTrue(AppUpdateManager.isAllowedUpdateUrl("https://raw.githubusercontent.com/sahir247/VeilFrame/main/android/update.json"))
        assertTrue(AppUpdateManager.isAllowedUpdateUrl("https://raw.githubusercontent.com/sahir247/VeilFrame/v2.2.6/android/update.json"))
        assertTrue(AppUpdateManager.isAllowedUpdateUrl("https://api.github.com/repos/sahir247/VeilFrame/releases/latest"))
        assertTrue(AppUpdateManager.isAllowedUpdateUrl("https://github.com/sahir247/VeilFrame/releases/download/v2.2.6/VeilFrame-v2.2.6.apk"))
        assertTrue(AppUpdateManager.isAllowedUpdateUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/123456?token=xyz"))

        // Blocked: Third-party or attacker GitHub repositories
        assertFalse("Block attacker raw GitHub", AppUpdateManager.isAllowedUpdateUrl("https://raw.githubusercontent.com/attacker/malicious/main/update.json"))
        assertFalse("Block attacker api GitHub", AppUpdateManager.isAllowedUpdateUrl("https://api.github.com/repos/attacker/malicious/releases"))
        assertFalse("Block attacker release download", AppUpdateManager.isAllowedUpdateUrl("https://github.com/attacker/malicious/releases/download/v1.0/trojan.apk"))

        // Blocked: Insecure HTTP or spoofed domains
        assertFalse("Block HTTP", AppUpdateManager.isAllowedUpdateUrl("http://raw.githubusercontent.com/sahir247/VeilFrame/main/android/update.json"))
        assertFalse("Block spoofed domain", AppUpdateManager.isAllowedUpdateUrl("https://attacker-domain.com/sahir247/VeilFrame/main/android/update.json"))
        assertFalse("Block custom port", AppUpdateManager.isAllowedUpdateUrl("https://raw.githubusercontent.com:8443/sahir247/VeilFrame/main/android/update.json"))
    }

    @Test
    fun testVerifyManifestSignature_RejectsUnsignedOrMalformed() {
        val unsignedJson = JSONObject().apply {
            put("versionCode", 226)
            put("versionName", "2.2.6")
        }
        assertFalse("Unsigned manifest must fail verification", AppUpdateManager.verifyManifestSignature(unsignedJson))

        val malformedSigJson = JSONObject().apply {
            put("versionCode", 226)
            put("versionName", "2.2.6")
            put("signature", "invalid-base64-not-ed25519")
        }
        assertFalse("Malformed signature must fail verification", AppUpdateManager.verifyManifestSignature(malformedSigJson))
    }
}
