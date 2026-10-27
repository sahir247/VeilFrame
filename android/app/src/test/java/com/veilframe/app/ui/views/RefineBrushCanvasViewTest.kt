package com.veilframe.app.ui.views

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Unit tests for [RefineBrushCanvasView] manual pixel refinement engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RefineBrushCanvasViewTest {

    @Test
    fun `test brush mode enum definitions`() {
        val modes = RefineBrushCanvasView.BrushMode.values()
        assertEquals(2, modes.size)
        assertNotNull(RefineBrushCanvasView.BrushMode.valueOf("ERASE"))
        assertNotNull(RefineBrushCanvasView.BrushMode.valueOf("RESTORE"))
    }

    @Test
    fun `test brush radius clamping logic`() {
        val context = RuntimeEnvironment.getApplication()
        val view = RefineBrushCanvasView(context)

        view.brushRadiusPx = 2f
        assertEquals(4f, view.brushRadiusPx, 0.01f)

        view.brushRadiusPx = 500f
        assertEquals(250f, view.brushRadiusPx, 0.01f)

        view.brushRadiusPx = 45f
        assertEquals(45f, view.brushRadiusPx, 0.01f)
    }

    @Test
    fun `test setBitmaps initialization and history setup`() {
        val context = RuntimeEnvironment.getApplication()
        val view = RefineBrushCanvasView(context)

        val source = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val cutout = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }

        var historyChangedCalled = false
        view.onHistoryChangedListener = { canUndo, canRedo ->
            historyChangedCalled = true
            assertFalse(canUndo) // Initial state has only 1 snapshot, cannot undo yet
            assertFalse(canRedo)
        }

        view.setBitmaps(source, cutout)

        assertFalse(view.canUndo())
        assertFalse(view.canRedo())
        assertTrue(historyChangedCalled)

        val refined = view.getRefinedBitmap()
        assertNotNull(refined)
        assertEquals(50, refined!!.width)
        assertEquals(50, refined.height)
    }

    @Test
    fun `test unconditional ERASE stroke clears alpha to 0`() {
        val context = RuntimeEnvironment.getApplication()
        val view = RefineBrushCanvasView(context)

        val source = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val cutout = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE) // Fully opaque initially
        }

        view.setBitmaps(source, cutout)
        view.brushMode = RefineBrushCanvasView.BrushMode.ERASE
        view.brushRadiusPx = 10f

        // Initial pixel at center is opaque white
        var current = view.getRefinedBitmap()!!
        assertEquals(255, Color.alpha(current.getPixel(20, 20)))

        // Apply ERASE dot at (20, 20)
        view.applyStrokeDirect(20f, 20f, 20f, 20f)

        current = view.getRefinedBitmap()!!
        // Pixel at center should now have alpha = 0 (unconditionally removed residue)
        assertEquals(0, Color.alpha(current.getPixel(20, 20)))
        assertTrue(view.canUndo())
        assertFalse(view.canRedo())
    }

    @Test
    fun `test unconditional RESTORE stroke restores source pixels and sets alpha to 255`() {
        val context = RuntimeEnvironment.getApplication()
        val view = RefineBrushCanvasView(context)

        // Source is vibrant blue
        val source = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        // Cutout starts completely transparent (alpha = 0)
        val cutout = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }

        view.setBitmaps(source, cutout)
        view.brushMode = RefineBrushCanvasView.BrushMode.RESTORE
        view.brushRadiusPx = 10f

        var current = view.getRefinedBitmap()!!
        assertEquals(0, Color.alpha(current.getPixel(20, 20)))

        // Apply RESTORE dot at (20, 20)
        view.applyStrokeDirect(20f, 20f, 20f, 20f)

        current = view.getRefinedBitmap()!!
        // Pixel at center should now be restored with alpha = 255 and color matching source (BLUE)
        assertEquals(255, Color.alpha(current.getPixel(20, 20)))
        assertEquals(Color.BLUE, current.getPixel(20, 20))
    }

    @Test
    fun `test undo and redo cycles`() {
        val context = RuntimeEnvironment.getApplication()
        val view = RefineBrushCanvasView(context)

        val source = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val cutout = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }

        view.setBitmaps(source, cutout)
        view.brushMode = RefineBrushCanvasView.BrushMode.ERASE
        view.brushRadiusPx = 8f

        // Initial: opaque green
        assertEquals(255, Color.alpha(view.getRefinedBitmap()!!.getPixel(15, 15)))

        // Stroke 1: Erase center
        view.applyStrokeDirect(15f, 15f, 15f, 15f)
        assertEquals(0, Color.alpha(view.getRefinedBitmap()!!.getPixel(15, 15)))
        assertTrue(view.canUndo())
        assertFalse(view.canRedo())

        // Undo -> should revert to green with alpha = 255
        view.undo()
        assertEquals(255, Color.alpha(view.getRefinedBitmap()!!.getPixel(15, 15)))
        assertEquals(Color.GREEN, view.getRefinedBitmap()!!.getPixel(15, 15))
        assertFalse(view.canUndo())
        assertTrue(view.canRedo())

        // Redo -> should re-erase center
        view.redo()
        assertEquals(0, Color.alpha(view.getRefinedBitmap()!!.getPixel(15, 15)))
        assertTrue(view.canUndo())
        assertFalse(view.canRedo())
    }

    @Test
    fun `test cleanup frees resources`() {
        val context = RuntimeEnvironment.getApplication()
        val view = RefineBrushCanvasView(context)

        val source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        val cutout = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)

        view.setBitmaps(source, cutout)
        assertNotNull(view.getRefinedBitmap())

        view.cleanup()
        assertNull(view.getRefinedBitmap())
        assertFalse(view.canUndo())
        assertFalse(view.canRedo())
    }
}
