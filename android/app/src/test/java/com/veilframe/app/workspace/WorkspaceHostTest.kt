package com.veilframe.app.workspace

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.veilframe.app.R
import com.veilframe.app.databinding.ActivityMainBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkspaceHostTest {

    private lateinit var context: Context
    private lateinit var binding: ActivityMainBinding
    private lateinit var host: WorkspaceHost

    private var upscalerInflatedCount = 0
    private var docScannerInflatedCount = 0
    private var bgRemoverInflatedCount = 0
    private var motionLabInflatedCount = 0
    private var provenanceInflatedCount = 0

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.setTheme(R.style.Theme_VeilFrame)
        context = android.view.ContextThemeWrapper(app, R.style.Theme_VeilFrame)
        binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
        upscalerInflatedCount = 0
        docScannerInflatedCount = 0
        bgRemoverInflatedCount = 0
        motionLabInflatedCount = 0
        provenanceInflatedCount = 0

        host = WorkspaceHost(
            binding = binding,
            onImageUpscalerInflated = { upscalerInflatedCount++ },
            onDocScannerInflated = { docScannerInflatedCount++ },
            onBackgroundRemoverInflated = { bgRemoverInflatedCount++ },
            onMotionLabInflated = { motionLabInflatedCount++ },
            onProvenanceInflated = { provenanceInflatedCount++ }
        )
    }

    @Test
    fun `initial state has zero inflated secondary workspaces`() {
        assertFalse(host.isImageUpscalerInflated)
        assertFalse(host.isDocScannerInflated)
        assertFalse(host.isBackgroundRemoverInflated)
        assertFalse(host.isMotionLabInflated)
        assertFalse(host.isProvenanceInflated)

        assertNull(host.imageUpscalerRoot)
        assertNull(host.docScannerRoot)
        assertNull(host.backgroundRemoverRoot)
        assertNull(host.motionLabRoot)
        assertNull(host.provenanceRoot)

        assertNull(host.activeWorkspace)

        SecondaryWorkspace.values().forEach { workspace ->
            assertFalse(host.isInflated(workspace))
            assertNull(host.getRoot(workspace))
        }
    }

    @Test
    fun `getOrInflateImageUpscaler inflates lazily and caches binding`() {
        val upscalerBinding1 = host.getOrInflateImageUpscaler()
        assertNotNull(upscalerBinding1)
        assertTrue(host.isImageUpscalerInflated)
        assertNotNull(host.imageUpscalerRoot)
        assertEquals(1, upscalerInflatedCount)

        // Subsequent call returns cached instance without re-inflating
        val upscalerBinding2 = host.getOrInflateImageUpscaler()
        assertSame(upscalerBinding1, upscalerBinding2)
        assertEquals(1, upscalerInflatedCount)
    }

    @Test
    fun `getOrInflateDocScanner inflates lazily and caches binding`() {
        val docBinding1 = host.getOrInflateDocScanner()
        assertNotNull(docBinding1)
        assertTrue(host.isDocScannerInflated)
        assertNotNull(host.docScannerRoot)
        assertEquals(1, docScannerInflatedCount)

        val docBinding2 = host.getOrInflateDocScanner()
        assertSame(docBinding1, docBinding2)
        assertEquals(1, docScannerInflatedCount)
    }

    @Test
    fun `getOrInflateBackgroundRemover inflates lazily and caches binding`() {
        val bgBinding1 = host.getOrInflateBackgroundRemover()
        assertNotNull(bgBinding1)
        assertTrue(host.isBackgroundRemoverInflated)
        assertNotNull(host.backgroundRemoverRoot)
        assertEquals(1, bgRemoverInflatedCount)

        val bgBinding2 = host.getOrInflateBackgroundRemover()
        assertSame(bgBinding1, bgBinding2)
        assertEquals(1, bgRemoverInflatedCount)
    }

    @Test
    fun `getOrInflateMotionLab inflates lazily and caches binding`() {
        val motionBinding1 = host.getOrInflateMotionLab()
        assertNotNull(motionBinding1)
        assertTrue(host.isMotionLabInflated)
        assertNotNull(host.motionLabRoot)
        assertEquals(1, motionLabInflatedCount)

        val motionBinding2 = host.getOrInflateMotionLab()
        assertSame(motionBinding1, motionBinding2)
        assertEquals(1, motionLabInflatedCount)
    }

    @Test
    fun `getOrInflateProvenance inflates lazily and caches binding`() {
        val provBinding1 = host.getOrInflateProvenance()
        assertNotNull(provBinding1)
        assertTrue(host.isProvenanceInflated)
        assertNotNull(host.provenanceRoot)
        assertEquals(1, provenanceInflatedCount)

        val provBinding2 = host.getOrInflateProvenance()
        assertSame(provBinding1, provBinding2)
        assertEquals(1, provenanceInflatedCount)
    }

    @Test
    fun `activate sets activeWorkspace and returns non-null root`() {
        val root = host.activate(SecondaryWorkspace.MOTION_LAB)
        assertNotNull(root)
        assertTrue(host.isMotionLabInflated)
        assertEquals(SecondaryWorkspace.MOTION_LAB, host.activeWorkspace)
    }

    @Test
    fun `deactivate hides root and clears activeWorkspace`() {
        host.activate(SecondaryWorkspace.DOCUMENT_SCANNER)
        assertEquals(SecondaryWorkspace.DOCUMENT_SCANNER, host.activeWorkspace)

        host.deactivate(SecondaryWorkspace.DOCUMENT_SCANNER)
        assertEquals(View.GONE, host.docScannerRoot?.visibility)
        assertNull(host.activeWorkspace)
    }

    @Test
    fun `cached window insets are propagated upon lazy inflation`() {
        host.updateInsets(
            statusBarTop = 64,
            navBarBottom = 48,
            imeBottom = 0,
            navBarLeft = 0,
            navBarRight = 0
        )

        // Inflate after insets are set
        val motionRoot = host.getOrInflateMotionLab().root
        assertTrue("Motion Lab should receive bottom insets", motionRoot.paddingBottom >= 48)
        assertTrue("Motion Lab should receive top insets", motionRoot.paddingTop >= 64)

        val upscalerRoot = host.getOrInflateImageUpscaler().root
        // SCROLLED_APPBAR contract keeps padTop = false
        assertEquals("Scrolled appbar should not add top padding", 0, upscalerRoot.paddingTop)
        assertTrue("Scrolled appbar should add bottom padding", upscalerRoot.paddingBottom >= 48)
    }

    @Test
    fun `onDestroy clears bindings and active workspace`() {
        host.activate(SecondaryWorkspace.IMAGE_UPSCALER)
        assertTrue(host.isImageUpscalerInflated)

        host.onDestroy()
        assertFalse(host.isImageUpscalerInflated)
        assertNull(host.activeWorkspace)
        assertNull(host.imageUpscalerRoot)
    }
}
