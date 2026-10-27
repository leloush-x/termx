package app.termx.terminal.input

object ModifierKeys {
    private val shiftSymbols = mapOf("/" to "?", "-" to "_", "." to "<", "," to ">", "=" to "+", ";" to ":", "'" to "\"", "[" to "{", "]" to "}", "\\" to "|", "`" to "~", "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%", "6" to "^", "7" to "&", "8" to "*", "9" to "(", "0" to ")")
    fun apply(send: String, ctrl: Boolean, alt: Boolean, shift: Boolean): String {
        if (send.isEmpty() || send.startsWith("\u001B")) return send
        var text = send
        if (shift) text = shiftSymbols[text] ?: if (text.codePointCount(0, text.length) == 1) text.uppercase() else text
        if (ctrl && text.codePointCount(0, text.length) == 1) controlCode(text.codePointAt(0))?.let { text = it.toChar().toString() }
        if (alt) text = "\u001B$text"
        return text
    }
    fun controlCode(cp: Int): Int? = when (cp) {
        in 'a'.code..'z'.code -> cp - 'a'.code + 1
        in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        ' '.code, '2'.code, '@'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code -> 31
        '8'.code, '?'.code -> 127
        else -> null
    }
}
