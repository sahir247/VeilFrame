package com.veilframe.app.pdf.operations

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A failed encoder/write must never replace the previous committed file. */
internal object SignatureLibraryFiles {
    fun validId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    fun atomicWrite(destination: File, writer: (FileOutputStream) -> Unit) {
        val parent = destination.parentFile ?: throw IOException("Missing signature directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create signature directory")
        val stage = File.createTempFile(".signature-", ".tmp", parent)
        try {
            FileOutputStream(stage).use { output ->
                writer(output)
                output.flush()
                output.fd.sync()
            }
            if (stage.length() == 0L) throw IOException("Empty signature library file")
            Files.move(stage.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            stage.delete()
        }
    }
}
