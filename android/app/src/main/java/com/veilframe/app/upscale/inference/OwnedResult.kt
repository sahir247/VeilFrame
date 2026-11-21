package com.veilframe.app.upscale.inference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Retain ownership until the dispatcher has actually delivered the result. */
internal suspend fun <T : Any> withOwnedResult(
    context: CoroutineContext,
    dispose: (T) -> Unit,
    block: suspend CoroutineScope.() -> T
): T {
    var pending: T? = null
    try {
        val result = withContext(context) {
            block().also { pending = it }
        }
        pending = null
        return result
    } finally {
        pending?.let(dispose)
    }
}
