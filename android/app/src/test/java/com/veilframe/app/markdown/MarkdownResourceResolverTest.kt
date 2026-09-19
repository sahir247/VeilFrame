package com.veilframe.app.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownResourceResolverTest {

    private val resolver = MarkdownResourceResolver(null)

    @Test
    fun `identifies safe relative paths`() {
        assertFalse(resolver.isPathTraversal("architecture.png"))
        assertFalse(resolver.isPathTraversal("images/diagram.png"))
        assertFalse(resolver.isPathTraversal("./images/photo.webp"))
        assertFalse(resolver.isPathTraversal("assets/subfolder/test.svg"))
    }

    @Test
    fun `detects directory traversal attempts with double dots`() {
        assertTrue(resolver.isPathTraversal("../secret.txt"))
        assertTrue(resolver.isPathTraversal("../../etc/passwd"))
        assertTrue(resolver.isPathTraversal("images/../../sensitive.png"))
        assertTrue(resolver.isPathTraversal("./../../root.key"))
    }

    @Test
    fun `detects absolute paths targeting filesystem root`() {
        assertTrue(resolver.isPathTraversal("/etc/passwd"))
        assertTrue(resolver.isPathTraversal("/sdcard/DCIM/private.jpg"))
    }

    @Test
    fun `detects url encoded traversal attempts`() {
        assertTrue(resolver.isPathTraversal("..%2Fsecret.txt"))
        assertTrue(resolver.isPathTraversal("%2E%2E%2Fpasswords.json"))
    }
}
