package com.veilframe.app.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class BundleTokenizerTest {
    @Test fun ordinaryTokensAreNotCharacterEstimates() {
        assertEquals(2L, BundleTokenizer.count("hello world"))
        assertEquals(1L, BundleTokenizer.count("hello"))
        assertTrue(BundleTokenizer.count("hello <|endoftext|> world") > 2L)
        assertTrue(BundleTokenizer.count("বাংলা 日本語 😀") > 0L)
    }

    @Test fun distinctNamedEncodingsAndStableBudgetChoices() {
        assertEquals(5L, BundleTokenizer.count("お誕生日おめでとう", BundleTokenizer.NAME))
        assertEquals(9L, BundleTokenizer.count("お誕生日おめでとう", BundleTokenizer.CL100K))
        assertEquals(64_000L, BundleBudgetOptions.at(1).tokens)
        assertEquals(0L, BundleBudgetOptions.at(4).tokens)
        assertEquals(BundleTokenizer.CL100K, BundleBudgetOptions.at(5).encoding)
        val file = File.createTempFile("bundle_encoding", ".txt")
        try {
            file.writeText("お誕生日おめでとう")
            assertEquals(5L, BundleTokenizer.verify(file, 5, BundleTokenizer.NAME))
            assertThrows(IOException::class.java) { BundleTokenizer.verify(file, 5, BundleTokenizer.CL100K) }
        } finally { file.delete() }
    }

    @Test fun wholeArtifactChecksBudgetIncludingMetadataAndEscapes() {
        val file = File.createTempFile("bundle_tokens", ".json")
        try {
            val content = """{"files":{"a.kt":"fun main() { println(\"hi\") }"},"omissions":["private.kt"]}"""
            file.writeText(content)
            val exact = BundleTokenizer.count(content)
            assertEquals(exact, BundleTokenizer.verify(file, exact))
            assertThrows(IOException::class.java) { BundleTokenizer.verify(file, exact - 1) }
            assertTrue(exact > BundleTokenizer.count("fun main() { println(\"hi\") }"))
        } finally { file.delete() }
    }

    @Test fun byteCeilingAndMalformedUtf8AreRejectedBeforePublication() {
        val file = File.createTempFile("bundle_tokens", ".txt")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(BundleTokenizer.MAX_TOKENIZED_BYTES + 1) }
            assertThrows(IOException::class.java) { BundleTokenizer.verify(file, Long.MAX_VALUE) }
            file.writeBytes(byteArrayOf(0xC0.toByte(), 0x80.toByte()))
            assertThrows(java.nio.charset.CharacterCodingException::class.java) {
                BundleTokenizer.verify(file, 100)
            }
        } finally { file.delete() }
    }
}
