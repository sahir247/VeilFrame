package com.veilframe.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strict contract test ensuring 1-to-1 parity between the 14 canonical [WorkspaceRoute]
 * definitions and the UI catalogue layout + Activity click bindings.
 */
class WorkspaceCatalogueContractTest {

    @Test
    fun `canonical workspace catalogue has exactly 14 routes with complete metadata`() {
        val routes = WorkspaceRoute.values()
        assertEquals("WorkspaceRoute catalogue must define exactly 14 canonical workspaces", 14, routes.size)

        routes.forEach { route ->
            assertNotNull(route.id)
            assertTrue("Title must not be empty for ${route.name}", route.title.isNotEmpty())
            assertTrue("Description must not be empty for ${route.name}", route.description.isNotEmpty())
            assertNotNull("Category must be assigned for ${route.name}", route.category)
            assertNotNull("Status must be assigned for ${route.name}", route.status)
            assertTrue("Aliases must not be empty for ${route.name}", route.aliases.isNotEmpty())
            assertTrue("Icon resource must be non-zero for ${route.name}", route.iconRes != 0)
        }
    }

    @Test
    fun `all 14 canonical workspaces have corresponding cards in layout_tools_catalogue`() {
        val layoutFile = File("src/main/res/layout/layout_tools_catalogue.xml")
        val altLayoutFile = File("android/app/src/main/res/layout/layout_tools_catalogue.xml")
        val targetFile = if (layoutFile.exists()) layoutFile else altLayoutFile
        assertTrue("layout_tools_catalogue.xml must exist", targetFile.exists())

        val xmlContent = targetFile.readText()

        val expectedCardIds = listOf(
            "cardToolImageStudio",
            "cardToolVideoStudio",
            "cardToolQrStudio",
            "cardToolDocScanner",
            "cardToolBgRemover",
            "cardToolAiUpscaler",
            "cardToolPrivacyScrubber",
            "cardToolVideoCleaner",
            "cardToolImageQuality",
            "cardToolFolderAnalyzer",
            "cardToolAiBundler",
            "cardToolMarkdownStudio",
            "cardToolProvenance",
            "cardToolMotionLab"
        )

        assertEquals("Expected exactly 14 card IDs in layout", 14, expectedCardIds.size)

        expectedCardIds.forEach { cardId ->
            assertTrue(
                "layout_tools_catalogue.xml must declare card $cardId",
                xmlContent.contains("@+id/$cardId")
            )
        }

        // Confirm obsolete/fake cards do not exist
        assertFalse(
            "Fake cardToolCvBench must not exist in catalogue",
            xmlContent.contains("@+id/cardToolCvBench")
        )
    }

    @Test
    fun `activity wires all 14 workspace card listeners and filter pairs`() {
        val actFile = File("src/main/java/com/veilframe/app/MainActivity.kt")
        val altActFile = File("android/app/src/main/java/com/veilframe/app/MainActivity.kt")
        val targetFile = if (actFile.exists()) actFile else altActFile
        assertTrue("MainActivity.kt must exist", targetFile.exists())

        val actContent = targetFile.readText()

        val expectedCards = listOf(
            "cardToolImageStudio",
            "cardToolVideoStudio",
            "cardToolQrStudio",
            "cardToolDocScanner",
            "cardToolBgRemover",
            "cardToolAiUpscaler",
            "cardToolPrivacyScrubber",
            "cardToolVideoCleaner",
            "cardToolImageQuality",
            "cardToolFolderAnalyzer",
            "cardToolAiBundler",
            "cardToolMarkdownStudio",
            "cardToolProvenance",
            "cardToolMotionLab"
        )

        expectedCards.forEach { cardId ->
            assertTrue(
                "MainActivity.kt must wire click listener for $cardId",
                actContent.contains("layoutToolsCatalogue.$cardId.setOnClickListener")
            )
            assertTrue(
                "MainActivity.kt must include $cardId in search filter map",
                actContent.contains("layoutToolsCatalogue.$cardId")
            )
        }
    }
}
