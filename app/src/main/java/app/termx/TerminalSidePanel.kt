package app.termx

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.termx.runtime.RootfsPackage
import com.kyant.backdrop.Backdrop

/** A single running session, listed inside its distro group. */
internal data class PanelSession(val id: String, val subtitle: String, val isCurrent: Boolean)

/** All running sessions of one distro, grouped together in the side panel. */
internal data class PanelGroup(
    val distroId: String,
    val distroName: String,
    val accent: Color,
    val sessions: List<PanelSession>,
)

/**
 * Slim 248dp drawer listing every running session grouped by distro.
 * Distro groups are retractable: tap a group header to fold its sessions away.
 */
@Composable
internal fun BoxScope.TerminalSidePanel(
    backdrop: Backdrop,
    distroName: String,
    distroId: String,
    workingDirectory: String,
    accent: Color,
    groups: List<PanelGroup>,
    installedDistros: List<RootfsPackage>,
    onTerminal: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onNewTerminal: (RootfsPackage) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val canAnimate = remember { ValueAnimator.areAnimatorsEnabled() }
    var showingDistroChoices by remember { mutableStateOf(false) }
    // Groups fold open by default; tapping a header retracts just that distro.
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    val panelShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
    val newTerminalEnter = if (canAnimate) fadeIn(tween(160)) + expandVertically(tween(220)) else EnterTransition.None
    val newTerminalExit = if (canAnimate) fadeOut(tween(100)) + shrinkVertically(tween(170)) else ExitTransition.None
    val groupEnter = if (canAnimate) fadeIn(tween(140)) + expandVertically(tween(190)) else EnterTransition.None
    val groupExit = if (canAnimate) fadeOut(tween(90)) + shrinkVertically(tween(150)) else ExitTransition.None

    Box(
        Modifier.fillMaxWidth().fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(role = Role.Button, onClick = onClose),
    )

    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().width(248.dp)
            .termxLiquidGlass(backdrop, panelShape, Color(0xF0141718))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Header: brand + close, one compact 36dp row.
        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("termx", color = Color(0xFFF2F1ED), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "✕",
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClose)
                    .defaultMinSize(minWidth = 40.dp, minHeight = 36.dp)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                color = Color(0xFFAAA9A4), style = MaterialTheme.typography.labelMedium,
            )
        }

        // Slim one-line readout of where the terminal currently sits.
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.12f))
                .clickable(role = Role.Button, onClick = onTerminal)
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(distroName, color = Color(0xFFF1F0EC), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(workingDirectory, color = Color(0xFF85847F), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }

        // Sessions, one retractable block per distro.
        if (groups.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("SESSIONS", color = Color(0xFF85847F), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.1.sp)
        }
        groups.forEach { group ->
            val collapsed = group.distroId in collapsedGroups
            val chevron by animateFloatAsState(if (collapsed) 0f else 90f, tween(180), label = "groupChevron")
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) {
                        collapsedGroups = if (collapsed) collapsedGroups - group.distroId else collapsedGroups + group.distroId
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(group.accent, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(group.distroName, Modifier.weight(1f), color = Color(0xFFF1F0EC),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("${group.sessions.size}", color = Color(0xFF85847F), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Text("›", Modifier.rotate(chevron), color = Color.White.copy(alpha = 0.45f), fontSize = 15.sp)
            }
            AnimatedVisibility(visible = !collapsed, enter = groupEnter, exit = groupExit) {
                Column {
                    group.sessions.forEachIndexed { index, session ->
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = 38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (session.isCurrent) group.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable(role = Role.Button) { onSelectSession(session.id) }
                                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(6.dp).background(
                                    if (session.isCurrent) group.accent else Color.White.copy(alpha = 0.30f), CircleShape,
                                ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("#${index + 1}", color = if (session.isCurrent) Color.White else Color(0xFFD8D6D0),
                                    style = MaterialTheme.typography.labelMedium)
                                Text(session.subtitle, color = Color(0xFF85847F),
                                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            if (session.isCurrent) {
                                Text("live", color = group.accent, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "×",
                                Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { onDeleteSession(session.id) }
                                    .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                color = Color(0xFF85847F), style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }

        // New terminal, compact: one row that expands into a distro picker only when needed.
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 42.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(role = Role.Button, enabled = installedDistros.isNotEmpty()) {
                    if (installedDistros.size == 1) onNewTerminal(installedDistros.single())
                    else showingDistroChoices = !showingDistroChoices
                }
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("+", color = accent, fontSize = 19.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("New terminal", color = Color(0xFFF1F0EC), style = MaterialTheme.typography.labelLarge)
                Text(
                    when (installedDistros.size) {
                        0 -> "No installed distro yet"
                        1 -> installedDistros.single().distroName
                        else -> if (showingDistroChoices) "Pick a distro" else "${installedDistros.size} distros installed"
                    },
                    color = Color(0xFF85847F), style = MaterialTheme.typography.labelSmall,
                )
            }
            if (installedDistros.size > 1) {
                Text(if (showingDistroChoices) "−" else "＋", color = Color.White.copy(alpha = 0.64f), fontSize = 15.sp)
            }
        }

        AnimatedVisibility(
            visible = showingDistroChoices && installedDistros.size > 1,
            enter = newTerminalEnter,
            exit = newTerminalExit,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                installedDistros.forEach { distro ->
                    val distroAccent = distroAccentColor(distro.distroId)
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { onNewTerminal(distro) }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(distro.distroName, Modifier.weight(1f), color = Color(0xFFE4E2DC),
                            style = MaterialTheme.typography.labelMedium)
                        Text(
                            if (distro.distroId == distroId) "current" else distro.version,
                            color = if (distro.distroId == distroId) distroAccent else Color(0xFF85847F),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 42.dp).clickable(role = Role.Button, onClick = onSettings)
                .padding(horizontal = 6.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", color = Color(0xFFD0CEC8), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text("›", color = Color.White.copy(alpha = 0.46f), fontSize = 17.sp)
        }
        Text("Linux sessions run locally.", color = Color(0xFF85847F), style = MaterialTheme.typography.labelSmall)
    }
}
