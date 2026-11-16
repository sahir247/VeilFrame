package com.veilframe.app.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoMetadataSanitizerTest {

    @Test
    fun `fallback result is successful but explicitly metadata only`() {
        val result = VideoSanitizationResult(VideoSanitizationStatus.METADATA_ONLY_FALLBACK)
        assertTrue(result.isSuccess)
        assertEquals(VideoSanitizationStatus.METADATA_ONLY_FALLBACK, result.status)
    }

    @Test
    fun `failed result is not successful`() {
        val result = VideoSanitizationResult(VideoSanitizationStatus.FAILED, "FFmpeg failed")
        assertFalse(result.isSuccess)
        assertEquals("FFmpeg failed", result.errorMessage)
    }

    @Test
    fun `test buildArguments strips metadata chapters and scrubs audio for mp4`() {
        val input = "/storage/emulated/0/DCIM/Camera/My Video (test's & special).mp4"
        val output = "/storage/emulated/0/Download/clean.mp4"

        val args = VideoMetadataSanitizer.buildArguments(
            inputPath = input,
            outputPath = output,
            noiseLevel = "medium",
            scrubAudio = true
        )

        assertEquals("-y", args[0])
        assertEquals("-i", args[1])
        assertEquals(input, args[2])
        assertTrue("Must include -map_metadata -1", args.contains("-map_metadata") && args[args.indexOf("-map_metadata") + 1] == "-1")
        assertTrue("Must include -map_chapters -1", args.contains("-map_chapters") && args[args.indexOf("-map_chapters") + 1] == "-1")
        assertTrue("Must include -an when audio scrubbed", args.contains("-an"))
        assertFalse("Must not include audio copy when scrubbed", args.contains("-c:a"))
        assertTrue("Must specify libx264 for mp4", args.contains("libx264"))
        assertTrue("Must specify noise filter for medium", args.contains("noise=alls=4:allf=t"))
        assertEquals(output, args.last())
    }

    @Test
    fun `test buildArguments handles webm output and preserves audio`() {
        val input = "/storage/emulated/0/DCIM/test.mp4"
        val output = "/storage/emulated/0/Download/clean.webm"

        val args = VideoMetadataSanitizer.buildArguments(
            inputPath = input,
            outputPath = output,
            noiseLevel = "high",
            scrubAudio = false
        )

        assertTrue(args.contains("-map_metadata"))
        assertFalse("Must not include -an when audio preserved", args.contains("-an"))
        assertTrue("Must encode opus for webm audio", args.contains("libopus"))
        assertTrue("Must encode libvpx-vp9 for webm video", args.contains("libvpx-vp9"))
        assertTrue("Must specify aggressive noise filter for high", args.contains("noise=alls=8:allf=t+u"))
        assertEquals(output, args.last())
    }

    @Test
    fun `test buildFallbackArguments strips metadata without complex filters`() {
        val input = "/storage/emulated/0/DCIM/test.mp4"
        val output = "/storage/emulated/0/Download/clean.mp4"

        val fallbackArgs = VideoMetadataSanitizer.buildFallbackArguments(
            inputPath = input,
            outputPath = output,
            scrubAudio = true
        )

        assertEquals("-y", fallbackArgs[0])
        assertEquals("-i", fallbackArgs[1])
        assertEquals(input, fallbackArgs[2])
        assertTrue(fallbackArgs.contains("-map_metadata"))
        assertTrue(fallbackArgs.contains("-map_chapters"))
        assertTrue(fallbackArgs.contains("-an"))
        assertTrue(fallbackArgs.contains("copy"))
        assertFalse("Fallback should not contain noise filter", fallbackArgs.contains("-vf"))
        assertEquals(output, fallbackArgs.last())
    }

    @Test
    fun `fallback respects disabled metadata stripping`() {
        val input = "/tmp/input.mp4"
        val output = "/tmp/output.mp4"

        val args = VideoMetadataSanitizer.buildFallbackArguments(
            inputPath = input,
            outputPath = output,
            scrubAudio = true,
            stripExif = false
        )

        assertFalse(args.contains("-map_metadata"))
        assertFalse(args.contains("-map_chapters"))
        assertTrue(args.contains("-an"))
        assertEquals(output, args.last())
    }

    @Test
    fun `fallback strips metadata when explicitly enabled`() {
        val args = VideoMetadataSanitizer.buildFallbackArguments(
            inputPath = "/tmp/input.mp4",
            outputPath = "/tmp/output.mp4",
            stripExif = true
        )

        assertTrue(args.contains("-map_metadata"))
        assertTrue(args.contains("-map_chapters"))
    }

    @Test
    fun `low noise preset emits exactly one video codec option for mp4 and webm`() {
        for (output in listOf("/tmp/out.mp4", "/tmp/out.webm")) {
            val args = VideoMetadataSanitizer.buildArguments(
                inputPath = "/tmp/input.mp4",
                outputPath = output,
                noiseLevel = "low"
            )
            val codecIndex = args.indexOf("-c:v")
            assertTrue(codecIndex >= 0)
            assertEquals(if (output.endsWith(".webm")) "libvpx-vp9" else "libx264", args[codecIndex + 1])
            assertEquals("The codec must appear only as the value of -c:v", 1, args.count { it == "libx264" || it == "libvpx-vp9" })
            assertEquals(output, args.last())
        }
    }

    @Test
    fun `test buildArguments handles space and special characters safely as individual tokens`() {
        val weirdPath = "/storage/emulated/0/Weird Dir/name with \"quotes\" & 'apostrophes'; rm -rf.mp4"
        val outputPath = "/storage/emulated/0/Clean Dir/out.mp4"

        val args = VideoMetadataSanitizer.buildArguments(weirdPath, outputPath)
        // With argument arrays, paths must NOT be wrapped with escaped quotes
        assertEquals(weirdPath, args[2])
        assertEquals(outputPath, args.last())
    }
}
