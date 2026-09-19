package com.veilframe.app.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SafStorageManagerFormatTest {

    @Test
    fun `maps markdown files to text markdown`() {
        assertEquals("text/markdown", SafStorageManager.getExportMimeType(File("document.md")))
        assertEquals("text/markdown", SafStorageManager.getExportMimeType(File("README.markdown")))
        assertEquals("text/markdown", SafStorageManager.getExportMimeType(File("notes.mdown")))
    }

    @Test
    fun `maps extended image formats to correct mime types`() {
        assertEquals("image/heif", SafStorageManager.getExportMimeType(File("photo.heif")))
        assertEquals("image/heic", SafStorageManager.getExportMimeType(File("photo.heic")))
        assertEquals("image/avif", SafStorageManager.getExportMimeType(File("photo.avif")))
        assertEquals("image/bmp", SafStorageManager.getExportMimeType(File("image.bmp")))
        assertEquals("image/tiff", SafStorageManager.getExportMimeType(File("photo.tiff")))
        assertEquals("image/tiff", SafStorageManager.getExportMimeType(File("photo.tif")))
        assertEquals("image/gif", SafStorageManager.getExportMimeType(File("animation.gif")))
    }

    @Test
    fun `maps standard media and bundle formats`() {
        assertEquals("video/mp4", SafStorageManager.getExportMimeType(File("clean.mp4")))
        assertEquals("video/webm", SafStorageManager.getExportMimeType(File("clean.webm")))
        assertEquals("application/json", SafStorageManager.getExportMimeType(File("manifest.json")))
        assertEquals("text/html", SafStorageManager.getExportMimeType(File("preview.html")))
        assertEquals("application/octet-stream", SafStorageManager.getExportMimeType(File("package.aibundle")))
        assertEquals("application/zip", SafStorageManager.getExportMimeType(File("archive.zip")))
    }
}
