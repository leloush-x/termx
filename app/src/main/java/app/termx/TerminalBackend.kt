package app.termx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableIntStateOf
import app.termx.terminal.input.ModifierState
import app.termx.terminal.view.TerminalView
import app.termx.terminal.view.TerminalViewClient
import app.termx.customization.TermXTheme
import app.termx.customization.TerminalTheme
import app.termx.terminal.input.ModKey
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** Connects the terminal surface to the emulator and the Android clipboard/IME. */
class TerminalBackend(
    private val context: Context,
    private val onOutput: () -> Unit = {},
    private val onFinished: (TerminalSession) -> Unit = {},
) : TerminalViewClient, TerminalSessionClient {
    private var terminalView: TerminalView? = null
    private var fontSize = 16

    val modifierRevision = mutableIntStateOf(0)
    val modifiers = ModifierState { modifierRevision.intValue++ }

    private class PhysicalHold(val downAt: Long = System.currentTimeMillis()) { var usedAsChord = false }
    private val physicalHolds = mutableMapOf<ModKey, PhysicalHold>()

    /** Palette to apply once the emulator is initialized (it is null until the first layout). */
    var theme: TermXTheme? = null

    fun attach(view: TerminalView) {
        terminalView = view
        view.setTerminalViewClient(this)
    }

    /**
     * Releases the view reference without clearing its client. TerminalView assumes the client is
     * non-null in input and lifecycle callbacks, which can still arrive while Compose removes it.
     */
    fun detach(view: TerminalView? = null) {
        val attached = terminalView ?: return
        if (view != null && attached !== view) return
        terminalView = null
    }

    /** Resets the IME shadow line (D20). */
    fun resetShadow() {
        terminalView?.resetShadow()
    }

    /** Focuses on the next frame so recreated views can take focus too. */
    fun focus() {
        terminalView?.post { terminalView?.let(::showKeyboardFor) }
    }

    private fun showKeyboardFor(view: TerminalView) {
        view.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Inserts text into the active terminal without sending Enter. */
    fun insertText(text: String) {
        if (text.isNotEmpty()) terminalView?.mEmulator?.paste(text)
    }

    override fun onScale(scale: Float): Float {
        // `scale` is the accumulated gesture factor since the last reset. Apply it once,
        // then reset the accumulator to 1 so the next pinch event is incremental instead of
        // compounding (which caused runaway font sizes and janky reflows).
        fontSize = (fontSize * scale).toInt().coerceIn(8, 48)
        terminalView?.setTextSize(fontSize)
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) {
        terminalView?.let(::showKeyboardFor)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun getInputMode(): Int =
        context.getSharedPreferences("termx", Context.MODE_PRIVATE).getInt("input_mode", 2)
    override fun shouldUseCtrlSpaceWorkaround() = true
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // A finished session can no longer accept input. Treat Enter as "acknowledge/close"
        // so the "press Enter" prompt (if any) actually does something instead of being ignored.
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            onFinished(session)
            return true
        }

        val mod = physicalModifier(keyCode)
        if (mod != null) { if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && physicalHolds[mod] == null) physicalHolds[mod] = PhysicalHold(); return true }
        physicalHolds.values.forEach { it.usedAsChord = true }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        val mod = physicalModifier(keyCode) ?: return false
        val hold = physicalHolds.remove(mod) ?: return true
        when { hold.usedAsChord -> Unit; System.currentTimeMillis() - hold.downAt >= ViewConfiguration.getLongPressTimeout() -> modifiers.longPress(mod); else -> modifiers.tap(mod) }
        return true
    }
    private fun physicalModifier(keyCode: Int): ModKey? = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> ModKey.CTRL
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> ModKey.SHIFT
        KeyEvent.KEYCODE_ALT_LEFT -> ModKey.ALT
        else -> null
    }
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = modifiers.isActive(ModKey.CTRL)
    override fun readAltKey() = modifiers.isActive(ModKey.ALT)
    override fun readShiftKey() = modifiers.isActive(ModKey.SHIFT)
    override fun readFnKey() = false
    override fun onModifierKeyUsed() = modifiers.consumeOneShots()
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() {
        terminalView?.let { view ->
            theme?.let { TerminalTheme.apply(view.mEmulator, it) }
            view.invalidate()
        }
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        onOutput()
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = onFinished(finishedSession)

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        if (text.isEmpty()) return
        clipboard().setPrimaryClip(ClipData.newPlainText("TermX terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard().primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isNotEmpty()) terminalView?.mEmulator?.paste(text)
        resetShadow()
    }

    override fun onBell(session: TerminalSession) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Terminal error", e) }

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}
