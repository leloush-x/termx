package app.termx

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.termx.runtime.RootfsPackage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
fun DistroLandingScreen(
    distros: List<RootfsPackage>,
    installedDistros: Set<String>,
    selectedDistroId: String,
    installingDistroId: String?,
    amoled: Boolean,
    onSelectDistro: (String) -> Unit,
    onSettings: () -> Unit,
    progress: Float,
    status: String?,
    onInstall: (RootfsPackage) -> Unit,
    onStart: (RootfsPackage) -> Unit,
    onCommands: () -> Unit,
    onFiles: () -> Unit,
    onProfiles: () -> Unit,
    onStorage: () -> Unit,
) {
    val selected = distros.firstOrNull { it.distroId == selectedDistroId } ?: distros.firstOrNull() ?: return
    val palette = distroPalette(selected.distroId)
    val canAnimate = remember { ValueAnimator.areAnimatorsEnabled() }
    val accent by animateColorAsState(
        targetValue = palette.accent,
        animationSpec = tween(if (canAnimate) 360 else 0, easing = FastOutSlowInEasing),
        label = "selected-distro-accent",
    )
    val installed = selected.distroId in installedDistros
    val downloading = installingDistroId == selected.distroId
    val anotherDownload = installingDistroId != null && !downloading
    val backdrop = rememberLayerBackdrop()
    val base = if (amoled) Color.Black else INK
    Box(Modifier.fillMaxSize().background(base)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Box(Modifier.fillMaxSize().background(base)) {
                Canvas(Modifier.fillMaxSize()) {
                    val glowCenter = Offset(size.width * 0.5f, size.height * 0.26f)
                    val glowRadius = size.minDimension * 0.78f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = if (amoled) 0.20f else 0.26f), Color.Transparent),
                            glowCenter,
                            glowRadius,
                        ),
                        radius = glowRadius,
                        center = glowCenter,
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("termx", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "Settings",
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onSettings)
                        .defaultMinSize(minWidth = 64.dp, minHeight = 44.dp)
                        .padding(horizontal = 10.dp, vertical = 11.dp),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(Modifier.height(38.dp))
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                Text("Choose a distro", color = Color(0xFFF2F1ED),
                    style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.Medium),
                    letterSpacing = (-1.2).sp)
                Spacer(Modifier.height(9.dp))
                Text("Open an installed system or download another one.",
                    color = Color(0xFFAAA9A4), style = MaterialTheme.typography.bodyMedium, lineHeight = 21.sp)
            }

            Spacer(Modifier.height(30.dp))
            DistroRail(
                distros = distros,
                selectedId = selected.distroId,
                backdrop = backdrop,
                onSelect = onSelectDistro,
            )

            Spacer(Modifier.height(22.dp))
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(SURFACE)
                    .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(palette.mark, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(selected.distroName, color = Color(0xFFF1F0EC), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text(selected.version, color = Color(0xFF9B9A95), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (installed) "Installed" else "Not installed", color = if (installed) accent else Color(0xFFAAA9A4),
                        style = MaterialTheme.typography.labelSmall)
                }

                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(accent)
                        .clickable(role = Role.Button, enabled = !downloading && (!anotherDownload || installed)) {
                            if (installed) onStart(selected) else onInstall(selected)
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            downloading -> "Preparing ${selected.distroName}…"
                            installed -> "Open terminal"
                            anotherDownload -> "Another download is running"
                            else -> "Install ${selected.distroName}"
                        },
                        Modifier.weight(1f), color = Color(0xFF151719), style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(13.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), color = accent,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
                val statusLine = status ?: if (anotherDownload) {
                    "${distros.firstOrNull { it.distroId == installingDistroId }?.distroName ?: "A distro"} is downloading."
                } else null
                if (!statusLine.isNullOrBlank()) {
                    Spacer(Modifier.height(11.dp))
                    Text(statusLine, color = Color(0xFFB6B4AE), style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Start)
                }
            }

            Spacer(Modifier.height(25.dp))
            Row(Modifier.widthIn(max = 460.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LandingLink("Commands", onCommands, Modifier.weight(1f))
                LandingLink("Files", onFiles, Modifier.weight(1f))
                LandingLink("Profiles", onProfiles, Modifier.weight(1f))
                LandingLink("Storage", onStorage, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun DistroRail(
    distros: List<RootfsPackage>,
    selectedId: String,
    backdrop: com.kyant.backdrop.Backdrop,
    onSelect: (String) -> Unit,
) {
    val railShape = RoundedCornerShape(16.dp)
    val canAnimate = remember { ValueAnimator.areAnimatorsEnabled() }
    Box(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(railShape)
                .termxLiquidGlass(backdrop, railShape, Color(0x99212527))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 5.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            distros.forEach { distro ->
                val selected = distro.distroId == selectedId
                val palette = distroPalette(distro.distroId)
                val selectedAccent by animateColorAsState(
                    targetValue = if (selected) palette.accent else Color.Transparent,
                    animationSpec = tween(if (canAnimate) 240 else 0),
                    label = "distro-rail-selection",
                )
                Column(
                    Modifier.widthIn(min = 88.dp).defaultMinSize(minHeight = 54.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.055f) else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) { onSelect(distro.distroId) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(distro.distroName, color = if (selected) Color.White else Color(0xFFAAA9A4),
                        style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Text(if (distro.distroId == selectedId) "SELECTED" else "${distro.version}",
                        color = if (selected) palette.accent else Color(0xFF85847F),
                        style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, letterSpacing = 0.55.sp, maxLines = 1)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(selectedAccent, RoundedCornerShape(1.dp)))
                }
            }
        }
    }
}

@Composable
private fun LandingLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .semantics { contentDescription = label },
        color = Color(0xFFAAA9A4), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

private data class DistroPalette(val accent: Color, val mark: String)

private fun distroPalette(id: String) = DistroPalette(distroAccentColor(id), when (id) {
    "alpine" -> "A"
    "debian" -> "D"
    "void" -> "V"
    else -> "W"
})

internal fun distroAccentColor(id: String): Color = when (id) {
    "alpine" -> Color(0xFF79B8F3)
    "debian" -> Color(0xFFE67583)
    "void" -> Color(0xFF64C3A8)
    else -> Color(0xFFF0A45F)
}

private val INK = Color(0xFF0D0F10)
private val SURFACE = Color(0xFF171A1B)
