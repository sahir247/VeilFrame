package com.veilframe.app.ui.insets

import android.content.Context
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkspaceInsetsTest {

    private lateinit var context: Context
    private lateinit var testView: View

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        testView = View(context)
    }

    @Test
    fun `initial base padding is preserved and additively augmented`() {
        // Simulates scrollTool with 160dp bottom dock clearance and 16dp top margin
        testView.setPadding(10, 20, 30, 160)

        WorkspaceInsets.apply(
            root = testView,
            statusBarTop = 50,
            navBarBottom = 48,
            imeBottom = 0,
            contract = WorkspaceInsets.DEFAULT
        )

        assertEquals("Left padding should be preserved", 10, testView.paddingLeft)
        assertEquals("Top padding should add statusBarTop", 20 + 50, testView.paddingTop)
        assertEquals("Right padding should be preserved", 30, testView.paddingRight)
        assertEquals("Bottom padding should add navBarBottom to base 160", 160 + 48, testView.paddingBottom)
    }

    @Test
    fun `scrolled appbar contract prevents double top status-bar padding`() {
        // Simulates layoutToolsCatalogue / scrollTool scrolled under AppBarLayout
        testView.setPadding(16, 16, 16, 96)

        WorkspaceInsets.apply(
            root = testView,
            statusBarTop = 64,
            navBarBottom = 48,
            imeBottom = 0,
            contract = WorkspaceInsets.SCROLLED_APPBAR
        )

        assertEquals("Top padding must not add statusBarTop under AppBarLayout", 16, testView.paddingTop)
        assertEquals("Bottom padding must add navBarBottom to base 96", 96 + 48, testView.paddingBottom)
    }

    @Test
    fun `consecutive inset dispatches are idempotent and never compound base padding`() {
        testView.setPadding(0, 0, 0, 160)

        // First dispatch
        WorkspaceInsets.apply(testView, statusBarTop = 50, navBarBottom = 48, imeBottom = 0)
        assertEquals(208, testView.paddingBottom)

        // Second dispatch with same insets
        WorkspaceInsets.apply(testView, statusBarTop = 50, navBarBottom = 48, imeBottom = 0)
        assertEquals("Padding must not compound on second dispatch", 208, testView.paddingBottom)

        // Third dispatch with different insets (e.g. rotation)
        WorkspaceInsets.apply(testView, statusBarTop = 30, navBarBottom = 24, imeBottom = 0)
        assertEquals("Padding must recompute cleanly from initial base 160", 160 + 24, testView.paddingBottom)
    }

    @Test
    fun `IME keyboard overrides navBarBottom when larger and restores on dismiss`() {
        testView.setPadding(0, 0, 0, 160)

        // Keyboard open (IME = 320px > navBar = 48px)
        WorkspaceInsets.apply(testView, statusBarTop = 50, navBarBottom = 48, imeBottom = 320)
        assertEquals("Bottom padding must accommodate keyboard", 160 + 320, testView.paddingBottom)

        // Keyboard closed (IME = 0px)
        WorkspaceInsets.apply(testView, statusBarTop = 50, navBarBottom = 48, imeBottom = 0)
        assertEquals("Bottom padding must restore to navBar clearance", 160 + 48, testView.paddingBottom)
    }

    @Test
    fun `immersive contract leaves all initial paddings untouched`() {
        testView.setPadding(12, 24, 36, 48)

        WorkspaceInsets.apply(
            testView,
            statusBarTop = 60,
            navBarBottom = 50,
            imeBottom = 200,
            contract = WorkspaceInsets.IMMERSIVE
        )

        assertEquals(12, testView.paddingLeft)
        assertEquals(24, testView.paddingTop)
        assertEquals(36, testView.paddingRight)
        assertEquals(48, testView.paddingBottom)
    }
}
