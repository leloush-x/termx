package app.termx.terminal.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowLineTest {
    @Test
    fun appendAndTail() {
        val s = ShadowLine()
        s.append("hello")
        assertEquals("hello", s.tail(5))
        assertEquals("llo", s.tail(3))
    }

    @Test
    fun deleteBeforeRemovesCodePoints() {
        val s = ShadowLine()
        s.append("abc")
        assertEquals(2, s.deleteBefore(2))
        assertEquals("a", s.toString())
    }

    @Test
    fun deleteBeforeMoreThanLengthReturnsActual() {
        val s = ShadowLine()
        s.append("ab")
        assertEquals(2, s.deleteBefore(10))
        assertTrue(s.isEmpty())
    }

    @Test
    fun resetClears() {
        val s = ShadowLine()
        s.append("hello")
        s.reset()
        assertTrue(s.isEmpty())
        assertEquals("", s.tail(3))
    }

    @Test
    fun tailWithFewerCodePoints() {
        val s = ShadowLine()
        s.append("hi")
        assertEquals("hi", s.tail(10))
    }

    @Test
    fun emojiCountsAsOne() {
        val s = ShadowLine()
        s.append("a😀b")
        assertEquals(3, s.length())
        assertEquals("😀b", s.tail(2))
        assertEquals(1, s.deleteBefore(1))
        assertEquals("a😀", s.toString())
    }
}
