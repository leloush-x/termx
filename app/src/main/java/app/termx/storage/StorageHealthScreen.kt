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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.termx.R
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
            Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text(stringResource(R.string.storage_back)) }
        }
        Text(stringResource(R.string.storage_current_selection, selectedTier.toString()))
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
                            Text(if (selectedTier == StorageTier.T0) stringResource(R.string.storage_using_tier, "T0") else stringResource(R.string.storage_use_tier, "T0"))
                        }
                        StorageTier.T1 -> when (status.availability) {
                            TierAvailability.NEEDS_PERMISSION -> Button(onClick = onGrantT1) { Text(stringResource(R.string.storage_grant_access)) }
                            TierAvailability.ACTIVE -> OutlinedButton(onClick = { onSelectTier(StorageTier.T1) }) {
                                Text(if (selectedTier == StorageTier.T1) stringResource(R.string.storage_using_tier, "T1") else stringResource(R.string.storage_use_tier, "T1"))
                            }
                            else -> Unit
                        }
                        StorageTier.T2 -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onSelectTier(StorageTier.T2) }) {
                                Text(if (selectedTier == StorageTier.T2) stringResource(R.string.storage_using_tier, "T2") else stringResource(R.string.storage_use_tier, "T2"))
                            }
                            Button(onClick = onAddWorkspace) { Text(stringResource(R.string.storage_add_workspace)) }
                        }
                    }
                }
            }
        }

        Text(stringResource(R.string.storage_saf_workspaces), style = MaterialTheme.typography.titleMedium)
        if (workspaces.isEmpty()) Text(stringResource(R.string.storage_no_folders))
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
                        OutlinedButton(onClick = { onCopyWorkspaceIn(workspace) }) { Text(stringResource(R.string.storage_copy_in)) }
                        OutlinedButton(onClick = { onCopySharedOut(workspace) }) { Text(stringResource(R.string.storage_copy_out)) }
                        TextButton(onClick = { onRemoveWorkspace(workspace) }) { Text(stringResource(R.string.storage_forget)) }
                    }
                }
            }
        }

        Text(stringResource(R.string.storage_app_folder, stringResource(if (sharedFilesAvailable) R.string.storage_available else R.string.storage_unavailable)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImportFile) { Text(stringResource(R.string.storage_import_file)) }
            OutlinedButton(onClick = onExportDownloads) { Text(stringResource(R.string.storage_export_downloads)) }
        }

        Text(stringResource(R.string.storage_exact_binds), style = MaterialTheme.typography.titleMedium)
        if (sessionBinds == null) {
            Text(stringResource(R.string.storage_no_session))
        } else if (sessionBinds.isEmpty()) {
            Text(stringResource(R.string.storage_no_binds))
        } else {
            sessionBinds.forEach { bind ->
                Text("${bind.host}  →  ${bind.guest}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!message.isNullOrBlank()) Text(message, color = MaterialTheme.colorScheme.error)
    }
}
