package com.veilframe.app.media.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhatsappStatusPipelineTest {

    @Test
    fun testRateControlExactValues() {
        val hd = WhatsappStatusResolution.HD_720P
        val fhd = WhatsappStatusResolution.FHD_1080P

        assertEquals(1900, WhatsappStatusRateControl.baseMaxRate(hd))
        assertEquals(3800, WhatsappStatusRateControl.baseMaxRate(fhd))

        // 4.0s (<6s) -> base / 2
        assertEquals(950, WhatsappStatusRateControl.calculateBufSize(1900, 4.0))
        assertEquals(1900, WhatsappStatusRateControl.calculateBufSize(3800, 4.0))

        // 8.0s (6-11s) -> base / 1.5
        assertEquals(1266, WhatsappStatusRateControl.calculateBufSize(1900, 8.0))
        assertEquals(2533, WhatsappStatusRateControl.calculateBufSize(3800, 8.0))

        // 14.0s (11-16s) -> base
        assertEquals(1900, WhatsappStatusRateControl.calculateBufSize(1900, 14.0))
        assertEquals(3800, WhatsappStatusRateControl.calculateBufSize(3800, 14.0))

        // 20.0s (>=16s) -> base * 1.5
        assertEquals(2850, WhatsappStatusRateControl.calculateBufSize(1900, 20.0))
        assertEquals(5700, WhatsappStatusRateControl.calculateBufSize(3800, 20.0))
    }

    @Test
    fun testRateControlExactBoundaries() {
        val base = 1900

        // Boundary: 5.999s vs 6.000s
        assertEquals(950, WhatsappStatusRateControl.calculateBufSize(base, 5.999))
        assertEquals(1266, WhatsappStatusRateControl.calculateBufSize(base, 6.000))

        // Boundary: 10.999s vs 11.000s
        assertEquals(1266, WhatsappStatusRateControl.calculateBufSize(base, 10.999))
        assertEquals(1900, WhatsappStatusRateControl.calculateBufSize(base, 11.000))

        // Boundary: 15.999s vs 16.000s
        assertEquals(1900, WhatsappStatusRateControl.calculateBufSize(base, 15.999))
        assertEquals(2850, WhatsappStatusRateControl.calculateBufSize(base, 16.000))
    }

    @Test
    fun testStage1ArgumentConstruction() {
        val src = File("input.mp4")
        val temp = File("temp_inter.mp4")

        val args = WhatsappStatusCommandBuilder.buildStage1Arguments(
            srcFile = src,
            tempIntermediateFile = temp,
            trimStartSec = 2.5,
            trimDurationSec = 10.0
        )

        val joined = args.joinToString(" ")
        assertTrue(joined.contains("-y"))
        assertTrue(joined.contains("-ss 2.500"))
        assertTrue(joined.contains("-t 10.000"))
        assertTrue(joined.contains("-i ${src.absolutePath}"))
        assertTrue(joined.contains("-map 0:v:0"))
        assertTrue(joined.contains("-map 0:a?"))
        assertTrue(joined.contains("-c copy"))
        assertTrue(joined.contains("-movflags +faststart"))
        assertTrue(joined.endsWith(temp.absolutePath))
    }

    @Test
    fun testStage2ArgumentConstructionAndRateControlPlacement() {
        val temp = File("temp_inter.mp4")
        val out = File("final_status.mp4")
        val filterGraph = "scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,format=yuv420p[vout]"

        val baseMaxRate = 1900
        val durationBufSize = 1266

        val args = WhatsappStatusCommandBuilder.buildStage2Arguments(
            tempIntermediateFile = temp,
            outFile = out,
            filterGraph = filterGraph,
            baseMaxRateKbps = baseMaxRate,
            bufSizeKbps = durationBufSize,
            isMuted = false
        )

        val joined = args.joinToString(" ")

        // Crucial requirement: -maxrate gets base, -bufsize gets duration calculated
        assertTrue("Expected -maxrate 1900k", joined.contains("-maxrate 1900k"))
        assertTrue("Expected -bufsize 1266k", joined.contains("-bufsize 1266k"))
        assertFalse("Calculated bufsize must NOT be in -maxrate", joined.contains("-maxrate 1266k"))

        // Standard properties
        assertTrue(joined.contains("-c:v libx264"))
        assertTrue(joined.contains("-crf 23"))
        assertTrue(joined.contains("-r 29.97"))
        assertTrue(joined.contains("-c:a aac"))
        assertTrue(joined.contains("-ar 44100"))
        assertTrue(joined.contains("-b:a 128k"))
        assertTrue(joined.contains("-color_primaries bt709"))
        assertTrue(joined.contains("-color_trc bt709"))
        assertTrue(joined.contains("-colorspace bt709"))
        assertTrue(joined.contains("-movflags +faststart"))
        assertTrue(joined.contains("-map [vout]"))
        assertTrue(joined.contains("-map 0:a?"))
    }

    @Test
    fun testStage2MuteOmission() {
        val temp = File("temp_inter.mp4")
        val out = File("final_status_muted.mp4")
        val filterGraph = "[0:v]format=yuv420p[vout]"

        val args = WhatsappStatusCommandBuilder.buildStage2Arguments(
            tempIntermediateFile = temp,
            outFile = out,
            filterGraph = filterGraph,
            baseMaxRateKbps = 3800,
            bufSizeKbps = 5700,
            isMuted = true
        )

        val joined = args.joinToString(" ")
        assertTrue(joined.contains("-an"))
        assertFalse(joined.contains("-map 0:a?"))
        assertFalse(joined.contains("-b:a 128k"))
    }

    @Test
    fun testBoundedDimensionsOriginalAspect() {
        // 16:9 Landscape
        val hd169 = WhatsappStatusResolution.HD_720P.calculateBoundedDimensions(1920, 1080, "Original")
        assertEquals(1280 to 720, hd169)
        val fhd169 = WhatsappStatusResolution.FHD_1080P.calculateBoundedDimensions(1920, 1080, "Original")
        assertEquals(1920 to 1080, fhd169)

        // 9:16 Portrait
        val hd916 = WhatsappStatusResolution.HD_720P.calculateBoundedDimensions(1080, 1920, "Original")
        assertEquals(720 to 1280, hd916)
        val fhd916 = WhatsappStatusResolution.FHD_1080P.calculateBoundedDimensions(1080, 1920, "Original")
        assertEquals(1080 to 1920, fhd916)

        // 1:1 Square
        val hd11 = WhatsappStatusResolution.HD_720P.calculateBoundedDimensions(1080, 1080, "Original")
        assertEquals(720 to 720, hd11)
        val fhd11 = WhatsappStatusResolution.FHD_1080P.calculateBoundedDimensions(1080, 1080, "Original")
        assertEquals(1080 to 1080, fhd11)

        // 4:5 Portrait
        val hd45 = WhatsappStatusResolution.HD_720P.calculateBoundedDimensions(1080, 1350, "Original")
        assertEquals(720 to 900, hd45)
        val fhd45 = WhatsappStatusResolution.FHD_1080P.calculateBoundedDimensions(1080, 1350, "Original")
        assertEquals(1080 to 1350, fhd45)

        // 4:3 Landscape
        val hd43 = WhatsappStatusResolution.HD_720P.calculateBoundedDimensions(1440, 1080, "Original")
        assertEquals(960 to 720, hd43)
        val fhd43 = WhatsappStatusResolution.FHD_1080P.calculateBoundedDimensions(1440, 1080, "Original")
        assertEquals(1440 to 1080, fhd43)

        // Arbitrary resolution (1437x891): must produce even dimensions strictly within bounds
        val hdArb = WhatsappStatusResolution.HD_720P.calculateBoundedDimensions(1437, 891, "Original")
        assertEquals(1160 to 720, hdArb)
        assertEquals(0, hdArb.first % 2)
        assertEquals(0, hdArb.second % 2)
        assertTrue(hdArb.first <= 1280 && hdArb.second <= 720)

        val fhdArb = WhatsappStatusResolution.FHD_1080P.calculateBoundedDimensions(1437, 891, "Original")
        assertEquals(1742 to 1080, fhdArb)
        assertEquals(0, fhdArb.first % 2)
        assertEquals(0, fhdArb.second % 2)
        assertTrue(fhdArb.first <= 1920 && fhdArb.second <= 1080)
    }

    @Test
    fun testFilterGraphSdrAndHdr() {
        // SDR HD with Original aspect: preserves DAR, no crop
        val sdrHd = WhatsappStatusFilterGraphBuilder.buildVideoFilterGraph(
            resolution = WhatsappStatusResolution.HD_720P,
            isHdr = false,
            aspect = "Original",
            srcWidth = 1920,
            srcHeight = 1080
        )
        assertTrue(sdrHd.contains("setsar=1"))
        assertTrue(sdrHd.contains("scale=1280:720"))
        assertFalse(sdrHd.contains("crop="))
        assertTrue(sdrHd.contains("format=yuv420p[vout]"))
        assertFalse(sdrHd.contains("zscale"))

        // SDR FHD with explicit 9:16 aspect: crops to 9:16 then scales
        val sdrFhd916 = WhatsappStatusFilterGraphBuilder.buildVideoFilterGraph(
            resolution = WhatsappStatusResolution.FHD_1080P,
            isHdr = false,
            aspect = "9:16",
            srcWidth = 1920,
            srcHeight = 1080
        )
        assertTrue(sdrFhd916.contains("setsar=1"))
        assertTrue(sdrFhd916.contains("crop="))
        assertTrue(sdrFhd916.contains("scale=1080:1920"))
        assertTrue(sdrFhd916.contains("format=yuv420p[vout]"))

        // HDR
        val hdr = WhatsappStatusFilterGraphBuilder.buildVideoFilterGraph(
            resolution = WhatsappStatusResolution.HD_720P,
            isHdr = true,
            aspect = "Original",
            srcWidth = 1920,
            srcHeight = 1080
        )
        assertTrue(hdr.contains("zscale=t=linear:npl=100"))
        assertTrue(hdr.contains("format=gbrpf32le"))
        assertTrue(hdr.contains("zscale=p=bt709"))
        assertTrue(hdr.contains("tonemap=tonemap=hable:desat=0"))
        assertTrue(hdr.contains("zscale=t=bt709:m=bt709:r=tv"))
        assertTrue(hdr.contains("setsar=1"))
        assertTrue(hdr.contains("scale=1280:720"))
    }

    @Test
    fun testPhotoToStatusFilterAndCommand() {
        val img = File("photo.jpg")
        val out = File("status_photo.mp4")
        val filter = WhatsappStatusFilterGraphBuilder.buildPhotoStatusFilterGraph(WhatsappStatusResolution.HD_720P)

        val args = WhatsappStatusCommandBuilder.buildPhotoStatusArguments(
            inputImageFile = img,
            outFile = out,
            filterGraph = filter
        )

        val joined = args.joinToString(" ")
        assertTrue(joined.contains("-loop 1"))
        assertTrue(joined.contains("-t 5.0"))
        assertTrue(joined.contains("-c:v libx264"))
        assertTrue(joined.contains("-crf 23"))
        assertTrue(joined.contains("-maxrate 1600k"))
        assertTrue(joined.contains("-bufsize 1600k"))
        assertTrue(joined.contains("-r 29.97"))
        assertTrue(joined.contains("-movflags +faststart"))
    }
}
