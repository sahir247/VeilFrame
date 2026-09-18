package com.veilframe.app.privacy

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

/**
 * Native Video Privacy and Metadata Sanitization Engine.
 * Enforces container metadata scrubbing, chapter removal, audio scrubbing,
 * and optional PRNU camera sensor fingerprint noise masking via FFmpegKit.
 */
object VideoMetadataSanitizer {

    private const val TAG = "VeilFrame.VideoSanitizer"

    fun cleanVideo(
        inputPath: String,
        outputPath: String,
        noiseLevel: String = "medium",
        scrubAudio: Boolean = true
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
                "high", "stealth", "aggressive" -> {
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
                "low" -> {
                    cmd.add("-vf")
                    cmd.add("noise=alls=2:allf=t")
                    cmd.add("-c:v")
                    cmd.add("libx264")
                    cmd.add("-preset")
                    cmd.add("veryfast")
                    cmd.add("-crf")
                    cmd.add("20")
                }
                else -> {
                    cmd.add("-c:v")
                    cmd.add("copy")
                }
            }

            cmd.add(outputPath)

            val cmdString = cmd.joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }
            Log.d(TAG, "Executing video sanitization: $cmdString")

            val session = FFmpegKit.execute(cmdString)
            val returnCode = session.returnCode
            val success = ReturnCode.isSuccess(returnCode)

            if (success) {
                Log.i(TAG, "Video cleaned successfully with FFmpegKit: $outputPath")
                true
            } else {
                Log.w(TAG, "Transcode pass exited with code $returnCode. Retrying fast remux pass...")
                val fallbackSession = FFmpegKit.execute("-y -i \"$inputPath\" -map_metadata -1 -map_chapters -1 -c copy \"$outputPath\"")
                ReturnCode.isSuccess(fallbackSession.returnCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video processing failed: ${e.message}", e)
            false
        }
    }
}
