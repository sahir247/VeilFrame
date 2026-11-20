package com.veilframe.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBundleSecretMaskingTest {
    @Test
    fun masksRecognizedModernAndStructuredCredentials() {
        val examples = listOf(
            "password = \"short!9\"",
            "github_pat_" + "a".repeat(40),
            "-----BEGIN PRIVATE KEY-----\nexample-fake-private-material\n-----END PRIVATE KEY-----",
            "Bearer fakeBearerToken012345",
            "Authorization: Bearer fakeBearerToken012345",
            "eyJfakeHeader.eyJfakePayload.fakeSignature",
            "postgresql://user:fakePassword@localhost/db",
            "sk-proj-" + "X".repeat(30)
        )
        for (example in examples) {
            val masked = AiBundleEngine.maskSecrets(example)
            assertTrue(masked.contains("[REDACTED_SECRET]"))
            assertFalse(masked.contains(example))
            assertFalse(masked.contains("fakeBearerToken012345"))
        }
    }

    @Test
    fun excludesCredentialContainersWithoutDependingOnTextPatterns() {
        listOf(".env", ".env.production", "ID_ED25519", "release.JKS", "service-account.json",
            "keystore.properties", "private.pem", "client.p12", ".npmrc").forEach {
            assertTrue(it, AiBundleEngine.isSensitiveFileName(it))
        }
        listOf(".env.example", "MainActivity.kt", "package.json", "readme.md").forEach {
            assertFalse(it, AiBundleEngine.isSensitiveFileName(it))
        }
    }

    @Test
    fun reviewCanReachEveryCharacterWithoutSplittingUnicode() {
        val file = java.io.File.createTempFile("bundle_review", ".txt")
        try {
            val content = "abc😀def ghi café"
            file.writeText(content)
            var offset = 0L
            val restored = StringBuilder()
            do {
                val page = BundleReviewReader.read(file, offset, 4)
                restored.append(page.text)
                val next = page.nextOffset
                if (next == null) break
                offset = next
            } while (true)
            org.junit.Assert.assertEquals(content, restored.toString())
        } finally { file.delete() }
    }

    @Test
    fun additionalCredentialSchemesAreMasked() {
        listOf("Basic dGVzdDp0ZXN0", "glpat-" + "x".repeat(24),
            "client_secret=exampleSecretValue", "hf_" + "a".repeat(32)).forEach {
            assertTrue(AiBundleEngine.maskSecrets(it).contains("[REDACTED_SECRET]"))
        }
    }

    @Test
    fun preservesOrdinarySourceText() {
        val source = "fun add(a: Int, b: Int) = a + b"
        assertTrue(AiBundleEngine.maskSecrets(source) == source)
    }
}
