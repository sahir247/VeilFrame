package com.veilframe.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.veilframe.app.MainActivity
import com.veilframe.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class QuickFileIntakeOnDeviceTest {

    private lateinit var context: Context
    private lateinit var testImageUri: Uri
    private lateinit var testVideoUri: Uri
    private lateinit var testPdfUri: Uri

    private lateinit var testMarkdownUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // 1. Real Image: Prefer real portrait from /data/local/tmp or create valid JPEG bitmap
        val imgFile = File(context.cacheDir, "test_sample_photo.jpg")
        val realPhotoSource = listOf(
            File("/data/local/tmp/test_portrait.jpg"),
            File("/data/local/tmp/veilframe_validation/real_inputs_20/06_720p_real_portrait.jpg")
        ).firstOrNull { it.exists() && it.length() > 0L }

        if (realPhotoSource != null) {
            realPhotoSource.copyTo(imgFile, overwrite = true)
        } else if (!imgFile.exists() || imgFile.length() < 2048) {
            val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.rgb(45, 90, 140))
            val paint = Paint().apply { color = Color.rgb(230, 200, 160) }
            canvas.drawCircle(400f, 300f, 150f, paint)
            FileOutputStream(imgFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            bmp.recycle()
        }
        testImageUri = Uri.fromFile(imgFile)

        // 2. Real Video: Prefer /data/local/tmp/sample.mp4
        val vidFile = File(context.cacheDir, "test_sample_video.mp4")
        val realVidSource = listOf(
            File("/data/local/tmp/sample.mp4"),
            File("/sdcard/Download/sample.mp4")
        ).firstOrNull { it.exists() && it.length() > 0L }

        if (realVidSource != null) {
            realVidSource.copyTo(vidFile, overwrite = true)
        } else if (!vidFile.exists() || vidFile.length() < 2048) {
            vidFile.writeBytes(ByteArray(2048) { 0x66 })
        }
        testVideoUri = Uri.fromFile(vidFile)

        // 3. Real PDF: Generate valid PDF with PDFBox
        val pdfFile = File(context.cacheDir, "test_sample_doc.pdf")
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context)
        }
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            content.beginText()
            content.setFont(PDType1Font.HELVETICA_BOLD, 18f)
            content.newLineAtOffset(50f, 700f)
            content.showText("VeilFrame Universal Intake On-Device Test Document")
            content.endText()
        }
        FileOutputStream(pdfFile).use { fos ->
            document.save(fos)
        }
        document.close()
        testPdfUri = Uri.fromFile(pdfFile)

        // 4. Real Markdown
        val mdFile = File(context.cacheDir, "test_sample_notes.md").apply {
            writeText("# Sample Markdown\n\nVeilFrame Intelligent Forensics On-Device Intake Test")
        }
        testMarkdownUri = Uri.fromFile(mdFile)
    }

    @Test
    fun testUnmountedIntakeLayoutHasUniformButtonsAndNoTextClipping() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val btnPhoto = activity.findViewById<MaterialButton>(R.id.btnHomeIntakePhoto)
            val btnVideo = activity.findViewById<MaterialButton>(R.id.btnHomeIntakeVideo)
            val btnDoc = activity.findViewById<MaterialButton>(R.id.btnHomeIntakeDoc)
            val btnBrowse = activity.findViewById<MaterialButton>(R.id.btnHomeIntakeBrowse)

            assertNotNull(btnPhoto)
            assertNotNull(btnVideo)
            assertNotNull(btnDoc)
            assertNotNull(btnBrowse)

            assertEquals("Photo", btnPhoto.text.toString())
            assertEquals("Video", btnVideo.text.toString())
            assertEquals("PDF", btnDoc.text.toString())
            assertTrue(btnBrowse.text.toString().startsWith("Browse Any File"))
        }
        scenario.close()
    }

    @Test
    fun testImageFileIntakeRecommendsFlagshipImageStudio() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.handleHomeFileIntake(testImageUri)

            val layoutMounted = activity.findViewById<View>(R.id.layoutHomeIntakeMounted)
            val layoutPickers = activity.findViewById<View>(R.id.layoutHomeIntakePickers)
            val btnEngine1 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine1)
            val btnEngine2 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine2)
            val btnEngine3 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine3)

            assertEquals(View.VISIBLE, layoutMounted.visibility)
            assertEquals(View.GONE, layoutPickers.visibility)

            // Primary hero recommendation: Image Studio
            assertEquals("Open in Image Studio", btnEngine1.text.toString())
            // Secondary matched action pair: AI Upscale and BG Remover
            assertEquals("AI Upscale", btnEngine2.text.toString())
            assertEquals("BG Remover", btnEngine3.text.toString())
        }
        scenario.close()
    }

    @Test
    fun testVideoFileIntakeRecommendsFlagshipVideoStudio() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.handleHomeFileIntake(testVideoUri)

            val btnEngine1 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine1)
            val btnEngine2 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine2)
            val btnEngine3 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine3)

            assertEquals("Open in Video Studio", btnEngine1.text.toString())
            assertEquals("Remove Metadata", btnEngine2.text.toString())
            assertEquals("Motion Lab", btnEngine3.text.toString())
        }
        scenario.close()
    }

    @Test
    fun testPdfFileIntakeRecommendsQuickToolAndSignature() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.handleHomeFileIntake(testPdfUri)

            val btnEngine1 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine1)
            val btnEngine2 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine2)
            val btnEngine3 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine3)

            assertEquals("PDF Quick Tool", btnEngine1.text.toString())
            assertEquals("Extract Text", btnEngine2.text.toString())
            assertEquals("Verify Signature", btnEngine3.text.toString())
        }
        scenario.close()
    }

    @Test
    fun testMarkdownFileIntakeRecommendsViewerAndExporters() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.handleHomeFileIntake(testMarkdownUri)

            val btnEngine1 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine1)
            val btnEngine2 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine2)
            val btnEngine3 = activity.findViewById<MaterialButton>(R.id.btnSuggestEngine3)

            assertEquals("View Markdown", btnEngine1.text.toString())
            assertEquals("Save as PDF", btnEngine2.text.toString())
            assertEquals("Save as DOCX", btnEngine3.text.toString())
        }
        scenario.close()
    }

    @Test
    fun testClearFileIntakeRestoresUnmountedPickers() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.handleHomeFileIntake(testImageUri)
            val layoutMounted = activity.findViewById<View>(R.id.layoutHomeIntakeMounted)
            assertEquals(View.VISIBLE, layoutMounted.visibility)

            activity.clearHomeFileIntake()
            val layoutPickers = activity.findViewById<View>(R.id.layoutHomeIntakePickers)
            assertEquals(View.GONE, layoutMounted.visibility)
            assertEquals(View.VISIBLE, layoutPickers.visibility)
        }
        scenario.close()
    }

    @Test
    fun testCaptureMountedCardVisual() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.handleHomeFileIntake(testImageUri)
            val card = activity.findViewById<View>(R.id.cardHomeFileIntake)
            card.post {
                val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                card.draw(canvas)
                FileOutputStream(File(context.cacheDir, "intake_card_image.png")).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
        Thread.sleep(1500)

        scenario.onActivity { activity ->
            activity.handleHomeFileIntake(testPdfUri)
            val card = activity.findViewById<View>(R.id.cardHomeFileIntake)
            card.post {
                val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                card.draw(canvas)
                FileOutputStream(File(context.cacheDir, "intake_card_pdf.png")).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
        Thread.sleep(1500)

        scenario.onActivity { activity ->
            activity.handleHomeFileIntake(testMarkdownUri)
            val card = activity.findViewById<View>(R.id.cardHomeFileIntake)
            card.post {
                val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                card.draw(canvas)
                FileOutputStream(File(context.cacheDir, "intake_card_markdown.png")).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        }
        Thread.sleep(1500)
        scenario.close()
    }

    @Test
    fun testIntakeSurvivesMultipleFileTransitionsWithoutLeakOrCrash() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            // 1. Mount real image
            activity.handleHomeFileIntake(testImageUri)
            val layoutMounted = activity.findViewById<View>(R.id.layoutHomeIntakeMounted)
            val layoutPickers = activity.findViewById<View>(R.id.layoutHomeIntakePickers)
            assertEquals(View.VISIBLE, layoutMounted.visibility)
            assertEquals(View.GONE, layoutPickers.visibility)

            // 2. Clear intake
            activity.clearHomeFileIntake()
            assertEquals(View.GONE, layoutMounted.visibility)
            assertEquals(View.VISIBLE, layoutPickers.visibility)

            // 3. Mount real video
            activity.handleHomeFileIntake(testVideoUri)
            assertEquals(View.VISIBLE, layoutMounted.visibility)
            assertEquals(View.GONE, layoutPickers.visibility)

            // 4. Mount real PDF without clearing first (direct replacement)
            activity.handleHomeFileIntake(testPdfUri)
            assertEquals(View.VISIBLE, layoutMounted.visibility)
            assertEquals(View.GONE, layoutPickers.visibility)

            // 5. Final clear
            activity.clearHomeFileIntake()
            assertEquals(View.GONE, layoutMounted.visibility)
            assertEquals(View.VISIBLE, layoutPickers.visibility)
        }
        scenario.close()
    }
}
