package app.termx

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.termx.runtime.RootfsPackage
import com.kyant.backdrop.Backdrop

@Composable
internal fun BoxScope.TerminalSidePanel(
    backdrop: Backdrop,
    distroName: String,
    distroId: String,
    workingDirectory: String,
    accent: Color,
    installedDistros: List<RootfsPackage>,
    onTerminal: () -> Unit,
    onNewTerminal: (RootfsPackage) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val canAnimate = remember { ValueAnimator.areAnimatorsEnabled() }
    var showingDistroChoices by remember { mutableStateOf(false) }
    val panelShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
    val newTerminalEnter = if (canAnimate) fadeIn(tween(160)) + expandVertically(tween(220)) else EnterTransition.None
    val newTerminalExit = if (canAnimate) fadeOut(tween(100)) + shrinkVertically(tween(170)) else ExitTransition.None

    Box(
        Modifier.fillMaxWidth().fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClick = onClose),
    )

    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().widthIn(max = 320.dp).fillMaxWidth(0.88f)
            .termxLiquidGlass(backdrop, panelShape, Color(0xF0141718))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("termx", color = Color(0xFFF2F1ED), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "Close",
                Modifier.clip(RoundedCornerShape(9.dp)).clickable(role = Role.Button, onClick = onClose)
                    .defaultMinSize(minWidth = 52.dp, minHeight = 44.dp)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                color = Color(0xFFAAA9A4), style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text("CURRENT SESSION", color = Color(0xFFAAA9A4), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 54.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.045f))
                .clickable(role = Role.Button, onClick = onTerminal)
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(distroName, color = Color(0xFFF1F0EC), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(workingDirectory, color = Color(0xFFAAA9A4), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Text("›", color = Color.White.copy(alpha = 0.55f), fontSize = 21.sp)
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, enabled = installedDistros.isNotEmpty()) {
                    if (installedDistros.size == 1) onNewTerminal(installedDistros.single())
                    else showingDistroChoices = !showingDistroChoices
                }
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("+", color = accent, fontSize = 22.sp, fontWeight = FontWeight.Normal)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("New terminal", color = Color(0xFFF1F0EC), style = MaterialTheme.typography.titleSmall)
                Text(
                    when (installedDistros.size) {
                        0 -> "No installed distro available"
                        1 -> "Start ${installedDistros.single().distroName}"
                        else -> if (showingDistroChoices) "Choose a distro below" else "Choose an installed distro",
                    },
                    color = Color(0xFFAAA9A4), style = MaterialTheme.typography.bodySmall,
                )
            }
            if (installedDistros.size > 1) {
                Text(if (showingDistroChoices) "−" else "＋", color = Color.White.copy(alpha = 0.64f), fontSize = 17.sp)
            }
        }

        AnimatedVisibility(
            visible = showingDistroChoices && installedDistros.size > 1,
            enter = newTerminalEnter,
            exit = newTerminalExit,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 28.dp, top = 3.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                installedDistros.forEach { distro ->
                    val distroAccent = distroAccentColor(distro.distroId)
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .clickable(role = Role.Button) { onNewTerminal(distro) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(distro.distroName, Modifier.weight(1f), color = Color(0xFFE4E2DC),
                            style = MaterialTheme.typography.labelLarge)
                        if (distro.distroId == distroId) {
                            Text("current", color = distroAccent, style = MaterialTheme.typography.labelSmall)
                        } else {
                            Text(distro.version, color = Color(0xFF85847F), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 50.dp).clickable(role = Role.Button, onClick = onSettings)
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", color = Color(0xFFD0CEC8), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text("›", color = Color.White.copy(alpha = 0.46f), fontSize = 21.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("Linux sessions run locally.", color = Color(0xFF85847F), style = MaterialTheme.typography.bodySmall)
    }
}
