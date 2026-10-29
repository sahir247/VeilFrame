package com.veilframe.app.privacy

import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Verification utility ensuring scrubbed artifacts contain zero leaked metadata tags.
 * Hardened to fail closed: parsing or container read failures produce VERIFICATION_FAILED
 * rather than a false CLEAN report.
 */
object PrivacyVerification {

    enum class VerificationStatus {
        CLEAN,
        NOT_CLEAN,
        VERIFICATION_FAILED
    }

    data class ImagePrivacyReport(
        val status: VerificationStatus,
        val isClean: Boolean, // Convenience boolean: true ONLY if status == VerificationStatus.CLEAN
        val foundTags: List<String>,
        val hasGps: Boolean,
        val failureReason: String? = null
    ) {
        // Backwards compatibility constructor
        constructor(isClean: Boolean, foundTags: List<String>, hasGps: Boolean) : this(
            status = if (isClean) VerificationStatus.CLEAN else VerificationStatus.NOT_CLEAN,
            isClean = isClean,
            foundTags = foundTags,
            hasGps = hasGps,
            failureReason = null
        )
    }

    /**
     * Exhaustive list of all identifying and metadata-bearing EXIF attributes.
     * Excludes non-identifying raster layout attributes (width, height, bits-per-sample).
     */
    val IDENTIFYING_EXIF_TAGS = listOf(
        // Camera & Hardware Identifiers
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,

        // Personal & Attribution Identifiers
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_SOFTWARE,

        // Timestamps & Temporal Identifiers
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,

        // Proprietary & Vendor Metadata
        ExifInterface.TAG_MAKER_NOTE,

        // GPS & Geolocation
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM
    )

    fun verifyImage(file: File): ImagePrivacyReport {
        if (!file.exists() || file.length() == 0L) {
            return ImagePrivacyReport(
                status = VerificationStatus.VERIFICATION_FAILED,
                isClean = false,
                foundTags = emptyList(),
                hasGps = false,
                failureReason = "File does not exist or is empty: ${file.absolutePath}"
            )
        }
        val found = mutableListOf<String>()
        var hasGps = false

        try {
            val exif = ExifInterface(file.absolutePath)
            if (exif.latLong != null || exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null) {
                hasGps = true
                found.add("GPS Coordinates")
            }

            if (exif.hasThumbnail()) {
                found.add("Embedded Thumbnail")
            }

            val xmp = exif.getAttribute(ExifInterface.TAG_XMP)
            if (!xmp.isNullOrBlank()) {
                found.add("XMP Packet")
            }

            // Inspect all identifying metadata tags to guarantee container-wide privacy
            for (tag in IDENTIFYING_EXIF_TAGS) {
                val value = exif.getAttribute(tag)
                if (!value.isNullOrBlank()) {
                    found.add(tag)
                }
            }

            val isClean = found.isEmpty() && !hasGps
            return ImagePrivacyReport(
                status = if (isClean) VerificationStatus.CLEAN else VerificationStatus.NOT_CLEAN,
                isClean = isClean,
                foundTags = found,
                hasGps = hasGps,
                failureReason = null
            )
        } catch (e: Exception) {
            // NEVER FAIL OPEN: Parsing failure means verification could not be completed
            return ImagePrivacyReport(
                status = VerificationStatus.VERIFICATION_FAILED,
                isClean = false,
                foundTags = listOf("PARSER_ERROR: ${e.message ?: e.javaClass.simpleName}"),
                hasGps = false,
                failureReason = "Exception while reading image metadata: ${e.message}"
            )
        }
    }
}
