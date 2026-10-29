package com.veilframe.app.privacy

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class PrivacyVerificationTest {

    @Test
    fun testVerifyImageNonExistentFileFailsClosed() {
        val nonExistent = File("build/tmp/non_existent_image_${System.currentTimeMillis()}.jpg")
        val report = PrivacyVerification.verifyImage(nonExistent)

        assertEquals(PrivacyVerification.VerificationStatus.VERIFICATION_FAILED, report.status)
        assertFalse("Verification of non-existent file must fail closed and never report clean", report.isClean)
        assertNotNull(report.failureReason)
        assertTrue(report.failureReason!!.contains("does not exist or is empty"))
    }

    @Test
    fun testVerifyImageZeroByteFileFailsClosed() {
        val emptyFile = File.createTempFile("empty_img", ".jpg")
        try {
            val report = PrivacyVerification.verifyImage(emptyFile)

            assertEquals(PrivacyVerification.VerificationStatus.VERIFICATION_FAILED, report.status)
            assertFalse("Verification of empty file must fail closed", report.isClean)
            assertNotNull(report.failureReason)
        } finally {
            emptyFile.delete()
        }
    }

    @Test
    fun testVerifyImageCleanBitmap() {
        val file = File.createTempFile("clean_img", ".jpg")
        try {
            val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            val report = PrivacyVerification.verifyImage(file)
            assertEquals(PrivacyVerification.VerificationStatus.CLEAN, report.status)
            assertTrue(report.isClean)
            assertTrue(report.foundTags.isEmpty())
            assertFalse(report.hasGps)
        } finally {
            file.delete()
        }
    }

    @Test
    fun testVerifyImageDirtyExifTagsDetected() {
        val file = File.createTempFile("dirty_img", ".jpg")
        try {
            val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, "TestPhoneBrand")
            exif.setAttribute(ExifInterface.TAG_MODEL, "TestCameraModel")
            exif.saveAttributes()

            val report = PrivacyVerification.verifyImage(file)
            assertEquals(PrivacyVerification.VerificationStatus.NOT_CLEAN, report.status)
            assertFalse(report.isClean)
            assertTrue(report.foundTags.contains(ExifInterface.TAG_MAKE))
            assertTrue(report.foundTags.contains(ExifInterface.TAG_MODEL))
        } finally {
            file.delete()
        }
    }

    @Test
    fun testImageMetadataSanitizerFailsClosedOnMissingFile() {
        val nonExistent = File("build/tmp/no_file_sanitize_${System.currentTimeMillis()}.jpg")
        val inspection = ImageMetadataSanitizer.inspectIdentifiableMetadata(nonExistent)
        assertTrue(inspection is ImageMetadataSanitizer.MetadataInspection.Uninspectable)

        // Must fail closed: hasIdentifiableMetadata returns true if uninspectable
        assertTrue(ImageMetadataSanitizer.hasIdentifiableMetadata(nonExistent))
    }

    @Test
    fun testImageMetadataSanitizerIdentifiesCleanAndDirty() {
        val file = File.createTempFile("sanitizer_test", ".jpg")
        try {
            val bmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
            FileOutputStream(file).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // Freshly written bitmap should be clean
            val cleanInspection = ImageMetadataSanitizer.inspectIdentifiableMetadata(file)
            assertTrue(cleanInspection is ImageMetadataSanitizer.MetadataInspection.Clean)
            assertFalse(ImageMetadataSanitizer.hasIdentifiableMetadata(file))

            // Add metadata
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MAKE, "SpyDevice")
            exif.saveAttributes()

            val dirtyInspection = ImageMetadataSanitizer.inspectIdentifiableMetadata(file)
            assertTrue(dirtyInspection is ImageMetadataSanitizer.MetadataInspection.Dirty)
            assertTrue(ImageMetadataSanitizer.hasIdentifiableMetadata(file))
        } finally {
            file.delete()
        }
    }
}
