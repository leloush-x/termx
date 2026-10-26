package app.termx

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalSession

/** Extra terminal keys adapted from wolfi-terminal's VirtualKeysListener. */
@Composable
fun ExtraKeysRow(session: TerminalSession, backend: TerminalBackend, accent: Color = Color(0xFF83C7FF)) {
    var ctrlEnabled by remember { mutableStateOf(false) }
    var altEnabled by remember { mutableStateOf(false) }
    val keys = listOf(
        "ESC" to "\u001B",
        "TAB" to "\u0009",
        "CTRL" to null,
        "ALT" to null,
        "↑" to "\u001B[A",
        "←" to "\u001B[D",
        "↓" to "\u001B[B",
        "→" to "\u001B[C",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        keys.forEach { (label, value) ->
            val active = (label == "CTRL" && ctrlEnabled) || (label == "ALT" && altEnabled)
            Box(
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
                    .sizeIn(minWidth = 38.dp, minHeight = 34.dp)
                    .background(if (active) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.075f), CircleShape)
                    .clickable {
                        when (label) {
                            "CTRL" -> {
                                ctrlEnabled = !ctrlEnabled
                                backend.controlPressed = ctrlEnabled
                            }
                            "ALT" -> {
                                altEnabled = !altEnabled
                                backend.altPressed = altEnabled
                            }
                            else -> value?.let(session::write)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text(label, Modifier.padding(horizontal = 9.dp), color = if (active) accent else Color.White.copy(alpha = 0.78f), fontSize = 11.sp) }
        }
    }
}
