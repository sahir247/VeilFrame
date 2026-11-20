package com.veilframe.app.cv.segmentation.rembg

import com.veilframe.app.media.refine.CutoutStroke

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
    @Test fun originalJpegUsesTheSameCropAndBackdropPipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "rembg_jpeg").toFile()
        val input = Bitmap.createBitmap(128, 96, Bitmap.Config.ARGB_8888)
        try {
            input.eraseColor(Color.TRANSPARENT)
            for (y in 16 until 80) for (x in 16 until 112) {
                input.setPixel(x, y, Color.argb(128, 255, 0, 0))
            }
            val source = File(dir, "source.png")
            source.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val out = File(dir, "cutout.jpg")
            OriginalResolutionCutout.export(RembgImageSource.Staged(source),
                RembgSourceInfo(128, 96, "image/png", source.length()),
                RembgAlphaMatte.fromAlpha(1, 1, byteArrayOf(255.toByte())), out,
                trim = true, backdrop = CutoutBackdrop.WHITE, jpeg = true)
            val decoded = checkNotNull(BitmapFactory.decodeFile(out.absolutePath))
            try {
                assertEquals(96, decoded.width)
                assertEquals(64, decoded.height)
                val pixel = decoded.getPixel(40, 30)
                assertEquals(255, Color.alpha(pixel))
                assertTrue(kotlin.math.abs(Color.red(pixel) - 255) <= 4)
                assertTrue(kotlin.math.abs(Color.green(pixel) - 127) <= 4)
                assertTrue(kotlin.math.abs(Color.blue(pixel) - 127) <= 4)
            } finally { decoded.recycle() }
        } finally { input.recycle(); dir.deleteRecursively() }
    }

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

    @Test fun brushReplayRestoresOriginalAlphaAndErasesAcrossStripBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "rembg_replay").toFile()
        val width = 1024
        val height = 1024
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            input.eraseColor(Color.argb(128, 0, 255, 0))
            val source = File(dir, "source.png")
            source.outputStream().use { assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val output = File(dir, "output.png")
            val strokes = listOf(
                CutoutStroke(512, 512, 128f, 240f, 128f, 272f, 24f, false),
                CutoutStroke(512, 512, 128f, 256f, 128f, 256f, 8f, true)
            )
            OriginalResolutionCutout.export(RembgImageSource.Staged(source),
                RembgSourceInfo(width, height, "image/png", source.length()),
                RembgAlphaMatte.fromAlpha(1, 1, byteArrayOf(128.toByte())), output, strokes = strokes)
            val decoded = checkNotNull(BitmapFactory.decodeFile(output.absolutePath))
            try {
                assertEquals(64, Color.alpha(decoded.getPixel(10, 10)))
                assertEquals(0, Color.alpha(decoded.getPixel(256, 480)))
                assertEquals(0, Color.alpha(decoded.getPixel(256, 544)))
                assertEquals(Color.argb(128, 0, 255, 0), decoded.getPixel(256, 511))
                assertEquals(Color.argb(128, 0, 255, 0), decoded.getPixel(256, 512))
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
