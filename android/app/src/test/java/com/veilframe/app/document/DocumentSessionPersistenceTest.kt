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
