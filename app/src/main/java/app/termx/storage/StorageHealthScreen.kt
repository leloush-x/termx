package app.termx.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.termx.runtime.Bind

@Composable
fun StorageHealthScreen(
    statuses: List<StorageTierStatus>,
    selectedTier: StorageTier,
    workspaces: List<StorageWorkspace>,
    sessionBinds: List<Bind>?,
    sharedFilesAvailable: Boolean,
    message: String?,
    onBack: () -> Unit,
    onSelectTier: (StorageTier) -> Unit,
    onGrantT1: () -> Unit,
    onAddWorkspace: () -> Unit,
    onRemoveWorkspace: (StorageWorkspace) -> Unit,
    onImportFile: () -> Unit,
    onCopyWorkspaceIn: (StorageWorkspace) -> Unit,
    onCopySharedOut: (StorageWorkspace) -> Unit,
    onExportDownloads: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Storage Health", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Current selection: $selectedTier. Changes apply to the next terminal session.")
        statuses.forEach { status ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("${status.tier} · ${status.availability}", style = MaterialTheme.typography.titleMedium)
                    Text(status.detail, style = MaterialTheme.typography.bodyMedium)
                    when (status.tier) {
                        StorageTier.T0 -> OutlinedButton(onClick = { onSelectTier(StorageTier.T0) }) {
                            Text(if (selectedTier == StorageTier.T0) "Using T0" else "Use T0")
                        }
                        StorageTier.T1 -> when (status.availability) {
                            TierAvailability.NEEDS_PERMISSION -> Button(onClick = onGrantT1) { Text("Grant access") }
                            TierAvailability.ACTIVE -> OutlinedButton(onClick = { onSelectTier(StorageTier.T1) }) {
                                Text(if (selectedTier == StorageTier.T1) "Using T1" else "Use T1")
                            }
                            else -> Unit
                        }
                        StorageTier.T2 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onSelectTier(StorageTier.T2) }) {
                                Text(if (selectedTier == StorageTier.T2) "Using T2" else "Use T2")
                            }
                            Button(onClick = onAddWorkspace) { Text("Add workspace") }
                        }
                    }
                }
            }
        }

        Text("SAF workspaces", style = MaterialTheme.typography.titleMedium)
        if (workspaces.isEmpty()) Text("No folders selected. Add a workspace to copy files in or out.")
        workspaces.forEach { workspace ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(workspace.label, style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { onCopyWorkspaceIn(workspace) }) { Text("Copy into shared") }
                        OutlinedButton(onClick = { onCopySharedOut(workspace) }) { Text("Copy shared files out") }
                        TextButton(onClick = { onRemoveWorkspace(workspace) }) { Text("Forget") }
                    }
                }
            }
        }

        Text("App shared folder: ${if (sharedFilesAvailable) "available" else "unavailable"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImportFile) { Text("Import a file") }
            OutlinedButton(onClick = onExportDownloads) { Text("Export to Downloads") }
        }

        Text("Exact binds for the current session", style = MaterialTheme.typography.titleMedium)
        if (sessionBinds == null) {
            Text("No session is running. The selected tier's binds will appear here after a session starts.")
        } else if (sessionBinds.isEmpty()) {
            Text("This session has no host binds.")
        } else {
            sessionBinds.forEach { bind ->
                Text("${bind.host}  →  ${bind.guest}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!message.isNullOrBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    }
}
