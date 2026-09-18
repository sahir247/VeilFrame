package com.veilframe.app.media.metadata

import com.veilframe.app.media.ImageEditState
import java.io.File

/**
 * Image Studio Metadata Engine.
 * Coordinates reading and writing of EXIF metadata for editing workflows.
 * NOTE: Privacy sanitization and scrubbing is strictly handled by ImageMetadataSanitizer.
 */
class ImageMetadataEngine {

    fun readMetadata(file: File): ImageExifData? {
        return ImageMetadataReader.readExif(file)
    }

    fun writeMetadata(file: File, state: ImageEditState): Boolean {
        return ImageMetadataWriter.applyMetadata(file, state)
    }
}
