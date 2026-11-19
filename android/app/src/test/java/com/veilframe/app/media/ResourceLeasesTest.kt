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
}
