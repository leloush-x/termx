package app.termx.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import rikka.shizuku.Shizuku

data class DoctorEntry(val label: String, val value: String)

/** Read-only capability checks. Every device probe is isolated so one denial cannot stop the report. */
object DoctorProbe {
    suspend fun collect(context: Context): List<DoctorEntry> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        buildList {
            add(probe("Notifications") {
                if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                        app, Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) "allowed" else "permission not granted"
            })
            add(probe("Battery optimization") {
                val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (power.isIgnoringBatteryOptimizations(app.packageName)) "ignored" else "optimized"
            })
            add(probe("/proc/net readable") { readable("/proc/net/tcp") })
            add(probe("/proc/stat readable") { readable("/proc/stat") })
            add(probe("App UID") { parseUid(File("/proc/self/status").readText())?.toString() ?: "unavailable" })
            add(probe("Shizuku/rish UID") {
                when {
                    !Shizuku.pingBinder() -> "Shizuku not running (no rish bridge detected)"
                    Shizuku.isPreV11() -> "unsupported Shizuku version"
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "permission not granted"
                    else -> "uid ${Shizuku.getUid()}"
                }
            })
            add(probe("App storage free") {
                formatBytes(app.filesDir.usableSpace)
            })
            add(probe("Phantom-process monitor") {
                setting(app, "settings_enable_monitor_phantom_procs")
            })
            add(probe("Phantom-process limit") {
                setting(app, "max_phantom_processes")
            })
        }
    }

    private inline fun probe(label: String, block: () -> String): DoctorEntry =
        runCatching { DoctorEntry(label, block()) }
            .getOrElse { DoctorEntry(label, "unavailable (${it.javaClass.simpleName})") }

    private fun readable(path: String): String =
        runCatching { File(path).inputStream().use { it.read() >= 0 } }
            .fold({ if (it) "yes" else "no" }, { "no (${it.javaClass.simpleName})" })

    private fun setting(context: Context, key: String): String =
        runCatching { Settings.Global.getString(context.contentResolver, key) ?: "not exposed" }
            .getOrElse { "not exposed (${it.javaClass.simpleName})" }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "unavailable"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "%.1f %s".format(java.util.Locale.ROOT, value, units[unit])
    }

    internal fun parseUid(status: String): Int? = status.lineSequence()
        .firstOrNull { it.startsWith("Uid:") }
        ?.substringAfter(':')
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.firstOrNull()
        ?.toIntOrNull()
}
