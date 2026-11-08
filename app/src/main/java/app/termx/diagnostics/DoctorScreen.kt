package app.termx.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import app.termx.customization.TermXSettingsStore
import app.termx.session.SessionService
import app.termx.session.WatchedCommandRegistry
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import androidx.core.content.ContextCompat

@Composable
fun DoctorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<DoctorEntry>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var watchedNames by remember { mutableStateOf("codex, opencode, claude") }
    var batteryPrompted by remember { mutableStateOf(false) }
    var wifiLockEnabled by remember { mutableStateOf(false) }
    var watchedCommands by remember { mutableStateOf("") }
    var shizukuAvailable by remember { mutableStateOf(false) }
    var shizukuGranted by remember { mutableStateOf(false) }
    var phantomMitigationActive by remember { mutableStateOf(false) }
    var phantomOperationRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settings = remember(context) { TermXSettingsStore(context.applicationContext) }

    LaunchedEffect(Unit) {
        supervisorScope {
            try {
                entries = DoctorProbe.collect(context)
                watchedNames = withContext(Dispatchers.IO) {
                    settings.get("watched_agent_names_v1", "codex,opencode,claude")
                }
                batteryPrompted = withContext(Dispatchers.IO) {
                    settings.get("keep_alive_battery_prompted_v1", "false") == "true"
                }
                wifiLockEnabled = withContext(Dispatchers.IO) {
                    settings.get("agent_wifi_lock_enabled_v1", "false") == "true"
                }
                watchedCommands = withContext(Dispatchers.IO) {
                    settings.get("watched_daemon_commands_v1", "")
                }
                shizukuAvailable = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
                shizukuGranted = shizukuAvailable && runCatching {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false)
                phantomMitigationActive = PhantomProcessSettings.isActive(settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w("TermXDoctor", "Device capability scan failed", failure)
                message = "Some checks could not be read."
            } finally {
                loading = false
            }
        }
    }

    DisposableEffect(Unit) {
        val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PhantomProcessSettings.UID_PERMISSION_REQUEST) {
                shizukuGranted = grantResult == PackageManager.PERMISSION_GRANTED
                message = if (shizukuGranted) "Shizuku permission granted. You can apply the verified mitigation."
                else "Shizuku permission was not granted. No system settings were changed."
            }
        }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
            .onFailure { Log.w("TermXDoctor", "Could not attach Shizuku permission listener", it) }
        onDispose {
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
                .onFailure { Log.w("TermXDoctor", "Could not detach Shizuku permission listener", it) }
        }
    }

    val report = remember(entries) { entries.joinToString("\n") { "${it.label}: ${it.value}" } }
    val phantomMonitorValue = entries.firstOrNull { it.label == "Phantom-process monitor" }?.value.orEmpty()
    val phantomLimitValue = entries.firstOrNull { it.label == "Phantom-process limit" }?.value.orEmpty()
    val manualUndo = buildList {
        add(undoSettingCommand("settings_enable_monitor_phantom_procs", phantomMonitorValue))
        add(undoSettingCommand("max_phantom_processes", phantomLimitValue))
    }.filterNotNull().joinToString("\n")
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Device Doctor", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Read-only checks help TermX choose safe background and process-monitoring behavior.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Watched agent commands", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = watchedNames,
            onValueChange = { watchedNames = it.take(256) },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Comma-separated command names. Only matching child processes in TermX sessions are watched.") },
            singleLine = true,
        )
        Button(onClick = {
            val normalized = watchedNames.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
            if (normalized.isEmpty() || normalized.any { !it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }) {
                message = "Use comma-separated command names with letters, numbers, dot, underscore, or dash."
            } else {
                scope.launch(Dispatchers.IO) {
                    val result = runCatching { settings.put("watched_agent_names_v1", normalized.joinToString(",")) }
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            requestServiceSettingsReload(context)
                            message = "Watched commands saved."
                        }
                            .onFailure {
                                Log.w("TermXDoctor", "Could not save watched command names", it)
                                message = "Could not save watched commands."
                            }
                        }
                }
            }
        }) { Text("Save watched commands") }
        Text("Registered daemon commands", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = watchedCommands,
            onValueChange = { watchedCommands = it.take(4096) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("process-name=command") },
            supportingText = { Text("One per line. Commands are only started or restarted when you enable supervision for a session.") },
            minLines = 2,
            maxLines = 6,
        )
        Button(onClick = {
            val parsed = WatchedCommandRegistry.parse(watchedCommands)
            if (parsed.isFailure) {
                message = "Invalid daemon list: ${parsed.exceptionOrNull()?.message ?: "check each line"}"
            } else {
                val saved = parsed.getOrThrow().joinToString("\n") { "${it.processName}=${it.command}" }
                scope.launch(Dispatchers.IO) {
                    val result = runCatching { settings.put("watched_daemon_commands_v1", saved) }
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            requestServiceSettingsReload(context)
                            message = "Registered daemon commands saved."
                        }
                            .onFailure {
                                Log.w("TermXDoctor", "Could not save watched daemon commands", it)
                                message = "Could not save daemon commands."
                            }
                    }
                }
            }
        }) { Text("Save daemon registrations") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Keep Wi-Fi awake", style = MaterialTheme.typography.titleSmall)
                Text("Optional; active only while a terminal session is alive.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = wifiLockEnabled, onCheckedChange = { enabled ->
                wifiLockEnabled = enabled
                scope.launch(Dispatchers.IO) {
                    val result = runCatching { settings.put("agent_wifi_lock_enabled_v1", enabled.toString()) }
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            runCatching {
                                ContextCompat.startForegroundService(
                                    context,
                                    Intent(context, SessionService::class.java)
                                        .setAction(SessionService.ACTION_SET_WIFI_LOCK)
                                        .putExtra(SessionService.EXTRA_WIFI_LOCK_ENABLED, enabled),
                                )
                            }.onFailure {
                                Log.w("TermXDoctor", "Could not update the Wi-Fi lock", it)
                                message = "Saved the setting, but the active service did not update."
                            }
                        }.onFailure {
                            Log.w("TermXDoctor", "Could not save Wi-Fi lock preference", it)
                            wifiLockEnabled = !enabled
                            message = "Could not save the Wi-Fi lock setting."
                        }
                    }
                }
            })
        }
        Button(onClick = {
            val action = if (batteryPrompted) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                batteryPrompted = true
                scope.launch(Dispatchers.IO) {
                    runCatching { settings.put("keep_alive_battery_prompted_v1", "true") }
                        .onFailure { Log.w("TermXDoctor", "Could not save battery guide state", it) }
                }
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            }
            runCatching { context.startActivity(action) }
                .onFailure {
                    Log.w("TermXDoctor", "Battery settings intent unavailable", it)
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }.onFailure { error ->
                        Log.w("TermXDoctor", "App settings fallback unavailable", error)
                        message = "Open Android Settings > Apps > TermX > Battery manually."
                    }
                }
        }) { Text(if (batteryPrompted) "Open battery optimization settings" else "Request battery exemption once") }
        Button(onClick = {
            val intents = listOf(
                Intent("miui.intent.action.OP_AUTO_START"),
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )),
            )
            val opened = intents.firstNotNullOfOrNull { candidate ->
                runCatching {
                    candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(candidate)
                    true
                }.getOrNull()
            } ?: runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                true
            }.getOrDefault(false)
            if (!opened) message = "Open Settings > Apps > TermX > Battery, then enable autostart and No restrictions if available."
            else message = "If the page is unavailable, open Settings > Apps > TermX > Battery and check autostart / No restrictions."
        }) { Text("Xiaomi / HyperOS background settings") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text(
                "Optional mitigation changes two global Android settings. TermX saves the current values, verifies the result, and offers an undo. It only runs after you grant TermX access in Shizuku.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = !phantomOperationRunning,
                onClick = {
                    when {
                        !shizukuAvailable -> message = "Start Shizuku, then return to Device Doctor. No settings changed."
                        !shizukuGranted -> {
                            runCatching { PhantomProcessSettings.requestPermissionIfNeeded() }
                                .onFailure {
                                    Log.w("TermXDoctor", "Could not request Shizuku permission", it)
                                    message = "Could not request Shizuku access."
                                }
                            if (!shizukuGranted) message = "Approve TermX in Shizuku, then tap Apply again."
                        }
                        else -> {
                            phantomOperationRunning = true
                            scope.launch(Dispatchers.IO) {
                                val result = runCatching { PhantomProcessSettings.apply(context, settings) }
                                val refreshed = runCatching { DoctorProbe.collect(context) }.getOrDefault(entries)
                                withContext(Dispatchers.Main) {
                                    phantomOperationRunning = false
                                    result.onSuccess {
                                        entries = refreshed
                                        phantomMitigationActive = true
                                        message = "Mitigation applied and verified. Use Undo to restore the saved values."
                                    }.onFailure {
                                        Log.w("TermXDoctor", "Phantom-process mitigation failed", it)
                                        message = "Mitigation was not verified; TermX attempted to restore the original values. ${it.message ?: "Check Shizuku permission and Doctor values."}"
                                    }
                                }
                            }
                        }
                    }
                },
            ) { Text(if (shizukuGranted) "Apply phantom-process mitigation" else "Connect Shizuku for mitigation") }
            TextButton(
                enabled = phantomMitigationActive && !phantomOperationRunning,
                onClick = {
                    phantomOperationRunning = true
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching { PhantomProcessSettings.undo(context, settings) }
                        val refreshed = runCatching { DoctorProbe.collect(context) }.getOrDefault(entries)
                        withContext(Dispatchers.Main) {
                            phantomOperationRunning = false
                            result.onSuccess {
                                entries = refreshed
                                phantomMitigationActive = false
                                message = "Original phantom-process settings restored and verified."
                            }.onFailure {
                                Log.w("TermXDoctor", "Could not restore phantom-process settings", it)
                                message = "Undo failed. Keep Shizuku running and retry."
                            }
                        }
                    }
                },
            ) { Text("Undo mitigation") }
            Text(
                "Android may limit child processes. Manual adb fallback (advanced): apply only when both original values are readable and recorded.\n" +
                    "adb shell settings put global settings_enable_monitor_phantom_procs false\n" +
                    "adb shell settings put global max_phantom_processes 2147483647\n" +
                    "Undo:\n$manualUndo",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading) Text("Checking device capabilities…")
        entries.forEach { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.titleSmall)
                    Text(entry.value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (message != null) Text(message!!, color = MaterialTheme.colorScheme.error)
        Button(
            enabled = entries.isNotEmpty(),
            onClick = {
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("TermX Device Doctor", report))
                }.onFailure {
                    Log.w("TermXDoctor", "Could not copy diagnostic report", it)
                    message = "Could not copy the report."
                }.onSuccess { message = "Report copied." }
            },
        ) { Text("Copy report") }
    }
}

private fun requestServiceSettingsReload(context: Context) {
    runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, SessionService::class.java).setAction(SessionService.ACTION_RELOAD_KEEP_ALIVE_SETTINGS),
        )
    }.onFailure { Log.w("TermXDoctor", "Could not notify session service about new settings", it) }
}

private fun undoSettingCommand(key: String, original: String): String? = when {
    original == "not exposed" || original.startsWith("not exposed (") -> "adb shell settings delete global $key"
    key == "settings_enable_monitor_phantom_procs" && original in setOf("true", "false", "0", "1") ->
        "adb shell settings put global $key $original"
    key == "max_phantom_processes" && original.toLongOrNull()?.let { it in 1L..Int.MAX_VALUE.toLong() } == true ->
        "adb shell settings put global $key $original"
    else -> null
}
