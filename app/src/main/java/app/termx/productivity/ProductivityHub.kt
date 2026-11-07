package app.termx.productivity

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.termx.R
import app.termx.storage.StorageWorkspace
import java.io.File

enum class ProductivityPage { PALETTE, HISTORY, FILES, SNIPPETS, PROFILES }

@Composable
fun ProductivityHub(
    page: ProductivityPage,
    sharedDirectory: File,
    workspaces: List<StorageWorkspace>,
    history: List<HistoryEntry>,
    profiles: List<TermXProfile>,
    selectedProfile: TermXProfile,
    historyQuery: String,
    redactionText: String,
    message: String?,
    onHistoryQuery: (String) -> Unit,
    onSearchHistory: () -> Unit,
    onClearHistory: () -> Unit,
    onRedactionText: (String) -> Unit,
    onSaveRedactions: () -> Unit,
    onBack: () -> Unit,
    onNavigate: (ProductivityPage) -> Unit,
    onOpenStorage: () -> Unit,
    onInsertText: (String) -> Unit,
    onShareFile: (File) -> Unit,
    onCreateProfile: (String) -> Unit,
    onSelectProfile: (String) -> Unit,
    onUpdateProfile: (TermXProfile) -> Unit,
    onRemoveProfile: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TermX", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onBack) { Text(stringResource(R.string.hub_back)) }
        }
        when (page) {
            ProductivityPage.PALETTE -> {
                var filter by remember { mutableStateOf("") }
                Text(stringResource(R.string.hub_palette), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.hub_palette_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(filter, { filter = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.hub_find_action)) }, singleLine = true)
                val actions = listOf(
                    Triple("history", stringResource(R.string.hub_action_history), stringResource(R.string.hub_action_history_desc)) to { onNavigate(ProductivityPage.HISTORY) },
                    Triple("files", stringResource(R.string.hub_action_files), stringResource(R.string.hub_action_files_desc)) to { onNavigate(ProductivityPage.FILES) },
                    Triple("snippets", stringResource(R.string.hub_action_snippets), stringResource(R.string.hub_action_snippets_desc)) to { onNavigate(ProductivityPage.SNIPPETS) },
                    Triple("profiles", stringResource(R.string.hub_action_profiles), stringResource(R.string.hub_action_profiles_desc)) to { onNavigate(ProductivityPage.PROFILES) },
                    Triple("storage", stringResource(R.string.hub_action_storage), stringResource(R.string.hub_action_storage_desc)) to onOpenStorage,
                )
                actions.filter { (item, _) -> fuzzyMatches("${item.first} ${item.second} ${item.third}", filter) }
                    .forEach { (item, action) -> PaletteAction(item.first, item.second, item.third, action) }
            }
            ProductivityPage.HISTORY -> {
                Text(stringResource(R.string.hub_history_title), style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = historyQuery,
                    onValueChange = onHistoryQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.hub_search_commands)) },
                    singleLine = true,
                )
                Button(onClick = onSearchHistory) { Text(stringResource(R.string.hub_search)) }
                OutlinedTextField(
                    value = redactionText,
                    onValueChange = onRedactionText,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.hub_redaction_label)) },
                    minLines = 2,
                    maxLines = 5,
                )
                TextButton(onClick = onSaveRedactions) { Text(stringResource(R.string.hub_save_privacy)) }
                var confirmClear by remember { mutableStateOf(false) }
                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.hub_clear_history)) }
                if (confirmClear) AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    title = { Text(stringResource(R.string.hub_clear_history_title)) },
                    text = { Text(stringResource(R.string.hub_clear_history_text)) },
                    confirmButton = { TextButton(onClick = { confirmClear = false; onClearHistory() }) { Text(stringResource(R.string.hub_clear)) } },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.hub_cancel)) } },
                )
                if (history.isEmpty()) Text(stringResource(R.string.hub_history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(history, key = { it.id }) { entry ->
                        Card(Modifier.fillMaxWidth().clickable { onInsertText(entry.command) }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(entry.command, fontFamily = FontFamily.Monospace)
                                Text(stringResource(R.string.hub_history_line, entry.cwd, entry.exitCode), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            ProductivityPage.FILES -> FileBrowser(sharedDirectory, workspaces, onShareFile)
            ProductivityPage.SNIPPETS -> SnippetList(onInsertText)
            ProductivityPage.PROFILES -> ProfileEditor(
                profiles, selectedProfile, onCreateProfile, onSelectProfile, onUpdateProfile, onRemoveProfile, onOpenStorage,
            )
        }
        if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
    }
}

internal fun fuzzyMatches(candidate: String, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    val haystack = candidate.lowercase()
    if (haystack.contains(needle)) return true
    var index = 0
    for (character in haystack) if (index < needle.length && character == needle[index]) index++
    return index == needle.length
}

@Composable
private fun ProfileEditor(
    profiles: List<TermXProfile>,
    selected: TermXProfile,
    onCreate: (String) -> Unit,
    onSelect: (String) -> Unit,
    onUpdate: (TermXProfile) -> Unit,
    onRemove: (String) -> Unit,
    onManageWorkspaces: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var name by remember(selected.id, selected.name) { mutableStateOf(selected.name) }
    var distro by remember(selected.id, selected.distroId) { mutableStateOf(selected.distroId) }
    var cwd by remember(selected.id, selected.workingDirectory) { mutableStateOf(selected.workingDirectory) }
    var environment by remember(selected.id, selected.environment) {
        mutableStateOf(selected.environment.entries.joinToString("\n") { (key, value) -> "$key=$value" })
    }
    var profileError by remember { mutableStateOf<String?>(null) }
    val envError = stringResource(R.string.hub_env_error)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.hub_profiles_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.hub_profiles_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        profiles.forEach { profile ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(profile.id) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(if (profile.id == selected.id) "✓ ${profile.name}" else profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("${profile.distroId} · ${profile.workingDirectory}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (profile.id != "default") TextButton(onClick = { onRemove(profile.id) }) { Text(stringResource(R.string.hub_delete)) }
                }
            }
        }
        OutlinedTextField(newName, { newName = it }, label = { Text(stringResource(R.string.hub_new_profile_name)) }, singleLine = true)
        Button(enabled = newName.isNotBlank(), onClick = { onCreate(newName); newName = "" }) { Text(stringResource(R.string.hub_create_profile)) }
        Text(stringResource(R.string.hub_edit_profile), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.hub_name)) }, singleLine = true)
        OutlinedTextField(distro, { distro = it.lowercase() }, label = { Text(stringResource(R.string.hub_distro_id)) }, singleLine = true)
        OutlinedTextField(cwd, { cwd = it }, label = { Text(stringResource(R.string.hub_guest_cwd)) }, singleLine = true)
        OutlinedTextField(
            environment, { environment = it }, label = { Text(stringResource(R.string.hub_env_label)) },
            minLines = 2, maxLines = 5,
        )
        Button(onClick = {
            val rows = environment.lineSequence().filter(String::isNotBlank).toList()
            if (rows.any { '=' !in it }) profileError = envError
            else {
                profileError = null
                val env = rows.associate { row -> row.substringBefore('=') to row.substringAfter('=') }
                onUpdate(selected.copy(name = name.trim(), distroId = distro.trim(), workingDirectory = cwd.trim(), environment = env))
            }
        }) {
            Text(stringResource(R.string.hub_save_profile))
        }
        profileError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = onManageWorkspaces) { Text(stringResource(R.string.hub_manage_workspaces)) }
    }
}

@Composable
private fun PaletteAction(id: String, title: String, detail: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SnippetList(onInsertText: (String) -> Unit) {
    val snippets = listOf(
        R.string.hub_snippet_find_files to "find . -type f -iname '{{pattern}}'",
        R.string.hub_snippet_git_status to "git status --short --branch",
        R.string.hub_snippet_python_server to "python3 -m http.server {{port}}",
    )
    var selected by remember { mutableStateOf<Pair<Int, String>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.hub_snippets_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.hub_snippets_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        snippets.forEach { (name, template) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(name), style = MaterialTheme.typography.titleMedium)
                    Text(template, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onInsertText(template) }) { Text(stringResource(R.string.hub_insert)) }
                        Button(onClick = { selected = name to template }) { Text(stringResource(R.string.hub_run_ellipsis)) }
                    }
                }
            }
        }
    }
    selected?.let { (name, command) ->
        val snippetTitle = stringResource(R.string.hub_run_named, stringResource(name))
        val variables = remember(name) { mutableStateMapOf<String, String>() }
        val keys = Regex("\\{\\{([a-zA-Z0-9_]+)}}").findAll(command).map { it.groupValues[1] }.distinct().toList()
        val resolved = Regex("\\{\\{([a-zA-Z0-9_]+)}}").replace(command) { variables[it.groupValues[1]].orEmpty() }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(snippetTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    keys.forEach { key ->
                        OutlinedTextField(
                            value = variables[key].orEmpty(),
                            onValueChange = { variables[key] = it },
                            label = { Text(key) },
                            singleLine = true,
                        )
                    }
                    Text(resolved, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { selected = null; onInsertText(resolved + "\n") }) { Text(stringResource(R.string.hub_run)) } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.hub_cancel)) } },
        )
    }
}

private data class BrowserDocument(val id: String, val name: String, val directory: Boolean)

@Composable
private fun FileBrowser(sharedDirectory: File, workspaces: List<StorageWorkspace>, onShareFile: (File) -> Unit) {
    val context = LocalContext.current
    var source by remember { mutableStateOf("shared") }
    var current by remember { mutableStateOf(sharedDirectory) }
    val entries = remember(current) { current.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }).orEmpty() }
    var selectedWorkspace by remember { mutableStateOf<StorageWorkspace?>(null) }
    var documentStack by remember(selectedWorkspace) {
        mutableStateOf(selectedWorkspace?.let { runCatching { listOf(DocumentsContract.getTreeDocumentId(it.uri)) }.getOrNull() }.orEmpty())
    }
    val documentId = documentStack.lastOrNull()
    val documents = remember(selectedWorkspace, documentId) {
        val workspace = selectedWorkspace ?: return@remember emptyList()
        queryDocuments(context, workspace.uri, documentId ?: runCatching { DocumentsContract.getTreeDocumentId(workspace.uri) }.getOrDefault(""))
    }
    val downloads = remember(source) { if (source == "downloads") queryDownloads(context) else emptyList() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.hub_files_title), style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("shared" to stringResource(R.string.hub_shared), "workspaces" to stringResource(R.string.hub_workspaces), "downloads" to stringResource(R.string.hub_downloads)).forEach { (key, label) ->
                androidx.compose.material3.FilterChip(selected = source == key, onClick = {
                    source = key
                    selectedWorkspace = null
                }, label = { Text(label) })
            }
        }
        when (source) {
            "shared" -> {
                Text(current.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current != sharedDirectory) OutlinedButton(onClick = { current = current.parentFile ?: sharedDirectory }) { Text(stringResource(R.string.hub_up)) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(entries, key = { it.absolutePath }) { file ->
                        Card(Modifier.fillMaxWidth().clickable {
                            if (file.isDirectory) current = file else onShareFile(file)
                        }) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${if (file.isDirectory) "📁" else "📄"} ${file.name}")
                                if (!file.isDirectory) Text("${file.length()} B", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            "workspaces" -> if (selectedWorkspace == null) {
                if (workspaces.isEmpty()) Text(stringResource(R.string.hub_add_workspace_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                workspaces.forEach { workspace ->
                    Card(Modifier.fillMaxWidth().clickable {
                        selectedWorkspace = workspace
                        documentStack = listOfNotNull(runCatching { DocumentsContract.getTreeDocumentId(workspace.uri) }.getOrNull())
                    }) { Text("📁 ${workspace.label}", Modifier.padding(12.dp)) }
                }
            } else {
                Text(selectedWorkspace?.label.orEmpty(), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    if (documentStack.size <= 1) selectedWorkspace = null
                    else documentStack = documentStack.dropLast(1)
                }) { Text(stringResource(R.string.hub_up)) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(documents, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth().clickable(enabled = item.directory) { documentStack = documentStack + item.id }) {
                            Text("${if (item.directory) "📁" else "📄"} ${item.name}", Modifier.padding(12.dp))
                        }
                    }
                }
            }
            else -> {
                if (Build.VERSION.SDK_INT < 29) Text(stringResource(R.string.hub_downloads_requires), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(downloads, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) { Text("📄 ${item.name}", Modifier.padding(12.dp)) }
                    }
                }
            }
        }
    }
}

private fun queryDocuments(context: Context, tree: Uri, parentId: String): List<BrowserDocument> = runCatching {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    context.contentResolver.query(children, arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    ), null, null, "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} COLLATE NOCASE")?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val name = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mime = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        buildList { while (cursor.moveToNext()) add(BrowserDocument(cursor.getString(id), cursor.getString(name),
            cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR)) }
    }.orEmpty()
}.getOrDefault(emptyList())

private fun queryDownloads(context: Context): List<BrowserDocument> = if (Build.VERSION.SDK_INT < 29) emptyList() else runCatching {
    context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME), null, null,
        "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
        val name = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
        buildList { while (cursor.moveToNext()) add(BrowserDocument(cursor.getLong(id).toString(), cursor.getString(name), false)) }
    }.orEmpty()
}.getOrDefault(emptyList())
