package com.veilframe.app.cv.segmentation.rembg

import java.io.File
import java.io.IOException

/** Private sequential baseline JPEG codec. No full-frame RGB or coefficient buffer. */
internal object StreamingJpeg {
    private val loaded by lazy {
        try {
            System.loadLibrary("veilframe_jpeg")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
    private fun requireCodec() {
        if (!loaded) throw IOException("Original-resolution JPEG codec unavailable")
    }

    fun write(file: File, width: Int, height: Int, quality: Int = 90,
              checkpoint: () -> Unit = {}, readRow: (Int, IntArray) -> Unit) {
        require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= 128_000_000L)
        require(quality in 1..100)
        requireCodec()
        checkpoint()
        val handle = openEncoder(file.absolutePath, width, height, quality)
        check(handle != 0L)
        try {
            val pixels = IntArray(width)
            val rgb = ByteArray(width * 3)
            for (y in 0 until height) {
                checkpoint()
                readRow(y, pixels)
                var i = 0
                for (pixel in pixels) {
                    require(pixel ushr 24 == 255) { "JPEG requires an opaque backdrop" }
                    rgb[i++] = (pixel ushr 16).toByte()
                    rgb[i++] = (pixel ushr 8).toByte()
                    rgb[i++] = pixel.toByte()
                }
                writeRow(handle, rgb)
            }
            checkpoint()
            finishEncoder(handle)
        } finally { closeEncoder(handle) }
    }
    fun verify(file: File, width: Int, height: Int, checkpoint: () -> Unit = {}) {
        requireCodec()
        checkpoint()
        val handle = openVerifier(file.absolutePath, width, height)
        check(handle != 0L)
        try {
            var remaining = height
            while (remaining > 0) {
                checkpoint()
                val rows = minOf(remaining, 16)
                verifyRows(handle, rows)
                remaining -= rows
            }
            checkpoint()
            finishVerifier(handle)
        } finally { closeVerifier(handle) }
    }

    private external fun openEncoder(path: String, width: Int, height: Int, quality: Int): Long
    private external fun writeRow(handle: Long, rgb: ByteArray)
    private external fun finishEncoder(handle: Long)
    private external fun closeEncoder(handle: Long)
    private external fun openVerifier(path: String, width: Int, height: Int): Long
    private external fun verifyRows(handle: Long, rows: Int)
    private external fun finishVerifier(handle: Long)
    private external fun closeVerifier(handle: Long)
}
