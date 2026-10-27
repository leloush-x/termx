package app.termx

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalSession
import app.termx.customization.KeyLayoutValidator
import app.termx.terminal.input.ModKey
import app.termx.terminal.input.ModMode
import app.termx.terminal.input.ModifierKeys

/** Extra terminal keys adapted from wolfi-terminal's VirtualKeysListener. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ExtraKeysRow(
    session: TerminalSession,
    backend: TerminalBackend,
    keyLayoutJson: String? = null,
    accent: Color = Color(0xFF83C7FF),
) {
    val mods = backend.modifiers
    val defaultKeys = listOf(
        KeyLayoutValidator.KeyButton("ESC", "\u001B", null, emptyList()),
        KeyLayoutValidator.KeyButton("TAB", "\t", null, emptyList()),
        KeyLayoutValidator.KeyButton("CTRL", null, "ctrl", emptyList()),
        KeyLayoutValidator.KeyButton("ALT", null, "alt", emptyList()),
        KeyLayoutValidator.KeyButton("SHIFT", null, "shift", emptyList()),
        KeyLayoutValidator.KeyButton("/", "/", null, emptyList()),
        KeyLayoutValidator.KeyButton("↑", "\u001B[A", null, emptyList()),
        KeyLayoutValidator.KeyButton("←", "\u001B[D", null, emptyList()),
        KeyLayoutValidator.KeyButton("↓", "\u001B[B", null, emptyList()),
        KeyLayoutValidator.KeyButton("→", "\u001B[C", null, emptyList()),
    )
    val keys = remember(keyLayoutJson) {
        keyLayoutJson?.let { runCatching { KeyLayoutValidator.baseKeys(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            ?: defaultKeys
    }

    key(backend.modifierRevision.intValue) { Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        keys.forEach { key ->
            val label = key.label
            val mod = ModKey.fromLayout(key.modifier)
            val mode = mod?.let(mods::mode) ?: ModMode.OFF
            val active = mode != ModMode.OFF
            Box(
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
                    .sizeIn(minWidth = 38.dp, minHeight = 34.dp)
                    .background(if (mode == ModMode.LOCKED) accent.copy(alpha = .42f) else if (active) accent.copy(alpha = .24f) else Color.White.copy(alpha = .075f), CircleShape)
                    .combinedClickable(onClick = {
                        if (mod != null) mods.tap(mod) else key.send?.let { send ->
                            session.write(ModifierKeys.apply(send, mods.isActive(ModKey.CTRL), mods.isActive(ModKey.ALT), mods.isActive(ModKey.SHIFT)))
                            mods.consumeOneShots()
                        }
                    }, onLongClick = { if (mod != null) mods.longPress(mod) else key.long.firstOrNull()?.let(session::write) }),
                contentAlignment = Alignment.Center,
            ) { Text(if (mode == ModMode.LOCKED) "$label 🔒" else label, Modifier.padding(horizontal = 9.dp), color = if (active) accent else Color.White.copy(alpha = 0.78f), fontSize = 11.sp) }
        }
    } }
}
