package app.termx

import android.os.Bundle
import android.animation.ValueAnimator
import android.graphics.Typeface
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import app.termx.runtime.DistroManifest
import app.termx.runtime.RootfsInstaller
import app.termx.runtime.RootfsPackage
import app.termx.runtime.ProotCommand
import app.termx.runtime.AndroidDnsResolver
import app.termx.runtime.Bind
import app.termx.runtime.ProotLaunch
import app.termx.session.SessionService
import app.termx.session.SessionService.SessionRecord
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
import app.termx.customization.FontRepository
import app.termx.customization.TermXSettingsStore
import app.termx.customization.TermXTheme
import app.termx.customization.TerminalTheme
import app.termx.customization.defaultAmoledTheme
import app.termx.customization.defaultDarkTheme
import com.termux.terminal.TerminalSession
import java.io.File

private data class CustomizationSnapshot(
    val amoled: Boolean,
    val themeJson: String?,
    val keyLayoutJson: String?,
    val fontPath: String?,
    val fontSize: Int,
    val wallpaperUri: String?,
    val wallpaperBlur: Float,
    val wallpaperDim: Float,
)

class MainActivity : ComponentActivity() {
    private lateinit var backend: TerminalBackend
    private lateinit var installer: RootfsInstaller
    private lateinit var distroPackages: List<RootfsPackage>
    private var terminalSession by mutableStateOf<TerminalSession?>(null)
    private var sessionBinds by mutableStateOf<List<Bind>?>(null)
    private var activeLaunch by mutableStateOf<ProotLaunch?>(null)
    private var sessionRecords by mutableStateOf<List<SessionRecord>>(emptyList())
    private var storageRevision by mutableIntStateOf(0)
    private var showStorageHealth by mutableStateOf(false)
    private var productivityPage by mutableStateOf<ProductivityPage?>(null)
    private var historyItems by mutableStateOf<List<HistoryEntry>>(emptyList())
    private var historyQuery by mutableStateOf("")
    private var redactionText by mutableStateOf("")
    private var profileRevision by mutableIntStateOf(0)
    private var selectedDistroId by mutableStateOf("wolfi")
    private var defaultDistroId by mutableStateOf("wolfi")
    private var installedRevision by mutableIntStateOf(0)
    private var installingDistroId by mutableStateOf<String?>(null)
    private var restoringSavedSession by mutableStateOf(false)
    private var showTerminalPanel by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var amoledMode by mutableStateOf(true)
    private var terminalThemeJson by mutableStateOf<String?>(null)
    private var terminalKeyLayoutJson by mutableStateOf<String?>(null)
    private var terminalTypeface by mutableStateOf<Typeface?>(null)
    private var terminalFontSize by mutableIntStateOf(16)
    private var wallpaperUri by mutableStateOf<String?>(null)
    private var wallpaperBlur by mutableFloatStateOf(12f)
    private var wallpaperDim by mutableFloatStateOf(0.35f)
    private var customizationMessage by mutableStateOf<String?>(null)
    private lateinit var customizationStore: TermXSettingsStore
    private lateinit var fontRepository: FontRepository
    private val installProgress = mutableStateMapOf<String, Float>()
    private val installStatuses = mutableStateMapOf<String, String>()
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
            restoringSavedSession = false
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
        installer = RootfsInstaller(this)
        customizationStore = TermXSettingsStore(this)
        fontRepository = FontRepository(this)
        distroPackages = DistroManifest.load(this)
        val preferences = getSharedPreferences("landing", MODE_PRIVATE)
        defaultDistroId = preferences.getString("default_distro", preferences.getString("selected_distro", "wolfi")) ?: "wolfi"
        selectedDistroId = defaultDistroId
        restoringSavedSession = SessionService.hasSavedSessions(this) || intent.getBooleanExtra(EXTRA_NEW_SESSION, false)
        redactionText = HistoryRedactions.text(this)
        Thread({
            val loaded = runCatching { kotlinx.coroutines.runBlocking {
                CustomizationSnapshot(customizationStore.get("amoled_mode", "true") != "false",
                    customizationStore.get("theme_json").takeIf { it.isNotBlank() },
                    customizationStore.get("key_layout_json").takeIf { it.isNotBlank() },
                    customizationStore.get("font_path").takeIf { it.isNotBlank() },
                    customizationStore.get("text_size", "16").toIntOrNull() ?: 16,
                    customizationStore.get("wallpaper_uri").takeIf { it.isNotBlank() },
                    customizationStore.get("wallpaper_blur", "12").toFloatOrNull() ?: 12f,
                    customizationStore.get("wallpaper_dim", "0.35").toFloatOrNull() ?: 0.35f)
            } }
            runOnUiThread {
                loaded.onSuccess { snapshot ->
                    amoledMode = snapshot.amoled
                    terminalThemeJson = snapshot.themeJson
                    terminalKeyLayoutJson = snapshot.keyLayoutJson
                    terminalFontSize = snapshot.fontSize
                    wallpaperUri = snapshot.wallpaperUri
                    wallpaperBlur = snapshot.wallpaperBlur.coerceIn(0f, 32f)
                    wallpaperDim = snapshot.wallpaperDim.coerceIn(0f, 1f)
                    if (snapshot.fontPath != null) fontRepository.loadImported(File(snapshot.fontPath)) { font ->
                        font.onSuccess { typeface -> runOnUiThread { terminalTypeface = typeface } }
                    }
                }
                    .onFailure { customizationMessage = it.message ?: "Could not load appearance settings" }
            }
        }, "termx-settings-load").start()
        fontRepository.loadBundled { result -> result.onSuccess { runOnUiThread { terminalTypeface = it } } }
        acceptSharedFiles(intent)

        setContent {
            val customTheme = terminalThemeJson?.let { runCatching { TermXTheme.parse(it) }.getOrNull() }
            val terminalTheme = customTheme ?: if (amoledMode) defaultAmoledTheme() else defaultDarkTheme()
            val terminalBackground = android.graphics.Color.parseColor(terminalTheme.background)
            val terminalSurfaceColor = Color(terminalBackground)
            val wallpaper = rememberWallpaperBitmap(wallpaperUri, wallpaperBlur)
            val darkTheme = when (customTheme?.mode) {
                "dark" -> true
                "light" -> false
                else -> amoledMode || isSystemInDarkTheme()
            }
            val colors = when {
                customTheme != null && darkTheme -> darkColorScheme(
                    primary = Color(android.graphics.Color.parseColor(customTheme.bold)),
                    background = Color(android.graphics.Color.parseColor(customTheme.background)),
                    surface = Color(android.graphics.Color.parseColor(customTheme.background)),
                    onBackground = Color(android.graphics.Color.parseColor(customTheme.foreground)),
                    onSurface = Color(android.graphics.Color.parseColor(customTheme.foreground)),
                )
                customTheme != null -> lightColorScheme(
                    primary = Color(android.graphics.Color.parseColor(customTheme.bold)),
                    background = Color(android.graphics.Color.parseColor(customTheme.background)),
                    surface = Color(android.graphics.Color.parseColor(customTheme.background)),
                    onBackground = Color(android.graphics.Color.parseColor(customTheme.foreground)),
                    onSurface = Color(android.graphics.Color.parseColor(customTheme.foreground)),
                )
                amoledMode -> darkColorScheme(background = Color.Black, surface = Color.Black, primary = Color(0xFF9CE6D8))
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(LocalContext.current)
                darkTheme -> darkColorScheme(primary = Color(0xFF9CE6D8), secondary = Color(0xFFF0A6CA))
                else -> lightColorScheme(primary = Color(0xFF006B5B), secondary = Color(0xFF82506F))
            }
            MaterialTheme(colorScheme = colors) {
                val rootSurfaceColor = when {
                    sessionIsDarkScreen() -> Color.Black
                    terminalSession != null && productivityPage == null && !showStorageHealth -> MaterialTheme.colorScheme.surface
                    else -> MaterialTheme.colorScheme.background
                }
                Surface(
                    modifier = Modifier.fillMaxSize().background(rootSurfaceColor).windowInsetsPadding(WindowInsets.safeDrawing),
                    color = Color.Transparent,
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
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            val useDarkIcons = !sessionIsDarkScreen() && !darkTheme
                            isAppearanceLightStatusBars = useDarkIcons
                            isAppearanceLightNavigationBars = useDarkIcons
                        }
                    }
                    BackHandler {
                        when {
                            showTerminalPanel -> showTerminalPanel = false
                            showSettings -> showSettings = false
                            productivityPage != null -> { productivityPage = null; storageMessage = null }
                            showStorageHealth -> showStorageHealth = false
                            terminalSession != null -> showTerminalPanel = true
                            restoringSavedSession -> Unit
                            else -> Unit
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
                    val fontImportLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) fontRepository.import(uri) { imported ->
                            imported.onSuccess { file -> fontRepository.loadImported(file) { loadedFont ->
                                loadedFont.onSuccess { typeface ->
                                    Thread({ runCatching { kotlinx.coroutines.runBlocking {
                                        customizationStore.put("font_path", file.absolutePath)
                                    } }.onFailure { runOnUiThread { customizationMessage = it.message } } }, "termx-font-save").start()
                                    runOnUiThread { terminalTypeface = typeface; customizationMessage = "Font imported." }
                                }.onFailure { runOnUiThread { customizationMessage = it.message } }
                            } }.onFailure { runOnUiThread { customizationMessage = it.message } }
                        }
                    }
                    val wallpaperPickerLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) {
                            // Persist read access so the wallpaper survives app restarts.
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                            wallpaperUri = uri.toString()
                            customizationMessage = "Wallpaper applied."
                            Thread({ runCatching { kotlinx.coroutines.runBlocking {
                                customizationStore.put("wallpaper_uri", wallpaperUri.orEmpty())
                            } }.onFailure { runOnUiThread { customizationMessage = it.message } } }, "termx-wallpaper-save").start()
                        }
                    }
                    val session = terminalSession
                    val installedDistros = remember(installedRevision) {
                        distroPackages.filter { installer.installedRoot(it.distroId).isDirectory }.map { it.distroId }.toSet()
                    }
                    if (restoringSavedSession && session == null) {
                        Column(
                            Modifier.fillMaxSize().background(Color.Black),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        ) {
                            Text("TERM X", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelLarge)
                            androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp))
                            Text("Reconnecting to your terminal…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                    } else if (showSettings) {
                        DistroSettingsScreen(
                            distros = distroPackages,
                            installedDistros = installedDistros,
                            defaultDistroId = defaultDistroId,
                            installingDistroId = installingDistroId,
                            installProgress = installingDistroId?.let(installProgress::get) ?: 0f,
                            installStatus = installingDistroId?.let(installStatuses::get),
                            amoledMode = amoledMode,
                            onAmoledMode = { enabled ->
                                amoledMode = enabled
                                Thread({ runCatching { kotlinx.coroutines.runBlocking { customizationStore.put("amoled_mode", enabled.toString()) } } },
                                    "termx-appearance-save").start()
                            },
                            terminalFontSize = terminalFontSize,
                            onFontSize = { size ->
                                terminalFontSize = size.coerceIn(12, 28)
                                Thread({ runCatching { kotlinx.coroutines.runBlocking { customizationStore.put("text_size", terminalFontSize.toString()) } } },
                                    "termx-appearance-save").start()
                            },
                            onImportFont = { fontImportLauncher.launch(arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream")) },
                            wallpaperUri = wallpaperUri,
                            wallpaperBlur = wallpaperBlur,
                            wallpaperDim = wallpaperDim,
                            onPickWallpaper = { wallpaperPickerLauncher.launch(arrayOf("image/*")) },
                            onWallpaperBlur = { value ->
                                wallpaperBlur = value.coerceIn(0f, 32f)
                                Thread({ runCatching { kotlinx.coroutines.runBlocking {
                                    customizationStore.put("wallpaper_blur", wallpaperBlur.toString())
                                } } }, "termx-wallpaper-save").start()
                            },
                            onWallpaperDim = { value ->
                                wallpaperDim = value.coerceIn(0f, 1f)
                                Thread({ runCatching { kotlinx.coroutines.runBlocking {
                                    customizationStore.put("wallpaper_dim", wallpaperDim.toString())
                                } } }, "termx-wallpaper-save").start()
                            },
                            onClearWallpaper = {
                                wallpaperUri = null
                                Thread({ runCatching { kotlinx.coroutines.runBlocking {
                                    customizationStore.put("wallpaper_uri", "")
                                } } }, "termx-wallpaper-save").start()
                            },
                            customizationMessage = customizationMessage,
                            onBack = { showSettings = false },
                            onSetDefault = { id ->
                                defaultDistroId = id
                                selectedDistroId = id
                                getSharedPreferences("landing", MODE_PRIVATE).edit()
                                    .putString("default_distro", id).putString("selected_distro", id).apply()
                            },
                            onInstall = ::installDistro,
                            onOpenStorage = {
                                if (::backend.isInitialized) backend.detach()
                                showSettings = false
                                showStorageHealth = true
                            },
                        )
                    } else if (productivityPage != null) {
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
                        DistroLandingScreen(
                            distros = distroPackages,
                            installedDistros = installedDistros,
                            selectedDistroId = selectedDistroId,
                            installingDistroId = installingDistroId,
                            amoled = amoledMode,
                            onSelectDistro = { id ->
                                selectedDistroId = id
                                getSharedPreferences("landing", MODE_PRIVATE).edit().putString("selected_distro", id).apply()
                            },
                            onSettings = {
                                if (::backend.isInitialized) backend.detach()
                                showSettings = true
                            },
                            progress = installProgress[selectedDistroId] ?: 0f,
                            status = installStatuses[selectedDistroId],
                            onInstall = ::installDistro,
                            onStart = { pkg -> runCatching { startTerminal(pkg) }
                                .onFailure { installStatuses[pkg.distroId] = it.message ?: "Could not start the distro" } },
                            onCommands = { productivityPage = ProductivityPage.PALETTE },
                            onFiles = { productivityPage = ProductivityPage.FILES },
                            onProfiles = { productivityPage = ProductivityPage.PROFILES },
                            onStorage = { showStorageHealth = true },
                        )
                    } else {
                        val terminalBackdrop = rememberLayerBackdrop()
                        val animateTerminalPanel = remember { ValueAnimator.areAnimatorsEnabled() }
                        val activeDistro = distroPackages.firstOrNull {
                            installer.installedRoot(it.distroId).absolutePath == activeLaunch?.workingDirectory
                        }
                        val terminalAccent = distroAccentColor(activeDistro?.distroId ?: "wolfi")
                        val guestDirectory = activeLaunch?.let(::guestDirOf) ?: "/root"
                        // Group every running session by the distro that owns it so the panel can
                        // list Wolfi sessions under Wolfi, Alpine under Alpine, and so on.
                        val currentId = sessionRecords.firstOrNull { it.terminal === session }?.id
                        val panelGroups = (sessionRecords.ifEmpty {
                            listOfNotNull(activeLaunch?.let { SessionRecord("current", it, session) })
                        }).groupBy { distroIdForWorkingDirectory(it.launch.workingDirectory) ?: "wolfi" }
                            .map { (id, records) ->
                                PanelGroup(
                                    distroId = id,
                                    distroName = distroPackages.firstOrNull { it.distroId == id }?.distroName ?: id,
                                    accent = distroAccentColor(id),
                                    sessions = records.map { record ->
                                        PanelSession(record.id, guestDirOf(record.launch), record.id == currentId)
                                    },
                                )
                            }
                        Box(Modifier.fillMaxSize().background(terminalSurfaceColor)) {
                            WallpaperLayer(wallpaper, wallpaperBlur, wallpaperDim)
                            Column(Modifier.fillMaxSize().layerBackdrop(terminalBackdrop)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "≡",
                                        Modifier.clickable { refreshSessions(); showTerminalPanel = true }
                                            .padding(horizontal = 10.dp, vertical = 3.dp),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(activeDistro?.distroName ?: "Terminal", color = Color.White,
                                            style = MaterialTheme.typography.labelLarge)
                                        Text(guestDirectory, color = Color.White.copy(alpha = 0.58f),
                                            style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                    Box(Modifier.size(7.dp).background(terminalAccent, androidx.compose.foundation.shape.CircleShape))
                                }
                                key(session) {
                                    AndroidView(
                                        modifier = Modifier.weight(1f),
                                        factory = { viewContext ->
                                            TerminalView(viewContext, null).apply {
                                                setTextSize(terminalFontSize)
                                                setBackgroundColor(terminalBackground)
                                                terminalTypeface?.let(::setTypeface)
                                                backend.theme = terminalTheme
                                                backend.attach(this)
                                                attachSession(session)
                                                isFocusableInTouchMode = true
                                                requestFocus()
                                            }
                                        },
                                        update = { view ->
                                            view.setTextSize(terminalFontSize)
                                            terminalTypeface?.let(view::setTypeface)
                                            backend.theme = terminalTheme
                                            TerminalTheme.apply(view.mEmulator, terminalTheme)
                                            view.setBackgroundColor(terminalBackground)
                                            view.invalidate()
                                        },
                                    )
                                }
                                ExtraKeysRow(session = session, backend = backend, keyLayoutJson = terminalKeyLayoutJson)
                            }
                            AnimatedVisibility(
                                visible = showTerminalPanel,
                                modifier = Modifier.fillMaxSize(),
                                enter = if (animateTerminalPanel) {
                                    slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(240)) + fadeIn(tween(180))
                                } else EnterTransition.None,
                                exit = if (animateTerminalPanel) {
                                    slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(180)) + fadeOut(tween(130))
                                } else ExitTransition.None,
                            ) {
                                TerminalSidePanel(
                                    backdrop = terminalBackdrop,
                                    distroName = activeDistro?.distroName ?: "Linux session",
                                    distroId = activeDistro?.distroId ?: "wolfi",
                                    workingDirectory = guestDirectory,
                                    accent = terminalAccent,
                                    groups = panelGroups,
                                    installedDistros = distroPackages.filter { it.distroId in installedDistros },
                                    onTerminal = { showTerminalPanel = false },
                                    onSelectSession = { id ->
                                        sessionRecords.firstOrNull { it.id == id }?.let { record ->
                                            terminalSession = record.terminal
                                            activeLaunch = record.launch
                                            sessionBinds = record.launch.binds
                                        }
                                        showTerminalPanel = false
                                    },
                                    onDeleteSession = { id ->
                                        val newActive = sessionBinder?.closeSession(id)
                                        sessionRecords = sessionBinder?.allSessions() ?: emptyList()
                                        if (terminalSession?.isRunning == false || terminalSession == null) {
                                            terminalSession = newActive ?: sessionBinder?.activeSession()
                                        }
                                        if (terminalSession != null) {
                                            setActiveSession(terminalSession!!)
                                        } else {
                                            activeLaunch = null
                                            sessionBinds = null
                                        }
                                        showTerminalPanel = false
                                    },
                                    onNewTerminal = { pkg ->
                                        selectedDistroId = pkg.distroId
                                        getSharedPreferences("landing", MODE_PRIVATE).edit().putString("selected_distro", pkg.distroId).apply()
                                        showTerminalPanel = false
                                        runCatching { startTerminal(pkg) }
                                            .onFailure { storageMessage = it.message ?: "Could not start ${pkg.distroName}" }
                                    },
                                    onSettings = {
                                        backend.detach()
                                        showTerminalPanel = false
                                        showSettings = true
                                    },
                                    onClose = { showTerminalPanel = false },
                                )
                            }
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
        if (intent.getBooleanExtra(EXTRA_NEW_SESSION, false)) {
            intent.removeExtra(EXTRA_NEW_SESSION)
            restoringSavedSession = true
            connectSessionService(newSession = true)
        }
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
            distroName = pkg.distroName,
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

    private fun installDistro(pkg: RootfsPackage) {
        if (installingDistroId != null) return
        installingDistroId = pkg.distroId
        installProgress[pkg.distroId] = 0f
        installStatuses[pkg.distroId] = "Preparing secure download…"
        Thread({
            val result = runCatching {
                installer.install(pkg) { downloaded, total ->
                    runOnUiThread {
                        installProgress[pkg.distroId] = if (total > 0) downloaded.toFloat() / total else 0f
                        installStatuses[pkg.distroId] = if (total > 0) {
                            "Downloading · ${downloaded / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                        } else {
                            "Downloading · ${downloaded / (1024 * 1024)} MB"
                        }
                    }
                }
            }
            runOnUiThread {
                installedRevision++
                installStatuses[pkg.distroId] = result.fold(
                    onSuccess = { "${pkg.distroName} is ready. Open it when you like." },
                    onFailure = { it.message ?: "Install failed" },
                )
                installingDistroId = null
            }
        }, "termx-rootfs-${pkg.distroId}").start()
    }

    private fun setActiveSession(session: TerminalSession) {
        terminalSession = session
        refreshSessions()
        val record = sessionRecords.firstOrNull { it.terminal === session }
        activeLaunch = record?.launch ?: sessionBinder?.activeLaunch()
        sessionBinds = activeLaunch?.binds ?: sessionBinder?.sessionBinds()
    }

    private fun refreshSessions() {
        sessionRecords = sessionBinder?.allSessions() ?: emptyList()
    }

    /** Maps a launch's host rootfs directory back to the distro id that owns it. */
    private fun distroIdForWorkingDirectory(workingDirectory: String): String? =
        distroPackages.firstOrNull { installer.installedRoot(it.distroId).absolutePath == workingDirectory }?.distroId

    /** Guest working directory encoded in a launch's `-w` argument. */
    private fun guestDirOf(launch: ProotLaunch): String =
        launch.arguments.indexOf("-w").takeIf { it >= 0 }?.let { launch.arguments.getOrNull(it + 1) } ?: "/root"

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
            restoringSavedSession = false
            return
        }
        val intent = Intent(this, SessionService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            if (!serviceBound) serviceBound = bindService(intent, sessionConnection, Context.BIND_AUTO_CREATE)
            if (!serviceBound) storageMessage = "Could not connect to the terminal session service. Tap Start again."
            if (!serviceBound) restoringSavedSession = false
        } catch (failure: Exception) {
            restoringSavedSession = false
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
        refreshSessions()
        if (terminalSession?.isRunning == false && !SessionService.hasSavedSessions(this)) terminalSession = null
    }

    private fun sessionIsDarkScreen() = showSettings || restoringSavedSession ||
        (terminalSession == null && productivityPage == null && !showStorageHealth)

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
