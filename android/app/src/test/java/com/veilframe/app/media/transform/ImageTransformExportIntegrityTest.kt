package com.veilframe.app.media.transform

import android.graphics.Bitmap
import android.graphics.Color
import com.veilframe.app.media.ImageEditState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageTransformExportIntegrityTest {
    @Test fun unknownFilterCannotSilentlyExportUnfilteredPhoto() {
        val src = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        try {
            val edit = ImageEditState(filter = "Nonexistent Future Filter")
            assertThrows(IllegalStateException::class.java) {
                ImageTransformEngine.transform(src, edit, 96, 96, useFullRes = true)
            }
            assertEquals(Color.GREEN, src.getPixel(40, 40))
        } finally { src.recycle() }
    }

    @Test fun previewCanDegradeGracefullyWithoutPublishingWrongExport() {
        val src = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        try {
            val preview = ImageTransformEngine.transform(
                src, ImageEditState(filter = "Nonexistent Future Filter"),
                96, 96, useFullRes = false
            )
            assertEquals(Color.GREEN, preview.getPixel(40, 40))
            if (preview !== src) preview.recycle()
        } finally { src.recycle() }
    }
}
