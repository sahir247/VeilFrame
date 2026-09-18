package com.veilframe.app.media.metadata

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.veilframe.app.media.ImageEditState
import java.io.File

/**
 * Dedicated metadata writer for Image Studio exports.
 * Writes user-selected EXIF tags (Make, Model, Software, DateTime, GPS) to output JPEG files.
 * Architecturally separated from ImageMetadataSanitizer (which strips metadata).
 */
object ImageMetadataWriter {

    private const val TAG = "VeilFrame.ImageMetadataWriter"

    fun applyMetadata(file: File, state: ImageEditState): Boolean {
        if (!file.exists()) return false
        if (state.stripExif) return true // Handled by sanitization path

        val hasCustomTags = state.exifMake.isNotBlank() ||
                state.exifModel.isNotBlank() ||
                state.exifSoftware.isNotBlank() ||
                state.exifDateTime.isNotBlank() ||
                state.exifGps.isNotBlank()

        if (!hasCustomTags) return true

        return try {
            val exif = ExifInterface(file.absolutePath)

            if (state.exifMake.isNotBlank()) {
                exif.setAttribute(ExifInterface.TAG_MAKE, state.exifMake.trim())
            }
            if (state.exifModel.isNotBlank()) {
                exif.setAttribute(ExifInterface.TAG_MODEL, state.exifModel.trim())
            }
            if (state.exifSoftware.isNotBlank()) {
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, state.exifSoftware.trim())
            }
            if (state.exifDateTime.isNotBlank()) {
                exif.setAttribute(ExifInterface.TAG_DATETIME, state.exifDateTime.trim())
            }
            if (state.exifGps.isNotBlank()) {
                val parts = state.exifGps.split(",")
                if (parts.size == 2) {
                    val lat = parts[0].trim().toDoubleOrNull()
                    val lon = parts[1].trim().toDoubleOrNull()
                    if (lat != null && lon != null) {
                        exif.setLatLong(lat, lon)
                    }
                }
            }

            exif.saveAttributes()
            Log.d(TAG, "Successfully wrote custom EXIF attributes to: ${file.name}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write EXIF attributes: ${e.message}", e)
            false
        }
    }
}
