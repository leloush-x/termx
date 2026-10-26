package app.termx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.termx.runtime.DistroManifest
import app.termx.runtime.RootfsInstaller
import app.termx.runtime.RootfsPackage
import app.termx.runtime.ProotCommand
import app.termx.terminal.view.TerminalView
import com.termux.terminal.TerminalSession
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var backend: TerminalBackend
    private lateinit var installer: RootfsInstaller
    private lateinit var distroPackages: List<RootfsPackage>
    private var terminalSession by mutableStateOf<TerminalSession?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureTerminalSystemBars()
        backend = TerminalBackend(this)
        installer = RootfsInstaller(this)
        distroPackages = DistroManifest.load(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    val session = terminalSession
                    if (session == null) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Choose a Linux distro. Downloads start only when you tap Install.", color = Color.White)
                            distroPackages.forEach { pkg ->
                                var installing by remember(pkg.distroId) { mutableStateOf(false) }
                                var status by remember(pkg.distroId) { mutableStateOf("") }
                                val installed = installer.installedRoot(pkg.distroId).isDirectory
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !installing,
                                    onClick = {
                                        if (installed) {
                                            runCatching { startTerminal(pkg) }
                                                .onFailure { status = it.message ?: "Could not start the distro" }
                                        } else {
                                            installing = true
                                            status = "Downloading ${pkg.distroName}…"
                                            Thread({
                                                val result = runCatching {
                                                    installer.install(pkg) { downloaded, total ->
                                                        if (downloaded == total || downloaded % (1024 * 1024) < 64 * 1024) {
                                                            runOnUiThread {
                                                                status = "Downloading ${pkg.distroName}: ${downloaded / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                                                            }
                                                        }
                                                    }
                                                }
                                                runOnUiThread {
                                                    installing = false
                                                    status = result.fold(
                                                        onSuccess = { "${pkg.distroName} installed. Tap Start to open it." },
                                                        onFailure = { it.message ?: "Install failed" },
                                                    )
                                                }
                                            }, "termx-rootfs-${pkg.distroId}").start()
                                        }
                                    },
                                ) {
                                    Text(if (installed) "Start ${pkg.distroName}" else "Install ${pkg.distroName}")
                                }
                                if (status.isNotEmpty()) Text(status, color = Color.LightGray)
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            AndroidView(
                                modifier = Modifier.weight(1f),
                                factory = { context ->
                                    TerminalView(context, null).apply {
                                        setTextSize(16)
                                        backend.attach(this)
                                        attachSession(session)
                                        isFocusableInTouchMode = true
                                        requestFocus()
                                    }
                                },
                            )
                            ExtraKeysRow(session = session, backend = backend)
                        }
                    }
                }
            }
        }
    }

    private fun startTerminal(pkg: RootfsPackage) {
        val rootfs = installer.installedRoot(pkg.distroId)
        val shared = File(getExternalFilesDir(null) ?: File(filesDir, "external"), "shared")
            .apply { mkdirs() }
        val launch = ProotCommand.build(
            nativeLibraryDirectory = File(applicationInfo.nativeLibraryDir),
            rootfs = rootfs,
            temporaryDirectory = File(cacheDir, "proot/${pkg.distroId}"),
            sharedDirectory = shared,
        )
        terminalSession = TerminalSession(
            launch.executable.absolutePath,
            launch.workingDirectory,
            launch.arguments,
            launch.environment,
            null,
            backend,
        )
    }

    /** Hide the top status bar while keeping system navigation available. */
    private fun configureTerminalSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureTerminalSystemBars()
    }

    override fun onDestroy() {
        terminalSession?.finishIfRunning()
        terminalSession = null
        super.onDestroy()
    }
}
