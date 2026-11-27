package com.veilframe.app.media.dialogs

import android.graphics.Bitmap
import android.util.Log
import com.veilframe.app.media.ImageEditState
import com.veilframe.app.media.transform.ImageTransformEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * One worker per dialog and one immutable source bitmap owned for its lifetime.
 *
 * The channel conflates fast slider events; heavy transforms run off Main, and
 * completed-but-superseded bitmaps are discarded. No overlapping transforms or
 * recycled source access. Displayed bitmaps are recycled only after replacement
 * and dismissed-dialog detachment.
 */
internal class CoalescingImagePreviewRenderer(
    ownerScope: CoroutineScope,
    input: Bitmap,
    originalWidth: Int,
    originalHeight: Int,
    private val onRendered: (Bitmap) -> Unit
) {
    private data class Request(val generation: Long, val state: ImageEditState)

    private val original = input.copy(Bitmap.Config.ARGB_8888, true)
        ?: throw IllegalStateException("Cannot snapshot image preview source")
    private val width = originalWidth
    private val height = originalHeight
    private val parent = SupervisorJob(ownerScope.coroutineContext[Job])
    private val workerScope = CoroutineScope(parent + Dispatchers.Main.immediate)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private var latestGeneration = 0L
    private var displayed: Bitmap? = null
    private var closed = false

    init {
        require(originalWidth > 0 && originalHeight > 0)
        workerScope.launch {
            try {
                for (first in requests) {
                    delay(24) // a display-frame-sized coalescing window
                    var request = first
                    while (true) {
                        val newer = requests.tryReceive().getOrNull() ?: break
                        request = newer
                    }
                    // The render remains synchronous on Default to prevent two
                    // simultaneous allocations per dialog under rapid input.
                    val pendingBitmap = AtomicReference<Bitmap?>()
                    try {
                        withContext(Dispatchers.Default) {
                            // Some transforms (notably PII redaction) edit a mutable
                            // input in place. Never render against our shared dialog
                            // snapshot: each result gets an independently owned
                            // mutable source, so previews cannot accumulate edits.
                            val working = original.copy(Bitmap.Config.ARGB_8888, true)
                                ?: throw IllegalStateException("Cannot allocate preview working bitmap")
                            var rendered: Bitmap? = null
                            try {
                                rendered = ImageTransformEngine.transform(
                                    src = working,
                                    state = request.state,
                                    origFullW = width,
                                    origFullH = height,
                                    useFullRes = false
                                )
                                pendingBitmap.set(rendered)
                            } finally {
                                if (rendered !== working) working.recycle()
                            }
                        }
                        val next = pendingBitmap.getAndSet(null) ?: continue
                        if (request.generation != latestGeneration || !isActive || closed) {
                            next.recycle()
                        } else {
                            val previous = displayed
                            displayed = next
                            onRendered(next)
                            if (previous !== next) previous?.recycle()
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (t: Exception) {
                        Log.w("VeilFrame.ImagePreview", "Preview rendering failed: ${t.message}", t)
                    } finally {
                        pendingBitmap.getAndSet(null)?.recycle()
                    }
                }
            } finally {
                requests.cancel()
            }
        }
        parent.invokeOnCompletion { original.recycle() }
    }

    /** Main-thread UI calls; the draft is copied before the worker observes it. */
    fun request(state: ImageEditState) {
        if (closed) return
        latestGeneration++
        requests.trySend(Request(latestGeneration, state.copy()))
    }

    /** Call after detaching the ImageView, e.g. from Dialog.onDismiss. */
    fun close() {
        if (closed) return
        closed = true
        requests.close()
        workerScope.cancel()
        displayed?.recycle()
        displayed = null
    }
}
