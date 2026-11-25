package com.veilframe.app.pdf

import android.content.Context
import android.net.Uri
import com.veilframe.app.pdf.operations.OfficeSourceFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

/** Identifies the exact admitted bytes, not provider metadata or URI alone. */
internal class OfficeSourceSnapshot private constructor(val file: File,val identity: Identity) : Closeable {
    data class Identity(val bytes: Long,val sha256: String) {
        fun requireSame(other: Identity) {
            if (this != other) throw IOException("Document changed since inspection; select and inspect it again")
        }
    }
    override fun close() { file.delete() }

    companion object {
        private const val MAX_SOURCE_BYTES = 128L*1024*1024

        fun copy(input: InputStream,output: OutputStream,maximum: Long = MAX_SOURCE_BYTES,
            checkpoint: ()->Unit = {}): Identity {
            require(maximum>0)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64*1024)
            var bytes = 0L
            while (true) {
                checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("Office document stream made no read progress")
                if (count.toLong() > maximum-bytes) throw IOException("Office document exceeds source size limit")
                bytes += count
                digest.update(buffer,0,count)
                output.write(buffer,0,count)
            }
            if (bytes == 0L) throw IOException("Selected Office document is empty")
            return Identity(bytes,digest.digest().joinToString("") { "%02x".format(Locale.ROOT,it.toInt() and 255) })
        }

        suspend fun open(context: Context,uri: Uri,format: OfficeSourceFormat): OfficeSourceSnapshot {
            val job = currentCoroutineContext()
            val file = File.createTempFile("office_source_",format.suffix,context.cacheDir)
            var handedOff = false
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open selected Office document")
                val identity = input.use { source ->
                    file.outputStream().buffered().use { output -> copy(source,output) { job.ensureActive() } }
                }
                job.ensureActive()
                check(file.length() == identity.bytes)
                return OfficeSourceSnapshot(file,identity).also { handedOff = true }
            } finally { if (!handedOff) file.delete() }
        }
    }
}
