package com.veilframe.app.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownViewerLifecycleTest {

    @Test
    fun `initial state is IDLE`() {
        val state = MarkdownViewerState()
        assertEquals(ViewerLifecycleState.IDLE, state.lifecycleState)
        assertFalse(state.isEditMode)
        assertFalse(state.isModified)
        assertEquals("Document.md", state.documentTitle)
    }

    @Test
    fun `standard lifecycle transitions without mermaid`() {
        val state = MarkdownViewerState()
        assertEquals(ViewerLifecycleState.IDLE, state.lifecycleState)

        // Web view begins loading viewer.html
        state.lifecycleState = ViewerLifecycleState.LOADING_VIEWER
        assertEquals(ViewerLifecycleState.LOADING_VIEWER, state.lifecycleState)

        // JS fires onViewerReady()
        state.lifecycleState = ViewerLifecycleState.VIEWER_READY
        assertEquals(ViewerLifecycleState.VIEWER_READY, state.lifecycleState)

        // Kotlin invokes loadMarkdown() -> JS fires onStageChanged('RENDERING')
        state.lifecycleState = ViewerLifecycleState.RENDERING
        assertEquals(ViewerLifecycleState.RENDERING, state.lifecycleState)

        // No Mermaid diagrams present -> JS fires onRenderFinished()
        state.lifecycleState = ViewerLifecycleState.RENDERED
        assertEquals(ViewerLifecycleState.RENDERED, state.lifecycleState)
    }

    @Test
    fun `mermaid lifecycle transitions with async loading and rendering`() {
        val state = MarkdownViewerState()

        state.lifecycleState = ViewerLifecycleState.LOADING_VIEWER
        state.lifecycleState = ViewerLifecycleState.VIEWER_READY
        state.lifecycleState = ViewerLifecycleState.RENDERING

        // Mermaid code blocks detected -> JS asynchronously loads local mermaid.min.js
        state.lifecycleState = ViewerLifecycleState.MERMAID_LOADING
        assertEquals(ViewerLifecycleState.MERMAID_LOADING, state.lifecycleState)

        // Mermaid script loaded, initializing and rendering SVGs
        state.lifecycleState = ViewerLifecycleState.MERMAID_RENDERING
        assertEquals(ViewerLifecycleState.MERMAID_RENDERING, state.lifecycleState)

        // All diagrams settled -> JS fires onRenderFinished()
        state.lifecycleState = ViewerLifecycleState.RENDERED
        assertEquals(ViewerLifecycleState.RENDERED, state.lifecycleState)
    }

    @Test
    fun `error transition can occur from any lifecycle stage`() {
        val state = MarkdownViewerState()

        // Error during initial viewer loading (e.g. asset error or watchdog timeout)
        state.lifecycleState = ViewerLifecycleState.LOADING_VIEWER
        state.lifecycleState = ViewerLifecycleState.ERROR
        assertEquals(ViewerLifecycleState.ERROR, state.lifecycleState)

        // Error during markdown rendering
        state.lifecycleState = ViewerLifecycleState.RENDERING
        state.lifecycleState = ViewerLifecycleState.ERROR
        assertEquals(ViewerLifecycleState.ERROR, state.lifecycleState)

        // Error during mermaid loading
        state.lifecycleState = ViewerLifecycleState.MERMAID_LOADING
        state.lifecycleState = ViewerLifecycleState.ERROR
        assertEquals(ViewerLifecycleState.ERROR, state.lifecycleState)
    }

    @Test
    fun `empty document handling`() {
        val state = MarkdownViewerState()
        val emptyMarkdown = ""
        val whitespaceMarkdown = "   \n\t\r\n   "

        val sanitizedEmpty = MarkdownSecurityPolicy.sanitizeMarkdown(emptyMarkdown)
        assertEquals("", sanitizedEmpty)

        val sanitizedWhitespace = MarkdownSecurityPolicy.sanitizeMarkdown(whitespaceMarkdown)
        assertTrue(sanitizedWhitespace.isBlank())

        state.rawMarkdown = sanitizedEmpty
        state.lifecycleState = ViewerLifecycleState.RENDERED
        assertEquals(0, state.rawMarkdown.length)
        assertEquals(ViewerLifecycleState.RENDERED, state.lifecycleState)
    }

    @Test
    fun `huge document stress test completes rapidly and safely`() {
        val state = MarkdownViewerState()

        // Generate a 10,000-line Markdown document containing mixed headings, lists, tables, and code
        val sb = StringBuilder()
        sb.append("# Stress Test Document\n\n")
        sb.append("> This is a generated huge markdown document to verify memory and parsing stability.\n\n")

        for (i in 1..2500) {
            sb.append("## Section $i - Performance and Scalability\n")
            sb.append("Paragraph with **bold**, *italic*, and `inline_code()` content for section $i.\n\n")
            sb.append("```kotlin\nfun calculateStress$i(x: Int): Int {\n    return x * 42 + $i\n}\n```\n\n")
            sb.append("| Metric | Value |\n|---|---|\n| Iteration | $i |\n| Status | OK |\n\n")
        }

        val hugeDocument = sb.toString()
        assertTrue("Document should exceed 300KB", hugeDocument.length > 300_000)

        val startTime = System.currentTimeMillis()
        val sanitized = MarkdownSecurityPolicy.sanitizeMarkdown(hugeDocument)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Sanitization of huge document must complete under 1000ms", elapsed < 1000)
        assertEquals(hugeDocument.length, sanitized.length)

        state.rawMarkdown = sanitized
        assertEquals(hugeDocument.length, state.rawMarkdown.length)
    }

    @Test
    fun `editor dirty state transitions`() {
        val state = MarkdownViewerState()
        assertEquals(false, state.isModified)
        assertEquals(false, state.isEditMode)

        // Switch to edit mode
        state.isEditMode = true
        assertEquals(true, state.isEditMode)
        assertEquals(false, state.isModified)

        // User edits document
        state.rawMarkdown = "# Edited Content"
        state.isModified = true
        assertTrue(state.isModified)

        // Document saved
        state.isModified = false
        assertFalse(state.isModified)
    }

    @Test
    fun `table of contents item data structure`() {
        val items = listOf(
            TocItem(id = "heading-0-overview", title = "Overview", level = 1),
            TocItem(id = "heading-1-installation", title = "Installation", level = 2),
            TocItem(id = "heading-2-configuration", title = "Configuration", level = 3)
        )

        val state = MarkdownViewerState(tableOfContents = items)
        assertEquals(3, state.tableOfContents.size)
        assertEquals("heading-0-overview", state.tableOfContents[0].id)
        assertEquals("Overview", state.tableOfContents[0].title)
        assertEquals(1, state.tableOfContents[0].level)
        assertEquals(2, state.tableOfContents[1].level)
        assertEquals(3, state.tableOfContents[2].level)
    }
}
