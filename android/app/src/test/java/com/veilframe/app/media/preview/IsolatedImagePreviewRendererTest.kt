package com.veilframe.app.media.preview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiDetection
import com.veilframe.app.privacy.PiiRedactionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IsolatedImagePreviewRendererTest {
    @Test fun enablingAndClearingSolidRedactionMustNotCorruptOriginalPixels() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val redacted = ImageEditState(
            piiDetections = listOf(
                PiiDetection(RectF(8f, 8f, 56f, 56f), PiiClass.FACE, 0.99f)
            ),
            piiRedactionStyle = PiiRedactionStyle.SOLID_BLACK
        )
        val censored = IsolatedImagePreviewRenderer.render(source, redacted, 64, 64)
        try {
            assertEquals(Color.BLACK, censored.getPixel(32, 32))
            assertEquals(Color.RED, source.getPixel(32, 32))
            val uncensored = IsolatedImagePreviewRenderer.render(source, ImageEditState(), 64, 64)
            try {
                assertEquals(Color.RED, uncensored.getPixel(32, 32))
                assertEquals(Color.RED, source.getPixel(32, 32))
                assertFalse(source.isRecycled)
            } finally { uncensored.recycle() }
        } finally {
            censored.recycle()
            source.recycle()
        }
    }

    @Test fun independentlyOwnedPreviewMayBeReleasedWithoutDestroyingTheSource() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val one = IsolatedImagePreviewRenderer.render(source, ImageEditState(), 64, 64)
        one.recycle()
        assertFalse(source.isRecycled)
        val two = IsolatedImagePreviewRenderer.render(source, ImageEditState(), 64, 64)
        assertFalse(two.isRecycled)
        two.recycle()
        source.recycle()
    }
}
