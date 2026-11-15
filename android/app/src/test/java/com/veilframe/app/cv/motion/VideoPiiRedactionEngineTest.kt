package com.veilframe.app.cv.motion

import android.graphics.RectF
import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiDetection
import com.veilframe.app.privacy.PiiRedactionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests verifying [VideoPiiRedactionEngine]:
 * 1. Motion-aware track association & swept sub-interval interpolation for moving targets.
 * 2. Transient detector gap bridging across short frame dropouts.
 * 3. Stationary track coalescing to keep FFmpeg filter graphs compact.
 * 4. Budget compression without silently dropping late-video PII detections.
 * 5. FFmpeg drawbox/avgblur/pixelate parameter clamping and multi-class PII isolation.
 */
@RunWith(RobolectricTestRunner::class)
class VideoPiiRedactionEngineTest {

    @Test
    fun testAnyFrameOrDetectorFailureMakesScanIncomplete() {
        assertTrue(
            VideoPiiRedactionEngine.isScanIncomplete(
                attemptedFrames = 120,
                sampledFrames = 119,
                failedFrameExtractions = 1,
                failedEngines = emptyMap(),
                cancelled = false
            )
        )
        assertTrue(
            VideoPiiRedactionEngine.isScanIncomplete(
                attemptedFrames = 120,
                sampledFrames = 120,
                failedFrameExtractions = 0,
                failedEngines = mapOf("OCR" to "timeout"),
                cancelled = false
            )
        )
        assertTrue(
            VideoPiiRedactionEngine.isScanIncomplete(
                attemptedFrames = 120,
                sampledFrames = 20,
                failedFrameExtractions = 0,
                failedEngines = emptyMap(),
                cancelled = true
            )
        )
        assertFalse(
            VideoPiiRedactionEngine.isScanIncomplete(
                attemptedFrames = 120,
                sampledFrames = 120,
                failedFrameExtractions = 0,
                failedEngines = emptyMap(),
                cancelled = false
            )
        )
    }

    @Test
    fun testMovingTargetSubdividesWithSweptCoverage() {
        // Face moves rapidly from x=100..220 at t=0.0s to x=900..1020 at t=1.2s
        val frames = listOf(
            0.0 to listOf(PiiDetection(RectF(100f, 200f, 220f, 360f), PiiClass.FACE, 0.92f, "Face")),
            0.4 to listOf(PiiDetection(RectF(360f, 200f, 480f, 360f), PiiClass.FACE, 0.90f, "Face")),
            0.8 to listOf(PiiDetection(RectF(630f, 200f, 750f, 360f), PiiClass.FACE, 0.91f, "Face")),
            1.2 to listOf(PiiDetection(RectF(900f, 200f, 1020f, 360f), PiiClass.FACE, 0.93f, "Face"))
        )

        val (regions, trackCount) = VideoPiiRedactionEngine.buildTrackedRegions(
            frames = frames,
            frameW = 1920,
            frameH = 1080,
            durationSec = 1.5,
            sampleStepSec = 0.4
        )

        assertEquals("Should associate rapid linear movement into 1 continuous track", 1, trackCount)
        assertTrue("Moving track should subdivide into multiple swept intervals", regions.size > 4)
        assertEquals(0.0, regions.first().startSec, 0.05)
        assertTrue("End of track should extend past last keyframe", regions.maxOf { it.endSec } >= 1.2)
        // Verify intervals progress left to right across X
        assertTrue(regions.last().rect.centerX() > regions.first().rect.centerX() + 500f)
    }

    @Test
    fun testBridgesShortDetectorDropoutGap() {
        // Plate detected at t=0.0s and t=0.8s (missed on middle frame at t=0.4s)
        val frames = listOf(
            0.0 to listOf(PiiDetection(RectF(400f, 500f, 620f, 580f), PiiClass.PLATE, 0.85f, "Plate")),
            0.4 to emptyList(),
            0.8 to listOf(PiiDetection(RectF(440f, 505f, 660f, 585f), PiiClass.PLATE, 0.86f, "Plate"))
        )

        val (regions, trackCount) = VideoPiiRedactionEngine.buildTrackedRegions(
            frames = frames,
            frameW = 1920,
            frameH = 1080,
            durationSec = 1.2,
            sampleStepSec = 0.4
        )

        assertEquals("Short 0.8s dropout must bridge into 1 track", 1, trackCount)
        // Coverage must span cleanly across the missing t=0.4s window
        val coversMiddle = regions.any { it.startSec <= 0.4 && it.endSec >= 0.4 }
        assertTrue("Middle missed keyframe at t=0.4s must remain continuously masked", coversMiddle)
    }

    @Test
    fun testStationaryScreenAndOcrTextCoalesceCompactly() {
        // Static screen & sensitive text email stays still across 8 keyframes
        val screenBox = RectF(300f, 200f, 900f, 650f)
        val ocrBox = RectF(350f, 300f, 650f, 340f)
        val frames = (0 until 8).map { i ->
            val t = i * 0.5
            t to listOf(
                PiiDetection(RectF(screenBox), PiiClass.SCREEN, 0.94f, "Laptop Screen"),
                PiiDetection(RectF(ocrBox), PiiClass.TEXT_PII, 0.90f, "Email Address")
            )
        }

        val (regions, trackCount) = VideoPiiRedactionEngine.buildTrackedRegions(
            frames = frames,
            frameW = 1920,
            frameH = 1080,
            durationSec = 4.0,
            sampleStepSec = 0.5
        )

        assertEquals("Two distinct static classes should produce 2 tracks", 2, trackCount)
        val screenRegions = regions.filter { it.piiClass == PiiClass.SCREEN }
        val textRegions = regions.filter { it.piiClass == PiiClass.TEXT_PII }
        assertEquals("Static screen should coalesce into 1 FFmpeg interval", 1, screenRegions.size)
        assertEquals("Static OCR text should coalesce into 1 FFmpeg interval", 1, textRegions.size)
        assertTrue(screenRegions[0].endSec >= 3.5)
        assertTrue(textRegions[0].endSec >= 3.5)
    }

    @Test
    fun testNeverDropsLateVideoDetectionsOverLegacy36FilterLimit() {
        // Create 60 non-overlapping regions distributed across 0s..30s
        val manyRegions = (0 until 60).map { idx ->
            val start = idx * 0.5
            val end = start + 0.45
            val x = 100f + (idx % 10) * 150f
            TimedPiiRegion(
                startSec = start,
                endSec = end,
                rect = RectF(x, 200f, x + 80f, 300f),
                piiClass = PiiClass.FACE,
                label = "Face"
            )
        }

        // Request compact maxFilters=36 -> must coalesce earlier neighbors rather than dropping idx 36..59!
        val filter = VideoPiiRedactionEngine.generateFfmpegRedactionFilters(
            regions = manyRegions,
            frameW = 1920,
            frameH = 1080,
            style = PiiRedactionStyle.SOLID_BLACK,
            maxFilters = 36
        )

        assertNotNull(filter)
        val entries = filter!!.split("drawbox=").drop(1)
        assertTrue("Filter count (${entries.size}) must stay within maxFilters=36", entries.size <= 36)
        // Crucial privacy invariant: the late timestamp (~29.50-29.95s) must NOT be silently dropped
        val maxEndTime = manyRegions.maxOf { it.endSec }
        assertTrue(
            "Late timestamp ~${maxEndTime}s must remain protected in filter graph: $filter",
            filter.contains("29.9") || filter.contains("29.5") || filter.contains("30.0")
        )
    }

    @Test
    fun testGenerateFfmpegRedactionFiltersBoundsClampingAndStyles() {
        // Edge-touching box near frame border to exercise padding, clipping & bounds
        val edgeRegion = TimedPiiRegion(
            startSec = 0.0,
            endSec = 2.5,
            rect = RectF(0f, 0f, 1920f, 1080f),
            piiClass = PiiClass.BARCODE,
            label = "QR Code"
        )

        val blurFilter = VideoPiiRedactionEngine.generateFfmpegRedactionFilters(
            regions = listOf(edgeRegion),
            frameW = 1920,
            frameH = 1080,
            style = PiiRedactionStyle.BLUR
        )
        assertNotNull(blurFilter)
        assertTrue("Blur filter must use genuine avgblur rather than delogo: $blurFilter", blurFilter!!.contains("avgblur=sizeX=16:sizeY=16"))
        assertTrue("Blur filter must crop bounded region: $blurFilter", blurFilter.contains("crop=w=1920:h=1080:x=0:y=0"))
        assertTrue("Blur filter must overlay with temporal enable: $blurFilter", blurFilter.contains("enable='between(t,0.00,2.50)'"))

        val pixelateFilter = VideoPiiRedactionEngine.generateFfmpegRedactionFilters(
            regions = listOf(edgeRegion),
            frameW = 1920,
            frameH = 1080,
            style = PiiRedactionStyle.PIXELATE
        )
        assertNotNull(pixelateFilter)
        assertTrue("Pixelate filter must use nearest-neighbor down/up scale mosaic: $pixelateFilter", pixelateFilter!!.contains("flags=neighbor"))
        assertTrue("Pixelate filter must overlay with temporal enable: $pixelateFilter", pixelateFilter.contains("enable='between(t,0.00,2.50)'"))

        val blackFilter = VideoPiiRedactionEngine.generateFfmpegRedactionFilters(
            regions = listOf(edgeRegion),
            frameW = 1920,
            frameH = 1080,
            style = PiiRedactionStyle.SOLID_BLACK
        )
        assertTrue(blackFilter!!.startsWith("drawbox=x=0:y=0:w=1920:h=1080:color=black:t=fill"))
        assertTrue(blackFilter.contains("enable='between(t,0.00,2.50)'"))

        val whiteFilter = VideoPiiRedactionEngine.generateFfmpegRedactionFilters(
            regions = listOf(edgeRegion),
            frameW = 1920,
            frameH = 1080,
            style = PiiRedactionStyle.SOLID_WHITE
        )
        assertTrue(whiteFilter!!.contains("color=white:t=fill"))

        assertNull(
            VideoPiiRedactionEngine.generateFfmpegRedactionFilters(emptyList(), 1920, 1080)
        )
        assertFalse(blackFilter.contains("color=white"))
    }
}

