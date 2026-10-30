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
    fun `canonical workspace catalogue has exactly 17 routes with complete metadata`() {
        val routes = WorkspaceRoute.values()
        assertEquals("WorkspaceRoute catalogue must define exactly 17 canonical workspaces", 17, routes.size)

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
    fun `all 17 canonical workspaces have corresponding cards in layout_tools_catalogue`() {
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
            "cardToolMotionLab",
            "cardToolPdfStudio",
            "cardToolPdfSignature",
            "cardToolPdfUnlock"
        )

        assertEquals("Expected exactly 17 card IDs in layout", 17, expectedCardIds.size)

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
    fun `activity wires all 17 workspace card listeners and filter pairs`() {
        val actFile = File("src/main/java/com/veilframe/app/MainActivity.kt")
        val altActFile = File("android/app/src/main/java/com/veilframe/app/MainActivity.kt")
        val targetFile = if (actFile.exists()) actFile else altActFile
        assertTrue("MainActivity.kt must exist", targetFile.exists())

        val coordinatorFile = File("src/main/java/com/veilframe/app/ui/tools/ToolsCatalogueCoordinator.kt")
        val altCoordinatorFile = File("android/app/src/main/java/com/veilframe/app/ui/tools/ToolsCatalogueCoordinator.kt")
        val coordFile = if (coordinatorFile.exists()) coordinatorFile else altCoordinatorFile
        assertTrue("ToolsCatalogueCoordinator.kt must exist", coordFile.exists())

        val actContent = targetFile.readText()
        val coordContent = coordFile.readText()

        assertTrue(
            "MainActivity must initialize ToolsCatalogueCoordinator",
            actContent.contains("toolsCatalogueCoordinator = ToolsCatalogueCoordinator") &&
                actContent.contains("toolsCatalogueCoordinator.init()")
        )

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
            "cardToolMotionLab",
            "cardToolPdfStudio",
            "cardToolPdfSignature",
            "cardToolPdfUnlock"
        )

        expectedCards.forEach { cardId ->
            assertTrue(
                "ToolsCatalogueCoordinator must wire click listener for $cardId",
                coordContent.contains("binding.$cardId") && coordContent.contains("card.setOnClickListener")
            )
            assertTrue(
                "ToolsCatalogueCoordinator must include $cardId in search filter map",
                coordContent.contains("binding.$cardId")
            )
        }
    }

    @Test
    fun `rowDev2 cards in layout_tools_catalogue declare symmetric weights and margins preventing column collapse`() {
        val layoutFile = File("src/main/res/layout/layout_tools_catalogue.xml")
        val altLayoutFile = File("android/app/src/main/res/layout/layout_tools_catalogue.xml")
        val targetFile = if (layoutFile.exists()) layoutFile else altLayoutFile
        assertTrue("layout_tools_catalogue.xml must exist", targetFile.exists())

        val xmlContent = targetFile.readText()

        // cardToolProvenance must use 0dp width, weight 1, and marginEnd 5dp
        val provIndex = xmlContent.indexOf("id=\"@+id/cardToolProvenance\"")
        assertTrue("cardToolProvenance must be found", provIndex != -1)
        val provBlock = xmlContent.substring(provIndex, xmlContent.indexOf("</com.google.android.material.card.MaterialCardView>", provIndex))
        assertTrue("cardToolProvenance must have layout_width=0dp", provBlock.contains("android:layout_width=\"0dp\""))
        assertTrue("cardToolProvenance must have layout_weight=1", provBlock.contains("android:layout_weight=\"1\""))
        assertTrue("cardToolProvenance must have layout_marginEnd=5dp", provBlock.contains("android:layout_marginEnd=\"5dp\""))

        // cardToolMotionLab must use 0dp width, weight 1, and marginStart 5dp
        val motionIndex = xmlContent.indexOf("id=\"@+id/cardToolMotionLab\"")
        assertTrue("cardToolMotionLab must be found", motionIndex != -1)
        val motionBlock = xmlContent.substring(motionIndex, xmlContent.indexOf("</com.google.android.material.card.MaterialCardView>", motionIndex))
        assertTrue("cardToolMotionLab must have layout_width=0dp", motionBlock.contains("android:layout_width=\"0dp\""))
        assertTrue("cardToolMotionLab must have layout_weight=1", motionBlock.contains("android:layout_weight=\"1\""))
        assertTrue("cardToolMotionLab must have layout_marginStart=5dp", motionBlock.contains("android:layout_marginStart=\"5dp\""))
    }

    @Test
    fun `settings panel scroll container allocates flexible weight preventing height overflow`() {
        val layoutFile = File("src/main/res/layout/layout_settings_panel.xml")
        val altLayoutFile = File("android/app/src/main/res/layout/layout_settings_panel.xml")
        val targetFile = if (layoutFile.exists()) layoutFile else altLayoutFile
        assertTrue("layout_settings_panel.xml must exist", targetFile.exists())

        val xmlContent = targetFile.readText()
        val scrollIndex = xmlContent.indexOf("id=\"@+id/scrollSettings\"")
        assertTrue("scrollSettings must be found", scrollIndex != -1)
        val scrollTag = xmlContent.substring(scrollIndex, xmlContent.indexOf(">", scrollIndex))
        assertTrue("scrollSettings must have layout_height=0dp", scrollTag.contains("android:layout_height=\"0dp\""))
        assertTrue("scrollSettings must have layout_weight=1", scrollTag.contains("android:layout_weight=\"1\""))
    }

    @Test
    fun `main navigation routes system back through closeSettingsOverlay to apply pending theme changes`() {
        val navFile = File("src/main/java/com/veilframe/app/navigation/MainNavigationController.kt")
        val altNavFile = File("android/app/src/main/java/com/veilframe/app/navigation/MainNavigationController.kt")
        val targetFile = if (navFile.exists()) navFile else altNavFile
        assertTrue("MainNavigationController.kt must exist", targetFile.exists())

        val content = targetFile.readText()
        assertTrue(
            "MainNavigationController must invoke closeSettingsOverlay() on back when settings are visible",
            content.contains("closeSettingsOverlay()")
        )
    }

    @Test
    fun `layout_tools_catalogue declares clean subtitle without number flexing`() {
        val layoutFile = File("src/main/res/layout/layout_tools_catalogue.xml")
        val altLayoutFile = File("android/app/src/main/res/layout/layout_tools_catalogue.xml")
        val targetFile = if (layoutFile.exists()) layoutFile else altLayoutFile
        assertTrue("layout_tools_catalogue.xml must exist", targetFile.exists())

        val xmlContent = targetFile.readText()
        assertTrue(
            "Catalogue subtitle must declare clean offline utilities subtext",
            xmlContent.contains("Offline privacy, media, and document utilities")
        )
        assertFalse(
            "Catalogue subtitle must not flex engine numbers",
            xmlContent.contains("offline engines")
        )
    }

    @Test
    fun `activity binds accessible contentDescription combining title, status, and description for cards`() {
        val actFile = File("src/main/java/com/veilframe/app/MainActivity.kt")
        val altActFile = File("android/app/src/main/java/com/veilframe/app/MainActivity.kt")
        val targetFile = if (actFile.exists()) actFile else altActFile
        assertTrue("MainActivity.kt must exist", targetFile.exists())

        val coordinatorFile = File("src/main/java/com/veilframe/app/ui/tools/ToolsCatalogueCoordinator.kt")
        val altCoordinatorFile = File("android/app/src/main/java/com/veilframe/app/ui/tools/ToolsCatalogueCoordinator.kt")
        val coordFile = if (coordinatorFile.exists()) coordinatorFile else altCoordinatorFile
        assertTrue("ToolsCatalogueCoordinator.kt must exist", coordFile.exists())

        val combinedContent = targetFile.readText() + "\n" + coordFile.readText()
        assertTrue(
            "ToolsCatalogueCoordinator must bind dynamic TalkBack contentDescription for catalogue cards",
            combinedContent.contains("contentDescription = \"\${route.title}, \${route.status.label}. \${route.description}\"")
        )
    }

    @Test
    fun `main navigation provides unified navigateBackFromWorkspace contract routing to originating screen`() {
        val navFile = File("src/main/java/com/veilframe/app/navigation/MainNavigationController.kt")
        val altNavFile = File("android/app/src/main/java/com/veilframe/app/navigation/MainNavigationController.kt")
        val targetFile = if (navFile.exists()) navFile else altNavFile
        assertTrue("MainNavigationController.kt must exist", targetFile.exists())

        val content = targetFile.readText()
        assertTrue(
            "MainNavigationController must define navigateBackFromWorkspace()",
            content.contains("fun navigateBackFromWorkspace()")
        )
        assertTrue(
            "navigateBackFromWorkspace must handle returning to TOOLS screen",
            content.contains("ScreenState.TOOLS -> showToolsScreen()")
        )
        assertTrue(
            "navigateBackFromWorkspace must handle returning to LIBRARY screen",
            content.contains("ScreenState.LIBRARY -> showLibraryScreen()")
        )
    }

    @Test
    fun `background remover layout guarantees export button visibility on mobile viewports`() {
        val layoutFile = File("src/main/res/layout/layout_background_remover.xml")
        val altLayoutFile = File("android/app/src/main/res/layout/layout_background_remover.xml")
        val targetFile = if (layoutFile.exists()) layoutFile else altLayoutFile
        assertTrue("layout_background_remover.xml must exist", targetFile.exists())

        val xmlContent = targetFile.readText()

        // Toolbar quick export button exists
        assertTrue(
            "Toolbar must declare btnBgToolbarExport",
            xmlContent.contains("@+id/btnBgToolbarExport")
        )

        // Action dock is match_parent width so buttons fill the bottom row without clipping off-screen
        val dockIndex = xmlContent.indexOf("id=\"@+id/cardBgActionDock\"")
        assertTrue("cardBgActionDock must be present", dockIndex != -1)
        val dockTag = xmlContent.substring(dockIndex, xmlContent.indexOf(">", dockIndex))
        assertTrue("cardBgActionDock must be match_parent width", dockTag.contains("android:layout_width=\"match_parent\""))

        // Save PNG button exists and has weighted width to stay visible alongside change and remove buttons
        val saveIndex = xmlContent.indexOf("id=\"@+id/btnBgSavePng\"")
        assertTrue("btnBgSavePng must be present", saveIndex != -1)
        val saveTag = xmlContent.substring(saveIndex, xmlContent.indexOf(">", saveIndex))
        assertTrue("btnBgSavePng must use 0dp width", saveTag.contains("android:layout_width=\"0dp\""))
        assertTrue("btnBgSavePng must have weight", saveTag.contains("android:layout_weight="))
    }

    @Test
    fun `main navigation preserves transactional back stack and workspace origin across subscreen transitions`() {
        val navFile = File("src/main/java/com/veilframe/app/navigation/MainNavigationController.kt")
        val altNavFile = File("android/app/src/main/java/com/veilframe/app/navigation/MainNavigationController.kt")
        val targetFile = if (navFile.exists()) navFile else altNavFile
        assertTrue("MainNavigationController.kt must exist", targetFile.exists())

        val content = targetFile.readText()
        assertTrue(
            "MainNavigationController must declare workspaceOriginScreen",
            content.contains("var workspaceOriginScreen: ScreenState")
        )
        assertTrue(
            "MainNavigationController must declare screenBackStack",
            content.contains("screenBackStack")
        )
        assertTrue(
            "MainNavigationController must define recordNavigationTo",
            content.contains("private fun recordNavigationTo(newScreen: ScreenState)")
        )
    }
}
