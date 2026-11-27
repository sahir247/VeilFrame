package com.veilframe.app.cv.motion

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Original frames are *already* JPEG-compressed by input extraction. Do not
 * decode them and encode again just to place them in the output sequence.
 *
 * Both directories are under the job's private workDir. A hardlink uses
 * constant memory and zero pixel work; fallback copy is byte-identical.
 * Original files are retained until final MP4 verification and cleanup.
 */
object MotionLabSourceFrameStage {
    fun stage(extracted: File, output: File) {
        require(extracted.isFile && extracted.length() > 0L) {
            "Motion input JPEG unavailable: ${extracted.name}"
        }
        require(!output.exists()) { "Motion output slot already populated: ${output.name}" }
        require(output.parentFile?.isDirectory == true) { "Motion output directory unavailable" }
        try {
            Files.createLink(output.toPath(), extracted.toPath())
        } catch (e: Exception) {
            // Some external/private filesystem configurations refuse hardlinks.
            // Do not decode and recompress: copy the exact JPEG bytes instead.
            Files.copy(extracted.toPath(), output.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }
        check(output.isFile && output.length() == extracted.length() && output.length() > 0L) {
            "Motion original frame stage mismatch"
        }
    }
}
