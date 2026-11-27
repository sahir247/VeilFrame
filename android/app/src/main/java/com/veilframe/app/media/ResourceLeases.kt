package com.veilframe.app.media

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Owners may retire resources while asynchronous readers still hold leases. */
internal class ResourceLeases<T : Any>(
    private val identity: Boolean = true,
    private val dispose: (T) -> Unit
) {
    private data class Entry(var count: Int = 0, var retired: Boolean = false)
    private val entries: MutableMap<T, Entry> = if (identity) IdentityHashMap() else HashMap()
    private val retirementQueue = ReferenceQueue<T>()
    private val retired = HashSet<RetiredKey<T>>()

    // Weak tombstones make a retirement terminal/idempotent without retaining
    // the resource strongly after its final reader releases.
    private class RetiredKey<T : Any>(
        item: T, private val identity: Boolean, queue: ReferenceQueue<T>?
    ) : WeakReference<T>(item, queue) {
        private val stableHash = if (identity) System.identityHashCode(item) else item.hashCode()

        override fun hashCode(): Int = stableHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RetiredKey<*> || identity != other.identity) return false
            val a = get() ?: return false
            val b = other.get() ?: return false
            return if (identity) a === b else a == b
        }
    }

    private fun purgeCollectedRetirements() {
        while (true) {
            val cleared = retirementQueue.poll() ?: return
            retired.remove(cleared)
        }
    }

    @Synchronized
    fun acquire(resource: T): () -> Unit {
        purgeCollectedRetirements()
        check(!retired.contains(RetiredKey(resource, identity, null))) {
            "Cannot acquire a retired resource"
        }
        val entry = entries.getOrPut(resource) { Entry() }
        check(!entry.retired) { "Cannot acquire a retired resource" }
        entry.count++
        val released = AtomicBoolean()
        return { if (released.compareAndSet(false, true)) release(resource) }
    }

    @Synchronized
    private fun release(resource: T) {
        val entry = checkNotNull(entries[resource]) { "Released an unknown resource" }
        check(entry.count > 0) { "Resource lease counter underflow" }
        entry.count--
        if (entry.count == 0) {
            entries.remove(resource)
            if (entry.retired) dispose(resource)
        }
    }

    @Synchronized
    fun retire(resource: T) {
        purgeCollectedRetirements()
        if (!retired.add(RetiredKey(resource, identity, retirementQueue))) return
        val entry = entries[resource]
        if (entry == null) dispose(resource)
        else entry.retired = true
    }
}
