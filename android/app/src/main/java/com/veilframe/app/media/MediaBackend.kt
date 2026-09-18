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
        scrubAudio: Boolean = true
    ): Boolean

    fun cleanImage(
        inputPath: String,
        outputPath: String,
        stripExif: Boolean = true
    ): Boolean

    fun getBackendDiagnostics(): String
}

class AndroidMediaBackend(private val context: Context) : IMediaBackend {

    private val tag = "VeilFrame.MediaBackend"

    override fun cleanVideo(
        inputPath: String,
        outputPath: String,
        noiseLevel: String,
        scrubAudio: Boolean
    ): Boolean {
        return VideoMetadataSanitizer.cleanVideo(
            inputPath = inputPath,
            outputPath = outputPath,
            noiseLevel = noiseLevel,
            scrubAudio = scrubAudio
        )
    }

    override fun cleanImage(
        inputPath: String,
        outputPath: String,
        stripExif: Boolean
    ): Boolean {
        val inFile = File(inputPath)
        val outFile = File(outputPath)
        return if (stripExif) {
            ImageMetadataSanitizer.stripExifLossless(inFile, outFile)
        } else {
            ImageMetadataSanitizer.reencodePixels(inFile, outFile)
        }
    }

    override fun getBackendDiagnostics(): String {
        return "VeilFrame Native Android Engine | FFmpegKit 8.1.7 + AndroidX ExifInterface"
    }
}
