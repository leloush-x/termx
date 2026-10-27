package app.termx.terminal.ime

/**
 * Pure transform applied to a committed string before it is sent to the shell.
 *
 * Single code points keep their identity (Enter, Ctrl, Alt, arrows are handled elsewhere).
 * Multi-code-point commits (dictation, paste, emoji sequences) are sanitized:
 *   - control characters below 0x20 are stripped, except tab
 *   - ESC and the C1 range (0x80-0x9F) are stripped
 *   - newlines become spaces, unless bracketed paste is on (DECSET 2004), in which case
 *     they are kept and the text is wrapped in ESC[200~ ... ESC[201~
 *   - Enter is never sent
 *
 * No Android imports — fully unit-testable.
 */
object CommitTransform {

    private const val ESC = '\u001B'
    private const val BRACKETED_PASTE_START = "\u001B[200~"
    private const val BRACKETED_PASTE_END = "\u001B[201~"

    /**
     * Returns the string to send for a commit of [input].
     * If [input] is a single code point, returns it unchanged.
     * If [input] is empty, returns empty.
     * [bracketedPaste] controls newline handling and wrapping.
     */
    fun transform(input: String, bracketedPaste: Boolean = false): String {
        if (input.isEmpty()) return ""
        // Count code points
        val codePointCount = input.codePointCount(0, input.length)
        if (codePointCount <= 1) return input

        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0x09 -> sb.append('\t') // tab kept
                cp < 0x20 -> { /* strip control chars */ }
                cp == 0x1B -> { /* strip ESC */ }
                cp in 0x80..0x9F -> { /* strip C1 range */ }
                cp == '\n'.code || cp == '\r'.code -> {
                    if (bracketedPaste) sb.append('\n') else sb.append(' ')
                }
                else -> {
                    sb.appendCodePoint(cp)
                }
            }
        }
        val result = sb.toString()
        if (result.isEmpty()) return ""
        return if (bracketedPaste && result.contains('\n')) {
            BRACKETED_PASTE_START + result + BRACKETED_PASTE_END
        } else {
            result
        }
    }
}
