package com.veilframe.app.tools

import java.io.File
import java.util.Locale

/** Keeps source hierarchy and guarantees a unique, pre-reserved output path per input. */
internal object BatchOutputPaths {
    private fun parentFor(inputRoot: File, outputRoot: File, input: File): File {
        val relative = input.canonicalFile.relativeTo(inputRoot.canonicalFile)
        require(!relative.path.startsWith(".." + File.separator) && relative.path != ".." && input.isFile)
        val parent = relative.parent?.let { File(outputRoot, it) } ?: outputRoot
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create batch output directory" }
        return parent
    }

    /** Reserve the entire directory hierarchy before any file is encoded. */
    fun prepare(inputRoot: File, outputRoot: File, inputs: List<File>) {
        inputs.forEach { parentFor(inputRoot, outputRoot, it) }
    }

    fun outputFor(inputRoot: File, outputRoot: File, input: File, index: Int,
                  operation: String, extension: String): File {
        require(index >= 0 && operation in setOf("cleaned", "sanitized"))
        require(extension.matches(Regex("[a-z0-9]{1,12}")))
        val parent = parentFor(inputRoot, outputRoot, input)
        val base = input.nameWithoutExtension.take(48).ifBlank { "file" }
        val stem = String.format(Locale.ROOT, "%06d_%s_%s", index + 1, base, operation)
        // Directory names are provider-controlled and may equal a generated filename.
        // The reserved hierarchy makes that conflict known before any encoding.
        var target = File(parent, "$stem.$extension")
        var suffix = 1
        while (target.exists()) {
            target = File(parent, "${stem}_${suffix++}.$extension")
            require(suffix <= 50_001) { "Too many output name collisions" }
        }
        return target
    }
}
