package app.termx.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import app.termx.customization.TermXSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** Applies the reversible, user-requested Android 12+ phantom-process mitigation through Shizuku. */
object PhantomProcessSettings {
    private const val ORIGINAL_VALUES_KEY = "phantom_process_original_values_v1"
    private const val ACTIVE_KEY = "phantom_process_mitigation_active_v1"
    const val UID_PERMISSION_REQUEST = 6201
    private const val TAG = "TermXPhantomSettings"

    suspend fun apply(context: Context, settings: TermXSettingsStore) = withContext(Dispatchers.IO) {
        withService(context) { service ->
            val original = KEYS.associateWith(service::readGlobal)
            val saved = JSONObject().apply { original.forEach { (key, value) -> put(key, value) } }.toString()
            settings.put(ORIGINAL_VALUES_KEY, saved)
            try {
                service.writeGlobal(PhantomSettingsUserService.MONITOR_KEY, "false")
                service.writeGlobal(PhantomSettingsUserService.PROCESS_LIMIT_KEY, Int.MAX_VALUE.toString())
                verify(service, PhantomSettingsUserService.MONITOR_KEY, "false")
                verify(service, PhantomSettingsUserService.PROCESS_LIMIT_KEY, Int.MAX_VALUE.toString())
                settings.put(ACTIVE_KEY, "true")
            } catch (failure: Throwable) {
                runCatching { restore(service, original) }
                    .onFailure { Log.w(TAG, "Could not roll back partial phantom setting change", it) }
                settings.put(ACTIVE_KEY, "false")
                throw failure
            }
        }
    }

    suspend fun undo(context: Context, settings: TermXSettingsStore) = withContext(Dispatchers.IO) {
        val original = JSONObject(settings.get(ORIGINAL_VALUES_KEY, "{}"))
        require(original.has(PhantomSettingsUserService.MONITOR_KEY) &&
            original.has(PhantomSettingsUserService.PROCESS_LIMIT_KEY)) { "No saved values to restore" }
        withService(context) { service ->
            val values = KEYS.associateWith { original.optString(it, PhantomSettingsUserService.UNSET) }
            restore(service, values)
            settings.put(ACTIVE_KEY, "false")
        }
    }

    suspend fun isActive(settings: TermXSettingsStore): Boolean =
        settings.get(ACTIVE_KEY, "false") == "true"

    fun requestPermissionIfNeeded(): Boolean {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return false
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        Shizuku.requestPermission(UID_PERMISSION_REQUEST)
        return false
    }

    private suspend fun <T> withService(context: Context, block: suspend (IPhantomSettingsService) -> T): T {
        check(Shizuku.pingBinder() && !Shizuku.isPreV11()) { "Shizuku is not running or is too old" }
        check(Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Grant TermX permission in Shizuku first"
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, PhantomSettingsUserService::class.java))
            .daemon(false)
            .tag("termx-phantom-settings-v1")
            .version(1)
        val connected = CompletableDeferred<IPhantomSettingsService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IPhantomSettingsService.Stub.asInterface(binder)
                if (service == null) connected.completeExceptionally(IllegalStateException("Shizuku returned no settings service"))
                else connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.completeExceptionally(IllegalStateException("Shizuku settings service disconnected"))
            }
        }
        return try {
            Shizuku.bindUserService(args, connection)
            val service = withTimeout(8_000L) { connected.await() }
            block(service)
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
                .onFailure { Log.w(TAG, "Could not unbind phantom settings service", it) }
        }
    }

    private fun restore(service: IPhantomSettingsService, values: Map<String, String>) {
        for ((key, value) in values) {
            if (value == PhantomSettingsUserService.UNSET) service.deleteGlobal(key) else service.writeGlobal(key, value)
            verify(service, key, value)
        }
    }

    private fun verify(service: IPhantomSettingsService, key: String, expected: String) {
        check(service.readGlobal(key) == expected) { "Android did not apply $key; change was rolled back" }
    }

    private val KEYS = listOf(
        PhantomSettingsUserService.MONITOR_KEY,
        PhantomSettingsUserService.PROCESS_LIMIT_KEY,
    )
}
