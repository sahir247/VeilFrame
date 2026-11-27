package com.veilframe.app.cv.segmentation.rembg

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class RembgImageSourceTest {
    @Test fun actualInputBytesAreBounded() {
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(payload.size.toLong(), RembgImageSource.copyBounded(payload.inputStream(), out, payload.size.toLong()))
        assertArrayEquals(payload, out.toByteArray())
        assertThrows(IOException::class.java) {
            RembgImageSource.copyBounded(payload.inputStream(), ByteArrayOutputStream(), 99_999)
        }
        assertThrows(IOException::class.java) {
            RembgImageSource.copyBounded(ByteArray(0).inputStream(), ByteArrayOutputStream(), 10)
        }
    }

    @Test fun cancellationCheckpointPreventsFurtherCopies() {
        val input = ByteArray(120_000).inputStream()
        val out = ByteArrayOutputStream()
        var calls = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            RembgImageSource.copyBounded(input, out, 120_000) {
                if (++calls == 2) throw kotlinx.coroutines.CancellationException("cancel")
            }
        }
        assertEquals(65_536, out.size())
    }
}
