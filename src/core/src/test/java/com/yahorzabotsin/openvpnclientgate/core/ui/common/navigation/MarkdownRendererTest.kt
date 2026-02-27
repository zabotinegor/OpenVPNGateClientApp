package com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun `renderDocument strips javascript links`() {
        val html = MarkdownRenderer.renderDocument("[x](javascript:alert(1))")
        assertFalse(html.contains("javascript:alert(1)", ignoreCase = true))
        assertTrue(html.contains("href=\"#\""))
    }

    @Test
    fun `renderDocument strips data image sources`() {
        val html = MarkdownRenderer.renderDocument("![x](data:image/svg+xml;base64,AAA)")
        assertFalse(html.contains("data:image", ignoreCase = true))
        assertTrue(html.contains("src=\"#\""))
    }

    @Test
    fun `renderDocument keeps https links`() {
        val html = MarkdownRenderer.renderDocument("[x](https://example.com/changelog)")
        assertTrue(html.contains("href=\"https://example.com/changelog\""))
    }

    @Test
    fun `renderDocument strips http links`() {
        val html = MarkdownRenderer.renderDocument("[x](http://example.com/changelog)")
        assertFalse(html.contains("href=\"http://example.com/changelog\""))
        assertTrue(html.contains("href=\"#\""))
    }
}
