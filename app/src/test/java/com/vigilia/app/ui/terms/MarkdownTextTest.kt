package com.vigilia.app.ui.terms

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun `plain text has no spans`() {
        val out = parseSimpleMarkdown("Hello world")
        assertEquals("Hello world", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `bold delimiters wrap a bold span and are stripped`() {
        val out = parseSimpleMarkdown("Read **the terms** now")
        assertEquals("Read the terms now", out.text)
        assertEquals(1, out.spanStyles.size)
        val range = out.spanStyles.single()
        assertEquals(FontWeight.Bold, range.item.fontWeight)
        assertEquals("the terms", out.text.substring(range.start, range.end))
    }

    @Test
    fun `italic delimiters wrap an italic span and are stripped`() {
        val out = parseSimpleMarkdown("Foo __bar__ baz")
        assertEquals("Foo bar baz", out.text)
        val range = out.spanStyles.single()
        assertEquals(FontStyle.Italic, range.item.fontStyle)
        assertEquals("bar", out.text.substring(range.start, range.end))
    }

    @Test
    fun `mixed bold and italic on the same line`() {
        val out = parseSimpleMarkdown("**a** and __b__")
        assertEquals("a and b", out.text)
        assertEquals(2, out.spanStyles.size)
    }

    @Test
    fun `unpaired bold delimiter is emitted literally`() {
        val out = parseSimpleMarkdown("prefix **dangling")
        assertEquals("prefix **dangling", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `unpaired italic delimiter is emitted literally`() {
        val out = parseSimpleMarkdown("prefix __dangling")
        assertEquals("prefix __dangling", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `empty string produces empty AnnotatedString`() {
        val out = parseSimpleMarkdown("")
        assertEquals("", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `adjacent bold spans both apply`() {
        val out = parseSimpleMarkdown("**a****b**")
        assertEquals("ab", out.text)
        assertEquals(2, out.spanStyles.size)
    }

    @Test
    fun `bold across multiple lines is supported`() {
        val out = parseSimpleMarkdown("intro\n**Última atualização:** hoje")
        assertEquals("intro\nÚltima atualização: hoje", out.text)
        val range = out.spanStyles.single()
        assertEquals("Última atualização:", out.text.substring(range.start, range.end))
    }
}
