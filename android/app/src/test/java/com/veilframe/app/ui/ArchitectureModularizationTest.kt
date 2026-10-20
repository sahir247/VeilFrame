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
    fun `rembg onnx session manager is deduplicated from upscale onnx session manager`() {
        // RembgOnnxSessionManager class must exist in segmentation.rembg
        val rembgClass = try {
            Class.forName("com.veilframe.app.cv.segmentation.rembg.RembgOnnxSessionManager")
        } catch (_: ClassNotFoundException) {
            null
        }
        assertNotNull("RembgOnnxSessionManager class must exist in com.veilframe.app.cv.segmentation.rembg", rembgClass)

        // Upscale does not use ONNX at all (strictly native NCNN / RealSR)
        val upscaleClass = try {
            Class.forName("com.veilframe.app.upscale.inference.OnnxSessionManager")
        } catch (_: ClassNotFoundException) {
            null
        }
        assertNull("Upscale must not have OnnxSessionManager (strictly native NCNN/RealSR)", upscaleClass)

        // Check OnnxSessionManager.kt in rembg is marked as deprecated typealias
        val rembgFile = File("src/main/java/com/veilframe/app/cv/segmentation/rembg/OnnxSessionManager.kt")
        val altRembgFile = File("android/app/src/main/java/com/veilframe/app/cv/segmentation/rembg/OnnxSessionManager.kt")
        val targetFile = if (rembgFile.exists()) rembgFile else altRembgFile
        assertTrue("rembg/OnnxSessionManager.kt must exist", targetFile.exists())

        val content = targetFile.readText()
        assertTrue("rembg/OnnxSessionManager must be deprecated", content.contains("@Deprecated"))
        assertTrue("rembg/OnnxSessionManager must typealias to RembgOnnxSessionManager", content.contains("typealias OnnxSessionManager = RembgOnnxSessionManager"))
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

        // Size check: Monolithic activity was 2,490 lines. Post-refactoring must be well under 2,000 lines.
        val lineCount = content.lines().size
        assertTrue(
            "MainActivity line count ($lineCount) should be significantly reduced (< 2,000 lines)",
            lineCount < 2000
        )
    }
}
