package app.termx.terminal.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun newlinesAreKept() {
        // Regression: a pasted two-line entry used to turn into "AAA BBB", so pasted
        // scripts lost every line break and never ran.
        assertEquals("AAA\nBBB\n", CommitTransform.transform("AAA\nBBB\n"))
        assertEquals("for i in 1 2:\n    print(i)\n",
            CommitTransform.transform("for i in 1 2:\n    print(i)\n"))
    }

    @Test
    fun crlfNormalisedToLf() {
        assertEquals("a\nb", CommitTransform.transform("a\r\nb"))
        assertEquals("a\nb", CommitTransform.transform("a\rb"))
    }

    @Test
    fun escStripped() {
        assertEquals("ab", CommitTransform.transform("a\u001Bb"))
    }

    @Test
    fun isMultilineDetectsLineBreaks() {
        assertTrue(CommitTransform.isMultiline("a\nb"))
        assertTrue(CommitTransform.isMultiline("a\rb"))
        assertFalse(CommitTransform.isMultiline("a b"))
    }
}
