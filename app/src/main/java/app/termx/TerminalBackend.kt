package app.termx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import app.termx.terminal.view.TerminalView
import app.termx.terminal.view.TerminalViewClient
import app.termx.customization.TermXTheme
import app.termx.customization.TerminalTheme
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

    var controlPressed = false
    var altPressed = false
    var shiftPressed = false

    // Tap-hold state for the physical Left Ctrl key.
    private val ctrlHandler = Handler(Looper.getMainLooper())
    private var ctrlDownTime = 0L
    private var ctrlIsHold = false
    private var ctrlOneShot = false
    private var ctrlPendingReset = false
    private val tapThresholdMs = 250L

    // Tap-hold state for the physical Left Alt key.
    private val altHandler = Handler(Looper.getMainLooper())
    private var altDownTime = 0L
    private var altIsHold = false
    private var altOneShot = false
    private var altPendingReset = false

    // Tap-hold state for the physical Left Shift key.
    private val shiftHandler = Handler(Looper.getMainLooper())
    private var shiftDownTime = 0L
    private var shiftIsHold = false
    private var shiftOneShot = false
    private var shiftPendingReset = false

    /** Palette to apply once the emulator is initialized (it is null until the first layout). */
    var theme: TermXTheme? = null

    fun attach(view: TerminalView) {
        terminalView = view
        view.setTerminalViewClient(this)
    }

    fun detach() {
        terminalView?.setTerminalViewClient(null)
        terminalView = null
    }

    /** Resets the IME shadow line (D20). */
    fun resetShadow() {
        terminalView?.resetShadow()
    }
    fun focus() {
        terminalView?.let { view ->
            view.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Inserts text into the active terminal without sending Enter. */
    fun insertText(text: String) {
        if (text.isNotEmpty()) terminalView?.mEmulator?.paste(text)
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
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // A finished session can no longer accept input. Treat Enter as "acknowledge/close"
        // so the "press Enter" prompt (if any) actually does something instead of being ignored.
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            onFinished(session)
            return true
        }

        // Tap-hold for physical Left Ctrl: tap = one-shot latch, hold = standard modifier.
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                ctrlDownTime = System.currentTimeMillis()
                ctrlIsHold = false
                controlPressed = true
                ctrlHandler.postDelayed({ ctrlIsHold = true }, tapThresholdMs)
            }
            return true
        }

        // Tap-hold for physical Left Alt.
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                altDownTime = System.currentTimeMillis()
                altIsHold = false
                altPressed = true
                altHandler.postDelayed({ altIsHold = true }, tapThresholdMs)
            }
            return true
        }

        // Tap-hold for physical Left Shift.
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                shiftDownTime = System.currentTimeMillis()
                shiftIsHold = false
                shiftPressed = true
                shiftHandler.postDelayed({ shiftIsHold = true }, tapThresholdMs)
            }
            return true
        }

        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT) {
            ctrlHandler.removeCallbacksAndMessages(null)
            if (ctrlIsHold) {
                controlPressed = false
                ctrlIsHold = false
            } else {
                ctrlOneShot = true
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ALT_LEFT) {
            altHandler.removeCallbacksAndMessages(null)
            if (altIsHold) {
                altPressed = false
                altIsHold = false
            } else {
                altOneShot = true
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT) {
            shiftHandler.removeCallbacksAndMessages(null)
            if (shiftIsHold) {
                shiftPressed = false
                shiftIsHold = false
            } else {
                shiftOneShot = true
            }
            return true
        }
        if (ctrlPendingReset) {
            controlPressed = false
            ctrlPendingReset = false
            ctrlOneShot = false
        }
        if (altPendingReset) {
            altPressed = false
            altPendingReset = false
            altOneShot = false
        }
        if (shiftPendingReset) {
            shiftPressed = false
            shiftPendingReset = false
            shiftOneShot = false
        }
        return false
    }
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey(): Boolean {
        if (ctrlOneShot) {
            ctrlOneShot = false
            ctrlPendingReset = true
            return true
        }
        return controlPressed
    }
    override fun readAltKey(): Boolean {
        if (altOneShot) {
            altOneShot = false
            altPendingReset = true
            return true
        }
        return altPressed
    }

    override fun readShiftKey(): Boolean {
        if (shiftOneShot) {
            shiftOneShot = false
            shiftPendingReset = true
            return true
        }
        return shiftPressed
    }
    override fun readFnKey() = false
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
        clipboard().setPrimaryClip(ClipData.newPlainText("TermX terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = clipboard().primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isNotEmpty()) terminalView?.mEmulator?.paste(text)
        // Paste resets the shadow (D20).
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
