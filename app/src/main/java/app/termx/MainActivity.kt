package app.termx

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import app.termx.runtime.Bind
import app.termx.storage.StorageAccess
import app.termx.storage.StorageHealthScreen
import app.termx.storage.StoragePreferences
import app.termx.storage.StorageTier
import app.termx.storage.WorkspaceTransfer
import app.termx.terminal.view.TerminalView
import com.termux.terminal.TerminalSession
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var backend: TerminalBackend
    private lateinit var installer: RootfsInstaller
    private lateinit var distroPackages: List<RootfsPackage>
    private var terminalSession by mutableStateOf<TerminalSession?>(null)
    private var sessionBinds by mutableStateOf<List<Bind>?>(null)
    private var storageRevision by mutableIntStateOf(0)
    private var showStorageHealth by mutableStateOf(false)
    private var storageMessage by mutableStateOf<String?>(null)

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
                    val context = this@MainActivity
                    val sharedDir = remember { sharedDirectory() }
                    val workspaces = remember(storageRevision) { StoragePreferences.workspaces(context) }
                    val snapshot = remember(storageRevision, workspaces.size) {
                        StorageAccess.snapshot(context, workspaces.size, sharedDir.isDirectory && sharedDir.canWrite())
                    }
                    val selectedTier = remember(storageRevision) { StoragePreferences.selectedTier(context) }
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { granted ->
                        storageRevision++
                        if (granted.values.any { !it }) {
                            storageMessage = "Storage access was not granted. T0 and SAF workspaces remain available."
                        }
                    }
                    val workspaceLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree(),
                    ) { uri ->
                        if (uri != null) {
                            runCatching { StoragePreferences.addWorkspace(context, uri) }
                                .onSuccess {
                                    StoragePreferences.selectTier(context, StorageTier.T2)
                                    storageMessage = "Workspace saved. Files are copied explicitly; SAF folders are not bind-mounted."
                                }
                                .onFailure { storageMessage = StorageAccess.permissionHint(it) ?: (it.message ?: "Could not save workspace") }
                        }
                        storageRevision++
                    }
                    val importLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) runStorageOperation("Import") {
                            WorkspaceTransfer.copyDocumentIntoShared(context, uri, sharedDir)
                        }
                    }
                    val session = terminalSession
                    if (showStorageHealth) {
                        StorageHealthScreen(
                            statuses = StorageAccess.statuses(snapshot),
                            selectedTier = selectedTier,
                            workspaces = workspaces,
                            sessionBinds = sessionBinds,
                            sharedFilesAvailable = sharedDir.isDirectory,
                            message = storageMessage,
                            onBack = { showStorageHealth = false },
                            onSelectTier = { tier ->
                                StoragePreferences.selectTier(context, tier)
                                storageRevision++
                                storageMessage = null
                            },
                            onGrantT1 = {
                                StoragePreferences.selectTier(context, StorageTier.T1)
                                when {
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> openAllFilesSettings()
                                    Build.VERSION.SDK_INT in 26..28 -> permissionLauncher.launch(arrayOf(
                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    ))
                                    else -> storageMessage = "T1 is unavailable on Android 10. Choose a SAF workspace for shared files."
                                }
                            },
                            onAddWorkspace = { workspaceLauncher.launch(null) },
                            onRemoveWorkspace = { workspace ->
                                runCatching { StoragePreferences.removeWorkspace(context, workspace.uri) }
                                    .onFailure { storageMessage = it.message ?: "Could not remove workspace" }
                                storageRevision++
                            },
                            onImportFile = { importLauncher.launch(arrayOf("*/*")) },
                            onCopyWorkspaceIn = { workspace -> runStorageOperation("Import") {
                                WorkspaceTransfer.copyWorkspaceIntoShared(context, workspace.uri, sharedDir)
                            } },
                            onCopySharedOut = { workspace -> runStorageOperation("Export") {
                                WorkspaceTransfer.copySharedIntoWorkspace(context, sharedDir, workspace.uri)
                            } },
                            onExportDownloads = { runStorageOperation("Downloads export") {
                                WorkspaceTransfer.exportSharedToDownloads(context, sharedDir)
                            } },
                        )
                    } else if (session == null) {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Choose a Linux distro. Downloads start only when you tap Install.", color = Color.White)
                            OutlinedButton(onClick = { showStorageHealth = true }) {
                                Text("Storage Health")
                            }
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
                            ExtraKeysRow(session = session, backend = backend, onStorageClick = {
                                storageMessage = null
                                storageRevision++
                                showStorageHealth = true
                            })
                        }
                    }
                }
            }
        }
    }

    private fun startTerminal(pkg: RootfsPackage) {
        val rootfs = installer.installedRoot(pkg.distroId)
        val shared = sharedDirectory().apply { mkdirs() }
        val workspaces = StoragePreferences.workspaceUris(this)
        val snapshot = StorageAccess.snapshot(this, workspaces.size, shared.isDirectory && shared.canWrite())
        val requestedTier = StoragePreferences.selectedTier(this)
        val activeTier = StorageAccess.effectiveTier(requestedTier, snapshot)
        if (activeTier != requestedTier) {
            storageMessage = "${requestedTier} is not active. This session will use T0; open Storage Health to fix access."
        }
        val launch = ProotCommand.build(
            nativeLibraryDirectory = File(applicationInfo.nativeLibraryDir),
            rootfs = rootfs,
            temporaryDirectory = File(cacheDir, "proot/${pkg.distroId}"),
            sharedDirectory = shared,
            storageTier = activeTier,
            externalStorageRoot = if (activeTier == StorageTier.T1) Environment.getExternalStorageDirectory() else null,
        )
        sessionBinds = launch.binds
        terminalSession = TerminalSession(
            launch.executable.absolutePath,
            launch.workingDirectory,
            launch.arguments,
            launch.environment,
            null,
            backend,
        )
    }

    private fun sharedDirectory(): File = File(getExternalFilesDir(null) ?: File(filesDir, "external"), "shared")
        .apply { mkdirs() }

    /** Settings deep-link flow adapted from wolfi-terminal SettingsScreen.kt, commit d4cc391. */
    private fun openAllFilesSettings() {
        val appSettings = Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(appSettings) }.onFailure {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure { error -> storageMessage = error.message ?: "Could not open All-files settings" }
        }
    }

    private fun runStorageOperation(label: String, operation: () -> Int) {
        Thread({
            val result = runCatching(operation)
            runOnUiThread {
                storageMessage = result.fold(
                    onSuccess = { "$label complete: $it file(s)." },
                    onFailure = { StorageAccess.permissionHint(it) ?: (it.message ?: "$label failed") },
                )
                storageRevision++
            }
        }, "termx-storage-transfer").start()
    }

    override fun onResume() {
        super.onResume()
        if (::backend.isInitialized) storageRevision++
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
