package app.termx.customization

import android.graphics.Color
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle

/** Applies the validated ANSI palette to the emulator's public palette arrays. */
object TerminalTheme {
    fun apply(emulator: TerminalEmulator, theme: TermXTheme) {
        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        theme.ansi.forEachIndexed { index, value -> colors[index] = Color.parseColor(value) }
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = Color.parseColor(theme.background)
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = Color.parseColor(theme.foreground)
        colors[TextStyle.COLOR_INDEX_CURSOR] = Color.parseColor(theme.cursor)
        System.arraycopy(colors, 0, emulator.mColors.mCurrentColors, 0, colors.size)
    }
}
