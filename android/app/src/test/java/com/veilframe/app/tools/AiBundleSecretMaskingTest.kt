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
            "eyJfakeHeader.eyJfakePayload.fakeSignature",
            "postgresql://user:fakePassword@localhost/db",
            "sk-proj-" + "X".repeat(30)
        )
        for (example in examples) {
            val masked = AiBundleEngine.maskSecrets(example)
            assertTrue(masked.contains("[REDACTED_SECRET]"))
            assertFalse(masked.contains(example))
        }
    }

    @Test
    fun preservesOrdinarySourceText() {
        val source = "fun add(a: Int, b: Int) = a + b"
        assertTrue(AiBundleEngine.maskSecrets(source) == source)
    }
}
