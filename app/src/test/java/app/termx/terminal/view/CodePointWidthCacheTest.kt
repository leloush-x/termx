package app.termx.terminal.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodePointWidthCacheTest {
    @Test
    fun returnsCachedWidthForSameCodePoint() {
        val cache = CodePointWidthCache()
        cache.put(0x4E2D, 18.5f)

        assertEquals(18.5f, cache.get(0x4E2D), 0f)
    }

    @Test
    fun missReturnsNaN() {
        assertTrue(CodePointWidthCache().get(0x1F680).isNaN())
    }

    @Test
    fun collidingCodePointReplacesOldEntry() {
        val cache = CodePointWidthCache()
        cache.put(0x4E2D, 18.5f)
        cache.put(0x522D, 22f) // same direct-mapped slot as 0x4E2D

        assertTrue(cache.get(0x4E2D).isNaN())
        assertEquals(22f, cache.get(0x522D), 0f)
    }
}
