package com.veilframe.app.pdf.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** Inspect and decode the same immutable-by-contract staged image bytes. */
internal class MergeImageSource private constructor(
    private val file: File, val sha256: String, private val options: BitmapFactory.Options
) : java.io.Closeable {
    override fun close() { file.delete() }

    fun decode(): Bitmap {
        var sample = 1
        while ((maxOf(options.outWidth,options.outHeight).toLong()+sample-1)/sample > MAX_EDGE) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }) ?: throw IOException("Cannot decode merge image")
        try {
            require(bitmap.width in 1..MAX_EDGE && bitmap.height in 1..MAX_EDGE) {
                "Image decoder exceeded merge pixel budget"
            }
            // Only formats supported by AndroidX EXIF are inspected. For those
            // formats, do not silently ignore an orientation-read exception.
            val transform = if (options.outMimeType in setOf("image/jpeg","image/png","image/webp","image/heif","image/heic")) {
                val exif = ExifInterface(file.absolutePath)
                exifOcrTransform(exif.rotationDegrees,exif.isFlipped)
            } else null
            if (transform == null) return bitmap
            return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,transform,true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    companion object {
        private const val MAX_BYTES = 128L*1024*1024
        private const val MAX_EDGE = 3072

        suspend fun open(context: Context, uri: Uri): MergeImageSource {
            val job = currentCoroutineContext()
            val file = File.createTempFile("vf_merge_image_",".img",context.cacheDir)
            var transferred = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open merge image")
                var total = 0L
                input.use { source ->
                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64*1024)
                        while (true) {
                            job.ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            if (n == 0) throw IOException("Merge image source made no read progress")
                            total += n
                            require(total <= MAX_BYTES) { "Merge image exceeds 128 MiB input limit" }
                            digest.update(buffer,0,n)
                            output.write(buffer,0,n)
                        }
                    }
                }
                job.ensureActive()
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath,options)
                require(total>0 && options.outMimeType?.startsWith("image/") == true &&
                    options.outWidth>0 && options.outHeight>0 &&
                    options.outWidth <= 100_000 && options.outHeight <= 100_000) {
                    "Invalid or unsupported merge image geometry"
                }
                val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT,it.toInt() and 255) }
                return MergeImageSource(file,hash,options).also { transferred = true }
            } finally { if (!transferred) file.delete() }
        }
    }
}
