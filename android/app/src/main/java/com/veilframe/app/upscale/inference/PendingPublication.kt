package com.veilframe.app.upscale.inference

import java.io.IOException

/** A newly allocated pending destination must never become visible on a failed write. */
internal object PendingPublication {
    fun run(rollback: () -> Unit, write: () -> Unit, publish: () -> Boolean) {
        try {
            write()
            if (!publish()) throw IOException("Destination publication failed")
        } catch (failure: Throwable) {
            try { rollback() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
