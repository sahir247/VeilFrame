package com.veilframe.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates architectural refactoring, size reduction, class deduplication,
 * and UI theme tone adaptability across VeilFrame components.
 */
class ArchitectureModularizationTest {

    @Test
    fun `tools catalogue layout uses dynamic colorPrimary attribute for icons and search views`() {
        val layoutFile = File("src/main/res/layout/layout_tools_catalogue.xml")
        val altLayoutFile = File("android/app/src/main/res/layout/layout_tools_catalogue.xml")
        val targetFile = if (layoutFile.exists()) layoutFile else altLayoutFile
        assertTrue("layout_tools_catalogue.xml must exist", targetFile.exists())

        val xmlContent = targetFile.readText()

        // Verify that no tool card icons are hardcoded to static vf_primary
        assertFalse(
            "Tool card icons must not use hardcoded @color/vf_primary tint",
            xmlContent.contains("app:tint=\"@color/vf_primary\"")
        )

        // Verify that dynamic ?attr/colorPrimary is used for tool card icon tints
        assertTrue(
            "Tool card icons must use dynamic ?attr/colorPrimary tint for theme adaptation",
            xmlContent.contains("app:tint=\"?attr/colorPrimary\"")
        )

        // Verify search view stroke and icon tints adapt dynamically
        assertTrue(
            "Search view stroke must use ?attr/colorPrimary",
            xmlContent.contains("app:boxStrokeColor=\"?attr/colorPrimary\"")
        )
        assertTrue(
            "Search view start icon must use ?attr/colorPrimary",
            xmlContent.contains("app:startIconTint=\"?attr/colorPrimary\"")
        )
    }

    @Test
    fun `neural pipelines use NCNN and do not retain legacy OnnxSessionManager`() {
        // RembgSessionManager class must exist in segmentation.rembg
        val rembgClass = try {
            Class.forName("com.veilframe.app.cv.segmentation.rembg.RembgSessionManager")
        } catch (_: ClassNotFoundException) {
            null
        }
        assertNotNull("RembgSessionManager class must exist in com.veilframe.app.cv.segmentation.rembg", rembgClass)

        // Upscale does not use ONNX at all (strictly native NCNN / RealSR)
        val upscaleClass = try {
            Class.forName("com.veilframe.app.upscale.inference.OnnxSessionManager")
        } catch (_: ClassNotFoundException) {
            null
        }
        assertNull("Upscale must not have OnnxSessionManager (strictly native NCNN/RealSR)", upscaleClass)

        // Verify OnnxSessionManager.kt has been purged from source
        val rembgFile = File("src/main/java/com/veilframe/app/cv/segmentation/rembg/OnnxSessionManager.kt")
        val altRembgFile = File("android/app/src/main/java/com/veilframe/app/cv/segmentation/rembg/OnnxSessionManager.kt")
        val targetFile = if (rembgFile.exists()) rembgFile else altRembgFile
        assertFalse("rembg/OnnxSessionManager.kt must be purged", targetFile.exists())
    }

    @Test
    fun `test DeterministicSvgRasterizer aliases production class without duplicate implementation`() {
        val testRasterizer = File("src/test/java/com/veilframe/app/qr/DeterministicSvgRasterizer.kt")
        val altTestRasterizer = File("android/app/src/test/java/com/veilframe/app/qr/DeterministicSvgRasterizer.kt")
        val targetFile = if (testRasterizer.exists()) testRasterizer else altTestRasterizer
        assertTrue("test DeterministicSvgRasterizer.kt must exist", targetFile.exists())

        val content = targetFile.readText()
        assertTrue(
            "Test DeterministicSvgRasterizer must typealias to canonical production class",
            content.contains("typealias DeterministicSvgRasterizer = com.veilframe.app.qr.raster.DeterministicSvgRasterizer")
        )
    }

    @Test
    fun `main activity delegates to modular coordinators and maintains optimal file size`() {
        val actFile = File("src/main/java/com/veilframe/app/MainActivity.kt")
        val altActFile = File("android/app/src/main/java/com/veilframe/app/MainActivity.kt")
        val targetFile = if (actFile.exists()) actFile else altActFile
        assertTrue("MainActivity.kt must exist", targetFile.exists())

        val content = targetFile.readText()

        // Verifies delegation to the 4 extracted coordinators
        assertTrue("MainActivity must wire ToolsCatalogueCoordinator", content.contains("toolsCatalogueCoordinator = ToolsCatalogueCoordinator"))
        assertTrue("MainActivity must wire LibraryCoordinator", content.contains("libraryCoordinator = LibraryCoordinator"))
        assertTrue("MainActivity must wire SettingsOverlayCoordinator", content.contains("settingsOverlayCoordinator = SettingsOverlayCoordinator"))
        assertTrue("MainActivity must wire MainDialogHelper", content.contains("MainDialogHelper.showDiagnosticsDialog"))

        // Size check: Monolithic activity was 2,490 lines. Post-refactoring must stay disciplined (< 2,300 lines).
        val lineCount = content.lines().size
        assertTrue(
            "MainActivity line count ($lineCount) should remain disciplined (< 2,300 lines)",
            lineCount < 2300
        )
    }
}
