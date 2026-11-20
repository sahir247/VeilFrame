package com.veilframe.app.media

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * One native session belongs to one coroutine. On cancellation wait for native
 * completion before letting the caller delete input/output files. FFmpegKit
 * cancellation is a request, not a guarantee that the encoder has already stopped.
 */
internal suspend fun <T> awaitOwnedNativeSession(
    start: ((T) -> Unit) -> Long,
    cancel: (Long) -> Unit
): T {
    coroutineContext.ensureActive()
    val done = CompletableDeferred<T>()
    val id = start { done.complete(it) }
    try {
        return done.await().also { coroutineContext.ensureActive() }
    } catch (cancelled: CancellationException) {
        // Do not interrupt any other FFmpeg session in this process.
        if (!done.isCompleted) {
            try { cancel(id) } catch (failure: Exception) { cancelled.addSuppressed(failure) }
            withContext(NonCancellable) { done.await() }
        }
        throw cancelled
    }
}

internal suspend fun executeOwnedFfmpeg(args: Array<String>): FFmpegSession =
    awaitOwnedNativeSession(
        start = { complete ->
            val session = FFmpegKit.executeWithArgumentsAsync(args) { finished -> complete(finished) }
                ?: error("FFmpegKit did not create a session")
            session.sessionId
        },
        cancel = { id -> FFmpegKit.cancel(id) }
    )
