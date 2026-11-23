package com.veilframe.app.cv.motion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class MotionLabSourceFrameStageTest {
    @Test fun sourceFramesAreNotDecodedOrRecompressed() {
        val root = Files.createTempDirectory("motion-originals").toFile()
        try {
            val input = java.io.File(root, "in.jpg")
            val outDir = java.io.File(root, "out").apply { mkdir() }
            val target = java.io.File(outDir, "o_00000.jpg")
            val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 42, 17, 11, 0xFF.toByte(), 0xD9.toByte())
            input.writeBytes(payload)
            MotionLabSourceFrameStage.stage(input, target)
            assertArrayEquals(payload, target.readBytes())
            assertEquals(input.length(), target.length())
        } finally { root.deleteRecursively() }
    }

    @Test fun refusesMissingOrEmptySourceAndRefusesOverwrite() {
        val root = Files.createTempDirectory("motion-errors").toFile()
        try {
            val input = java.io.File(root, "input.jpg")
            val target = java.io.File(root, "output.jpg")
            assertThrows(IllegalArgumentException::class.java) {
                MotionLabSourceFrameStage.stage(input, target)
            }
            input.writeBytes(byteArrayOf())
            assertThrows(IllegalArgumentException::class.java) {
                MotionLabSourceFrameStage.stage(input, target)
            }
            input.writeBytes(byteArrayOf(1))
            target.writeBytes(byteArrayOf(9))
            assertThrows(IllegalArgumentException::class.java) {
                MotionLabSourceFrameStage.stage(input, target)
            }
        } finally { root.deleteRecursively() }
    }
}
