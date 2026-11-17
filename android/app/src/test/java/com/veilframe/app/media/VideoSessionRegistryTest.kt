package com.veilframe.app.media

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSessionRegistryTest {

    @Test
    fun `cancelling one owner does not cancel another encoder`() {
        val cancellations = mutableListOf<Long>()
        val registry = VideoSessionRegistry(cancellations::add)
        val compression = Job()
        val stabilization = Job()
        registry.register(compression, 101L)
        registry.register(stabilization, 202L)

        compression.cancel()
        registry.cancel(compression)
        assertEquals(listOf(101L), cancellations)

        registry.cancel(stabilization)
        assertEquals(listOf(101L, 202L), cancellations)
    }

    @Test
    fun `session registered after owner cancellation is cancelled immediately`() {
        val cancellations = mutableListOf<Long>()
        val registry = VideoSessionRegistry(cancellations::add)
        val owner = Job()
        owner.cancel()
        registry.register(owner, 303L)
        registry.cancel(owner)
        assertEquals(listOf(303L), cancellations)
    }

    @Test
    fun `completed sessions are not cancelled by a later unrelated discard`() {
        val cancellations = mutableListOf<Long>()
        val registry = VideoSessionRegistry(cancellations::add)
        val finished = Job()
        val active = Job()
        registry.register(finished, 111L)
        registry.register(active, 222L)
        finished.complete()
        registry.forget(finished)
        registry.cancelAll()
        assertEquals(listOf(222L), cancellations)
    }

    @Test
    fun `session cancellation is idempotent`() {
        val cancellations = mutableListOf<Long>()
        val registry = VideoSessionRegistry(cancellations::add)
        val owner = Job()
        registry.register(owner, 99L)
        registry.cancel(owner)
        registry.cancel(owner)
        registry.cancelAll()
        assertEquals(1, cancellations.size)
        assertTrue(cancellations.contains(99L))
    }
}
