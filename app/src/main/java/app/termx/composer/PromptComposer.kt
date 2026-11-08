package app.termx.composer

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.termx.customization.TermXSettingsStore
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val HISTORY_KEY = "prompt_composer_history_v1"
private const val SNIPPETS_KEY = "prompt_composer_snippets_v1"
private const val TAG = "TermXComposer"

private data class ComposerSnippet(val name: String, val template: String)

@Composable
fun PromptComposer(
    sessionId: String,
    session: TerminalSession,
    settings: TermXSettingsStore,
) {
    val scope = remember(sessionId) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    DisposableEffect(scope) { onDispose { scope.cancel() } }
    var expanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var draft by rememberSaveable(sessionId) { mutableStateOf("") }
    var loaded by rememberSaveable(sessionId) { mutableStateOf(false) }
    var busy by remember(sessionId) { mutableStateOf(false) }
    var message by remember(sessionId) { mutableStateOf<String?>(null) }
    var history by remember(sessionId) { mutableStateOf<List<String>>(emptyList()) }
    var snippets by remember(sessionId) { mutableStateOf<List<ComposerSnippet>>(emptyList()) }
    var showHistory by remember(sessionId) { mutableStateOf(false) }
    var showSnippets by remember(sessionId) { mutableStateOf(false) }
    var editSnippet by remember(sessionId) { mutableStateOf(false) }
    var snippetName by remember(sessionId) { mutableStateOf("") }
    var snippetTemplate by remember(sessionId) { mutableStateOf("") }
    var activeSnippet by remember(sessionId) { mutableStateOf<ComposerSnippet?>(null) }
    val placeholderValues = remember(sessionId) { mutableStateMapOf<String, String>() }

    LaunchedEffect(sessionId) {
        try {
            val storedDraft = withContext(Dispatchers.IO) { settings.get(draftKey(sessionId), "") }
            draft = storedDraft.take(MAX_DRAFT_CHARS)
            history = withContext(Dispatchers.IO) { loadHistory(settings) }
            snippets = withContext(Dispatchers.IO) { loadSnippets(settings) }
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not load composer data", failure)
            message = "Could not load saved composer data."
            loaded = true
        }
    }

    LaunchedEffect(sessionId, draft, loaded) {
        if (!loaded) return@LaunchedEffect
        delay(300)
        try {
            withContext(Dispatchers.IO) { settings.put(draftKey(sessionId), draft.take(MAX_DRAFT_CHARS)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Could not save composer draft", failure)
            message = "Draft could not be saved."
        }
    }

    fun send(submit: Boolean) {
        if (busy || draft.isBlank()) return
        val sentText = draft
        busy = true
        scope.launch {
            try {
                val success = withContext(Dispatchers.IO) { TextSender.send(session, sentText, submit) }
                if (!success) {
                    message = "Terminal input failed; the draft is still here."
                } else {
                    val updated = withContext(Dispatchers.IO) { addHistory(settings, sentText) }
                    history = updated
                    if (submit) draft = ""
                    message = if (submit) "Sent." else "Inserted without Enter."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "Could not send composer text", failure)
                message = "Terminal input failed; the draft is still here."
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text(if (expanded) "Hide prompt composer" else "Open prompt composer")
        }
        if (expanded) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_DRAFT_CHARS) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
                    label = { Text("Prompt or command") },
                    supportingText = { Text("Voice typing works here. Insert never presses Enter automatically.") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && draft.isNotBlank(), onClick = { send(false) }) { Text("Insert") }
                    Button(enabled = !busy && draft.isNotBlank(), onClick = { send(true) }) { Text("Send") }
                    TextButton(onClick = { showHistory = true }) { Text("History") }
                    TextButton(onClick = { showSnippets = true }) { Text("Snippets") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false },
        title = { Text("Composer history") },
        text = {
            Column {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { settings.put(HISTORY_KEY, "[]") }
                            history = emptyList()
                            message = "Composer history cleared."
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            Log.w(TAG, "Could not clear composer history", failure)
                            message = "Could not clear history."
                        }
                    }
                }) { Text("Clear history") }
                if (history.isEmpty()) Text("No composer history yet.")
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(history) { item ->
                        TextButton(onClick = { draft = item; showHistory = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(item, maxLines = 2)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Done") } },
    )

    if (showSnippets) AlertDialog(
        onDismissRequest = { showSnippets = false },
        title = { Text("Prompt snippets") },
        text = {
            Column {
                TextButton(onClick = { snippetTemplate = draft; editSnippet = true }) { Text("Save draft as snippet") }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(snippets, key = { it.name }) { snippet ->
                        TextButton(onClick = {
                            val names = SnippetTemplates.placeholders(snippet.template)
                            if (names.isEmpty()) {
                                draft = snippet.template
                                showSnippets = false
                            } else {
                                placeholderValues.clear()
                                names.forEach { placeholderValues[it] = "" }
                                activeSnippet = snippet
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Text(snippet.name)
                                Text(snippet.template, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSnippets = false }) { Text("Done") } },
    )

    if (editSnippet) AlertDialog(
        onDismissRequest = { editSnippet = false },
        title = { Text("Save snippet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(snippetName, { snippetName = it.take(64) }, label = { Text("Name") })
                OutlinedTextField(snippetTemplate, { snippetTemplate = it.take(MAX_SNIPPET_CHARS) }, label = { Text("Template; use {{name}} placeholders") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = {
            val name = snippetName.trim()
            if (name.isNotEmpty() && snippetTemplate.isNotBlank()) scope.launch {
                val updated = (snippets.filterNot { it.name == name } + ComposerSnippet(name, snippetTemplate))
                    .takeLast(MAX_SNIPPETS)
                try {
                    withContext(Dispatchers.IO) { saveSnippets(settings, updated) }
                    snippets = updated
                    editSnippet = false
                    snippetName = ""
                    snippetTemplate = ""
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Log.w(TAG, "Could not save prompt snippet", failure)
                    message = "Could not save snippet."
                }
            }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editSnippet = false }) { Text("Cancel") } },
    )

    activeSnippet?.let { snippet ->
        AlertDialog(
            onDismissRequest = { activeSnippet = null },
            title = { Text("Fill ${snippet.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    placeholderValues.forEach { (name, value) ->
                        OutlinedTextField(value, { placeholderValues[name] = it.take(1024) }, label = { Text(name) })
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                draft = SnippetTemplates.fill(snippet.template, placeholderValues)
                activeSnippet = null
                showSnippets = false
            }) { Text("Insert") } },
            dismissButton = { TextButton(onClick = { activeSnippet = null }) { Text("Cancel") } },
        )
    }
}

private const val MAX_DRAFT_CHARS = 32_768
private const val MAX_SNIPPET_CHARS = 4096
private const val MAX_SNIPPETS = 50

private fun draftKey(sessionId: String): String =
    "prompt_composer_draft_${sessionId.filter { it.isLetterOrDigit() || it in "_.-" }.take(96)}"

private suspend fun loadHistory(settings: TermXSettingsStore): List<String> = try {
    val json = JSONArray(settings.get(HISTORY_KEY, "[]"))
    (0 until minOf(json.length(), ComposerHistory.MAX_ENTRIES)).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Log.w(TAG, "Could not read composer history", failure)
    emptyList()
}

private suspend fun addHistory(settings: TermXSettingsStore, command: String): List<String> {
    val result = ComposerHistory.append(loadHistory(settings), command)
    settings.put(HISTORY_KEY, JSONArray(result).toString())
    return result
}

private suspend fun loadSnippets(settings: TermXSettingsStore): List<ComposerSnippet> = try {
    val json = JSONArray(settings.get(SNIPPETS_KEY, "[]"))
    (0 until minOf(json.length(), MAX_SNIPPETS)).mapNotNull { index ->
        json.optJSONObject(index)?.let { ComposerSnippet(it.optString("name"), it.optString("template")) }
            ?.takeIf { it.name.isNotBlank() && it.template.isNotBlank() }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Log.w(TAG, "Could not read prompt snippets", failure)
    emptyList()
}

private suspend fun saveSnippets(settings: TermXSettingsStore, snippets: List<ComposerSnippet>) {
    val json = JSONArray().apply {
        snippets.takeLast(MAX_SNIPPETS).forEach { put(JSONObject().put("name", it.name).put("template", it.template)) }
    }
    settings.put(SNIPPETS_KEY, json.toString())
}
