package com.veilframe.app.pdf.operations

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Length-prefix the individual words to preserve exact Unicode, order and boundaries. */
internal fun digestOcrWords(words: Sequence<String>): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    for (word in words) {
        val bytes = word.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest()
}

/**
 * Verify the last, app-generated content stream without depending on reading-order
 * heuristics or text already on the page. Read the saved font's actual ToUnicode
 * mapping for each text code. A pre-existing visible text layer cannot satisfy this
 * check in place of the newly appended one.
 */
@Suppress("DEPRECATION")
internal fun digestSerializedOcrLayer(page: PDPage, checkpoint: () -> Unit = {}): ByteArray {
    val streams = page.contentStreams
    if (!streams.hasNext()) throw IOException("Missing OCR content stream")
    var last = streams.next()
    while (streams.hasNext()) {
        checkpoint()
        last = streams.next()
    }
    val bytes = last.createInputStream().use { input ->
        val output = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            checkpoint()
            val count = input.read(block)
            if (count < 0) break
            if (output.size().toLong() + count > 16L * 1024 * 1024) {
                throw IOException("OCR page text layer exceeds verification budget")
            }
            output.write(block, 0, count)
        }
        output.toByteArray()
    }
    val parser = PDFStreamParser(bytes)
    val operands = mutableListOf<Any>()
    var font: PDFont? = null
    return digestOcrWords(sequence {
        while (true) {
            checkpoint()
            val token = parser.parseNextToken() ?: break
            if (token is Operator) {
                when (token.name) {
                    "Tf" -> {
                        val name = operands.firstOrNull() as? COSName ?: throw IOException("Invalid OCR font operator")
                        font = page.resources?.getFont(name) ?: throw IOException("Missing saved OCR font")
                    }
                    "Tj" -> {
                        val activeFont = font ?: throw IOException("OCR text has no font")
                        val encoded = operands.singleOrNull() as? COSString ?: throw IOException("Invalid OCR text operator")
                        val input = ByteArrayInputStream(encoded.bytes)
                        val decoded = StringBuilder()
                        while (input.available() > 0) {
                            checkpoint()
                            val before = input.available()
                            val code = activeFont.readCode(input)
                            if (input.available() >= before || code < 0) throw IOException("Invalid saved OCR character code")
                            decoded.append(activeFont.toUnicode(code) ?: throw IOException("Missing Unicode mapping in OCR font"))
                        }
                        yield(decoded.toString())
                    }
                    "TJ", "'", "\"" -> throw IOException("Unexpected OCR text operator")
                }
                operands.clear()
            } else {
                if (operands.size >= 16) throw IOException("Invalid OCR content operands")
                operands.add(token)
            }
        }
        if (operands.isNotEmpty()) throw IOException("Truncated OCR content stream")
    })
}
