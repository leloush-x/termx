package app.termx.diagnostics

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.termx.customization.TermXSettingsStore
import app.termx.session.SessionService
import app.termx.session.WatchedCommandRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

@Composable
fun DoctorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { TermXSettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<DoctorEntry>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var watchedNames by remember { mutableStateOf("codex, opencode, claude") }
    var batteryPrompted by remember { mutableStateOf(false) }
    var wifiLockEnabled by remember { mutableStateOf(false) }
    var watchedCommands by remember { mutableStateOf("") }
    var terminalNotificationsEnabled by remember { mutableStateOf(false) }
    var notificationHooksEnabled by remember { mutableStateOf(false) }
    var processViewerEnabled by remember { mutableStateOf(true) }

    val saveTerminalNotifications: (Boolean) -> Unit = { enabled ->
        scope.launch(Dispatchers.IO) {
            val result = runCatching { settings.put("terminal_notifications_enabled_v1", enabled.toString()) }
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    terminalNotificationsEnabled = enabled
                    requestServiceSettingsReload(context)
                    message = if (enabled) "Terminal notifications enabled in the background only."
                    else "Terminal notifications disabled."
                }.onFailure {
                    Log.w("TermXDoctor", "Could not save terminal notification setting", it)
                    message = "Could not save terminal notification setting."
                }
            }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveTerminalNotifications(true)
        else message = "Notification permission was not granted; terminal notifications remain off."
    }

    LaunchedEffect(Unit) {
        supervisorScope {
            try {
                entries = DoctorProbe.collect(context)
                watchedNames = withContext(Dispatchers.IO) { settings.get("watched_agent_names_v1", "codex,opencode,claude") }
                batteryPrompted = withContext(Dispatchers.IO) { settings.get("keep_alive_battery_prompted_v1", "false") == "true" }
                wifiLockEnabled = withContext(Dispatchers.IO) { settings.get("agent_wifi_lock_enabled_v1", "false") == "true" }
                watchedCommands = withContext(Dispatchers.IO) { settings.get("watched_daemon_commands_v1", "") }
                terminalNotificationsEnabled = withContext(Dispatchers.IO) {
                    settings.get("terminal_notifications_enabled_v1", "false") == "true"
                }
                notificationHooksEnabled = withContext(Dispatchers.IO) {
                    settings.get("terminal_notification_hooks_v1", "false") == "true"
                }
                processViewerEnabled = withContext(Dispatchers.IO) {
                    settings.get("process_viewer_enabled_v1", "true") == "true"
                }
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

    val report = remember(entries) { entries.joinToString("\n") { "${it.label}: ${it.value}" } }
    val phantomMonitor = entries.firstOrNull { it.label == "Phantom-process monitor" }?.value ?: "unavailable"
    val phantomLimit = entries.firstOrNull { it.label == "Phantom-process limit" }?.value ?: "unavailable"
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Device Doctor", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Read-only checks report what this device allows for background sessions and process monitoring.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Process and port viewer", style = MaterialTheme.typography.titleSmall)
                Text("Scans app-owned processes only while its panel is open.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = processViewerEnabled, onCheckedChange = { enabled ->
                scope.launch(Dispatchers.IO) {
                    val result = runCatching { settings.put("process_viewer_enabled_v1", enabled.toString()) }
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            processViewerEnabled = enabled
                            message = if (enabled) "Process viewer enabled." else "Process viewer disabled."
                        }.onFailure {
                            Log.w("TermXDoctor", "Could not save process viewer setting", it)
                            message = "Could not save process viewer setting."
                        }
                    }
                }
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Shell command hooks", style = MaterialTheme.typography.titleSmall)
                Text("Opt in to command-finished events and a notification for commands taking at least 10 seconds. Takes effect in new shells.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = notificationHooksEnabled, onCheckedChange = { enabled ->
                scope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        TerminalNotificationHooks.setEnabled(context.applicationContext, enabled)
                        settings.put("terminal_notification_hooks_v1", enabled.toString())
                    }
                    withContext(Dispatchers.Main) {
                        result.onSuccess {
                            notificationHooksEnabled = enabled
                            message = if (enabled) "Shell hooks enabled for installed distros. Restart shells to apply."
                            else "Shell hooks disabled. Restart shells to apply."
                        }.onFailure {
                            Log.w("TermXDoctor", "Could not update terminal notification hooks", it)
                            message = "Could not update shell hooks."
                        }
                    }
                }
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Terminal notifications", style = MaterialTheme.typography.titleSmall)
                Text("Bell, OSC notifications, and command exit status. Only delivered while TermX is in the background.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = terminalNotificationsEnabled, onCheckedChange = { enabled ->
                if (!enabled) saveTerminalNotifications(false)
                else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else saveTerminalNotifications(true)
            })
        }
        Text("Watched agent commands", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = watchedNames,
            onValueChange = { watchedNames = it.take(256) },
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Comma-separated command names. Only matching child processes in TermX sessions are watched.") },
            singleLine = true,
        )
        Button(onClick = {
            val names = watchedNames.split(',').map(String::trim).filter(String::isNotEmpty).distinct()
            if (names.isEmpty() || names.any { !it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }) {
                message = "Use command names with letters, numbers, dot, underscore, or dash."
            } else scope.launch(Dispatchers.IO) {
                val result = runCatching { settings.put("watched_agent_names_v1", names.joinToString(",")) }
                withContext(Dispatchers.Main) {
                    result.onSuccess {
                        requestServiceSettingsReload(context)
                        message = "Watched commands saved."
                    }.onFailure {
                        Log.w("TermXDoctor", "Could not save watched command names", it)
                        message = "Could not save watched commands."
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
            supportingText = { Text("One per line. Commands start or restart only when Watch is enabled for a session.") },
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
                        }.onFailure {
                            Log.w("TermXDoctor", "Could not save daemon commands", it)
                            message = "Could not save daemon commands."
                        }
                    }
                }
            }
        }) { Text("Save daemon registrations") }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Keep Wi-Fi awake", style = MaterialTheme.typography.titleSmall)
                Text("Optional; active only while a terminal session is alive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            runCatching { context.startActivity(action) }.onFailure {
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
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            )
            val opened = intents.firstNotNullOfOrNull { candidate ->
                runCatching { candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(candidate); true }.getOrNull()
            } ?: runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                true
            }.getOrDefault(false)
            message = if (opened) "If that page is unavailable, check TermX battery settings and enable autostart / No restrictions if available."
            else "Open Settings > Apps > TermX > Battery and check autostart / No restrictions if available."
        }) { Text("Xiaomi / HyperOS background settings") }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Text(
                "Phantom-process settings are read-only here. Their values are reported below. If you choose to change them, use adb and record the current values first: " +
                    "monitor=$phantomMonitor, limit=$phantomLimit. Android may restrict these settings on some devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Manual adb example (advanced): adb shell settings put global settings_enable_monitor_phantom_procs false; " +
                    "adb shell settings put global max_phantom_processes 2147483647. Restore only the values you recorded.",
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
        Button(enabled = entries.isNotEmpty(), onClick = {
            runCatching {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TermX Device Doctor", report))
            }.onFailure {
                Log.w("TermXDoctor", "Could not copy diagnostic report", it)
                message = "Could not copy the report."
            }.onSuccess { message = "Report copied." }
        }) { Text("Copy report") }
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
