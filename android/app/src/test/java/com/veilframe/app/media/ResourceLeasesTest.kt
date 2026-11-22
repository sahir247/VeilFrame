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
    @Test fun equivalentFileHandlesShareTheSameLeaseWhenRequested() {
        var disposed = 0
        val registry = ResourceLeases<java.io.File>(identity = false) { disposed++ }
        val reader = registry.acquire(java.io.File("same-file"))
        registry.retire(java.io.File("same-file"))
        assertEquals(0, disposed)
        reader()
        assertEquals(1, disposed)
    }

    @Test fun repeatedRetirementIsTerminalAndDisposesExactlyOnce() {
        var disposed = 0
        val registry = ResourceLeases<Any> { disposed++ }
        val item = Any()
        val first = registry.acquire(item)
        registry.retire(item)
        registry.retire(item)
        first()
        first()
        registry.retire(item)
        assertEquals(1, disposed)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            registry.acquire(item)
        }
    }

    @Test fun retiredWithoutReadersCannotBeReacquired() {
        var disposed = 0
        val registry = ResourceLeases<Any> { disposed++ }
        val item = Any()
        registry.retire(item)
        registry.retire(item)
        assertEquals(1, disposed)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            registry.acquire(item)
        }
    }

    @Test fun retirementMatchingPreservesIdentityOrValueSemantics() {
        data class Value(val id: Int)
        val first = Value(42)
        val equalButDistinct = Value(42)
        var freed = 0
        val identityRegistry = ResourceLeases<Value>(identity = true) { freed++ }
        identityRegistry.retire(first)
        val lease = identityRegistry.acquire(equalButDistinct)
        identityRegistry.retire(equalButDistinct)
        lease()
        assertEquals(2, freed)

        val valueRegistry = ResourceLeases<Value>(identity = false) {}
        valueRegistry.retire(first)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            valueRegistry.acquire(equalButDistinct)
        }
    }
}
