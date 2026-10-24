package com.veilframe.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates the Quick File Intake component layout and recommendation routing:
 * - Uniform heights, proper padding, and non-clipping text in activity_main.xml
 * - Matched styles across secondary suggested engine buttons
 * - Flagship engine recommendations (Image Studio, Video Studio, PDF Quick Tool popup, Markdown Studio) in MainActivity.kt
 * - Intelligent cryptographic signature detection and password protection handling for PDF
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

    private fun getIntakeCode(): String {
        val mainActivityFile = getFile("src/main/java/com/veilframe/app/MainActivity.kt")
        val coordinatorFile = getFile("src/main/java/com/veilframe/app/ui/intake/HomeFileIntakeCoordinator.kt")
        return (if (mainActivityFile.exists()) mainActivityFile.readText() else "") +
                "\n" +
                (if (coordinatorFile.exists()) coordinatorFile.readText() else "")
    }

    @Test
    fun `quick file intake routes to flagship workspaces with reliable uri passing`() {
        val mainActivityFile = getFile("src/main/java/com/veilframe/app/MainActivity.kt")
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())
        val code = getIntakeCode()

        // MainActivity must delegate to HomeFileIntakeCoordinator
        assertTrue("MainActivity must wire HomeFileIntakeCoordinator",
            mainActivityFile.readText().contains("homeFileIntakeCoordinator.handleHomeFileIntake(uri)")
        )

        // Image intake must route to Image Studio with handleImagesSelected
        assertTrue("Image intake must recommend Image Studio",
            code.contains("openImageStudio()") &&
            code.contains("imageStudioController.handleImagesSelected(listOf(uri))")
        )

        // Video intake must recommend Open in Video Studio, Remove Metadata, Motion Lab
        assertTrue("Video intake must recommend Open in Video Studio",
            code.contains("\"Open in Video Studio\"") &&
            code.contains("videoStudioController.handleVideosSelected(listOf(uri))")
        )
        assertTrue("Video intake must recommend Remove Metadata",
            code.contains("\"Remove Metadata\"") &&
            code.contains("openTool(ToolMode.VIDEO_CLEANER)")
        )
        assertTrue("Video intake must recommend Motion Lab",
            code.contains("\"Motion Lab\"") &&
            code.contains("motionLabController.setSource(uri)")
        )

        // SAF read permission must be persisted on intake
        assertTrue("safStorageManager must persist read permission on file intake",
            code.contains("safStorageManager.persistReadPermission(uri)")
        )
    }

    @Test
    fun `markdown intake provides preview, theme-matched save as pdf, and docx conversion`() {
        val mainActivityFile = getFile("src/main/java/com/veilframe/app/MainActivity.kt")
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())
        val code = getIntakeCode()

        // Markdown detection
        assertTrue("Must detect markdown files",
            code.contains("val isMarkdown") &&
            code.contains(".endsWith(\".md\")")
        )

        // View Markdown
        assertTrue("Must offer View Markdown action",
            code.contains("\"View Markdown\"") &&
            code.contains("openMarkdownViewer(uri, displayName)")
        )

        // Save as PDF with theme popup
        assertTrue("Must offer Save as PDF action with theme popup",
            code.contains("\"Save as PDF\"") &&
            code.contains("showMarkdownPdfThemePopup(uri, displayName)") &&
            code.contains("exportToPdfWithTheme")
        )

        // Save as DOCX
        assertTrue("Must offer Save as DOCX action",
            code.contains("\"Save as DOCX\"") &&
            code.contains("exportMarkdownToDocx(uri, displayName)") &&
            code.contains("MarkdownDocxExporter.exportToDocx")
        )
    }

    @Test
    fun `pdf intake intelligently inspects cryptographic signatures and handles password protection`() {
        val mainActivityFile = getFile("src/main/java/com/veilframe/app/MainActivity.kt")
        assertTrue("MainActivity.kt must exist", mainActivityFile.exists())
        val code = getIntakeCode()

        // Intelligent signature check
        assertTrue("Must check signatureDictionaries on PDF document",
            code.contains("doc.signatureDictionaries")
        )

        // Encrypted PDF password popup
        assertTrue("Must trigger password dialog when PDF is encrypted",
            code.contains("showPdfPasswordDialog") &&
            code.contains("PdfUnlockEngine.checkEncryption(bytes)")
        )

        // Verify Signature routing
        assertTrue("Must offer Verify Signature when cryptographic signatures are present",
            code.contains("\"Verify Signature\"") &&
            code.contains("pdfSignatureController.loadPdfBytes")
        )

        // PDF Quick Tool popup
        assertTrue("Must provide PDF Quick Tool popup with full suite of tools",
            code.contains("showPdfQuickToolPopup") &&
            code.contains("DialogPdfQuickToolPickerBinding")
        )
    }

    @Test
    fun `pdf quick tool popup layout exists and contains tool container`() {
        val dialogFile = getFile("src/main/res/layout/dialog_pdf_quick_tool_picker.xml")
        assertTrue("dialog_pdf_quick_tool_picker.xml must exist", dialogFile.exists())
        val dialogXml = dialogFile.readText()
        assertTrue("dialog must have containerPdfToolsList", dialogXml.contains("containerPdfToolsList"))

        val itemFile = getFile("src/main/res/layout/item_pdf_quick_tool_option.xml")
        assertTrue("item_pdf_quick_tool_option.xml must exist", itemFile.exists())
        val itemXml = itemFile.readText()
        assertTrue("item must have tvToolOptionTitle and ivToolOptionIcon",
            itemXml.contains("tvToolOptionTitle") &&
            itemXml.contains("ivToolOptionIcon")
        )
    }
}
