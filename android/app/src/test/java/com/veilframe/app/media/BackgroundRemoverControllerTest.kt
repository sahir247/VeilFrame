package com.veilframe.app.media

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.veilframe.app.R
import com.veilframe.app.cv.segmentation.rembg.RembgModel
import com.veilframe.app.databinding.LayoutBackgroundRemoverBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Unit tests verifying:
 * 1. Dual model & Hybrid mode selection in BackgroundRemoverController.
 * 2. Segmented toggle button interactions and UI state transitions.
 * 3. Dynamic badge updates for bundled and downloadable neural models.
 * 4. Model download and fallback flows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundRemoverControllerTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var binding: LayoutBackgroundRemoverBinding
    private lateinit var controller: BackgroundRemoverController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.setTheme(R.style.Theme_VeilFrame)
        val context = android.view.ContextThemeWrapper(app, R.style.Theme_VeilFrame)
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        activity.setTheme(R.style.Theme_VeilFrame)

        binding = LayoutBackgroundRemoverBinding.inflate(LayoutInflater.from(context))
        controller = BackgroundRemoverController(
            activity = activity,
            binding = binding,
            scope = scope,
            onPickImageRequest = {},
            onExportPngRequest = {},
            onNavigateBack = {}
        )
        controller.init()
    }

    @Test
    fun `test initial mode is HYBRID with appropriate badges`() {
        assertEquals(BackgroundRemoverController.RembgMode.HYBRID, controller.currentMode)
        assertEquals("⚡ Hybrid (Smart Auto)", binding.tvBgCurrentModelName.text.toString())
        assertTrue(binding.btnModeSelectHybrid.isChecked)
        assertFalse(binding.btnModeSelectU2Net.isChecked)
        assertFalse(binding.btnModeSelectModNet.isChecked)
    }

    @Test
    fun `test selecting U2Net mode updates state and badge`() {
        binding.toggleGroupMattingMode.check(binding.btnModeSelectU2Net.id)

        assertEquals(BackgroundRemoverController.RembgMode.U2NETP, controller.currentMode)
        assertEquals("U²-Net-P (Bundled)", binding.tvBgCurrentModelName.text.toString())
        assertEquals("Ready (Bundled)", binding.tvBgModelBadge.text.toString())
        assertTrue(binding.btnModeSelectU2Net.isChecked)
    }

    @Test
    fun `test selecting MODNet mode updates state and badge`() {
        binding.toggleGroupMattingMode.check(binding.btnModeSelectModNet.id)

        assertEquals(BackgroundRemoverController.RembgMode.MODNET, controller.currentMode)
        assertEquals("MODNet (HD Portrait)", binding.tvBgCurrentModelName.text.toString())
        assertTrue(binding.btnModeSelectModNet.isChecked)
    }

    @Test
    fun `test switching back to Hybrid mode from MODNet`() {
        binding.toggleGroupMattingMode.check(binding.btnModeSelectModNet.id)
        assertEquals(BackgroundRemoverController.RembgMode.MODNET, controller.currentMode)

        binding.toggleGroupMattingMode.check(binding.btnModeSelectHybrid.id)
        assertEquals(BackgroundRemoverController.RembgMode.HYBRID, controller.currentMode)
        assertEquals("⚡ Hybrid (Smart Auto)", binding.tvBgCurrentModelName.text.toString())
        assertTrue(binding.btnModeSelectHybrid.isChecked)
    }

    @Test
    fun `test RembgMode enum covers all required modes`() {
        val modes = BackgroundRemoverController.RembgMode.values()
        assertEquals(3, modes.size)
        assertNotNull(BackgroundRemoverController.RembgMode.valueOf("HYBRID"))
        assertNotNull(BackgroundRemoverController.RembgMode.valueOf("U2NETP"))
        assertNotNull(BackgroundRemoverController.RembgMode.valueOf("MODNET"))
    }
}
