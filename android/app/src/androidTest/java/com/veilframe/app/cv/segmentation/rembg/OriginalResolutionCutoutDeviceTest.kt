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
    @Test fun trimUsesOriginalSourceAlphaAcrossStripBoundaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "rembg_trim").toFile()
        val width = 1024
        val height = 1100
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            input.eraseColor(Color.TRANSPARENT)
            input.setPixel(100, 499, Color.RED)
            input.setPixel(120, 1025, Color.argb(128, 0, 255, 0))
            val source = File(dir, "source.png")
            source.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val output = File(dir, "output.png")
            OriginalResolutionCutout.export(RembgImageSource.Staged(source),
                RembgSourceInfo(width, height, "image/png", source.length()),
                RembgAlphaMatte.fromAlpha(1, 1, byteArrayOf(255.toByte())), output, trim = true)
            val decoded = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                assertEquals(21, decoded.width)
                assertEquals(527, decoded.height)
                assertEquals(Color.RED, decoded.getPixel(0, 0))
                assertEquals(Color.argb(128, 0, 255, 0), decoded.getPixel(20, 526))
                assertEquals(Color.TRANSPARENT, decoded.getPixel(7, 9))
            } finally { decoded.recycle() }
        } finally {
            input.recycle()
            dir.deleteRecursively()
        }
    }

    @Test fun trimPrecedesOpaqueBackdropAndKeepsOriginalPixelCoordinates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "rembg_backdrop").toFile()
        val input = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        try {
            input.eraseColor(Color.TRANSPARENT)
            input.setPixel(2, 2, Color.RED)
            input.setPixel(5, 4, Color.argb(128, 255, 0, 0))
            val source = File(dir, "source.png")
            source.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val output = File(dir, "output.png")
            OriginalResolutionCutout.export(RembgImageSource.Staged(source),
                RembgSourceInfo(8, 6, "image/png", source.length()),
                RembgAlphaMatte.fromAlpha(1, 1, byteArrayOf(255.toByte())), output,
                trim = true, backdrop = CutoutBackdrop.WHITE)
            val decoded = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                assertEquals(4, decoded.width)
                assertEquals(3, decoded.height)
                assertEquals(Color.RED, decoded.getPixel(0, 0))
                assertEquals(Color.WHITE, decoded.getPixel(1, 1))
                assertEquals(0xFFFF7F7F.toInt(), decoded.getPixel(3, 2))
            } finally { decoded.recycle() }
        } finally { input.recycle(); dir.deleteRecursively() }
    }

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
