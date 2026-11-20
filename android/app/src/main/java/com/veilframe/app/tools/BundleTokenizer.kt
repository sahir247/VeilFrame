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
    const val CL100K = "cl100k_base"
    private val registry by lazy { Encodings.newLazyEncodingRegistry() }

    fun count(text: String, name: String = NAME): Long {
        val type = when (name) {
            NAME -> EncodingType.O200K_BASE
            CL100K -> EncodingType.CL100K_BASE
            else -> throw IllegalArgumentException("Unsupported bundle encoding: $name")
        }
        return registry.getEncoding(type).countTokensOrdinary(text).toLong()
    }

    /** A bounded immutable artifact is counted as a single text sequence. */
    fun verify(file: File, budget: Long, name: String = NAME, checkpoint: () -> Unit = {}): Long {
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
        val tokens = count(text, name)
        checkpoint()
        if (tokens > budget) throw IOException("Complete bundle exceeds " + name +
            " token budget (" + tokens + " > " + budget + "); increase budget or narrow selection")
        return tokens
    }
}
