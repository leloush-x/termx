package app.termx.terminal.ime

/**
 * A pure shadow of the text typed since the last reset.
 *
 * The IME is shown this text as the surrounding text, so suggestions and autocorrect
 * work against what is really on the line. No Android imports — fully unit-testable.
 */
class ShadowLine {
    private val text = StringBuilder()

    /** Appends [s] to the shadow, counting surrogate pairs as one code point. */
    fun append(s: CharSequence) {
        text.append(s)
    }

    /**
     * Removes up to [n] code points from the end. Returns how many were actually removed.
     */
    fun deleteBefore(n: Int): Int {
        if (n <= 0 || text.isEmpty()) return 0
        val codePointCount = text.codePointCount(0, text.length)
        val toRemove = n.coerceAtMost(codePointCount)
        if (toRemove == 0) return 0
        val newLength = text.offsetByCodePoints(text.length, -toRemove)
        text.setLength(newLength)
        return toRemove
    }

    /** Clears the shadow. */
    fun reset() {
        text.setLength(0)
    }

    /** Returns the last [n] code points (or fewer if the shadow is shorter). */
    fun tail(n: Int): String {
        if (n <= 0 || text.isEmpty()) return ""
        val codePointCount = text.codePointCount(0, text.length)
        val start = if (n >= codePointCount) 0 else text.offsetByCodePoints(text.length, -n)
        return text.substring(start)
    }

    /** Current length in code points. */
    fun length(): Int = text.codePointCount(0, text.length)

    /** Whether the shadow is empty. */
    fun isEmpty(): Boolean = text.isEmpty()

    override fun toString(): String = text.toString()
}
