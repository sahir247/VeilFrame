package com.veilframe.app.media

import org.junit.Assert.assertEquals
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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDimensions() {
        VideoThumbnailPolicy.fitWithin(0, 1080)
    }
}
