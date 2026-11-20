package com.veilframe.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceLeasesTest {
    @Test fun retirementWaitsForEveryReader() {
        val freed = mutableListOf<Any>()
        val registry = ResourceLeases<Any> { freed.add(it) }
        val resource = Any()
        val first = registry.acquire(resource)
        val second = registry.acquire(resource)
        registry.retire(resource)
        first()
        first() // completion handlers may be observed more than once
        assertEquals(0, freed.size)
        second()
        assertEquals(listOf(resource), freed)
    }

    @Test fun releaseWithoutRetirementDoesNotDisposeOwnedResource() {
        var freed = 0
        val registry = ResourceLeases<Any> { freed++ }
        val resource = Any()
        registry.acquire(resource)()
        assertEquals(0, freed)
        registry.retire(resource)
        assertEquals(1, freed)
    }
    @Test fun retiredOutputSurvivesDelayedConcurrentReaders() {
        val disposed = java.util.concurrent.atomic.AtomicInteger()
        val registry = ResourceLeases<Any> { disposed.incrementAndGet() }
        val output = Any()
        val save = registry.acquire(output)
        val share = registry.acquire(output)
        val releaseReader = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val reader = executor.submit {
                check(releaseReader.await(5, java.util.concurrent.TimeUnit.SECONDS))
                save()
            }
            registry.retire(output)
            share()
            assertEquals(0, disposed.get())
            releaseReader.countDown()
            reader.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(1, disposed.get())
            save()
            assertEquals(1, disposed.get())
        } finally {
            releaseReader.countDown()
            executor.shutdownNow()
        }
    }
}
