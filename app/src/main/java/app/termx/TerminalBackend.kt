package app.termx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import app.termx.terminal.view.TerminalView
import app.termx.terminal.view.TerminalViewClient
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** Connects the terminal surface to the emulator and the Android clipboard/IME. */
class TerminalBackend(private val context: Context) : TerminalViewClient, TerminalSessionClient {
    private var terminalView: TerminalView? = null
    private var fontSize = 16

    var controlPressed = false
    var altPressed = false

    fun attach(view: TerminalView) {
        terminalView = view
        view.setTerminalViewClient(this)
    }

    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).toInt().coerceIn(8, 48)
        terminalView?.setTextSize(fontSize)
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        terminalView?.let { view ->
            view.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = true
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = controlPressed
    override fun readAltKey() = altPressed
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() = terminalView?.invalidate() ?: Unit

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        clipboard().setPrimaryClip(ClipData.newPlainText("TermX terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard().primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isNotEmpty()) terminalView?.mEmulator?.paste(text)
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
