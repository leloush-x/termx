package app.termx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.weight
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

    override fun onDestroy() {
        if (::terminalSession.isInitialized) terminalSession.finishIfRunning()
        super.onDestroy()
    }
}
