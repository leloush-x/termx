package app.termx.terminal.input

enum class ModKey { CTRL, ALT, SHIFT;
    companion object { fun fromLayout(name: String?): ModKey? = when (name) { "ctrl" -> CTRL; "alt" -> ALT; "shift" -> SHIFT; else -> null } }
}
enum class ModMode { OFF, ONE_SHOT, LOCKED }

class ModifierState(private val onChanged: () -> Unit = {}) {
    private val modes = ModKey.entries.associateWith { ModMode.OFF }.toMutableMap()
    fun mode(key: ModKey): ModMode = modes.getValue(key)
    fun isActive(key: ModKey): Boolean = mode(key) != ModMode.OFF
    fun tap(key: ModKey) = set(key, if (mode(key) == ModMode.OFF) ModMode.ONE_SHOT else ModMode.OFF)
    fun longPress(key: ModKey) = set(key, ModMode.LOCKED)
    fun consumeOneShots() { var changed = false; ModKey.entries.forEach { if (mode(it) == ModMode.ONE_SHOT) { modes[it] = ModMode.OFF; changed = true } }; if (changed) onChanged() }
    fun clear() { var changed = false; ModKey.entries.forEach { if (mode(it) != ModMode.OFF) { modes[it] = ModMode.OFF; changed = true } }; if (changed) onChanged() }
    private fun set(key: ModKey, mode: ModMode) { if (this.mode(key) != mode) { modes[key] = mode; onChanged() } }
}
