package com.veilframe.app.upscale

import com.veilframe.app.upscale.inference.qnn.QnnRuntimeCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnRuntimeCompatibilityTest {

    @Test
    fun testSemanticVersionComparison() {
        assertEquals(0, QnnRuntimeCompatibility.compareVersions("1.24.1", "1.24.1"))
        assertTrue(QnnRuntimeCompatibility.compareVersions("1.27.0", "1.24.1") > 0)
        assertTrue(QnnRuntimeCompatibility.compareVersions("1.24.1", "1.27.0") < 0)
        assertTrue(QnnRuntimeCompatibility.compareVersions("1.20.0", "1.24.1") < 0)
        assertTrue(QnnRuntimeCompatibility.compareVersions("2.0.0", "1.27.0") > 0)
    }

    @Test
    fun testOrtPluginEpCompatibilityGating() {
        // Bundled ORT below 1.24.1 must be rejected
        assertFalse(QnnRuntimeCompatibility.isOrtCompatible("1.17.0"))
        assertFalse(QnnRuntimeCompatibility.isOrtCompatible("1.20.0"))
        assertFalse(QnnRuntimeCompatibility.isOrtCompatible("1.24.0"))

        // Exact minimum version 1.24.1 is accepted
        assertTrue(QnnRuntimeCompatibility.isOrtCompatible("1.24.1"))

        // Modern 1.27.0 release is accepted
        assertTrue(QnnRuntimeCompatibility.isOrtCompatible("1.27.0"))
        assertTrue(QnnRuntimeCompatibility.isOrtCompatible("1.28.0"))
    }
}
