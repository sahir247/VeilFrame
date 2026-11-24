package com.veilframe.app.media.compression

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import com.veilframe.app.media.compression.ImageFormatEncoder.Format

class ImageContainerContractTest {
    private fun ftyp(major: String, vararg compatible: String): ByteArray =
        ByteBuffer.allocate(16 + compatible.size * 4).apply {
            putInt(capacity()); put("ftyp".toByteArray()); put(major.toByteArray()); putInt(0)
            compatible.forEach { put(it.toByteArray()) }
        }.array()

    @Test fun rejectsWebpDisguisedAsAvif() {
        val webp = "RIFF1234WEBP".toByteArray()
        assertTrue(ImageContainerContract.matchesHeader(webp, webp.size.toLong(), Format.WEBP))
        assertFalse(ImageContainerContract.matchesHeader(webp, webp.size.toLong(), Format.AVIF))
    }

    @Test fun distinguishesAvifAndHeicBrandsAndRejectsTruncation() {
        val avif = ftyp("avif", "mif1", "miaf")
        val heic = ftyp("mif1", "heic")
        assertTrue(ImageContainerContract.matchesHeader(avif, avif.size.toLong(), Format.AVIF))
        assertFalse(ImageContainerContract.matchesHeader(avif, avif.size.toLong(), Format.HEIC))
        assertTrue(ImageContainerContract.matchesHeader(heic, heic.size.toLong(), Format.HEIC))
        assertFalse(ImageContainerContract.matchesHeader(heic, heic.size.toLong(), Format.AVIF))
        val truncated = avif.copyOf(16)
        assertFalse(ImageContainerContract.matchesHeader(truncated, truncated.size.toLong(), Format.AVIF))
    }

    @Test fun doesNotAcceptJpegAsAnotherFormat() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
        assertTrue(ImageContainerContract.matchesHeader(jpeg, jpeg.size.toLong(), Format.JPEG))
        for (format in Format.entries.filterNot { it == Format.JPEG }) {
            assertFalse(format.name, ImageContainerContract.matchesHeader(jpeg, jpeg.size.toLong(), format))
        }
    }
}
