package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException

/** Immutable input backed by a private staged file in the production workspace. */
internal sealed class RembgImageSource {
    abstract val size: Long
    abstract fun open(): InputStream
    abstract fun decode(options: BitmapFactory.Options): Bitmap?

    class Bytes(private val data: ByteArray) : RembgImageSource() {
        override val size: Long get() = data.size.toLong()
        override fun open(): InputStream = ByteArrayInputStream(data)
        override fun decode(options: BitmapFactory.Options): Bitmap? =
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    class Staged(val file: File) : RembgImageSource() {
        override val size: Long get() = file.length()
        override fun open(): InputStream = file.inputStream()
        override fun decode(options: BitmapFactory.Options): Bitmap? =
            BitmapFactory.decodeFile(file.absolutePath, options)
    }

    companion object {
        private const val MAX_COMPRESSED_BYTES = 256L * 1024 * 1024
        private const val STORAGE_RESERVE = 128L * 1024 * 1024

        fun stage(input: InputStream, cacheDir: File, checkpoint: () -> Unit = {}): Staged {
            val allowed = minOf(MAX_COMPRESSED_BYTES, (cacheDir.usableSpace - STORAGE_RESERVE).coerceAtLeast(0))
            if (allowed < 1) throw IOException("Not enough private storage to import image")
            val file = File.createTempFile("rembg_source_", ".image", cacheDir)
            try {
                file.outputStream().buffered().use { copyBounded(input, it, allowed, checkpoint) }
                checkpoint()
                return Staged(file)
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }

        internal fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long,
                                 checkpoint: () -> Unit = {}): Long {
            require(limit > 0)
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                checkpoint()
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0 || n > limit - count) throw IOException("Image import exceeds its private storage budget or stream made no progress")
                output.write(buffer, 0, n)
                count += n
            }
            if (count == 0L) throw IOException("Selected image is empty")
            return count
        }
    }
}
