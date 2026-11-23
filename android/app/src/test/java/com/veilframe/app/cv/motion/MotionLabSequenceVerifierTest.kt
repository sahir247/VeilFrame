package com.veilframe.app.cv.motion

import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class MotionLabSequenceVerifierTest {
    @Test fun acceptsCompleteTwoTimesSequence() {
        val directory = Files.createTempDirectory("motion-valid").toFile()
        try {
            repeat(6) { index ->
                java.io.File(directory, "o_%05d.jpg".format(index)).writeBytes(byteArrayOf(1))
            }
            MotionLabSequenceVerifier.verify(directory, 3, 2, 6)
            MotionLabSequenceVerifier.verifyPresentation(
                listOf(0L, 16_667L, 33_333L, 50_000L, 66_667L, 83_333L), 6, 60.0
            )
        } finally { directory.deleteRecursively() }
    }

    @Test fun refusesMissingFrameEvenWhenFileCountMatches() {
        val directory = Files.createTempDirectory("motion-gap").toFile()
        try {
            (0 until 6).filter { it != 3 }.plus(7).forEach {
                java.io.File(directory, "o_%05d.jpg".format(it)).writeBytes(byteArrayOf(1))
            }
            assertThrows(IllegalStateException::class.java) {
                MotionLabSequenceVerifier.verify(directory, 3, 2, 6)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsZeroByteAndWrongFrameCounts() {
        val directory = Files.createTempDirectory("motion-empty").toFile()
        try {
            repeat(6) { java.io.File(directory, "o_%05d.jpg".format(it)).writeBytes(byteArrayOf(1)) }
            java.io.File(directory, "o_00004.jpg").writeBytes(byteArrayOf())
            assertThrows(IllegalStateException::class.java) {
                MotionLabSequenceVerifier.verify(directory, 3, 2, 6)
            }
            assertThrows(IllegalStateException::class.java) {
                MotionLabSequenceVerifier.verify(directory, 3, 2, 5)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsEncodedTemporalGapsAndFrameLoss() {
        assertThrows(IllegalStateException::class.java) {
            MotionLabSequenceVerifier.verifyPresentation(
                listOf(0L, 16_667L, 100_000L, 116_667L), 4, 60.0
            )
        }
        assertThrows(IllegalStateException::class.java) {
            MotionLabSequenceVerifier.verifyPresentation(
                listOf(0L, 16_667L, 33_333L), 4, 60.0
            )
        }
    }
}
