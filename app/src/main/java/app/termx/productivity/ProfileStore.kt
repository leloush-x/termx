package app.termx.productivity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TermXProfile(
    val id: String,
    val name: String,
    val distroId: String,
    val workingDirectory: String,
    val environment: Map<String, String> = emptyMap(),
)

/** Small app-private profile store. The selected profile supplies the distro and guest start directory. */
object ProfileStore {
    private const val PREFS = "profiles"
    private const val KEY_PROFILES = "items"
    private const val KEY_SELECTED = "selected"
    private val defaultProfile = TermXProfile("default", "Default", "wolfi", "/root")

    fun all(context: Context): List<TermXProfile> = runCatching {
        val json = JSONArray(prefs(context).getString(KEY_PROFILES, "[]"))
        (0 until json.length()).map { index -> json.getJSONObject(index).toProfile() }
    }.getOrDefault(emptyList()).ifEmpty { listOf(defaultProfile) }

    fun selected(context: Context): TermXProfile {
        val profiles = all(context)
        val id = prefs(context).getString(KEY_SELECTED, defaultProfile.id)
        return profiles.firstOrNull { it.id == id } ?: profiles.first()
    }

    fun select(context: Context, id: String) {
        if (all(context).any { it.id == id }) prefs(context).edit().putString(KEY_SELECTED, id).apply()
    }

    fun create(context: Context, name: String): TermXProfile {
        require(name.isNotBlank()) { "Profile name is required" }
        val profiles = all(context).toMutableList()
        val profile = TermXProfile(UUID.randomUUID().toString(), name.trim().take(48), "wolfi", "/root")
        profiles += profile
        persist(context, profiles)
        select(context, profile.id)
        return profile
    }

    fun update(context: Context, profile: TermXProfile) {
        validate(profile)
        persist(context, all(context).map { if (it.id == profile.id) profile else it })
    }

    internal fun validate(profile: TermXProfile) {
        require(profile.name.isNotBlank() && profile.name.length <= 48) { "Profile name must be 1–48 characters" }
        require(profile.workingDirectory.startsWith('/') && profile.workingDirectory.split('/').none { it == ".." }) {
            "Working directory must be an absolute guest path without parent traversal"
        }
        require(profile.workingDirectory.length <= 1024 && !profile.workingDirectory.contains('\u0000')) {
            "Working directory is too long or contains an invalid character"
        }
        require(profile.distroId in setOf("wolfi", "alpine", "debian", "void")) { "Choose a supported distro" }
        require(profile.environment.keys.all { it.matches(Regex("[A-Z_][A-Z0-9_]*")) }) { "Environment variable names must use A–Z, 0–9 and underscore" }
        require(profile.environment.keys.none { it == "PROOT_LOADER" || it == "PROOT_TMP_DIR" }) { "PROOT runtime variables are managed by TermX" }
        require(profile.environment.values.none { it.contains('\u0000') }) { "Environment values cannot contain a null character" }
    }

    fun remove(context: Context, id: String) {
        val selectedId = prefs(context).getString(KEY_SELECTED, defaultProfile.id)
        val remaining = all(context).filterNot { it.id == id }.ifEmpty { listOf(defaultProfile) }
        persist(context, remaining)
        if (selectedId == id) select(context, remaining.first().id)
    }

    private fun persist(context: Context, profiles: List<TermXProfile>) {
        val json = JSONArray().apply { profiles.forEach { put(JSONObject()
            .put("id", it.id).put("name", it.name).put("distroId", it.distroId).put("workingDirectory", it.workingDirectory)
            .put("environment", JSONObject(it.environment))) } }
        prefs(context).edit().putString(KEY_PROFILES, json.toString()).apply()
    }

    private fun JSONObject.toProfile() = TermXProfile(
        id = getString("id"), name = getString("name"), distroId = getString("distroId"),
        workingDirectory = optString("workingDirectory", "/root"),
        environment = optJSONObject("environment")?.let { objectValue ->
            objectValue.keys().asSequence().associateWith { objectValue.optString(it) }
        }.orEmpty(),
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
