package com.veilframe.app.cv.motion

import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiRedactionStyle
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.pow

/**
 * Device-level integration test for the actual bundled FFmpegKit filters.
 *
 * Unit tests validate graph construction; this test also asks FFmpeg to render each style,
 * decodes the resulting video, and checks that the selected region was materially changed.
 */
@RunWith(AndroidJUnit4::class)
class VideoPiiFfmpegRenderTest {

    @Test
    fun allRedactionStylesRenderAndModifyTheTargetRegion() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "video_pii_ffmpeg_test").apply {
            deleteRecursively()
            mkdirs()
        }
        val source = File(dir, "source.mp4")
        val region = TimedPiiRegion(
            startSec = 0.0,
            endSec = 0.95,
            rect = RectF(80f, 60f, 240f, 180f),
            piiClass = PiiClass.FACE,
            label = "test region"
        )

        try {
            val sourceSession = FFmpegKit.executeWithArguments(
                arrayOf(
                    "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=10:duration=1",
                    "-an", "-c:v", "mpeg4", source.absolutePath
                )
            )
            assertTrue(
                "FFmpeg test source generation failed: ${sourceSession.allLogsAsString}",
                ReturnCode.isSuccess(sourceSession.returnCode) && source.exists() && source.length() > 0L
            )

            val original = decodeFrame(source)
                ?: throw AssertionError("Generated source video must decode")
            val baselineVariance = lumaVariance(original, 80, 60, 240, 180)

            for (style in listOf(
                PiiRedactionStyle.SOLID_BLACK,
                PiiRedactionStyle.SOLID_WHITE,
                PiiRedactionStyle.BLUR,
                PiiRedactionStyle.PIXELATE
            )) {
                val filter = VideoPiiRedactionEngine.generateFfmpegRedactionFilters(
                    regions = listOf(region),
                    frameW = 320,
                    frameH = 240,
                    style = style
                )
                assertNotNull("Filter graph missing for $style", filter)
                val output = File(dir, "${style.name.lowercase()}.mp4")
                val args = if (style == PiiRedactionStyle.SOLID_BLACK || style == PiiRedactionStyle.SOLID_WHITE) {
                    arrayOf("-y", "-i", source.absolutePath, "-vf", filter!!, "-an", "-c:v", "mpeg4", output.absolutePath)
                } else {
                    arrayOf("-y", "-i", source.absolutePath, "-filter_complex", filter!!, "-map", "[v_out]", "-an", "-c:v", "mpeg4", output.absolutePath)
                }
                val session = FFmpegKit.executeWithArguments(args)
                assertTrue(
                    "$style render failed: ${session.allLogsAsString}",
                    ReturnCode.isSuccess(session.returnCode) && output.exists() && output.length() > 0L
                )

                val rendered = decodeFrame(output)
                    ?: throw AssertionError("$style output must decode")
                val variance = lumaVariance(rendered, 80, 60, 240, 180)
                if (style == PiiRedactionStyle.SOLID_BLACK || style == PiiRedactionStyle.SOLID_WHITE) {
                    val center = rendered.getPixel(160, 120)
                    val rgb = center and 0x00FFFFFF
                    val isExpectedFill = if (style == PiiRedactionStyle.SOLID_BLACK) {
                        rgb < 0x00030303
                    } else {
                        rgb > 0x00F0F0F0
                    }
                    assertTrue("$style must fill the ROI with the selected solid color", (center ushr 24) > 0 && isExpectedFill)
                } else {
                    assertTrue(
                        "$style should reduce high-frequency ROI variation (source=$baselineVariance output=$variance)",
                        variance < baselineVariance * 0.98
                    )
                }
                rendered.recycle()
            }
            original.recycle()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun decodeFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            retriever.release()
        }
    }

    private fun lumaVariance(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Double {
        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        val luma = pixels.map { pixel ->
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            0.299 * red + 0.587 * green + 0.114 * blue
        }
        val mean = luma.average()
        return luma.sumOf { (it - mean).pow(2) } / luma.size
    }
}
