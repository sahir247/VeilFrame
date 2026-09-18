package com.veilframe.app.media.metadata

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Data class representing extracted EXIF metadata for studio editing.
 */
data class ImageExifData(
    val make: String? = null,
    val model: String? = null,
    val software: String? = null,
    val dateTime: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val orientation: Int = ExifInterface.ORIENTATION_NORMAL
)

/**
 * Dedicated metadata reader for Image Studio.
 * Reads existing EXIF attributes from image files without performing sanitization.
 */
object ImageMetadataReader {

    private const val TAG = "VeilFrame.ImageMetadataReader"

    fun readExif(file: File): ImageExifData? {
        if (!file.exists()) return null
        return try {
            val exif = ExifInterface(file.absolutePath)
            val latLong = exif.latLong
            ImageExifData(
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE),
                dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME),
                latitude = latLong?.get(0),
                longitude = latLong?.get(1),
                orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF attributes from ${file.name}: ${e.message}")
            null
        }
    }
}
