package com.veilframe.app.upscale

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.upscale.inference.UriRegionSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UriRegionSourceDeviceTest {

    @Test
    fun fileUriDecodesOwnedRegionsWithoutFullSourceBitmap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.cacheDir, "region_source_" + System.nanoTime() + ".png")
        val original = Bitmap.createBitmap(11, 7, Bitmap.Config.ARGB_8888)
        try {
            original.eraseColor(Color.BLACK)
            original.setPixel(7, 4, Color.rgb(30, 90, 130))
            fixture.outputStream().use { out ->
                assertTrue("PNG fixture encoding failed", original.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
            val source = UriRegionSource(context, Uri.fromFile(fixture), 11, 7)
            try {
                val crop = source.region(5, 3, 4, 3)
                try {
                    assertEquals(4, crop.width)
                    assertEquals(3, crop.height)
                    assertEquals(Color.rgb(30, 90, 130), crop.getPixel(2, 1))
                } finally {
                    crop.recycle()
                }
                val second = source.region(7, 4, 1, 1)
                try {
                    assertEquals(Color.rgb(30, 90, 130), second.getPixel(0, 0))
                } finally {
                    second.recycle()
                }
                try {
                    source.region(10, 5, 2, 1)
                    fail("Out-of-bounds region should be rejected before decoding")
                } catch (_: IllegalArgumentException) {
                    // Expected.
                }
            } finally {
                source.close()
            }
            try {
                source.region(0, 0, 1, 1)
                fail("Closed decoder must not be usable")
            } catch (_: IllegalStateException) {
                // Expected.
            }
        } finally {
            original.recycle()
            fixture.delete()
        }
    }

    @Test
    fun corruptInputDoesNotPretendToHaveReadableAlphaPixels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = File(context.cacheDir, "corrupt_source_" + System.nanoTime() + ".png")
        fixture.writeText("not an image")
        try {
            UriRegionSource(context, Uri.fromFile(fixture), 11, 7).use { source ->
                try {
                    source.hasAlpha
                    fail("Corrupt region image must not be silently accepted")
                } catch (_: Exception) {
                    // Provider/decoder failure; VM Errors must not be swallowed.
                }
            }
        } finally {
            fixture.delete()
        }
    }
}
