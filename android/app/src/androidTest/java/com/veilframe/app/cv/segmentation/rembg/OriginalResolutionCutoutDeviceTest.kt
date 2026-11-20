package com.veilframe.app.cv.segmentation.rembg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OriginalResolutionCutoutDeviceTest {
    @Test fun multipleStripsKeepOriginalColorAndSourceAlpha() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "rembg_strips").toFile()
        val width = 1024
        val height = 600
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            input.eraseColor(Color.RED)
            input.setPixel(100, 550, Color.argb(128, 0, 255, 0))
            input.setPixel(900, 550, Color.TRANSPARENT)
            val source = File(dir, "source.png")
            source.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val output = File(dir, "output.png")
            OriginalResolutionCutout.export(RembgImageSource.Staged(source),
                RembgSourceInfo(width, height, "image/png", source.length()),
                RembgAlphaMatte.fromAlpha(1, 1, byteArrayOf(255.toByte())), output)
            val decoded = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                assertEquals(width, decoded.width)
                assertEquals(height, decoded.height)
                assertEquals(Color.RED, decoded.getPixel(20, 20))
                assertEquals(Color.argb(128, 0, 255, 0), decoded.getPixel(100, 550))
                assertEquals(Color.TRANSPARENT, decoded.getPixel(900, 550))
            } finally { decoded.recycle() }
        } finally {
            input.recycle()
            dir.deleteRecursively()
        }
    }
}
