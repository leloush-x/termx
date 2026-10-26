package app.termx.customization

import org.json.JSONArray
import org.json.JSONObject

data class TermXTheme(
    val name: String,
    val mode: String,
    val background: String,
    val foreground: String,
    val cursor: String,
    val selection: String,
    val bold: String,
    val ansi: List<String>,
    val backgroundImage: BackgroundImage? = null,
) {
    data class BackgroundImage(val uri: String?, val blur: Float, val dim: Float)

    fun toJson(): JSONObject = JSONObject().put("schema", 1).put("name", name).put("mode", mode)
        .put("colors", JSONObject().put("background", background).put("foreground", foreground)
            .put("cursor", cursor).put("selection", selection).put("bold", bold))
        .put("ansi", JSONArray(ansi))
        .put("background", JSONObject().put("image", backgroundImage?.uri ?: JSONObject.NULL)
            .put("blur", backgroundImage?.blur ?: 0f).put("dim", backgroundImage?.dim ?: 0f))

    companion object {
        fun parse(json: String): TermXTheme {
            val root = JSONObject(json)
            require(root.optInt("schema", -1) == 1) { "schema: expected 1" }
            val name = root.optString("name").trim()
            require(name.isNotEmpty() && name.length <= 48) { "name: expected 1–48 characters" }
            val mode = root.optString("mode")
            require(mode in setOf("dark", "light", "system")) { "mode: expected dark, light or system" }
            val colors = root.optJSONObject("colors") ?: error("colors: required object")
            fun color(field: String): String = colors.optString(field).also {
                require(it.matches(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?"))) { "colors.$field: expected #RRGGBB or #AARRGGBB" }
            }
            val ansiJson = root.optJSONArray("ansi") ?: error("ansi: required array")
            require(ansiJson.length() == 16) { "ansi: expected exactly 16 colors" }
            val ansi = (0 until ansiJson.length()).map { index ->
                ansiJson.optString(index).also {
                    require(it.matches(Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?"))) {
                        "ansi[$index]: expected #RRGGBB or #AARRGGBB"
                    }
                }
            }
            val backgroundJson = root.optJSONObject("background") ?: error("background: required object")
            val imageJson = backgroundJson.opt("image")
            val uri = when (imageJson) {
                null, JSONObject.NULL -> null
                is String -> imageJson.also {
                    require(it.startsWith("content://") || it.startsWith("file://")) {
                        "background.image: expected a local content or file URI"
                    }
                }
                else -> error("background.image: expected null or local URI")
            }
            val blur = backgroundJson.optDouble("blur", -1.0).toFloat()
            val dim = backgroundJson.optDouble("dim", -1.0).toFloat()
            require(blur in 0f..32f) { "background.blur: expected 0–32" }
            require(dim in 0f..1f) { "background.dim: expected 0–1" }
            val image = BackgroundImage(uri, blur, dim)
            return TermXTheme(name, mode, color("background"), color("foreground"), color("cursor"),
                color("selection"), color("bold"), ansi, image)
        }
    }
}

/**
 * Terminal palette defaults. The terminal always gets a concrete theme so AMOLED mode can switch
 * the shell between true #000000 (pixels off on OLED) and a soft near-black for standard dark mode.
 */
private fun terminalThemeNamed(name: String, background: String): TermXTheme = TermXTheme(
    name = name,
    mode = "dark",
    background = background,
    foreground = "#E6E6E6",
    cursor = "#9CE6D8",
    selection = "#2A3A38",
    bold = "#9CE6D8",
    ansi = listOf(
        "#000000", "#E06C75", "#98C379", "#E5C07B",
        "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF",
        "#5C6370", "#E06C75", "#98C379", "#E5C07B",
        "#61AFEF", "#C678DD", "#56B6C2", "#FFFFFF",
    ),
)

/** True-black (AMOLED) terminal: pure #000000 so OLED pixels switch fully off. */
fun defaultAmoledTheme(): TermXTheme = terminalThemeNamed("AMOLED", "#000000")

/** Standard dark terminal: a soft near-black surface, used when AMOLED mode is off. */
fun defaultDarkTheme(): TermXTheme = terminalThemeNamed("Dark", "#0D0F10")

/** Layout schema follows ARCHITECTURE §20.4 and rejects unknown control sequences by key name. */
object KeyLayoutValidator {
    data class KeyButton(val label: String, val send: String?, val modifier: String?, val long: List<String>)
    private val allowedControlSequences = setOf("\u001B", "\t", "\n", "\r", "\u001B[A", "\u001B[B", "\u001B[C", "\u001B[D", "\u001B[H", "\u001B[F", "\u001B[5~", "\u001B[6~")

    fun validate(json: String): JSONObject {
        val root = JSONObject(json)
        require(root.optInt("schema", -1) == 1) { "schema: expected 1" }
        val layers = root.optJSONObject("layers") ?: error("layers: required object")
        require(layers.has("base") && layers.has("more")) { "layers: require base and more layers" }
        val names = layers.keys().asSequence().toList()
        require(names.all { it in setOf("base", "more", "symbols") }) { "layers: unknown layer" }
        var count = 0
        names.forEach { layerName ->
            val rows = layers.optJSONArray(layerName) ?: error("layers.$layerName: expected rows")
            for (rowIndex in 0 until rows.length()) {
                val row = rows.optJSONArray(rowIndex) ?: error("layers.$layerName[$rowIndex]: expected key array")
                for (keyIndex in 0 until row.length()) {
                    count++
                    validateKey(row.optJSONObject(keyIndex) ?: error("layers.$layerName[$rowIndex][$keyIndex]: expected object"),
                        "layers.$layerName[$rowIndex][$keyIndex]")
                }
            }
        }
        require(count in 1..64) { "layers: expected 1–64 keys" }
        return root
    }

    fun baseKeys(json: String): List<KeyButton> = validate(json).getJSONObject("layers").getJSONArray("base").let { rows ->
        buildList {
            for (r in 0 until rows.length()) {
                val row = rows.getJSONArray(r)
                for (k in 0 until row.length()) {
                    val item = row.getJSONObject(k)
                    add(KeyButton(item.getString("label"), item.optString("send").takeIf { item.has("send") },
                        item.optString("modifier").takeIf { item.has("modifier") },
                        item.optJSONArray("long")?.let { long -> (0 until long.length()).map(long::getString) }.orEmpty()))
                }
            }
        }
    }

    private fun validateKey(key: JSONObject, path: String) {
        val label = key.optString("label").trim()
        require(label.isNotEmpty() && label.length <= 12) { "$path.label: expected 1–12 characters" }
        val hasSend = key.has("send")
        val hasModifier = key.has("modifier")
        require(hasSend.xor(hasModifier)) { "$path: provide exactly one of send or modifier" }
        if (hasSend) {
            val send = key.optString("send")
            require(send.length <= 32 && !send.contains('\u0000')) { "$path.send: invalid sequence" }
            require(if (send.any { it.code < 0x20 || it.code == 0x7f }) send in allowedControlSequences
                else send.isNotEmpty() && send.length <= 16 && send.all { it.code >= 0x20 && it.code != 0x7f }) {
                "$path.send: unsupported control sequence for '$label'"
            }
        } else {
            val modifier = key.optString("modifier").lowercase()
            require(modifier in setOf("ctrl", "alt", "shift")) { "$path.modifier: unsupported modifier" }
        }
        val long = key.optJSONArray("long")
        require(long == null || long.length() <= 6) { "$path.long: at most 6 choices" }
    }
}

/** Versioned key bindings for app actions such as palette.open and session.new. */
object KeymapValidator {
    fun validate(json: String): JSONObject {
        val root = JSONObject(json)
        require(root.optInt("schema", -1) == 1) { "schema: expected 1" }
        val bindings = root.optJSONObject("bindings") ?: error("bindings: required object")
        require(bindings.length() in 1..128) { "bindings: expected 1–128 actions" }
        val supportedKeys = setOf("CTRL", "ALT", "SHIFT", "META", "ENTER", "ESC", "TAB", "SPACE", "BACKSPACE")
        val names = bindings.keys()
        while (names.hasNext()) {
            val action = names.next()
            require(action.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) { "bindings.$action: invalid action id" }
            val combo = bindings.optJSONArray(action) ?: error("bindings.$action: expected key array")
            require(combo.length() in 1..4) { "bindings.$action: expected 1–4 keys" }
            val keys = (0 until combo.length()).map { combo.optString(it).uppercase() }
            require(keys.distinct().size == keys.size) { "bindings.$action: duplicate key" }
            keys.forEachIndexed { index, key ->
                require(key in supportedKeys || key.matches(Regex("[A-Z0-9]")) || key.matches(Regex("F([1-9]|1[0-2])"))) {
                    "bindings.$action[$index]: unsupported key '$key'"
                }
            }
        }
        return root
    }
}

object SettingsExport {
    private val secretNames = setOf("password", "token", "secret", "privatekey", "api_key", "apikey", "credential")

    fun settingsJson(values: Map<String, String>): JSONObject = JSONObject().put("schema", 1).put("settings", JSONObject().apply {
        values.forEach { (key, raw) ->
            if (secretNames.none { key.replace(Regex("[^A-Za-z]"), "").contains(it, ignoreCase = true) }) {
                val safe = when {
                    raw.trimStart().startsWith("{") -> runCatching { withoutSecrets(JSONObject(raw)) }.getOrNull()?.toString() ?: raw
                    raw.trimStart().startsWith("[") -> runCatching {
                        withoutSecrets(JSONObject().put("items", JSONArray(raw))).getJSONArray("items").toString()
                    }.getOrNull() ?: raw
                    else -> raw
                }
                put(key, safe)
            }
        }
    })

    fun withoutSecrets(settings: JSONObject): JSONObject = sanitize(settings) as JSONObject

    private fun sanitize(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { target ->
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (secretNames.none { key.replace(Regex("[^A-Za-z]"), "").contains(it, ignoreCase = true) }) {
                    target.put(key, sanitize(value.opt(key)))
                }
            }
        }
        is JSONArray -> JSONArray().also { target ->
            for (i in 0 until value.length()) target.put(sanitize(value.opt(i)))
        }
        else -> value
    } ?: JSONObject.NULL
}
