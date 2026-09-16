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
            val py = Python.getInstance()
            val coreModule = py.getModule("veilframe.core.pipeline")
            val pipelineClass = coreModule.get("Pipeline")
            val pipeline = pipelineClass?.callAttr("create_default")
            pipeline?.callAttr("clean_video", inputPath, outputPath)
            Log.i(tag, "Video cleaned successfully: $outputPath")
            true
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
            val cleaner = cleanerClass?.callAttr()
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
