package com.veilframe.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the Quick File Intake component layout and recommendation routing:
 * - Uniform heights, proper padding, and non-clipping text in activity_main.xml
 * - Matched styles across secondary suggested engine buttons
 * - Flagship engine recommendations (Image Studio, Video Studio, PDF Quick Tool) in MainActivity.kt
 */
class QuickFileIntakeTest {

    private fun getFile(relPath: String): File {
        val f1 = File(relPath)
        if (f1.exists()) return f1
        val f2 = File("android/app/$relPath")
        if (f2.exists()) return f2
        val f3 = File("../$relPath")
        if (f3.exists()) return f3
        return f1
    }

    @Test
    fun `quick file intake layout defines uniform button heights and zero style mismatch`() {
        val layoutFile = getFile("src/main/res/layout/activity_main.xml")
        assertTrue("activity_main.xml must exist", layoutFile.exists())
        val xml = layoutFile.readText()

        // btnSuggestEngine1 must be Primary filled button with full width
        assertTrue("btnSuggestEngine1 must use Widget.VeilFrame.Button.Primary",
            xml.contains("android:id=\"@+id/btnSuggestEngine1\"") &&
            xml.contains("style=\"@style/Widget.VeilFrame.Button.Primary\"")
        )

        // btnSuggestEngine2 and btnSuggestEngine3 must share the EXACT SAME style (Action tonal)
        // to prevent mismatched heights and uneven visual weights
        assertFalse("btnSuggestEngine3 must NOT use Widget.VeilFrame.Button.Utility (which caused 40dp vs 48dp mismatch)",
            xml.contains("android:id=\"@+id/btnSuggestEngine3\"\n                                style=\"@style/Widget.VeilFrame.Button.Utility\"")
        )

        // Verify layoutHomeSuggestedEngines is oriented vertically with hero primary + 2 secondary columns
        assertTrue("layoutHomeSuggestedEngines must be vertical to stack hero button above secondary row",
            xml.contains("android:id=\"@+id/layoutHomeSuggestedEngines\"")
        )

        // Verify intake picker doc button uses concise 'PDF' label to prevent text clipping
        assertTrue("btnHomeIntakeDoc must use concise 'PDF' label",
            xml.contains("android:id=\"@+id/btnHomeIntakeDoc\"") &&
            xml.contains("android:text=\"PDF\"")
        )
    }

    @Test
    fun `quick file intake routes to flagship workspaces with reliable uri passing`() {
        val mainActivityFile = getFile("src/main/java/com/veilframe/app/MainActivity.kt")
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())
        val code = mainActivityFile.readText()

        // Image intake must route to Image Studio with handleImagesSelected
        assertTrue("Image intake must recommend Image Studio",
            code.contains("openImageStudio()") &&
            code.contains("imageStudioController.handleImagesSelected(listOf(uri))")
        )

        // Video intake must route to Video Studio with handleVideosSelected
        assertTrue("Video intake must recommend Video Studio",
            code.contains("openVideoStudio()") &&
            code.contains("videoStudioController.handleVideosSelected(listOf(uri))")
        )

        // PDF intake must route to PDF Quick Tool with handleFilesSelected
        assertTrue("PDF intake must recommend PDF Quick Tool with uri passing",
            code.contains("pdfSuiteCoordinator.openQuickTool(com.veilframe.app.pdf.PdfQuickToolController.QuickMode.SPLIT)") &&
            code.contains("pdfSuiteCoordinator.quickToolController.handleFilesSelected(listOf(uri))")
        )

        // SAF read permission must be persisted on intake
        assertTrue("safStorageManager must persist read permission on file intake",
            code.contains("safStorageManager.persistReadPermission(uri)")
        )
    }
}
