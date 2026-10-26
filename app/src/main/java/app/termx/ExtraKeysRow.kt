package app.termx

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.TerminalSession

/** Extra terminal keys adapted from wolfi-terminal's VirtualKeysListener. */
@Composable
fun ExtraKeysRow(session: TerminalSession, backend: TerminalBackend, onStorageClick: () -> Unit = {}) {
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
            Button(
                modifier = Modifier.padding(horizontal = 2.dp).height(40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Color(0xFF546E7A) else Color(0xFF263238),
                    contentColor = Color.White,
                ),
                onClick = {
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
            ) {
                Text(label, fontSize = 12.sp)
            }
        }
        TextButton(onClick = onStorageClick, modifier = Modifier.height(40.dp)) {
            Text("STORAGE", fontSize = 11.sp)
        }
    }
}
