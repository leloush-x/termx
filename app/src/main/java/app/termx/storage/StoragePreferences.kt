package app.termx.storage

import android.content.Context
import android.net.Uri

data class StorageWorkspace(val uri: Uri, val label: String)

object StoragePreferences {
    private const val PREFS = "storage_tiers"
    private const val SELECTED_TIER = "selected_tier"
    private const val WORKSPACES = "workspace_uris"

    fun selectedTier(context: Context): StorageTier = runCatching {
        StorageTier.valueOf(prefs(context).getString(SELECTED_TIER, StorageTier.T0.name)!!)
    }.getOrDefault(StorageTier.T0)

    fun selectTier(context: Context, tier: StorageTier) {
        prefs(context).edit().putString(SELECTED_TIER, tier.name).apply()
    }

    fun workspaceUris(context: Context): List<Uri> = prefs(context).getStringSet(WORKSPACES, emptySet())
        .orEmpty().mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    fun workspaces(context: Context): List<StorageWorkspace> = workspaceUris(context).map { uri ->
        StorageWorkspace(uri, WorkspaceTransfer.displayName(context, uri) ?: uri.lastPathSegment ?: "SAF workspace")
    }

    fun addWorkspace(context: Context, uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val existing = prefs(context).getStringSet(WORKSPACES, emptySet()).orEmpty().toMutableSet()
        existing += uri.toString()
        prefs(context).edit().putStringSet(WORKSPACES, existing).apply()
    }

    fun removeWorkspace(context: Context, uri: Uri) {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
        val existing = prefs(context).getStringSet(WORKSPACES, emptySet()).orEmpty().toMutableSet()
        existing -= uri.toString()
        prefs(context).edit().putStringSet(WORKSPACES, existing).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
