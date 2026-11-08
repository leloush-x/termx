package app.termx.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSenderTest {
    @Test
    fun stripsEscControlsAndPasteTerminatorFromUntrustedText() {
        assertEquals("hellothere\nnext", TextSender.sanitize("hello\u001bthere\u0007\nnext\u001b[201~"))
    }

    @Test
    fun bracketedPasteWrapsSanitizedMultilinePayload() {
        assertEquals("\u001b[200~first\rsecond\u001b[201~", TextSender.payload("first\nsecond", true, false))
    }

    @Test
    fun embeddedPasteEndCannotEscapeTheBracketedPayload() {
        assertEquals("\u001b[200~safe text\u001b[201~", TextSender.payload("safe\u001b[201~ text", true, false))
    }

    @Test
    fun nonBracketedPasteNormalizesNewlinesAndSendAddsEnter() {
        assertEquals("first\rsecond\r", TextSender.payload("first\r\nsecond", false, true))
    }

    @Test
    fun insertNeverAddsEnter() {
        assertEquals("plain text", TextSender.payload("plain text", false, false))
    }

    @Test
    fun chunkingRespectsTheLimitAndPreservesTotalLength() {
        val chunks = TextSender.chunkSizes(2500)
        assertEquals(listOf(1024, 1024, 452), chunks)
        assertEquals(2500, chunks.sum())
        assertEquals(true, chunks.all { it in 1..TextSender.CHUNK_BYTES })
    }
}
