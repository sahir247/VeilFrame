package com.veilframe.app.document

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DocumentSessionPersistenceTest {
    @Test
    fun olderQueuedSnapshotCannotOverwriteNewerCommittedSession() {
        val context = RuntimeEnvironment.getApplication()
        val session = DocumentSession()
        try {
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            session.addPage(ScannedPage(originalBitmapCache = bmp, renderedVersion = -1L))
            session.title = "older"
            val old = session.captureSaveSnapshot()
            session.title = "newer"
            assertTrue(session.saveToDisk(context))
            assertTrue(session.saveToDisk(context, old))
            assertEquals("newer", DocumentSession.loadFromDisk(context, session.id)?.title)
        } finally {
            DocumentSession.deleteSession(context, session.id)
        }
    }

    @Test
    fun exportSnapshotDoesNotFollowChangesOrReorder() {
        val session = DocumentSession()
        val page = ScannedPage(id = "first", corners = listOf(org.opencv.core.Point(3.0, 4.0)))
        session.addPage(page)
        session.addPage(ScannedPage(id = "second"))
        session.title = "Before"
        val captured = session.snapshotForExport()
        page.corners!![0].x = 80.0
        session.movePage(0, 1)
        session.title = "After"
        assertEquals("Before", captured.title)
        assertEquals(listOf("first", "second"), captured.pages.map { it.id })
        assertEquals(3.0, captured.pages.first().corners!![0].x, 0.0)
    }

    @Test
    fun validatesOrderedManualCropRatherThanReorderingCrossedHandles() {
        fun point(x: Double, y: Double) = org.opencv.core.Point(x, y)
        val quad = listOf(point(0.0, 0.0), point(100.0, 0.0), point(100.0, 80.0), point(0.0, 80.0))
        assertTrue(isValidDocumentCrop(quad, 100, 80))
        assertFalse(isValidDocumentCrop(listOf(quad[0], quad[2], quad[1], quad[3]), 100, 80))
        assertFalse(isValidDocumentCrop(quad.toMutableList().apply { this[1] = point(Double.NaN, 0.0) }, 100, 80))
    }

    @Test
    fun deletionPermanentlyRetiresQueuedAndLiveAutosaves() {
        val context = RuntimeEnvironment.getApplication()
        val session = DocumentSession()
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            session.addPage(ScannedPage(originalBitmapCache = bmp, renderedVersion = -1L))
            val pending = session.captureSaveSnapshot()
            assertTrue(session.saveToDisk(context))
            assertTrue(DocumentSession.deleteSession(context, session.id))
            assertFalse(session.saveToDisk(context, pending))
            assertFalse(session.saveToDisk(context))
            assertFalse(DocumentSession.getSessionDirectory(context, session.id).exists())
            assertNull(DocumentSession.loadFromDisk(context, session.id))
            assertFalse(DocumentSession.listSavedSessions(context).any { it.sessionId == session.id })
        } finally {
            bmp.recycle()
            DocumentSession.deleteSession(context, session.id)
        }
    }

    @Test
    fun recoveryRejectsMissingOriginalAndForeignSessionPaths() {
        val context = RuntimeEnvironment.getApplication()
        val session = DocumentSession()
        val other = DocumentSession()
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            session.addPage(ScannedPage(originalBitmapCache = bmp, renderedVersion = -1L))
            other.addPage(ScannedPage(originalBitmapCache = bmp, renderedVersion = -1L))
            assertTrue(session.saveToDisk(context))
            assertTrue(other.saveToDisk(context))
            val dir = DocumentSession.getSessionDirectory(context, session.id)
            val manifest = java.io.File(dir, "manifest.json")
            val original = java.io.File(session.pages.single().originalImagePath)
            val json = org.json.JSONObject(manifest.readText())
            val page = json.getJSONArray("pages").getJSONObject(0)
            page.put("originalImagePath", other.pages.single().originalImagePath)
            manifest.writeText(json.toString())
            assertNull(DocumentSession.loadFromDisk(context, session.id))
            page.put("originalImagePath", original.absolutePath)
            manifest.writeText(json.toString())
            assertTrue(original.delete())
            assertNull(DocumentSession.loadFromDisk(context, session.id))
        } finally {
            bmp.recycle()
            DocumentSession.deleteSession(context, session.id)
            DocumentSession.deleteSession(context, other.id)
        }
    }

    @Test
    fun failedLaterPageKeepsCommittedManifestAndReclaimsUnpublishedRenderCaches() {
        val context = RuntimeEnvironment.getApplication()
        val session = DocumentSession()
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(Color.WHITE)
            val page = ScannedPage(originalBitmapCache = bmp, processedBitmapCache = bmp,
                renderedVersion = 0L, persistedRenderVersion = -1L)
            session.addPage(page)
            assertTrue(session.saveToDisk(context))
            val directory = DocumentSession.getSessionDirectory(context, session.id)
            val manifest = java.io.File(directory, "manifest.json")
            val committed = manifest.readText()
            val priorCaches = directory.listFiles()!!.filter { "_proc_" in it.name || "_thumb_" in it.name }
                .map { it.name }.toSet()
            assertEquals(2, priorCaches.size)

            page.editVersion++
            page.renderedVersion = page.editVersion
            session.addPage(ScannedPage(renderedVersion = -1L)) // No original source.
            assertFalse(session.saveToDisk(context))
            assertEquals(committed, manifest.readText())
            assertEquals(priorCaches, directory.listFiles()!!.filter { "_proc_" in it.name || "_thumb_" in it.name }
                .map { it.name }.toSet())
            assertEquals(1, DocumentSession.loadFromDisk(context, session.id)?.pageCount)
        } finally {
            bmp.recycle()
            DocumentSession.deleteSession(context, session.id)
        }
    }

    @Test
    fun rejectsDuplicatePageIdsBeforeCommittingSession() {
        val context = RuntimeEnvironment.getApplication()
        val session = DocumentSession()
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            session.addPage(ScannedPage(id = "same", originalBitmapCache = bmp, renderedVersion = -1L))
            session.addPage(ScannedPage(id = "same", originalBitmapCache = bmp, renderedVersion = -1L))
            assertFalse(session.saveToDisk(context))
            assertFalse(java.io.File(DocumentSession.getSessionDirectory(context, session.id), "manifest.json").exists())
        } finally {
            bmp.recycle()
            DocumentSession.deleteSession(context, session.id)
        }
    }

    @Test
    fun modifiedOriginalCannotBeRestoredOrRebaselinedByAutosave() {
        val context = RuntimeEnvironment.getApplication()
        val session = DocumentSession()
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            session.addPage(ScannedPage(originalBitmapCache = bmp, renderedVersion = -1L))
            assertTrue(session.saveToDisk(context))
            val original = java.io.File(session.pages.single().originalImagePath)
            val bytes = original.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            original.writeBytes(bytes)
            assertNull(DocumentSession.loadFromDisk(context, session.id))
            assertFalse(session.saveToDisk(context))
        } finally {
            bmp.recycle()
            DocumentSession.deleteSession(context, session.id)
        }
    }

    @Test
    fun corruptDerivedRenderIsDiscardedWithoutLosingOriginal() {
        val context = RuntimeEnvironment.getApplication()
        val session = DocumentSession()
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            session.addPage(ScannedPage(originalBitmapCache = bmp, processedBitmapCache = bmp,
                renderedVersion = 0L, persistedRenderVersion = -1L))
            assertTrue(session.saveToDisk(context))
            val page = session.pages.single()
            val processed = java.io.File(checkNotNull(page.processedImagePath))
            val bytes = processed.readBytes()
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            processed.writeBytes(bytes)
            val recovered = checkNotNull(DocumentSession.loadFromDisk(context, session.id)).pages.single()
            assertNull(recovered.processedImagePath)
            assertNull(recovered.thumbnailPath)
            assertEquals(-1L, recovered.persistedRenderVersion)
            assertTrue(java.io.File(recovered.originalImagePath).isFile)
        } finally {
            bmp.recycle()
            DocumentSession.deleteSession(context, session.id)
        }
    }

    @Test
    fun capturedSnapshotOwnsItsSessionIdentityAndPageMetadata() {
        val session = DocumentSession()
        val page = ScannedPage(rotationDegrees = 90)
        session.addPage(page)
        val snapshot = session.captureSaveSnapshot()
        val oldId = session.id
        page.rotationDegrees = 180
        session.replaceAllPages(emptyList())
        session.id = "new-session"
        assertEquals(oldId, snapshot.sessionId)
        assertEquals(90, snapshot.pages.single().second.rotationDegrees)
        assertTrue(session.pages.isEmpty())
    }
}
