package app.termx

import android.os.Bundle
import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
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
import app.termx.runtime.ProotLaunch
import app.termx.session.SessionService
import app.termx.storage.StorageAccess
import app.termx.storage.StorageHealthScreen
import app.termx.storage.StoragePreferences
import app.termx.storage.StorageTier
import app.termx.storage.WorkspaceTransfer
import app.termx.productivity.HistoryDatabase
import app.termx.productivity.HistoryEntry
import app.termx.productivity.HistoryImporter
import app.termx.productivity.HistoryRedactions
import app.termx.productivity.ProductivityHub
import app.termx.productivity.ProductivityPage
import app.termx.productivity.ProfileStore
import app.termx.productivity.searchHistory
import app.termx.terminal.view.TerminalView
import com.termux.terminal.TerminalSession
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var backend: TerminalBackend
    private lateinit var installer: RootfsInstaller
    private lateinit var distroPackages: List<RootfsPackage>
    private var terminalSession by mutableStateOf<TerminalSession?>(null)
    private var sessionBinds by mutableStateOf<List<Bind>?>(null)
    private var activeLaunch by mutableStateOf<ProotLaunch?>(null)
    private var storageRevision by mutableIntStateOf(0)
    private var showStorageHealth by mutableStateOf(false)
    private var productivityPage by mutableStateOf<ProductivityPage?>(null)
    private var historyItems by mutableStateOf<List<HistoryEntry>>(emptyList())
    private var historyQuery by mutableStateOf("")
    private var redactionText by mutableStateOf("")
    private var profileRevision by mutableIntStateOf(0)
    private var storageMessage by mutableStateOf<String?>(null)
    private var sessionBinder: SessionService.LocalBinder? = null
    private var pendingLaunch: ProotLaunch? = null
    private var pendingNewSession = false
    private var serviceBound = false
    private var notificationPermissionRequester: (() -> Unit)? = null

    private val sessionConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SessionService.LocalBinder ?: return
            sessionBinder = binder
            backend = binder.terminalBackend()
            when {
                pendingLaunch != null -> setActiveSession(binder.createSession(requireNotNull(pendingLaunch)))
                pendingNewSession -> {
                    val newSession = binder.createSessionFromLastLaunch()
                    if (newSession != null) setActiveSession(newSession)
                    else storageMessage = "Start a distro before creating another session."
                }
                else -> binder.activeSession()?.let(::setActiveSession)
            }
            pendingLaunch = null
            pendingNewSession = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sessionBinder = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureTerminalSystemBars()
        installer = RootfsInstaller(this)
        distroPackages = DistroManifest.load(this)
        redactionText = HistoryRedactions.text(this)
        acceptSharedFiles(intent)

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
                    val profiles = remember(profileRevision) { ProfileStore.all(context) }
                    val selectedProfile = remember(profileRevision) { ProfileStore.selected(context) }
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
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (!granted) storageMessage = "Notifications are off; Android may hide the session notification and its controls."
                        connectSessionService(pendingLaunch, pendingNewSession)
                    }
                    SideEffect {
                        notificationPermissionRequester = {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                    if (productivityPage != null) {
                        ProductivityHub(
                            page = requireNotNull(productivityPage),
                            sharedDirectory = sharedDir,
                            workspaces = workspaces,
                            history = historyItems,
                            profiles = profiles,
                            selectedProfile = selectedProfile,
                            historyQuery = historyQuery,
                            redactionText = redactionText,
                            message = storageMessage,
                            onHistoryQuery = { historyQuery = it },
                            onSearchHistory = { refreshHistory(historyQuery) },
                            onClearHistory = {
                                Thread({
                                    val result = runCatching {
                                        HistoryDatabase.get(this).history().clear()
                                        distroPackages.forEach { pkg ->
                                            File(installer.installedRoot(pkg.distroId), "root/.local/state/termx/history.tsv")
                                                .takeIf { it.isFile }?.writeText("")
                                        }
                                    }
                                    runOnUiThread {
                                        historyItems = emptyList()
                                        storageMessage = result.fold({ "Command history cleared." }, { "Could not clear history: ${it.message}" })
                                    }
                                }, "termx-history-clear").start()
                            },
                            onRedactionText = { redactionText = it },
                            onSaveRedactions = {
                                runCatching { HistoryRedactions.save(context, redactionText) }
                                    .onSuccess { storageMessage = "History privacy rules saved." }
                                    .onFailure { storageMessage = "Invalid redaction regex: ${it.message}" }
                            },
                            onBack = { productivityPage = null; storageMessage = null },
                            onNavigate = { page ->
                                productivityPage = page
                                if (page == ProductivityPage.HISTORY) refreshHistory("")
                            },
                            onOpenStorage = { productivityPage = null; showStorageHealth = true },
                            onInsertText = { text ->
                                if (terminalSession == null) {
                                    storageMessage = "Start a terminal session before inserting or running a command."
                                } else {
                                    productivityPage = null
                                    backend.insertText(text)
                                }
                            },
                            onShareFile = { file -> shareFile(file) },
                            onCreateProfile = { name ->
                                runCatching { ProfileStore.create(context, name) }
                                    .onFailure { storageMessage = it.message ?: "Could not create profile" }
                                profileRevision++
                            },
                            onSelectProfile = { id -> ProfileStore.select(context, id); profileRevision++ },
                            onUpdateProfile = { profile ->
                                runCatching { ProfileStore.update(context, profile) }
                                    .onSuccess { storageMessage = "Profile saved." }
                                    .onFailure { storageMessage = it.message ?: "Could not save profile" }
                                profileRevision++
                            },
                            onRemoveProfile = { id -> ProfileStore.remove(context, id); profileRevision++ },
                        )
                    } else if (showStorageHealth) {
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
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        Text("Session notifications keep the terminal controls available while you work elsewhere.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Text("Your distros", style = MaterialTheme.typography.titleLarge)
                            OutlinedButton(onClick = { showStorageHealth = true }) { Text("Storage Health") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { productivityPage = ProductivityPage.PALETTE }) { Text("Commands") }
                                OutlinedButton(onClick = { productivityPage = ProductivityPage.FILES }) { Text("Files") }
                                OutlinedButton(onClick = { productivityPage = ProductivityPage.PROFILES }) { Text("Profiles") }
                            }
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
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { productivityPage = ProductivityPage.PALETTE }) { Text("⌕ Commands") }
                                TextButton(onClick = { productivityPage = ProductivityPage.FILES }) { Text("Files") }
                                TextButton(onClick = { productivityPage = ProductivityPage.HISTORY; refreshHistory("") }) { Text("History") }
                            }
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
                                backend.detach()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedFiles(intent)
    }

    private fun acceptSharedFiles(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            } else {
                @Suppress("DEPRECATION")
                listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            }
            Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            storageMessage = "The shared item did not contain a readable file."
            return
        }
        runCatching { uris.sumOf { WorkspaceTransfer.copyDocumentIntoShared(this, it, sharedDirectory()) } }
            .onSuccess { count -> storageMessage = "Imported $count shared file${if (count == 1) "" else "s"} into TermX shared storage." }
            .onFailure { storageMessage = StorageAccess.permissionHint(it) ?: (it.message ?: "Could not import shared file") }
    }

    private fun shareFile(file: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            val mime = contentResolver.getType(uri) ?: java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share ${file.name}"))
        }.onFailure { storageMessage = it.message ?: "Could not share this file" }
    }

    private fun startTerminal(pkg: RootfsPackage) {
        val rootfs = installer.installedRoot(pkg.distroId)
        val shared = sharedDirectory().apply { mkdirs() }
        val workspaces = StoragePreferences.workspaceUris(this)
        val snapshot = StorageAccess.snapshot(this, workspaces.size, shared.isDirectory && shared.canWrite())
        val requestedTier = StoragePreferences.selectedTier(this)
        val activeTier = StorageAccess.effectiveTier(requestedTier, snapshot)
        val profile = ProfileStore.selected(this)
        val guestWorkingDirectory = if (profile.distroId == pkg.distroId) profile.workingDirectory else "/root"
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
            guestWorkingDirectory = guestWorkingDirectory,
            extraEnvironment = if (profile.distroId == pkg.distroId) profile.environment else emptyMap(),
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingLaunch = launch
            storageMessage = "Allow notifications to show session status and Open, New session, and Stop all controls."
            notificationPermissionRequester?.invoke() ?: connectSessionService(launch = launch)
        } else {
            connectSessionService(launch = launch)
        }
    }

    private fun setActiveSession(session: TerminalSession) {
        terminalSession = session
        sessionBinds = sessionBinder?.sessionBinds()
        activeLaunch = sessionBinder?.activeLaunch()
    }

    private fun refreshHistory(query: String) {
        Thread({
            val result = runCatching {
                val database = HistoryDatabase.get(this)
                val profile = ProfileStore.selected(this)
                val rootfs = activeLaunch?.workingDirectory?.let(::File) ?: installer.installedRoot(profile.distroId)
                val historyFile = File(rootfs, "root/.local/state/termx/history.tsv")
                HistoryImporter(database).import(historyFile, profileId = profile.id, redactionPatterns = HistoryRedactions.patterns(this))
                searchHistory(database, query)
            }
            runOnUiThread {
                historyItems = result.getOrDefault(emptyList())
                storageMessage = result.exceptionOrNull()?.let { "History is unavailable: ${it.message ?: "database error"}" }
            }
        }, "termx-history-query").start()
    }

    private fun connectSessionService(launch: ProotLaunch? = null, newSession: Boolean = false) {
        pendingLaunch = launch
        pendingNewSession = newSession
        sessionBinder?.let { binder ->
            when {
                launch != null -> setActiveSession(binder.createSession(launch))
                newSession -> binder.createSessionFromLastLaunch()?.let(::setActiveSession)
                else -> binder.activeSession()?.let(::setActiveSession)
            }
            pendingLaunch = null
            pendingNewSession = false
            return
        }
        val intent = Intent(this, SessionService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            if (!serviceBound) serviceBound = bindService(intent, sessionConnection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) storageMessage = "Could not connect to the terminal session service. Tap Start again."
        } catch (failure: Exception) {
            storageMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && failure is ForegroundServiceStartNotAllowedException) {
                "Android blocked the background service start. Open TermX and tap Start again."
            } else {
                failure.message ?: "Could not start the terminal session service."
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (intent.getBooleanExtra(EXTRA_NEW_SESSION, false)) {
            intent.removeExtra(EXTRA_NEW_SESSION)
            connectSessionService(newSession = true)
        } else if (SessionService.hasSavedSessions(this) && sessionBinder == null) {
            connectSessionService()
        }
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
        storageRevision++
        if (terminalSession?.isRunning == false && !SessionService.hasSavedSessions(this)) terminalSession = null
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
        if (::backend.isInitialized) backend.detach()
        terminalSession = null
        if (serviceBound) {
            unbindService(sessionConnection)
            serviceBound = false
        }
        sessionBinder = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NEW_SESSION = "app.termx.extra.NEW_SESSION"
    }
}
