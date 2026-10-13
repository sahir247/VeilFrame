package com.veilframe.app.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelActivationRollbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSuccessfulActivationReplacesModelAndCleansBackup() {
        val finalFile = tempFolder.newFile("model.ort").apply {
            writeText("original-known-good-model-v1")
        }
        val tempFinal = tempFolder.newFile("model.ort.temp_final").apply {
            writeText("new-downloaded-model-v2")
        }

        val backupFile = File(finalFile.parentFile, "${finalFile.name}.backup_${System.currentTimeMillis()}")
        var backupCreated = false

        try {
            if (finalFile.exists()) {
                if (!finalFile.renameTo(backupFile)) {
                    finalFile.copyTo(backupFile, overwrite = true)
                    finalFile.delete()
                }
                backupCreated = true
            }

            var activated = tempFinal.renameTo(finalFile)
            if (!activated) {
                tempFinal.copyTo(finalFile, overwrite = true)
                tempFinal.delete()
                activated = true
            }

            if (!finalFile.exists() || finalFile.length() == 0L) {
                throw IllegalStateException("Activated model file is missing or empty")
            }

            if (backupCreated && backupFile.exists()) {
                backupFile.delete()
            }
        } catch (t: Throwable) {
            if (backupCreated && backupFile.exists()) {
                if (finalFile.exists()) finalFile.delete()
                if (!backupFile.renameTo(finalFile)) {
                    backupFile.copyTo(finalFile, overwrite = true)
                    backupFile.delete()
                }
            }
            throw t
        }

        assertTrue("Final model file must exist", finalFile.exists())
        assertEquals("Final model must contain new version", "new-downloaded-model-v2", finalFile.readText())
        assertFalse("Backup file must be cleaned up on success", backupFile.exists())
        assertFalse("Temp final file must no longer exist", tempFinal.exists())
    }

    @Test
    fun testFailedActivationRestoresOriginalModelFromBackup() {
        val finalFile = tempFolder.newFile("model.ort").apply {
            writeText("original-known-good-model-v1")
        }
        val tempFinal = tempFolder.newFile("model.ort.temp_final").apply {
            // Empty file that fails validation
        }

        val backupFile = File(finalFile.parentFile, "${finalFile.name}.backup_${System.currentTimeMillis()}")
        var backupCreated = false

        var errorCaught = false
        try {
            if (finalFile.exists()) {
                if (!finalFile.renameTo(backupFile)) {
                    finalFile.copyTo(backupFile, overwrite = true)
                    finalFile.delete()
                }
                backupCreated = true
            }

            var activated = tempFinal.renameTo(finalFile)
            if (!activated) {
                tempFinal.copyTo(finalFile, overwrite = true)
                tempFinal.delete()
                activated = true
            }

            // Simulating post-activation validation failure (empty file)
            if (!finalFile.exists() || finalFile.length() == 0L) {
                throw IllegalStateException("Activated model file is missing or empty")
            }

            if (backupCreated && backupFile.exists()) {
                backupFile.delete()
            }
        } catch (t: Throwable) {
            errorCaught = true
            if (backupCreated && backupFile.exists()) {
                if (finalFile.exists()) finalFile.delete()
                if (!backupFile.renameTo(finalFile)) {
                    backupFile.copyTo(finalFile, overwrite = true)
                    backupFile.delete()
                }
            }
        }

        assertTrue("Error must be caught on invalid replacement", errorCaught)
        assertTrue("Original model must be restored and exist", finalFile.exists())
        assertEquals("Original model content must be preserved intact", "original-known-good-model-v1", finalFile.readText())
        assertFalse("Backup file must be cleaned up after rollback", backupFile.exists())
    }
}
