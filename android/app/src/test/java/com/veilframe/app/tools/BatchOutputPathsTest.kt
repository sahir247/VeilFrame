package com.veilframe.app.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BatchOutputPathsTest {
    @Test fun equalStemsAndNestedNamesDoNotOverwrite() {
        val root = java.nio.file.Files.createTempDirectory("vf-batch").toFile()
        try {
            val inputs = File(root, "inputs").apply { mkdirs() }
            val out = File(root, "output").apply { mkdirs() }
            val files = listOf("a.png", "a.jpg", "nested/a.png").map {
                File(inputs, it).apply { parentFile!!.mkdirs(); writeText("input") }
            }
            // A source directory named like a would-be output is reserved first.
            val nestedConflict = File(inputs, "000001_a_cleaned.png/another.png").apply { parentFile!!.mkdirs(); writeText("input") }
            BatchOutputPaths.prepare(inputs, out, files + nestedConflict)
            val destinations = files.mapIndexed { index, file ->
                BatchOutputPaths.outputFor(inputs, out, file, index, "cleaned", "png")
            }
            assertEquals(3, destinations.toSet().size)
            assertTrue(destinations.first().name.endsWith("_1.png"))
            assertTrue(File(out, "000001_a_cleaned.png").isDirectory)
            destinations.forEachIndexed { index, file -> file.writeText(index.toString()) }
            assertEquals(listOf("0", "1", "2"), destinations.map { it.readText() })
            assertTrue(destinations[2].relativeTo(out).path.startsWith("nested"))
        } finally { root.deleteRecursively() }
    }
}
