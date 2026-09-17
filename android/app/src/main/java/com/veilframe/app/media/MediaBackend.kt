package com.veilframe.app.media

import android.content.Context
import android.util.Log
import com.chaquo.python.Python

/**
 * Pluggable Media Backend interface for VeilFrame Android.
 * Decouples the UI and Privacy engine from any specific FFmpeg vendor artifact.
 * Supports swapping between Chaquopy-driven pipelines, FFmpegKitNext, or native builds.
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
        return try {
            val cmd = mutableListOf<String>()
            cmd.add("-y")
            cmd.add("-i")
            cmd.add(inputPath)
            cmd.add("-map_metadata")
            cmd.add("-1")
            cmd.add("-map_chapters")
            cmd.add("-1")

            if (scrubAudio) {
                cmd.add("-an")
            } else {
                cmd.add("-c:a")
                cmd.add("copy")
            }

            when (noiseLevel.lowercase()) {
                "high", "stealth" -> {
                    cmd.add("-vf")
                    cmd.add("noise=alls=8:allf=t+u")
                    cmd.add("-c:v")
                    cmd.add("libx264")
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add("23")
                }
                "medium" -> {
                    cmd.add("-vf")
                    cmd.add("noise=alls=4:allf=t")
                    cmd.add("-c:v")
                    cmd.add("libx264")
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add("22")
                }
                else -> {
                    cmd.add("-c:v")
                    cmd.add("copy")
                }
            }

            cmd.add(outputPath)

            val cmdString = cmd.joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }
            val session = com.arthenica.ffmpegkit.FFmpegKit.execute(cmdString)
            val returnCode = session.returnCode
            val success = com.arthenica.ffmpegkit.ReturnCode.isSuccess(returnCode)

            if (success) {
                Log.i(tag, "Video cleaned successfully with FFmpegKit: $outputPath")
                true
            } else {
                Log.w(tag, "Transcode pass exited with code $returnCode. Retrying fast remux pass...")
                val fallbackSession = com.arthenica.ffmpegkit.FFmpegKit.execute("-y -i \"$inputPath\" -map_metadata -1 -c copy \"$outputPath\"")
                com.arthenica.ffmpegkit.ReturnCode.isSuccess(fallbackSession.returnCode)
            }
        } catch (e: Exception) {
            Log.e(tag, "Video processing failed: ${e.message}", e)
            false
        }
    }

    override fun cleanImage(
        inputPath: String,
        outputPath: String,
        stripExif: Boolean
    ): Boolean {
        return try {
            val py = Python.getInstance()
            val imgModule = py.getModule("veilframe.image.cleaner")
            val cleanerClass = imgModule.get("ImageCleaner")
            val cleaner = cleanerClass?.call(*emptyArray())
            cleaner?.callAttr("clean_image", inputPath, outputPath)
            Log.i(tag, "Image cleaned successfully: $outputPath")
            true
        } catch (e: Exception) {
            Log.e(tag, "Image processing failed: ${e.message}", e)
            false
        }
    }

    override fun getBackendDiagnostics(): String {
        return try {
            val py = Python.getInstance()
            val sysModule = py.getModule("sys")
            val version = sysModule.get("version")?.toString() ?: "unknown"
            "Chaquopy Python $version | Pluggable AndroidMediaBackend active"
        } catch (e: Exception) {
            "Fallback AndroidMediaBackend (Chaquopy inactive: ${e.message})"
        }
    }
}
