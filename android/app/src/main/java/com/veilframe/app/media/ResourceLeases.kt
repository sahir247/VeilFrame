package com.veilframe.app.media

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Owners may retire a resource while async readers still have leases. */
internal class ResourceLeases<T : Any>(identity: Boolean = true, private val dispose: (T) -> Unit) {
    private data class Entry(var count: Int = 0, var retired: Boolean = false)
    private val entries: MutableMap<T, Entry> = if (identity) IdentityHashMap() else HashMap()

    @Synchronized
    fun acquire(resource: T): () -> Unit {
        val entry = entries.getOrPut(resource) { Entry() }
        check(!entry.retired) { "Cannot acquire a retired resource" }
        entry.count++
        val released = AtomicBoolean()
        return { if (released.compareAndSet(false, true)) release(resource) }
    }

    @Synchronized
    private fun release(resource: T) {
        val entry = checkNotNull(entries[resource])
        entry.count--
        if (entry.count == 0) {
            entries.remove(resource)
            if (entry.retired) dispose(resource)
        }
    }

    @Synchronized
    fun retire(resource: T) {
        val entry = entries[resource]
        if (entry == null) dispose(resource)
        else entry.retired = true
    }
}
