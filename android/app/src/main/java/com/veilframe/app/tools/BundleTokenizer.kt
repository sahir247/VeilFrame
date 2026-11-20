package com.veilframe.app.tools

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Exact ordinary-text counting for the named encoding, not a model's chat
 * template. Never add per-file token counts and call the result exact.
 */
internal object BundleTokenizer {
    const val NAME = "o200k_base"
    const val MAX_TOKENIZED_BYTES = 2L * 1024 * 1024
    private val encoding by lazy {
        Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.O200K_BASE)
    }

    fun count(text: String): Long = encoding.countTokensOrdinary(text).toLong()

    /** A bounded immutable artifact is counted as a single text sequence. */
    fun verify(file: File, budget: Long, checkpoint: () -> Unit = {}): Long {
        require(budget > 0)
        checkpoint()
        val length = file.length()
        if (length !in 1L..MAX_TOKENIZED_BYTES)
            throw IOException("Bundle exceeds 2 MiB exact-tokenization safety limit")
        val bytes = ByteArray(length.toInt())
        file.inputStream().use { input ->
            java.io.DataInputStream(input).readFully(bytes)
            if (input.read() != -1) throw IOException("Bundle changed during token validation")
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        checkpoint()
        val tokens = count(text)
        checkpoint()
        if (tokens > budget) throw IOException("Complete bundle exceeds " + NAME +
            " token budget (" + tokens + " > " + budget + "); increase budget or narrow selection")
        return tokens
    }
}
