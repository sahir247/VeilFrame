package com.veilframe.app.pdf

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class PdfQuickExportStageTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun onlyTransferredFileSurvives() = runBlocking<Unit> {
        val dir = temp.newFolder()
        PdfQuickExportStage.generate(dir,{ it.write(7); true }) { success,file ->
            assertTrue(success); assertEquals(1L,file.length()); false
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        PdfQuickExportStage.generate(dir,{ it.write(7); true }) { success,_ -> success }
        assertEquals(1,dir.listFiles().orEmpty().size)
    }

    @Test fun failureAndEmptyOutputAreNotSuccessfulAndCleanUp() = runBlocking<Unit> {
        val dir = temp.newFolder()
        PdfQuickExportStage.generate(dir,{ true }) { success,_ -> assertFalse(success); false }
        assertTrue(runCatching {
            PdfQuickExportStage.generate(dir,{ throw java.io.IOException("failure") }) { _,_ ->
                fail("Must not publish"); false
            }
        }.isFailure)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun legacyTransferIsBounded() {
        val file = temp.newFile().apply { writeBytes(byteArrayOf(1,2,3)) }
        assertArrayEquals(byteArrayOf(1,2,3),PdfQuickExportStage.legacyBytes(file))
        RandomAccessFile(file,"rw").use { it.setLength(16L*1024*1024+1) }
        assertThrows(IllegalArgumentException::class.java) { PdfQuickExportStage.legacyBytes(file) }
    }
}
