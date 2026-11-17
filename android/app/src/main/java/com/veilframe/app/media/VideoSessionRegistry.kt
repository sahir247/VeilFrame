package com.veilframe.app.media

import kotlinx.coroutines.Job

/**
 * Tracks FFmpegKit sessions by their owning coroutine, never by a single global "last session".
 * Registration can race with cancellation when a native session is created late: cancelled
 * owners immediately have their new session cancelled, instead of leaving a hidden encoder.
 */
internal class VideoSessionRegistry(private val cancelNative: (Long) -> Unit) {
    private val lock = Any()
    private val active = mutableMapOf<Job, Long>()

    fun register(owner: Job, sessionId: Long) {
        if (sessionId < 0L) return
        val shouldCancel = synchronized(lock) {
            if (!owner.isActive) true else {
                active[owner] = sessionId
                false
            }
        }
        if (shouldCancel) cancelNative(sessionId)
    }

    fun cancel(owner: Job?) {
        if (owner == null) return
        val sessionId = synchronized(lock) { active.remove(owner) }
        if (sessionId != null) cancelNative(sessionId)
    }

    fun forget(owner: Job?) {
        if (owner != null) synchronized(lock) { active.remove(owner) }
    }

    fun cancelAll() {
        val sessions = synchronized(lock) {
            active.values.toSet().also { active.clear() }
        }
        sessions.forEach(cancelNative)
    }
}
