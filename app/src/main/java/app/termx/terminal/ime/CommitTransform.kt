package app.termx.terminal.ime

/**
 * Pure transform applied to a committed string before it is sent to the shell.
 *
 * Single code points keep their identity (Enter, Ctrl, Alt, arrows are handled elsewhere).
 * Multi-code-point commits (dictation, paste, emoji sequences) are sanitized:
 *   - control characters below 0x20 are stripped, except tab, newline and carriage return
 *   - ESC and the C1 range (0x80-0x9F) are stripped
 *   - CRLF and lone CR are normalised to LF
 *   - newlines are KEPT: a multi-line paste is meant to be multi-line. Turning them into
 *     spaces silently mangled pasted scripts (and broke every `for`/`if` block). The caller
 *     decides how a kept newline is delivered (Enter) and whether to wrap the text in
 *     bracketed-paste markers.
 *
 * No Android imports — fully unit-testable.
 */
object CommitTransform {

    /**
     * Returns the string to send for a commit of [input].
     * If [input] is a single code point, returns it unchanged.
     * If [input] is empty, returns empty.
     */
    fun transform(input: String): String {
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
                cp == '\r'.code -> {
                    // CRLF -> single LF, lone CR -> LF
                    if (i < input.length && input.codePointAt(i) == '\n'.code) i += 1
                    sb.append('\n')
                }
                cp == '\n'.code -> sb.append('\n') // newline kept
                cp < 0x20 -> { /* strip other control chars */ }
                cp == 0x7F -> { /* strip DEL */ }
                cp in 0x80..0x9F -> { /* strip C1 range */ }
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    /** Whether a commit contains a line break, i.e. has to be delivered as a paste. */
    fun isMultiline(input: String): Boolean = input.indexOf('\n') >= 0 || input.indexOf('\r') >= 0
}
