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
    fun reviewSnapshotDoesNotFollowSourceReplacement() {
        val root = java.nio.file.Files.createTempDirectory("bundle_snapshot").toFile()
        try {
            val source = java.io.File(root, "bundle.md").apply { writeText("reviewed first") }
            val snapshot = java.io.File(java.io.File(root, "owned"), source.name)
            BundleReviewReader.freeze(source, snapshot)
            source.writeText("different content")
            org.junit.Assert.assertEquals("reviewed first", BundleReviewReader.read(snapshot, 0).text)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun escapedQuotedSecretAndNativeDelimitersFailSafely() {
        val input = """password = "fake\"suffix-secret" """
        assertFalse(AiBundleEngine.maskSecrets(input).contains("suffix-secret"))
        assertFalse(AiBundleEngine.isNativeBundleEntrySafe("file.kt", "before\n  >>>\r\nafter"))
        assertFalse(AiBundleEngine.isNativeBundleEntrySafe("bad\"name.kt", "normal code"))
        assertTrue(AiBundleEngine.isNativeBundleEntrySafe("file.kt", "fun add(a: Int) = a"))
    }

    @Test
    fun masksShortAndEnvironmentNamedCredentials() {
        for ((input, secret) in listOf(
            "password=abc" to "abc",
            "AWS_SESSION_TOKEN=exampleSessionValue" to "exampleSessionValue",
            "DB_PASSWORD=xy" to "xy",
            "OPENAI_API_KEY=exampleProviderValue" to "exampleProviderValue",
            "DATABASE_URL=sqlite:///private-db" to "sqlite:///private-db"
        )) {
            val masked = AiBundleEngine.maskSecrets(input)
            assertTrue(masked.contains("[REDACTED_SECRET]"))
            assertFalse(masked.contains(secret))
        }
        assertTrue(AiBundleEngine.isSensitiveFileName("terraform.tfvars"))
        assertTrue(AiBundleEngine.isSensitiveFileName(".git-credentials"))
    }

    @Test
    fun rejectsUnsafeRelativePaths() {
        for (path in listOf("../secret.txt", "/abs.txt", "src/../secret", "src//file",
            "a\\b", "a\nfile", "src/./file")) {
            assertFalse(path, AiBundleEngine.isSafeBundlePath(path))
            assertFalse(path, AiBundleEngine.isNativeBundleEntrySafe(path, "code"))
        }
        assertTrue(AiBundleEngine.isSafeBundlePath("src/hello world.kt"))
    }

    @Test
    fun explicitExclusionsMatchEveryAliasAndNeverSilentlyIgnoreTypos() {
        val paths = AiBundleEngine.parseExcludedPaths("src/private.kt\nconfig/local.json\n")
        val exclusions = AiBundleEngine.FileExclusions(paths)
        assertFalse(exclusions.excludes("src/Private.kt"))
        assertFalse(exclusions.excludes("other/private.kt"))
        assertTrue(exclusions.excludes("src/private.kt"))
        assertTrue(exclusions.excludes("src/private.kt"))
        org.junit.Assert.assertThrows(java.io.IOException::class.java) { exclusions.requireAllMatched() }
        assertTrue(exclusions.excludes("config/local.json"))
        exclusions.requireAllMatched()
    }

    @Test
    fun explicitExclusionsRejectTraversalAndPreserveFilenameSpaces() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AiBundleEngine.parseExcludedPaths("../private.kt")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AiBundleEngine.FileExclusions(setOf("/private.kt"))
        }
        val paths = AiBundleEngine.parseExcludedPaths("src/a file.kt\n src.kt ")
        assertTrue("src/a file.kt" in paths)
        assertTrue(" src.kt " in paths)
    }

    @Test
    fun preservesOrdinarySourceText() {
        val source = "fun add(a: Int, b: Int) = a + b"
        assertTrue(AiBundleEngine.maskSecrets(source) == source)
    }
}
