package com.veilframe.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRouteTest {

    @Test
    fun `all 14 canonical workspaces are defined and populated`() {
        val routes = WorkspaceRoute.values()
        assertEquals("Must define exactly 14 canonical workspaces", 14, routes.size)

        routes.forEach { route ->
            assertNotNull(route.id)
            assertTrue("Title must not be empty", route.title.isNotEmpty())
            assertTrue("Description must not be empty", route.description.isNotEmpty())
            assertNotNull("Category must be assigned", route.category)
            assertTrue("Must define at least one search alias", route.aliases.isNotEmpty())
            assertTrue("Icon drawable resource ID must be valid", route.iconRes != 0)
        }
    }

    @Test
    fun `categories partition all workspaces cleanly`() {
        val createEdit = WorkspaceRoute.forCategory(WorkspaceCategory.CREATE_EDIT)
        val privacy = WorkspaceRoute.forCategory(WorkspaceCategory.PRIVACY)
        val analyze = WorkspaceRoute.forCategory(WorkspaceCategory.ANALYZE)
        val developer = WorkspaceRoute.forCategory(WorkspaceCategory.DEVELOPER)

        assertEquals("Create & Edit category must contain 7 studios", 7, createEdit.size)
        assertEquals("Privacy category must contain 2 sanitizers", 2, privacy.size)
        assertEquals("Analyze category must contain 2 tools", 2, analyze.size)
        assertEquals("Developer category must contain 3 tools", 3, developer.size)

        assertTrue(createEdit.contains(WorkspaceRoute.IMAGE_STUDIO))
        assertTrue(createEdit.contains(WorkspaceRoute.VIDEO_STUDIO))
        assertTrue(createEdit.contains(WorkspaceRoute.QR_STUDIO))
        assertTrue(createEdit.contains(WorkspaceRoute.DOCUMENT_SCANNER))
        assertTrue(createEdit.contains(WorkspaceRoute.BACKGROUND_REMOVER))
        assertTrue(createEdit.contains(WorkspaceRoute.IMAGE_UPSCALER))
        assertTrue(createEdit.contains(WorkspaceRoute.MOTION_LAB))

        assertTrue(privacy.contains(WorkspaceRoute.IMAGE_CLEANER))
        assertTrue(privacy.contains(WorkspaceRoute.VIDEO_CLEANER))

        assertTrue(analyze.contains(WorkspaceRoute.IMAGE_QUALITY))
        assertTrue(analyze.contains(WorkspaceRoute.FOLDER_SCANNER))

        assertTrue(developer.contains(WorkspaceRoute.AI_BUNDLE))
        assertTrue(developer.contains(WorkspaceRoute.MARKDOWN_STUDIO))
        assertTrue(developer.contains(WorkspaceRoute.PROVENANCE))
    }

    @Test
    fun `synonym and alias search routes correctly`() {
        val pdfResults = WorkspaceRoute.search("pdf")
        assertTrue("Searching 'pdf' must return Document Scanner", pdfResults.contains(WorkspaceRoute.DOCUMENT_SCANNER))

        val bgResults = WorkspaceRoute.search("remove bg")
        assertTrue("Searching 'remove bg' must return Background Remover", bgResults.contains(WorkspaceRoute.BACKGROUND_REMOVER))

        val metadataResults = WorkspaceRoute.search("metadata")
        assertTrue("Searching 'metadata' must return Image Cleaner", metadataResults.contains(WorkspaceRoute.IMAGE_CLEANER))
        assertTrue("Searching 'metadata' must return Video Cleaner", metadataResults.contains(WorkspaceRoute.VIDEO_CLEANER))

        val sharpnessResults = WorkspaceRoute.search("sharpness")
        assertTrue("Searching 'sharpness' must return Image Quality", sharpnessResults.contains(WorkspaceRoute.IMAGE_QUALITY))

        val qrResults = WorkspaceRoute.search("qr")
        assertTrue("Searching 'qr' must return QR Code Studio", qrResults.contains(WorkspaceRoute.QR_STUDIO))

        val motionResults = WorkspaceRoute.search("optical flow")
        assertTrue("Searching 'optical flow' must return Motion Lab", motionResults.contains(WorkspaceRoute.MOTION_LAB))

        val emptyResults = WorkspaceRoute.search("")
        assertEquals("Empty query must return all 14 workspaces", 14, emptyResults.size)
    }

    @Test
    fun `screen state covers all required destinations from ADR 0004`() {
        val states = ScreenState.values().map { it.name }
        assertTrue(states.contains("HOME"))
        assertTrue(states.contains("TOOLS"))
        assertTrue(states.contains("LIBRARY"))
        assertTrue(states.contains("DOCUMENT_SCANNER_ENTRY"))
        assertTrue(states.contains("DOCUMENT_SCANNER_CAMERA"))
        assertTrue(states.contains("DOCUMENT_SCANNER_PAGES"))
        assertTrue(states.contains("DOCUMENT_SCANNER_EDITOR"))
        assertTrue(states.contains("DOCUMENT_SCANNER_EXPORT"))
        assertTrue(states.contains("BACKGROUND_REMOVER"))
        assertTrue(states.contains("IMAGE_QUALITY"))
    }
}
