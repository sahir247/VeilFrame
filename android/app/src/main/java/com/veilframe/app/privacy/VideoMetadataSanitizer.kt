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
            val isWebm = outputPath.endsWith(".webm", ignoreCase = true)
            val isInputWebm = inputPath.endsWith(".webm", ignoreCase = true)

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
                if (isWebm) {
                    cmd.add("-c:a")
                    cmd.add("libopus")
                } else {
                    cmd.add("-c:a")
                    cmd.add("copy")
                }
            }

            val vCodec = if (isWebm) "libvpx-vp9" else "libx264"

            when (noiseLevel.lowercase()) {
                "high", "stealth", "aggressive" -> {
                    cmd.add("-vf")
                    cmd.add("noise=alls=8:allf=t+u")
                    cmd.add("-c:v")
                    cmd.add(vCodec)
                    if (isWebm) {
                        cmd.add("-b:v")
                        cmd.add("0")
                        cmd.add("-crf")
                        cmd.add("32")
                    } else {
                        cmd.add("-preset")
                        cmd.add("veryfast")
                        cmd.add("-crf")
                        cmd.add("23")
                    }
                }
                "medium" -> {
                    cmd.add("-vf")
                    cmd.add("noise=alls=4:allf=t")
                    cmd.add("-c:v")
                    cmd.add(vCodec)
                    if (isWebm) {
                        cmd.add("-b:v")
                        cmd.add("0")
                        cmd.add("-crf")
                        cmd.add("30")
                    } else {
                        cmd.add("-preset")
                        cmd.add("veryfast")
                        cmd.add("-crf")
                        cmd.add("22")
                    }
                }
                "low" -> {
                    cmd.add("-vf")
                    cmd.add("noise=alls=2:allf=t")
                    cmd.add("-c:v")
                    cmd.add(vCodec)
                    if (isWebm) {
                        cmd.add("-b:v")
                        cmd.add("0")
                        cmd.add("-crf")
                        cmd.add("28")
                    } else {
                        cmd.add("-preset")
                        cmd.add("veryfast")
                        cmd.add("-crf")
                        cmd.add("20")
                    }
                }
                else -> {
                    if (isWebm && !isInputWebm) {
                        cmd.add("-c:v")
                        cmd.add("libvpx-vp9")
                        cmd.add("-b:v")
                        cmd.add("0")
                        cmd.add("-crf")
                        cmd.add("30")
                    } else {
                        cmd.add("-c:v")
                        cmd.add("copy")
                    }
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
                Log.w(TAG, "Transcode pass exited with code $returnCode. Retrying fallback pass...")
                val fallbackCmd = if (isWebm) {
                    val aFlag = if (scrubAudio) "-an" else "-c:a libopus"
                    "-y -i \"$inputPath\" -map_metadata -1 -map_chapters -1 -c:v libvpx-vp9 -b:v 0 -crf 32 $aFlag \"$outputPath\""
                } else {
                    val aFlag = if (scrubAudio) "-an" else "-c:a copy"
                    "-y -i \"$inputPath\" -map_metadata -1 -map_chapters -1 -c:v copy $aFlag \"$outputPath\""
                }
                val fallbackSession = FFmpegKit.execute(fallbackCmd)
                ReturnCode.isSuccess(fallbackSession.returnCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video processing failed: ${e.message}", e)
            false
        }
    }
}
