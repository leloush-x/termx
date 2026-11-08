package app.termx.process

import android.os.Process
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.termx.customization.TermXSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

private const val TAG = "TermXProcessPanel"

@Composable
fun ProcessPortPanel(roots: List<ProcessRoot>) {
    val context = LocalContext.current.applicationContext
    val sampler = remember(context) { ProcessSampler(context) }
    val settings = remember(context) { TermXSettingsStore(context) }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    var enabled by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<ProcessPortSnapshot?>(null) }
    var filter by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ProcessRow?>(null) }

    LaunchedEffect(settings) {
        try {
            enabled = withContext(Dispatchers.IO) { settings.get("process_viewer_enabled_v1", "true") == "true" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not load process viewer preference", failure)
        }
    }

    LaunchedEffect(enabled, expanded, roots) {
        if (!enabled || !expanded) return@LaunchedEffect
        while (true) {
            try {
                val lifecycleContext = currentCoroutineContext()
                snapshot = withContext(Dispatchers.IO) {
                    sampler.sample(roots, Process.myUid()) { lifecycleContext.ensureActive() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "Process and port scan failed", failure)
                message = "Some process information is unavailable."
            }
            delay(1_500)
        }
    }

    val filteredRows = snapshot?.processes.orEmpty().filter {
        filter.isBlank() || it.command.contains(filter, true) || it.sessionId.contains(filter, true) || it.pid.toString().contains(filter)
    }
    val rows = if (sortByName) filteredRows.sortedBy { it.command.lowercase() } else filteredRows
    val protectedPids = remember(roots) { roots.mapTo(mutableSetOf()) { it.pid }.apply { add(Process.myPid()) } }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (enabled) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Hide processes and ports" else "Processes and ports")
            }
        } else {
            Text("Process viewer is disabled in Device Doctor.", modifier = Modifier.padding(8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                snapshot?.let { current ->
                    Text(
                        "Session CPU ${current.processes.mapNotNull { it.cpuPercent }.sum().let { "%.1f".format(it) }}% · " +
                            "app RAM ${formatBytes(current.appRamBytes)} · available ${formatBytes(current.availableMemoryBytes)} · " +
                            "battery ${current.batteryPercent?.let { "$it%" } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text("CPU uses per-process jiffy deltas at ${current.clockTicks ?: "unknown"} ticks/s; no system-wide CPU counter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Text("Ports · ${current.portTier}", style = MaterialTheme.typography.labelMedium)
                    Text("No privileged process bridge is used; when /proc/net is blocked, the fallback probes common localhost ports without claiming a process owner.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    if (current.ports.isEmpty()) Text("No app-owned listening ports were found.", style = MaterialTheme.typography.bodySmall)
                    current.ports.take(64).forEach { port ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${port.protocol.uppercase()}:${port.localPort} · ${port.state} · pid ${port.ownerPid ?: "?"}",
                                style = MaterialTheme.typography.bodySmall)
                            val owner = rows.firstOrNull { it.pid == port.ownerPid && !it.isProtected }
                            if (owner != null) TextButton(onClick = { selected = owner }) { Text("Free") }
                        }
                    }
                } ?: Text("Scanning app-owned session processes…")
                OutlinedTextField(filter, { filter = it.take(96) }, modifier = Modifier.fillMaxWidth(), label = { Text("Filter processes") }, singleLine = true)
                TextButton(onClick = { sortByName = !sortByName }) {
                    Text(if (sortByName) "Sort: name" else "Sort: CPU")
                }
                LazyColumn(Modifier.heightIn(max = 230.dp)) {
                    items(rows, key = { "${it.pid}:${it.startTicks}" }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text("${row.command} · ${row.pid}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                Text("${row.sessionId} · ${row.state} · ${row.cpuPercent?.let { "%.1f%%".format(it) } ?: "—"} · ${formatBytes(row.residentBytes)}",
                                    maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!row.isProtected && row.pid !in protectedPids) TextButton(onClick = { selected = row }) { Text("Stop") }
                        }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Stop process ${row.pid}?") },
            text = { Text("TermX will send SIGTERM, wait up to one second, then recheck the UID and process start time before using SIGKILL. Only this app’s own UID can be signalled.") },
            confirmButton = { TextButton(onClick = {
                selected = null
                scope.launch {
                    try {
                        val lifecycleContext = kotlinx.coroutines.currentCoroutineContext()
                        message = withContext(Dispatchers.IO) {
                            SafeProcessTerminator.terminate(row.pid, row.startTicks, Process.myUid(), protectedPids) {
                                lifecycleContext.ensureActive()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Log.w(TAG, "Could not stop process", failure)
                        message = "Could not stop process."
                    }
                }
            }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } },
        )
    }
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return "%.1f MiB".format(mb)
}
