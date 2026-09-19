package com.veilframe.app.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSecurityPolicyTest {

    @Test
    fun `strips script tags and contents`() {
        val input = "Hello <script>alert('xss')</script>World"
        val output = MarkdownSecurityPolicy.sanitizeMarkdown(input)
        assertFalse(output.contains("<script", ignoreCase = true))
        assertFalse(output.contains("alert('xss')", ignoreCase = true))
        assertTrue(output.contains("Hello"))
        assertTrue(output.contains("World"))
    }

    @Test
    fun `strips iframe tags and contents`() {
        val input = "Intro <iframe src=\"https://malicious.com\">hidden</iframe> Outro"
        val output = MarkdownSecurityPolicy.sanitizeMarkdown(input)
        assertFalse(output.contains("<iframe", ignoreCase = true))
        assertFalse(output.contains("hidden", ignoreCase = true))
        assertTrue(output.contains("Intro"))
        assertTrue(output.contains("Outro"))
    }

    @Test
    fun `strips dangerous event handlers from tags`() {
        val input = "<img src=\"foo.jpg\" onerror=\"alert(1)\" onload=\"malicious()\" />"
        val output = MarkdownSecurityPolicy.sanitizeMarkdown(input)
        assertFalse(output.contains("onerror", ignoreCase = true))
        assertFalse(output.contains("onload", ignoreCase = true))
        assertFalse(output.contains("alert(1)", ignoreCase = true))
    }

    @Test
    fun `strips javascript pseudo-protocol in links and src`() {
        val input = "<a href=\"javascript:alert(1)\">Click me</a>"
        val output = MarkdownSecurityPolicy.sanitizeMarkdown(input)
        assertFalse(output.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun `preserves safe formatting HTML elements`() {
        val input = "<div><span class=\"bold\">Text</span> <details><summary>More</summary><table><tr><td>Cell</td></tr></table></details></div>"
        val output = MarkdownSecurityPolicy.sanitizeMarkdown(input)
        assertTrue(output.contains("<div"))
        assertTrue(output.contains("<span"))
        assertTrue(output.contains("<details"))
        assertTrue(output.contains("<summary"))
        assertTrue(output.contains("<table>"))
        assertTrue(output.contains("<td>Cell</td>"))
    }
}
