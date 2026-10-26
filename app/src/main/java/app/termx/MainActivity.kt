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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
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
import app.termx.runtime.AndroidDnsResolver
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
            val darkTheme = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(LocalContext.current)
                darkTheme -> darkColorScheme(primary = Color(0xFF9CE6D8), secondary = Color(0xFFF0A6CA))
                else -> lightColorScheme(primary = Color(0xFF006B5B), secondary = Color(0xFF82506F))
            }
            MaterialTheme(colorScheme = colors) {
                Surface(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    color = MaterialTheme.colorScheme.background,
                ) {
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
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("TermX  🐙", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("A little Linux world in your pocket", style = MaterialTheme.typography.titleMedium)
                                    Text("Choose a distro to install or continue where you left off.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("Your distros", style = MaterialTheme.typography.titleLarge)
                            OutlinedButton(onClick = { showStorageHealth = true }) { Text("Storage Health") }
                            distroPackages.forEach { pkg ->
                                var installing by remember(pkg.distroId) { mutableStateOf(false) }
                                var status by remember(pkg.distroId) { mutableStateOf("") }
                                var downloadProgress by remember(pkg.distroId) { mutableFloatStateOf(0f) }
                                val installed = installer.installedRoot(pkg.distroId).isDirectory
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text("${if (pkg.distroId == "wolfi") "🐙" else "🐧"}  ${pkg.distroName}", style = MaterialTheme.typography.titleLarge)
                                        Text(if (installed) "Ready · ${pkg.version}" else "${pkg.version} · verified before install", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Button(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !installing,
                                            onClick = {
                                                if (installed) {
                                                    runCatching { startTerminal(pkg) }
                                                        .onFailure { status = it.message ?: "Could not start the distro" }
                                                } else {
                                                    installing = true
                                                    downloadProgress = 0f
                                                    status = "Preparing secure download…"
                                                    Thread({
                                                        val result = runCatching {
                                                            installer.install(pkg) { downloaded, total ->
                                                                runOnUiThread {
                                                                    downloadProgress = if (total > 0) downloaded.toFloat() / total else 0f
                                                                    status = "Downloading · ${downloaded / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                                                                }
                                                            }
                                                        }
                                                        runOnUiThread {
                                                            installing = false
                                                            status = result.fold(
                                                                onSuccess = { "${pkg.distroName} is ready. Start it when you like." },
                                                                onFailure = { it.message ?: "Install failed" },
                                                            )
                                                        }
                                                    }, "termx-rootfs-${pkg.distroId}").start()
                                                }
                                            },
                                        ) {
                                            Text(if (installing) "Installing…" else if (installed) "Start ${pkg.distroName}" else "Install ${pkg.distroName}")
                                        }
                                        if (installing) LinearProgressIndicator(
                                            progress = { downloadProgress.coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
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
            dnsResolverFile = AndroidDnsResolver.writeConfig(this, File(cacheDir, "proot/${pkg.distroId}")),
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

    /** Keep immersive terminal mode; Compose applies safe drawing insets around the curved/cutout edges. */
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
