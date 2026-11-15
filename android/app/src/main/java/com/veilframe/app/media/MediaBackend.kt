package com.veilframe.app.media

import android.content.Context
import android.util.Log
import com.veilframe.app.cv.core.CvRuntime
import com.veilframe.app.cv.prnu.PrnuSanitizerEngine
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

    fun cleanImageWithDefense(
        inputPath: String,
        outputPath: String,
        defenseMode: String = "stealth",
        stripExif: Boolean = true,
        quality: Int = 90,
        targetExtension: String? = null,
        onMilestone: ((String) -> Unit)? = null
    ): Boolean = cleanImage(
        inputPath = inputPath,
        outputPath = outputPath,
        stripExif = stripExif,
        quality = quality,
        targetExtension = targetExtension,
        stripThumbnails = true,
        sanitizeIcc = true
    )

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

    override fun cleanImageWithDefense(
        inputPath: String,
        outputPath: String,
        defenseMode: String,
        stripExif: Boolean,
        quality: Int,
        targetExtension: String?,
        onMilestone: ((String) -> Unit)?
    ): Boolean {
        if (!CvRuntime.isNativeAvailable && !CvRuntime.initialize()) {
            Log.w(tag, "CvRuntime native unavailable; falling back to cleanImage")
            return cleanImage(inputPath, outputPath, stripExif, quality, targetExtension)
        }
        val mode = when (defenseMode.lowercase()) {
            "stealth", "hybrid", "2-stage", "stealth (2-stage hybrid)", "stealth (2-stage)" ->
                PrnuSanitizerEngine.DefenseMode.STEALTH_HYBRID
            "standard", "remap", "fused remap", "standard (fused remap)" ->
                PrnuSanitizerEngine.DefenseMode.STANDARD_FUSED_REMAP
            "high suppression", "suppression", "max suppression", "maximum privacy" ->
                PrnuSanitizerEngine.DefenseMode.HIGH_SUPPRESSION
            "high", "high defense" ->
                PrnuSanitizerEngine.DefenseMode.HIGH_DEFENSE
            "metadata only", "metadata" ->
                PrnuSanitizerEngine.DefenseMode.METADATA_ONLY
            else ->
                PrnuSanitizerEngine.DefenseMode.STEALTH_HYBRID
        }
        val inFile = File(inputPath)
        val outFile = File(outputPath)
        return PrnuSanitizerEngine.sanitizeImageFile(
            context = context,
            inputFile = inFile,
            outputFile = outFile,
            mode = mode,
            targetQuality = quality,
            onMilestone = onMilestone
        )
    }

    override fun getBackendDiagnostics(): String {
        return "VeilFrame Native Android Engine | FFmpegKit 8.1.7 + AndroidX ExifInterface"
    }
}
