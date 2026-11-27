package com.veilframe.app.pdf.operations

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SignatureLibraryFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedWritePreservesPreviousFileAndCleansStage() {
        val destination = File(folder.root, "signatures_index.json")
        destination.writeText("previous")
        assertThrows(IOException::class.java) {
            SignatureLibraryFiles.atomicWrite(destination) {
                it.write("partial".toByteArray())
                throw IOException("injected")
            }
        }
        assertEquals("previous", destination.readText())
        assertEquals(listOf(destination.name), folder.root.list()!!.toList())
        SignatureLibraryFiles.atomicWrite(destination) { it.write("replacement".toByteArray()) }
        assertEquals("replacement", destination.readText())
    }

    @Test fun emptyWriteCannotReplaceExistingFile() {
        val destination = File(folder.root, "signature.png")
        destination.writeText("existing")
        assertThrows(IOException::class.java) { SignatureLibraryFiles.atomicWrite(destination) {} }
        assertEquals("existing", destination.readText())
    }

    @Test fun idsPreserveLegacyCompatibilityAndRejectTraversal() {
        assertTrue(SignatureLibraryFiles.validId("123456789"))
        assertTrue(SignatureLibraryFiles.validId(java.util.UUID.randomUUID().toString()))
        assertFalse(SignatureLibraryFiles.validId("../outside"))
        assertFalse(SignatureLibraryFiles.validId("a/b"))
        assertFalse(SignatureLibraryFiles.validId(""))
    }
}
