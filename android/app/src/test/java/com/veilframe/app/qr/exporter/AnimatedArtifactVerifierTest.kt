package com.veilframe.app.qr.exporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedArtifactVerifierTest {
    @Test fun verificationBudgetHasOverflowSentinelAndCannotPassEmptyOrTruncatedDecode() {
        val low = AnimatedArtifactVerifier.frameLimit(512)
        val high = AnimatedArtifactVerifier.frameLimit(2048)
        assertTrue(low in 1..600)
        assertTrue(high in 1 until low)
        AnimatedArtifactVerifier.requireCompleteFrameCount(low, low)
        assertThrows(IllegalArgumentException::class.java) {
            AnimatedArtifactVerifier.requireCompleteFrameCount(low + 1, low)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnimatedArtifactVerifier.requireCompleteFrameCount(0, low)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnimatedArtifactVerifier.frameLimit(Int.MAX_VALUE)
        }
    }
}
