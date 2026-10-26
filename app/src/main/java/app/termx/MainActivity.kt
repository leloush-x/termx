package app.termx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import app.termx.terminal.view.TerminalView
import com.termux.terminal.TerminalSession

class MainActivity : ComponentActivity() {
    private lateinit var terminalSession: TerminalSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureTerminalSystemBars()

        val backend = TerminalBackend(this)
        val shell = "/system/bin/sh"
        val environment = System.getenv().map { (key, value) -> "$key=$value" }.toMutableList().apply {
            removeAll { it.startsWith("HOME=") || it.startsWith("TERM=") || it.startsWith("TMPDIR=") }
            add("HOME=${filesDir.absolutePath}")
            add("TERM=xterm-256color")
            add("TMPDIR=${cacheDir.absolutePath}")
        }.toTypedArray()
        terminalSession = TerminalSession(
            shell,
            filesDir.absolutePath,
            arrayOf(shell),
            environment,
            null,
            backend,
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.weight(1f),
                            factory = { context ->
                                TerminalView(context, null).apply {
                                    setTextSize(16)
                                    backend.attach(this)
                                    attachSession(terminalSession)
                                    isFocusableInTouchMode = true
                                    requestFocus()
                                }
                            },
                        )
                        ExtraKeysRow(session = terminalSession, backend = backend)
                    }
                }
            }
        }
    }

    /** Keep terminal output immersive while leaving system navigation available. */
    private fun configureTerminalSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureTerminalSystemBars()
    }

    override fun onDestroy() {
        if (::terminalSession.isInitialized) terminalSession.finishIfRunning()
        super.onDestroy()
    }
}
