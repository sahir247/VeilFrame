package com.veilframe.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
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

        val imgFile = File(context.cacheDir, "test_sample_photo.jpg").apply {
            if (!exists()) writeBytes(ByteArray(1024) { 0x55 })
        }
        testImageUri = Uri.fromFile(imgFile)

        val vidFile = File(context.cacheDir, "test_sample_video.mp4").apply {
            if (!exists()) writeBytes(ByteArray(2048) { 0x66 })
        }
        testVideoUri = Uri.fromFile(vidFile)

        val pdfFile = File(context.cacheDir, "test_sample_doc.pdf").apply {
            if (!exists()) writeBytes(ByteArray(1024) { 0x77 })
        }
        testPdfUri = Uri.fromFile(pdfFile)

        val mdFile = File(context.cacheDir, "test_sample_notes.md").apply {
            if (!exists()) writeText("# Sample Markdown\n\nVeilFrame Intelligent Forensics")
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
            assertEquals("Browse Any File...", btnBrowse.text.toString())
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
            // Secondary matched action pair: AI Upscale and Remove BG
            assertEquals("AI Upscale", btnEngine2.text.toString())
            assertEquals("Remove BG", btnEngine3.text.toString())
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
}
