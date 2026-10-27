package app.termx.terminal.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitTransformTest {
    @Test
    fun singleCodePointUnchanged() {
        assertEquals("a", CommitTransform.transform("a"))
        assertEquals("\n", CommitTransform.transform("\n"))
    }

    @Test
    fun emptyReturnsEmpty() {
        assertEquals("", CommitTransform.transform(""))
    }

    @Test
    fun controlCharsStripped() {
        assertEquals("ab", CommitTransform.transform("a\u0001b"))
    }

    @Test
    fun newlineToSpace() {
        assertEquals("a b", CommitTransform.transform("a\nb"))
    }

    @Test
    fun bracketedPasteWraps() {
        val result = CommitTransform.transform("a\nb", bracketedPaste = true)
        assertTrue(result.startsWith("\u001B[200~"))
        assertTrue(result.endsWith("\u001B[201~"))
        assertTrue(result.contains("a\nb"))
    }

    @Test
    fun noEnterSent() {
        val result = CommitTransform.transform("hello\nworld")
        assertTrue(!result.contains("\n") || result.contains(" "))
    }

    @Test
    fun escStripped() {
        assertEquals("ab", CommitTransform.transform("a\u001Bb"))
    }
}
