package app.termx.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import app.termx.MainActivity
import app.termx.R
import app.termx.TerminalBackend
import app.termx.customization.TermXSettingsStore
import app.termx.runtime.ProotLaunch
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Owns PTYs beyond the Activity lifetime and reconnects them after process recreation. */
class SessionService : Service() {
    private data class RunningSession(val id: String, val launch: ProotLaunch, val terminal: TerminalSession)

    /** A live session exposed to the UI so it can list and switch between them. */
    data class SessionRecord(
        val id: String,
        val launch: ProotLaunch,
        val terminal: TerminalSession,
        val keepAlive: Boolean = false,
        val superviseCommands: Boolean = false,
    )

    private val sessions = linkedMapOf<String, RunningSession>()
    private lateinit var terminalBackend: TerminalBackend
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
        Log.e(TAG, "Session background task failed", failure)
    })
    private val settings by lazy { TermXSettingsStore(applicationContext) }
    private val manualKeepAlive = linkedSetOf<String>()
    private val watchedAgents = linkedSetOf("codex", "opencode", "claude")
    private val watchedCommands = linkedMapOf<String, WatchedCommand>()
    private val supervisedSessions = linkedSetOf<String>()
    private val daemonRuntime = mutableMapOf<Pair<String, String>, WatchedCommandRuntime>()
    private val agentProcesses = mutableMapOf<String, List<SessionProcess>>()
    private var scanningAgents = false
    private var wifiLockEnabled = false
    private var lastFeatureFailureAt = 0L
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val renewWakeLock = Runnable { refreshWakeLock() }
    private val renewWifiLock = Runnable { refreshWifiLock() }
    private val scanAgentsRunnable = Runnable { scanAgents() }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    inner class LocalBinder : Binder() {
        private var finishedListener: ((TerminalSession) -> Unit)? = null
        fun setFinishedListener(listener: ((TerminalSession) -> Unit)?) { finishedListener = listener }
        fun notifyFinished(terminal: TerminalSession) { finishedListener?.invoke(terminal) }
        fun createSession(launch: ProotLaunch): TerminalSession = createSessionInternal(launch)
        fun createSessionFromLastLaunch(): TerminalSession? = latestLaunch()?.let(::createSessionInternal)
        fun activeSession(): TerminalSession? = sessions.values.lastOrNull()?.terminal
        fun allSessions(): List<SessionRecord> =
            sessions.values.map {
                SessionRecord(it.id, it.launch, it.terminal, it.id in manualKeepAlive, it.id in supervisedSessions)
            }
        fun setKeepAlive(sessionId: String, enabled: Boolean) = setManualKeepAlive(sessionId, enabled)
        fun setSupervision(sessionId: String, enabled: Boolean): Boolean = setCommandSupervision(sessionId, enabled)
        /** Closes one session and returns the session that is now active (or null). */
        fun closeSession(id: String): TerminalSession? {
            val running = sessions.remove(id) ?: return activeSession()
            running.terminal.finishIfRunning()
            manualKeepAlive.remove(id)
            agentProcesses.remove(id)
            disableSupervision(id)
            saveSessions()
            persistKeepAliveSettings()
            updateNotification()
            refreshWakeLock()
            refreshWifiLock()
            scheduleAgentScan()
            if (sessions.isEmpty()) stopSelf()
            return sessions.values.lastOrNull()?.terminal
        }
        fun sessionBinds(): List<app.termx.runtime.Bind>? = sessions.values.lastOrNull()?.launch?.binds
        fun activeLaunch(): ProotLaunch? = sessions.values.lastOrNull()?.launch
        fun terminalBackend(): TerminalBackend = terminalBackend
        fun attach(view: app.termx.terminal.view.TerminalView) = terminalBackend.attach(view)
        fun detach() = terminalBackend.detach()
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        terminalBackend = TerminalBackend(applicationContext, ::onTerminalOutput, ::onSessionFinished)
        createNotificationChannel()
        startForegroundCompat(createNotification())
        loadKeepAliveSettings()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELOAD_KEEP_ALIVE_SETTINGS) {
            loadKeepAliveSettings()
            if (sessions.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_SET_WIFI_LOCK) {
            setWifiLockEnabled(intent.getBooleanExtra(EXTRA_WIFI_LOCK_ENABLED, false))
            if (sessions.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_STOP_ALL) {
            finishAllSessions(clearSaved = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (sessions.isEmpty()) restoreSessions()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(renewWakeLock)
        handler.removeCallbacks(renewWifiLock)
        handler.removeCallbacks(scanAgentsRunnable)
        scope.cancel()
        releaseWakeLockNow()
        releaseWifiLockNow()
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { it.terminal.finishIfRunning() }
        terminalBackend.detach()
        super.onDestroy()
    }

    private fun createSessionInternal(launch: ProotLaunch, sessionId: String = "session-${UUID.randomUUID()}"): TerminalSession {
        val id = sessionId
        val terminal = TerminalSession(
            launch.executable.absolutePath,
            launch.workingDirectory,
            launch.arguments,
            launch.environment,
            null,
            terminalBackend,
        )
        sessions[id] = RunningSession(id, launch, terminal)
        saveSessions()
        updateNotification()
        refreshWifiLock()
        scheduleAgentScan()
        return terminal
    }

    private fun onTerminalOutput() {
        // Output by itself does not keep the device awake. A watched agent or explicit
        // per-session keep-alive request owns the bounded wake lock.
    }

    private fun releaseWakeLockNow() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    private fun loadKeepAliveSettings() {
        scope.launch {
            try {
                val ids = JSONArray(settings.get(KEY_KEEP_ALIVE_SESSIONS, "[]"))
                    .let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }
                val names = settings.get(KEY_WATCHED_AGENTS, DEFAULT_WATCHED_AGENTS)
                    .split(',').map(String::trim).filter(String::isNotEmpty)
                val wifiEnabled = settings.get(KEY_WIFI_LOCK, "false") == "true"
                val commands = WatchedCommandRegistry.parse(settings.get(KEY_WATCHED_COMMANDS, "")).getOrThrow()
                val enabledSessions = JSONArray(settings.get(KEY_SUPERVISED_SESSIONS, "[]"))
                    .let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }
                withContext(Dispatchers.Main) {
                    manualKeepAlive.clear()
                    manualKeepAlive.addAll(ids)
                    watchedAgents.clear()
                    watchedAgents.addAll(names.ifEmpty { DEFAULT_WATCHED_AGENTS.split(',') })
                    watchedCommands.clear()
                    commands.forEach { watchedCommands[it.processName] = it }
                    supervisedSessions.clear()
                    if (commands.isNotEmpty()) supervisedSessions.addAll(enabledSessions)
                    wifiLockEnabled = wifiEnabled
                    refreshWakeLock()
                    refreshWifiLock()
                    scheduleAgentScan()
                    sessions.keys.filter { it in supervisedSessions }.forEach { ensureWatchedCommandsStarted(it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "Could not load keep-alive settings", failure)
                withContext(Dispatchers.Main) {
                    notifyFeatureFailure("Keep-alive settings could not be loaded.")
                    scheduleAgentScan()
                }
            }
        }
    }

    private fun setManualKeepAlive(sessionId: String, enabled: Boolean) {
        if (sessionId !in sessions) return
        if (enabled) manualKeepAlive.add(sessionId) else manualKeepAlive.remove(sessionId)
        refreshWakeLock()
        updateNotification()
        persistKeepAliveSettings()
    }

    private fun setCommandSupervision(sessionId: String, enabled: Boolean): Boolean {
        if (sessionId !in sessions) return false
        if (enabled && watchedCommands.isEmpty()) return false
        if (enabled) {
            supervisedSessions.add(sessionId)
            ensureWatchedCommandsStarted(sessionId)
        } else {
            disableSupervision(sessionId)
        }
        scheduleAgentScan()
        updateNotification()
        persistSupervisedSessions()
        return true
    }

    private fun ensureWatchedCommandsStarted(sessionId: String) {
        val running = sessions[sessionId] ?: return
        if (sessionId !in supervisedSessions) return
        watchedCommands.values.forEach { command ->
            val key = sessionId to command.processName
            val runtime = daemonRuntime.getOrPut(key) { WatchedCommandRuntime() }
            if (!runtime.enabled && runtime.enable(System.currentTimeMillis())) launchWatchedCommand(running, command)
        }
    }

    private fun disableSupervision(sessionId: String) {
        supervisedSessions.remove(sessionId)
        daemonRuntime.filterKeys { it.first == sessionId }.values.forEach(WatchedCommandRuntime::disable)
        persistSupervisedSessions()
    }

    private fun launchWatchedCommand(session: RunningSession, command: WatchedCommand) {
        runCatching {
            session.terminal.write(
                "printf '\\n[TermX supervisor] starting ${command.processName}\\n'; " +
                    "(${command.command}) >/dev/null 2>&1 &\n",
            )
        }.onFailure {
            Log.w(TAG, "Could not start registered daemon ${command.processName}", it)
            notifyFeatureFailure("A registered daemon could not be started.")
        }
    }

    private fun persistSupervisedSessions() {
        val saved = JSONArray().apply { supervisedSessions.forEach { put(it) } }.toString()
        scope.launch {
            runCatching { settings.put(KEY_SUPERVISED_SESSIONS, saved) }
                .onFailure {
                    Log.w(TAG, "Could not save daemon supervision settings", it)
                    handler.post { notifyFeatureFailure("Daemon supervision settings could not be saved.") }
                }
        }
    }

    private fun persistKeepAliveSettings() {
        val saved = JSONArray().apply { manualKeepAlive.forEach { put(it) } }.toString()
        scope.launch {
            try {
                settings.put(KEY_KEEP_ALIVE_SESSIONS, saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "Could not save keep-alive settings", failure)
                handler.post { notifyFeatureFailure("Keep-alive settings could not be saved.") }
            }
        }
    }

    /** Runs /proc reads on IO; service state and lock transitions stay on the main thread. */
    private fun scanAgents() {
        if (scanningAgents || sessions.isEmpty()) return
        scanningAgents = true
        val snapshot = sessions.values.map { it.id to it.terminal.pid }
        val names = (watchedAgents + watchedCommands.keys).toSet()
        scope.launch {
            val result = runCatching {
                snapshot.associate { (id, pid) -> id to AgentProcessProbe.findWatchedDescendants(pid, names) }
            }
            withContext(Dispatchers.Main) {
                scanningAgents = false
                result.onSuccess { found ->
                    agentProcesses.clear()
                    agentProcesses.putAll(found)
                    superviseWatchedCommands(found)
                    refreshWakeLock()
                    updateNotification()
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    Log.w(TAG, "Agent process scan failed", failure)
                    notifyFeatureFailure("Agent monitoring is temporarily unavailable.")
                }
                scheduleAgentScan()
            }
        }
    }

    private fun scheduleAgentScan() {
        handler.removeCallbacks(scanAgentsRunnable)
        if (sessions.isEmpty() || scanningAgents) return
        val activeAgent = agentProcesses.values.any { it.isNotEmpty() } || supervisedSessions.isNotEmpty()
        val delay = KeepAlivePolicy.pollingIntervalMillis(sessions.size, activeAgent)
        if (delay > 0) handler.postDelayed(scanAgentsRunnable, delay)
    }

    private fun superviseWatchedCommands(found: Map<String, List<SessionProcess>>) {
        if (watchedCommands.isEmpty()) return
        val now = System.currentTimeMillis()
        supervisedSessions.toList().forEach sessionLoop@{ sessionId ->
            val session = sessions[sessionId] ?: return@sessionLoop
            watchedCommands.values.forEach commandLoop@{ command ->
                val runtime = daemonRuntime[sessionId to command.processName] ?: return@commandLoop
                val alive = found[sessionId].orEmpty().any {
                    AgentProcessProbe.matches(it.command, setOf(command.processName))
                }
                val wasGaveUp = runtime.gaveUp
                if (runtime.update(alive, now)) launchWatchedCommand(session, command)
                if (!wasGaveUp && runtime.gaveUp) {
                    Log.w(TAG, "Stopped restarting registered command ${command.processName} after repeated failures")
                    session.terminal.write("\n[TermX supervisor] stopped restarting ${command.processName}; check its command.\n")
                    notifySupervisorFailure()
                }
            }
        }
    }

    private fun notifySupervisorFailure() {
        runCatching {
            val openIntent = Intent(this, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                this, REQUEST_OPEN, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            notificationManager.notify(
                SUPERVISOR_ERROR_NOTIFICATION_ID,
                NotificationCompat.Builder(this, SUPERVISOR_ERROR_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("TermX daemon restart stopped")
                    .setContentText("A watched command repeatedly exited. Check its terminal session.")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Could not post daemon supervisor notification", it) }
    }

    /** Keep feature errors visible without spamming or interrupting terminal use. */
    private fun notifyFeatureFailure(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastFeatureFailureAt < FEATURE_FAILURE_RATE_LIMIT_MILLIS) return
        lastFeatureFailureAt = now
        runCatching {
            val pending = PendingIntent.getActivity(
                this, REQUEST_OPEN, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            notificationManager.notify(
                FEATURE_FAILURE_NOTIFICATION_ID,
                NotificationCompat.Builder(this, SUPERVISOR_ERROR_CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("TermX background feature paused")
                    .setContentText(message)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Could not post background feature error", it) }
    }

    /** Acquires only while needed and renews before the bounded OS timeout expires. */
    private fun refreshWakeLock() {
        val needed = sessions.keys.any { it in manualKeepAlive } || agentProcesses.values.any { it.isNotEmpty() }
        if (!needed) {
            handler.removeCallbacks(renewWakeLock)
            releaseWakeLockNow()
            return
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = wakeLock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermX:watched-agent").also {
            wakeLock = it
        }
        runCatching { if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
            .onFailure {
                Log.w(TAG, "Could not acquire bounded agent wake lock", it)
                notifyFeatureFailure("Android did not allow the requested keep-alive lock.")
            }
        handler.removeCallbacks(renewWakeLock)
        handler.postDelayed(renewWakeLock, WAKE_LOCK_RENEW_MILLIS)
    }

    private fun setWifiLockEnabled(enabled: Boolean) {
        wifiLockEnabled = enabled
        scope.launch {
            runCatching { settings.put(KEY_WIFI_LOCK, enabled.toString()) }
                .onFailure { Log.w(TAG, "Could not save Wi-Fi lock preference", it) }
        }
        refreshWifiLock()
    }

    private fun refreshWifiLock() {
        val wanted = wifiLockEnabled && sessions.isNotEmpty()
        if (!wanted) {
            handler.removeCallbacks(renewWifiLock)
            releaseWifiLockNow()
            return
        }
        val manager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifiLock ?: manager.createWifiLock(WifiManager.WIFI_MODE_FULL, "TermX:user-enabled-network").also {
            wifiLock = it
        }
        runCatching { if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS) }
            .onFailure {
                Log.w(TAG, "Could not acquire bounded Wi-Fi lock", it)
                notifyFeatureFailure("Android did not allow the requested Wi-Fi lock.")
            }
        handler.removeCallbacks(renewWifiLock)
        handler.postDelayed(renewWifiLock, WAKE_LOCK_RENEW_MILLIS)
    }

    private fun releaseWifiLockNow() {
        wifiLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wifiLock = null
    }

    private fun finishAllSessions(clearSaved: Boolean) {
        val active = sessions.values.toList()
        sessions.clear()
        manualKeepAlive.clear()
        agentProcesses.clear()
        supervisedSessions.clear()
        daemonRuntime.values.forEach(WatchedCommandRuntime::disable)
        handler.removeCallbacks(scanAgentsRunnable)
        handler.removeCallbacks(renewWakeLock)
        handler.removeCallbacks(renewWifiLock)
        releaseWakeLockNow()
        releaseWifiLockNow()
        if (clearSaved) preferences().edit().remove(KEY_SESSIONS).apply()
        else saveSessions()
        persistKeepAliveSettings()
        persistSupervisedSessions()
        terminalBackend.detach()
        active.forEach { it.terminal.finishIfRunning() }
        updateNotification()
    }

    private fun onSessionFinished(terminal: TerminalSession) {
        handler.post {
            val id = sessions.entries.firstOrNull { it.value.terminal === terminal }?.key ?: return@post
            sessions.remove(id)
            manualKeepAlive.remove(id)
            agentProcesses.remove(id)
            disableSupervision(id)
            persistKeepAliveSettings()
            saveSessions()
            refreshWakeLock()
            refreshWifiLock()
            scheduleAgentScan()
            updateNotification()
            binder.notifyFinished(terminal)
            if (sessions.isEmpty()) stopSelf()
        }
    }

    private fun saveSessions() {
        val records = JSONArray()
        sessions.values.forEach { running -> records.put(encode(running)) }
        preferences().edit().putString(KEY_SESSIONS, records.toString()).apply()
    }

    private fun restoreSessions() {
        val records = runCatching { JSONArray(preferences().getString(KEY_SESSIONS, "[]")) }.getOrNull() ?: return
        for (index in 0 until records.length()) {
            val json = records.getJSONObject(index)
            val launch = runCatching { decode(json) }.getOrNull() ?: continue
            val id = json.optString("id").takeIf(String::isNotBlank) ?: "session-${UUID.randomUUID()}"
            createSessionInternal(launch, id)
        }
        updateNotification()
    }

    private fun latestLaunch(): ProotLaunch? = sessions.values.lastOrNull()?.launch
        ?: runCatching {
            val records = JSONArray(preferences().getString(KEY_SESSIONS, "[]"))
            if (records.length() == 0) null else decode(records.getJSONObject(records.length() - 1))
        }.getOrNull()

    private fun encode(session: RunningSession): JSONObject = JSONObject()
        .put("id", session.id)
        .put("executable", session.launch.executable.absolutePath)
        .put("workingDirectory", session.launch.workingDirectory)
        .put("arguments", JSONArray(session.launch.arguments.toList()))
        .put("environment", JSONArray(session.launch.environment.toList()))
        .put("binds", JSONArray().apply {
            session.launch.binds.forEach { put(JSONObject().put("host", it.host).put("guest", it.guest)) }
        })

    private fun decode(json: JSONObject): ProotLaunch {
        val bindArray = json.getJSONArray("binds")
        val binds = (0 until bindArray.length()).map { index ->
            val bind = bindArray.getJSONObject(index)
            app.termx.runtime.Bind(bind.getString("host"), bind.getString("guest"))
        }
        return ProotLaunch(
            executable = File(json.getString("executable")),
            workingDirectory = json.getString("workingDirectory"),
            arguments = json.getJSONArray("arguments").toStringArray(),
            environment = json.getJSONArray("environment").toStringArray(),
            binds = binds,
        )
    }

    private fun JSONArray.toStringArray(): Array<String> = Array(length()) { getString(it) }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, REQUEST_OPEN, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val newIntent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_NEW_SESSION, true)
        val newPending = PendingIntent.getActivity(
            this, REQUEST_NEW, newIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val count = sessions.size
        val agents = agentProcesses.values.sumOf { it.size }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                if (agents > 0) "$agents agent${if (agents == 1) "" else "s"} running · " +
                    resources.getQuantityString(R.plurals.notif_sessions_running, count, count)
                else resources.getQuantityString(R.plurals.notif_sessions_running, count, count),
            )
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_open), openPending)
            .addAction(0, getString(R.string.notif_new_session), newPending)
            .addAction(0, getString(R.string.notif_stop_all), stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    SUPERVISOR_ERROR_CHANNEL,
                    "Watched command errors",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (sessions.isNotEmpty()) notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun preferences() = getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        const val ACTION_STOP_ALL = "app.termx.session.STOP_ALL"
        const val ACTION_SET_WIFI_LOCK = "app.termx.session.SET_WIFI_LOCK"
        const val EXTRA_WIFI_LOCK_ENABLED = "wifi_lock_enabled"
        const val ACTION_RELOAD_KEEP_ALIVE_SETTINGS = "app.termx.session.RELOAD_KEEP_ALIVE_SETTINGS"
        private const val CHANNEL_ID = "termx_sessions"
        private const val SUPERVISOR_ERROR_CHANNEL = "termx_supervisor_errors"
        private const val NOTIFICATION_ID = 41
        private const val SUPERVISOR_ERROR_NOTIFICATION_ID = 42
        private const val FEATURE_FAILURE_NOTIFICATION_ID = 43
        private const val FEATURE_FAILURE_RATE_LIMIT_MILLIS = 60_000L
        private const val REQUEST_OPEN = 4101
        private const val REQUEST_NEW = 4102
        private const val REQUEST_STOP = 4103
        private const val PREFERENCES = "session-reconnect"
        private const val KEY_SESSIONS = "launches"
        private const val KEY_KEEP_ALIVE_SESSIONS = "agent_keep_alive_sessions_v1"
        private const val KEY_WATCHED_AGENTS = "watched_agent_names_v1"
        private const val KEY_WIFI_LOCK = "agent_wifi_lock_enabled_v1"
        private const val KEY_WATCHED_COMMANDS = "watched_daemon_commands_v1"
        private const val KEY_SUPERVISED_SESSIONS = "watched_daemon_supervised_sessions_v1"
        private const val DEFAULT_WATCHED_AGENTS = "codex,opencode,claude"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L
        private const val WAKE_LOCK_RENEW_MILLIS = 8 * 60_000L
        private const val TAG = "TermXSessionService"

        fun hasSavedSessions(context: Context): Boolean = runCatching {
            JSONArray(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_SESSIONS, "[]")).length() > 0
        }.getOrDefault(false)
    }
}
