package app.termx.composer

import com.termux.terminal.TerminalSession
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/** Sanitizes and streams composer input without putting large writes on the UI thread. */
object TextSender {
    const val CHUNK_BYTES = 1024
    private const val START_PASTE = "\u001B[200~"
    private const val END_PASTE = "\u001B[201~"

    fun sanitize(text: String): String {
        val withoutPasteEnd = text.replace(END_PASTE, "")
        return buildString(withoutPasteEnd.length) {
            withoutPasteEnd.forEach { char ->
                if (char == '\n' || char == '\t' || (!char.isISOControl() && char !in '\u0080'..'\u009F')) append(char)
            }
        }
    }

    fun payload(text: String, bracketedPaste: Boolean, submit: Boolean): String {
        val normalized = sanitize(text.replace("\r\n", "\n").replace('\r', '\n')).replace('\n', '\r')
        if (normalized.isEmpty()) return ""
        return buildString {
            if (bracketedPaste) append(START_PASTE)
            append(normalized)
            if (bracketedPaste) append(END_PASTE)
            if (submit) append('\r')
        }
    }

    fun chunkSizes(byteCount: Int): List<Int> {
        require(byteCount >= 0)
        return buildList {
            var remaining = byteCount
            while (remaining > 0) {
                val chunk = minOf(CHUNK_BYTES, remaining)
                add(chunk)
                remaining -= chunk
            }
        }
    }

    suspend fun send(session: TerminalSession, text: String, submit: Boolean): Boolean {
        if (!session.isRunning) return false
        val prepared = payload(text, session.isBracketedPasteModeEnabled, submit)
        if (prepared.isEmpty()) return false
        val data = prepared.toByteArray(Charsets.UTF_8)
        var offset = 0
        for (count in chunkSizes(data.size)) {
            while (true) {
                coroutineContext.ensureActive()
                if (!session.isRunning) return false
                if (session.writeComposerChunk(data, offset, count)) break
                delay(8)
            }
            offset += count
            yield()
        }
        return session.isRunning
    }
}
