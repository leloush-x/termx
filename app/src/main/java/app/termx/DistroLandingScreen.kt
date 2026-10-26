package app.termx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.termx.runtime.RootfsPackage

@Composable
fun DistroLandingScreen(
    distros: List<RootfsPackage>,
    installedDistros: Set<String>,
    selectedDistroId: String,
    installingDistroId: String?,
    onSelectDistro: (String) -> Unit,
    isInstalling: Boolean,
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
    val theme = remember(selected.distroId) { distroPalette(selected.distroId) }
    val transition = rememberInfiniteTransition(label = "ambient-glow")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow-drift",
    )
    val animatedAccent by animateColorAsState(theme.accent, label = "distro-accent")
    val installed = selected.distroId in installedDistros
    val currentInstall = isInstalling
    val anotherInstallIsBusy = installingDistroId != null && installingDistroId != selected.distroId

    Box(Modifier.fillMaxSize().background(Color(0xFF090C13))) {
        Canvas(Modifier.fillMaxSize()) {
            val first = Offset(size.width * (0.18f + drift * 0.13f), size.height * 0.19f)
            val second = Offset(size.width * (0.84f - drift * 0.11f), size.height * 0.72f)
            drawRect(Brush.verticalGradient(listOf(Color(0xFF101522), Color(0xFF090C13), Color(0xFF0A0D14))))
            drawCircle(Brush.radialGradient(listOf(theme.accent.copy(alpha = 0.21f), Color.Transparent), first, size.minDimension * 0.62f),
                radius = size.minDimension * 0.62f, center = first)
            drawCircle(Brush.radialGradient(listOf(theme.secondary.copy(alpha = 0.13f), Color.Transparent), second, size.minDimension * 0.58f),
                radius = size.minDimension * 0.58f, center = second)
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(animatedAccent, theme.secondary))),
                    contentAlignment = Alignment.Center,
                ) { Text("✦", color = Color(0xFF101116), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Text("TERM X", style = MaterialTheme.typography.labelLarge, letterSpacing = 2.2.sp, color = Color.White.copy(alpha = 0.92f))
                Spacer(Modifier.weight(1f))
                Text("ROOTLESS  ·  PRIVATE", style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.48f), letterSpacing = 1.2.sp)
            }

            Spacer(Modifier.weight(0.62f))
            Text("A LITTLE LINUX WORLD", style = MaterialTheme.typography.labelMedium,
                color = animatedAccent, letterSpacing = 2.4.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Linux,\nshaped your way.",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 39.sp, lineHeight = 43.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text("A quiet, capable terminal. Pick a world and make it yours.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.62f), textAlign = TextAlign.Center)

            Spacer(Modifier.height(30.dp))
            DistroGlassDock(
                distros = distros,
                selectedId = selected.distroId,
                onSelect = onSelectDistro,
                palette = theme,
            )

            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp).animateContentSize(),
                shape = RoundedCornerShape(30.dp),
                color = Color.White.copy(alpha = 0.065f),
                border = BorderStroke(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), animatedAccent.copy(alpha = 0.14f), Color.White.copy(alpha = 0.05f)))),
                shadowElevation = 18.dp,
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(15.dp))
                            .background(Brush.linearGradient(listOf(animatedAccent.copy(alpha = 0.95f), theme.secondary.copy(alpha = 0.78f)))),
                            contentAlignment = Alignment.Center) {
                            Text(theme.mark, color = Color(0xFF101116), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selected.distroName, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(theme.caption, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.59f))
                        }
                        Surface(shape = CircleShape, color = if (installed) animatedAccent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.075f)) {
                            Text(if (installed) "READY" else "ON DEMAND", Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.labelSmall, color = if (installed) animatedAccent else Color.White.copy(alpha = 0.62f), letterSpacing = 0.8.sp)
                        }
                    }

                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = { if (installed) onStart(selected) else onInstall(selected) },
                        enabled = !currentInstall && (installed || !anotherInstallIsBusy),
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = animatedAccent, contentColor = Color(0xFF101116),
                            disabledContainerColor = animatedAccent.copy(alpha = 0.42f)),
                    ) {
                        Text(when { currentInstall -> "Preparing ${selected.distroName}…"; installed -> "Open ${selected.distroName}"; else -> "Install ${selected.distroName}" },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (currentInstall) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = animatedAccent,
                            trackColor = Color.White.copy(alpha = 0.12f))
                    }
                    val visibleStatus = status ?: if (anotherInstallIsBusy) {
                        "${distros.firstOrNull { it.distroId == installingDistroId }?.distroName ?: "A distro"} is downloading in the background."
                    } else null
                    if (!visibleStatus.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(visibleStatus, color = Color.White.copy(alpha = 0.66f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                LandingAction("⌘", "Commands", onCommands)
                LandingAction("▦", "Files", onFiles)
                LandingAction("◉", "Profiles", onProfiles)
                LandingAction("⌁", "Storage", onStorage)
            }
            Spacer(Modifier.weight(0.58f))
            Text("NO ROOT  ·  NO SURPRISES  ·  JUST LINUX", style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.36f), letterSpacing = 1.1.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DistroGlassDock(
    distros: List<RootfsPackage>,
    selectedId: String,
    onSelect: (String) -> Unit,
    palette: DistroPalette,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.White.copy(alpha = 0.055f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 12.dp,
    ) {
        Row(Modifier.padding(7.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            distros.forEach { distro ->
                val selected = distro.distroId == selectedId
                val tint = remember(distro.distroId) { distroPalette(distro.distroId) }
                val fill by animateColorAsState(if (selected) tint.accent.copy(alpha = 0.19f) else Color.Transparent, label = "dock-selection")
                Row(
                    Modifier.defaultMinSize(minHeight = 46.dp).clip(RoundedCornerShape(18.dp))
                        .background(fill)
                        .then(if (selected) Modifier.border(1.dp, tint.accent.copy(alpha = 0.32f), RoundedCornerShape(18.dp)) else Modifier)
                        .clickable { onSelect(distro.distroId) }
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(tint.mark, color = if (selected) tint.accent else Color.White.copy(alpha = 0.62f), fontSize = 14.sp)
                    Text(distro.distroName, style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Color.White else Color.White.copy(alpha = 0.58f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun LandingAction(mark: String, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.055f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.085f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mark, color = Color.White.copy(alpha = 0.74f), fontSize = 14.sp)
            Text(label, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private data class DistroPalette(val accent: Color, val secondary: Color, val mark: String, val caption: String)

private fun distroPalette(id: String) = when (id) {
    "alpine" -> DistroPalette(Color(0xFF83C7FF), Color(0xFF98A7FF), "▲", "Small, secure, and wonderfully quick")
    "debian" -> DistroPalette(Color(0xFFFF91AA), Color(0xFFC6A0FF), "◉", "The dependable classic, ready to explore")
    "void" -> DistroPalette(Color(0xFF83E0BE), Color(0xFF77C9D5), "◌", "Independent, minimal, and yours to shape")
    else -> DistroPalette(Color(0xFFFFB17A), Color(0xFFFF7FA4), "🐙", "A tiny, modern Linux world in your pocket")
}
