package com.veilframe.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoThumbnailPolicyTest {
    @Test
    fun scales4KFrameToPreviewSize() {
        assertEquals(1280 to 720, VideoThumbnailPolicy.fitWithin(3840, 2160))
    }

    @Test
    fun scalesPortraitVideoWithoutDistortion() {
        assertEquals(720 to 1280, VideoThumbnailPolicy.fitWithin(1080, 1920))
    }

    @Test
    fun preservesSmallVideoDimensions() {
        assertEquals(640 to 360, VideoThumbnailPolicy.fitWithin(640, 360))
    }

    @Test
    fun handlesExtremePortraitRatiosWithoutZeroDimensions() {
        assertEquals(1 to 1280, VideoThumbnailPolicy.fitWithin(1, 100000))
    }

    @Test
    fun acceptsScaledFrameWithMatchingAspectRatio() {
        assertTrue(VideoThumbnailPolicy.matchesSourceAspect(3840, 2160, 1280, 720))
        assertTrue(VideoThumbnailPolicy.matchesSourceAspect(1080, 1920, 720, 1280))
        assertTrue(VideoThumbnailPolicy.matchesSourceAspect(1920, 1080, 1279, 720))
    }

    @Test
    fun rejectsRotatedOrCorruptScaledFrameBeforeRedactionMapping() {
        assertFalse(VideoThumbnailPolicy.matchesSourceAspect(3840, 2160, 720, 1280))
        assertFalse(VideoThumbnailPolicy.matchesSourceAspect(3840, 2160, 0, 720))
        assertFalse(VideoThumbnailPolicy.matchesSourceAspect(0, 2160, 1280, 720))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDimensions() {
        VideoThumbnailPolicy.fitWithin(0, 1080)
    }
}
