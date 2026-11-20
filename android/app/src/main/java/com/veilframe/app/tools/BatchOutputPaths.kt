package com.veilframe.app.tools

import java.io.File
import java.util.Locale

/** Keeps source hierarchy and guarantees a unique, pre-reserved output path per input. */
internal object BatchOutputPaths {
    fun outputFor(inputRoot: File, outputRoot: File, input: File, index: Int,
                  operation: String, extension: String): File {
        require(index >= 0 && operation in setOf("cleaned", "sanitized"))
        require(extension.matches(Regex("[a-z0-9]{1,12}")))
        val relative = input.canonicalFile.relativeTo(inputRoot.canonicalFile)
        require(!relative.path.startsWith(".."+File.separator) && relative.path != ".." && input.isFile)
        val parent = relative.parent?.let { File(outputRoot, it) } ?: outputRoot
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create batch output directory" }
        // Separate file entries from directory names, even if an original directory
        // has a name identical to a generated output filename.
        val files = File(parent, ".vf-outputs")
        check(files.isDirectory || files.mkdirs())
        val base = input.nameWithoutExtension.take(60).ifBlank { "file" }
        val filename = String.format(Locale.ROOT, "%06d_%s_%s.%s", index + 1, base, operation, extension)
        val target = File(files, filename)
        require(!target.exists()) { "Duplicate batch output identity" }
        return target
    }
}
