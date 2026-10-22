package com.veilframe.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.Security

class PdfEngineTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupClass() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private fun createSamplePdf(): ByteArray {
        val doc = PDDocument()
        doc.addPage(PDPage())
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun createEncryptedPdf(password: String): ByteArray {
        val doc = PDDocument()
        doc.addPage(PDPage())

        val accessPermission = AccessPermission()
        accessPermission.setCanPrint(false)
        accessPermission.setCanModify(false)

        val spp = StandardProtectionPolicy(password, password, accessPermission)
        spp.encryptionKeyLength = 128
        doc.protect(spp)

        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    @Test
    fun testCheckEncryption_clearDocument() {
        val clearPdf = createSamplePdf()
        val check = PdfUnlockEngine.checkEncryption(clearPdf)
        assertTrue("Clear PDF should be detected as Clear", check is PdfInitialCheck.Clear)
        assertEquals(1, (check as PdfInitialCheck.Clear).pageCount)
    }

    @Test
    fun testCheckEncryption_encryptedDocument() {
        val encryptedPdf = createEncryptedPdf("SecretPassword123")
        val check = PdfUnlockEngine.checkEncryption(encryptedPdf)
        assertTrue("Encrypted PDF should require password", check is PdfInitialCheck.PasswordRequired)
    }

    @Test
    fun testUnlockPdf_withCorrectPassword_removesSecurityPermanently() = runBlocking {
        val secret = "P@ssword2026"
        val encryptedPdf = createEncryptedPdf(secret)

        val result = PdfUnlockEngine.unlockPdf(encryptedPdf, secret)
        assertTrue("Unlock should succeed with correct password", result.isSuccess)

        val unlockedData = result.getOrThrow()
        assertEquals(1, unlockedData.pageCount)
        assertNotNull(unlockedData.unlockedBytes)
        assertTrue("Unlocked bytes should not be empty", unlockedData.unlockedBytes.isNotEmpty())

        // Verify the decrypted PDF can be loaded without any password
        PDDocument.load(ByteArrayInputStream(unlockedData.unlockedBytes)).use { doc ->
            assertFalse("Document must not be encrypted anymore", doc.isEncrypted)
            assertEquals("Permissions should be fully unrestricted", true, doc.currentAccessPermission.canPrint())
            assertEquals("Permissions should be fully unrestricted", true, doc.currentAccessPermission.canModify())
        }
    }

    @Test
    fun testUnlockPdf_withIncorrectPassword_returnsFailure() = runBlocking {
        val encryptedPdf = createEncryptedPdf("CorrectPassword")
        val result = PdfUnlockEngine.unlockPdf(encryptedPdf, "WrongPassword")

        assertTrue("Unlock should fail with incorrect password", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("Error message should mention password", ex?.message?.contains("password", ignoreCase = true) == true)
    }
}
