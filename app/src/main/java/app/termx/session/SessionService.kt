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
import androidx.core.app.NotificationCompat
import app.termx.MainActivity
import app.termx.R
import app.termx.TerminalBackend
import app.termx.runtime.ProotLaunch
import com.termux.terminal.TerminalSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Owns PTYs beyond the Activity lifetime and reconnects them after process recreation. */
class SessionService : Service() {
    private data class RunningSession(val id: String, val launch: ProotLaunch, val terminal: TerminalSession)

    private val sessions = linkedMapOf<String, RunningSession>()
    private lateinit var terminalBackend: TerminalBackend
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val releaseWakeLock = Runnable { releaseWakeLockNow() }
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    inner class LocalBinder : Binder() {
        fun createSession(launch: ProotLaunch): TerminalSession = createSessionInternal(launch)
        fun createSessionFromLastLaunch(): TerminalSession? = latestLaunch()?.let(::createSessionInternal)
        fun activeSession(): TerminalSession? = sessions.values.lastOrNull()?.terminal
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
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        handler.removeCallbacks(releaseWakeLock)
        releaseWakeLockNow()
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { it.terminal.finishIfRunning() }
        terminalBackend.detach()
        super.onDestroy()
    }

    private fun createSessionInternal(launch: ProotLaunch): TerminalSession {
        val id = "session-${UUID.randomUUID()}"
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
        return terminal
    }

    private fun onTerminalOutput() {
        handler.post {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermX:terminal-output")
            }
            runCatching {
                if (wakeLock?.isHeld != true) wakeLock?.acquire(35_000L)
            }
            handler.removeCallbacks(releaseWakeLock)
            handler.postDelayed(releaseWakeLock, WAKE_SILENCE_MILLIS)
        }
    }

    private fun releaseWakeLockNow() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    private fun finishAllSessions(clearSaved: Boolean) {
        val active = sessions.values.toList()
        sessions.clear()
        if (clearSaved) preferences().edit().remove(KEY_SESSIONS).apply()
        else saveSessions()
        active.forEach { it.terminal.finishIfRunning() }
        updateNotification()
    }

    private fun onSessionFinished(terminal: TerminalSession) {
        handler.post {
            val id = sessions.entries.firstOrNull { it.value.terminal === terminal }?.key ?: return@post
            sessions.remove(id)
            saveSessions()
            updateNotification()
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
            val launch = runCatching { decode(records.getJSONObject(index)) }.getOrNull() ?: continue
            createSessionInternal(launch)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TermX terminal")
            .setContentText(if (count == 1) "1 Linux session running" else "$count Linux sessions running")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, "Open", openPending)
            .addAction(0, "New session", newPending)
            .addAction(0, "Stop all", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Terminal sessions", NotificationManager.IMPORTANCE_LOW),
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
        private const val CHANNEL_ID = "termx_sessions"
        private const val NOTIFICATION_ID = 41
        private const val REQUEST_OPEN = 4101
        private const val REQUEST_NEW = 4102
        private const val REQUEST_STOP = 4103
        private const val PREFERENCES = "session-reconnect"
        private const val KEY_SESSIONS = "launches"
        private const val WAKE_SILENCE_MILLIS = 30_000L

        fun hasSavedSessions(context: Context): Boolean = runCatching {
            JSONArray(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_SESSIONS, "[]")).length() > 0
        }.getOrDefault(false)
    }
}
