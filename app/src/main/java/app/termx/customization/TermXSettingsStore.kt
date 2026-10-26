package app.termx.customization

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.termxSettings by preferencesDataStore(name = "termx-settings")
private val Context.termxSecrets by preferencesDataStore(name = "termx-secrets")

/** Preferences use DataStore; secret ciphertext is isolated so backup rules can exclude it. */
class TermXSettingsStore(private val context: Context) {
    suspend fun get(name: String, default: String = ""): String =
        context.termxSettings.data.first()[stringPreferencesKey(name)] ?: default

    suspend fun put(name: String, value: String) {
        require(name !in SECRET_KEYS) { "Secret values must use SecretStore" }
        context.termxSettings.edit { it[stringPreferencesKey(name)] = value }
    }

    suspend fun export(): JSONObject {
        val entries = context.termxSettings.data.first()
        return SettingsExport.settingsJson(entries.asMap().mapKeys { it.key.name }.mapValues { it.value.toString() })
    }

    suspend fun import(json: String) {
        val root = JSONObject(json)
        require(root.optInt("schema", -1) == 1) { "schema: expected 1" }
        val settings = root.optJSONObject("settings") ?: error("settings: required object")
        val values = settings.keys().asSequence().associateWith { key -> settings.get(key).toString() }
        require(values.keys.none { key -> key in SECRET_KEYS ||
            key.contains("secret", true) || key.contains("password", true) || key.contains("token", true) }) {
            "settings: secret fields cannot be imported"
        }
        context.termxSettings.edit { prefs ->
            prefs.clear()
            values.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        }
    }

    private companion object {
        val SECRET_KEYS = setOf("password", "token", "secret", "api_key", "apikey")
    }
}

class SecretStore(private val context: Context) {
    suspend fun put(name: String, cleartext: ByteArray) {
        require(name.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "name: invalid secret identifier" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(cleartext)
        val packed = cipher.iv + encrypted
        context.termxSecrets.edit { it[stringPreferencesKey(name)] = android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP) }
    }

    suspend fun get(name: String): ByteArray? {
        val encoded = context.termxSecrets.data.first()[stringPreferencesKey(name)] ?: return null
        val packed = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        require(packed.size > 12) { "secret: invalid encrypted value" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return cipher.doFinal(packed.copyOfRange(12, packed.size))
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    private companion object { const val KEY_ALIAS = "termx_secret_store_v1" }
}
