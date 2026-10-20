package com.veilframe.app.media

import android.content.Context
import android.util.Log
import com.veilframe.app.privacy.ImageMetadataSanitizer
import com.veilframe.app.privacy.VideoMetadataSanitizer
import java.io.File

/**
 * Pluggable Media Backend interface for VeilFrame Android.
 * Decouples the UI and Privacy engine from any specific FFmpeg vendor artifact.
 * Fully native Android implementation powered by FFmpegKit and AndroidX ExifInterface.
 */
interface IMediaBackend {
    fun cleanVideo(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true,
        crf: Int? = null,
        stripExif: Boolean = true,
        reencodeBitstream: Boolean = false
    ): Boolean

    fun cleanImage(
        inputPath: String,
        outputPath: String,
        stripExif: Boolean = true,
        quality: Int = 95,
        targetExtension: String? = null,
        stripThumbnails: Boolean = true,
        sanitizeIcc: Boolean = true
    ): Boolean

    fun getBackendDiagnostics(): String
}

class AndroidMediaBackend(private val context: Context) : IMediaBackend {

    private val tag = "VeilFrame.MediaBackend"

    override fun cleanVideo(
        inputPath: String,
        outputPath: String,
        noiseLevel: String,
        scrubAudio: Boolean,
        crf: Int?,
        stripExif: Boolean,
        reencodeBitstream: Boolean
    ): Boolean {
        return VideoMetadataSanitizer.cleanVideo(
            inputPath = inputPath,
            outputPath = outputPath,
            noiseLevel = noiseLevel,
            scrubAudio = scrubAudio,
            crf = crf,
            stripExif = stripExif,
            reencodeBitstream = reencodeBitstream
        )
    }

    suspend fun cleanVideoCancellable(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true,
        crf: Int? = null,
        stripExif: Boolean = true,
        reencodeBitstream: Boolean = false
    ): Boolean {
        return VideoMetadataSanitizer.cleanVideoCancellable(
            inputPath = inputPath,
            outputPath = outputPath,
            noiseLevel = noiseLevel,
            scrubAudio = scrubAudio,
            crf = crf,
            stripExif = stripExif,
            reencodeBitstream = reencodeBitstream
        )
    }

    override fun cleanImage(
        inputPath: String,
        outputPath: String,
        stripExif: Boolean,
        quality: Int,
        targetExtension: String?,
        stripThumbnails: Boolean,
        sanitizeIcc: Boolean
    ): Boolean {
        val inFile = File(inputPath)
        val outFile = File(outputPath)
        val inExt = inFile.extension.lowercase(java.util.Locale.US)
        val outExt = (targetExtension ?: outFile.extension).lowercase(java.util.Locale.US)

        val needsTranscode = (inExt != outExt && outExt.isNotEmpty()) || quality < 98 || !stripExif

        val success = if (needsTranscode) {
            ImageMetadataSanitizer.reencodePixels(inFile, outFile, format = outExt, quality = quality)
        } else {
            ImageMetadataSanitizer.stripExifLossless(inFile, outFile)
        }

        if (success && stripExif && outFile.exists()) {
            ImageMetadataSanitizer.stripExifLossless(outFile, outFile)
        }
        return success && outFile.exists() && outFile.length() > 0L
    }

    override fun getBackendDiagnostics(): String {
        return "VeilFrame Native Android Engine | FFmpegKit 8.1.7 + AndroidX ExifInterface"
    }
}
