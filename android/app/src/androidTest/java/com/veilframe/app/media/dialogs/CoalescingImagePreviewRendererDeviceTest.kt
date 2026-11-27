package com.veilframe.app.media.dialogs

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.privacy.PiiClass
import com.veilframe.app.privacy.PiiDetection
import com.veilframe.app.privacy.PiiRedactionStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Must execute on Android: verifies image ownership in the async renderer,
 * not just the state-coalescing code. Debug APK/manual device evidence remains
 * separate until instrumentation actually runs.
 */
@RunWith(AndroidJUnit4::class)
class CoalescingImagePreviewRendererDeviceTest {
    @Test fun redactionAndRapidSliderUpdatesCannotMutateSourceOrBleedIntoNextRender() = runBlocking {
        val original = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.YELLOW)
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var result = CompletableDeferred<Bitmap>()
        val renderer = withContext(Dispatchers.Main) {
            CoalescingImagePreviewRenderer(
                ownerScope = owner,
                input = original,
                originalWidth = 64,
                originalHeight = 64,
                onRendered = { bitmap ->
                    if (!result.isCompleted) {
                        result.complete(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    }
                }
            )
        }
        try {
            val redaction = ImageEditState(
                piiDetections = listOf(
                    PiiDetection(RectF(8f, 8f, 56f, 56f), PiiClass.FACE, 0.98f)
                ),
                piiRedactionStyle = PiiRedactionStyle.SOLID_BLACK
            )
            withContext(Dispatchers.Main) { renderer.request(redaction) }
            val first = withTimeout(8_000) { result.await() }
            assertEquals(Color.BLACK, first.getPixel(32, 32))
            assertEquals(Color.YELLOW, original.getPixel(32, 32))
            first.recycle()

            val next = CompletableDeferred<Bitmap>()
            withContext(Dispatchers.Main) {
                result = next
                // A rapid slider drag should end with the latest requested scale.
                for (scale in 100..150) {
                    renderer.request(ImageEditState(resizeScale = scale))
                }
            }
            val last = withTimeout(8_000) { next.await() }
            assertEquals(96, last.width) // 64 * 150%
            assertEquals(Color.YELLOW, last.getPixel(32, 32))
            assertEquals(Color.YELLOW, original.getPixel(32, 32))
            last.recycle()
        } finally {
            withContext(Dispatchers.Main) { renderer.close() }
            owner.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            original.recycle()
        }
    }
}
