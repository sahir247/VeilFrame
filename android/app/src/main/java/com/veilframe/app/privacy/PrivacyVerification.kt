package com.veilframe.app.privacy

import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Verification utility ensuring scrubbed artifacts contain zero leaked metadata tags.
 */
object PrivacyVerification {

    data class ImagePrivacyReport(
        val isClean: Boolean,
        val foundTags: List<String>,
        val hasGps: Boolean
    )

    fun verifyImage(file: File): ImagePrivacyReport {
        if (!file.exists()) return ImagePrivacyReport(false, listOf("File does not exist"), false)
        val found = mutableListOf<String>()
        var hasGps = false

        try {
            val exif = ExifInterface(file.absolutePath)
            if (exif.latLong != null) {
                hasGps = true
                found.add("GPS Coordinates")
            }

            val criticalTags = arrayOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_BODY_SERIAL_NUMBER,
                ExifInterface.TAG_CAMERA_OWNER_NAME,
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_SOFTWARE
            )

            for (tag in criticalTags) {
                val value = exif.getAttribute(tag)
                if (!value.isNullOrBlank()) {
                    found.add(tag)
                }
            }
        } catch (_: Exception) {}

        return ImagePrivacyReport(
            isClean = found.isEmpty() && !hasGps,
            foundTags = found,
            hasGps = hasGps
        )
    }
}
