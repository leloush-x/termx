package app.termx

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.termx.runtime.RootfsPackage
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
internal fun DistroSettingsScreen(
    distros: List<RootfsPackage>,
    installedDistros: Set<String>,
    defaultDistroId: String,
    installingDistroId: String?,
    installProgress: Float,
    installStatus: String?,
    amoledMode: Boolean,
    onAmoledMode: (Boolean) -> Unit,
    terminalFontSize: Int,
    onFontSize: (Int) -> Unit,
    onImportFont: () -> Unit,
    wallpaperUri: String?,
    wallpaperBlur: Float,
    wallpaperDim: Float,
    onPickWallpaper: () -> Unit,
    onWallpaperBlur: (Float) -> Unit,
    onWallpaperDim: (Float) -> Unit,
    onClearWallpaper: () -> Unit,
    onGitHubLogin: (String) -> Unit,
    onGitHubLogout: () -> Unit,
    onKillBackgroundJobs: () -> Unit,
    onOpenBackgroundProcesses: () -> Unit,
    customizationMessage: String?,
    onBack: () -> Unit,
    onSetDefault: (String) -> Unit,
    onInstall: (RootfsPackage) -> Unit,
    onOpenStorage: () -> Unit,
) {
    val panel = RoundedCornerShape(24.dp)
    val backdrop = rememberLayerBackdrop()
    val base = if (amoledMode) Color.Black else Color(0xFF0D0F10)
    var searchQuery by remember { mutableStateOf("") }
    val query = searchQuery.trim().lowercase()
    fun matches(vararg terms: String): Boolean = query.isBlank() || terms.any { it.contains(query, ignoreCase = true) }
    Box(Modifier.fillMaxSize().background(if (wallpaperUri != null) base.copy(alpha = 0.55f) else base)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width * 0.82f, size.height * 0.16f)
                drawRect(base)
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFF7D3050).copy(alpha = 0.38f), Color.Transparent), center, size.minDimension * 0.72f),
                    radius = size.minDimension * 0.72f,
                    center = center,
                )
            }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color.White, fontSize = 30.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Settings", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                Text("Make your Linux space yours", color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        // Search / filter
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search settings", color = Color.White.copy(alpha = 0.5f)) },
            leadingIcon = { Text("🔍", color = Color.White.copy(alpha = 0.6f)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = 0.4f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            ),
        )

        Column(
            Modifier.fillMaxWidth().clip(panel)
                .termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = 0.075f))
                .padding(18.dp),
        ) {
            Text("APPEARANCE & INPUT", color = Color.White.copy(alpha = 0.54f), style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.25.sp)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AMOLED black", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text("Use a true black app and terminal surface", color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = amoledMode, onCheckedChange = onAmoledMode)
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Text size", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text("Terminal font size · ${terminalFontSize}sp", color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Slider(
                value = terminalFontSize.toFloat(),
                onValueChange = { onFontSize(it.toInt()) },
                valueRange = 12f..28f,
                steps = 15,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
            SettingsAction(if (wallpaperUri == null) "Choose background image" else "Change background image", onPickWallpaper)
            if (wallpaperUri != null) {
                SettingsAction("Remove background image", onClearWallpaper)
                Text("Blur · ${wallpaperBlur.toInt()}dp", Modifier.padding(top = 10.dp),
                    color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = wallpaperBlur,
                    onValueChange = onWallpaperBlur,
                    valueRange = 0f..32f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Dim · ${(wallpaperDim * 100).toInt()}%", Modifier.padding(top = 4.dp),
                    color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = wallpaperDim,
                    onValueChange = onWallpaperDim,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SettingsAction("Import TTF font", onImportFont)
            customizationMessage?.let { Text(it, Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall) }
        }

        Column(
            Modifier.fillMaxWidth().clip(panel)
                .termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = 0.075f))
                .padding(18.dp),
        ) {
            Text("PROCESSES", color = Color.White.copy(alpha = 0.54f), style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.25.sp)
            Spacer(Modifier.height(8.dp))
            val bgCount = remember { ProcessRunner.list().size }
            Text(if (bgCount == 0) "No background jobs running" else "$bgCount background job(s) running",
                color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            SettingsAction("View background processes", onOpenBackgroundProcesses)
            SettingsAction("Stop all background jobs", onKillBackgroundJobs)
        }

        Column(
            Modifier.fillMaxWidth().clip(panel)
                .termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = 0.075f))
                .padding(18.dp),
        ) {
            Text("GITHUB", color = Color.White.copy(alpha = 0.54f), style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.25.sp)
            Spacer(Modifier.height(8.dp))
            Text("Paste a GitHub token and login in the current terminal", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            var ghToken by remember { mutableStateOf("") }
            OutlinedTextField(
                value = ghToken,
                onValueChange = { ghToken = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("GitHub token (ghp_...)", color = Color.White.copy(alpha = 0.5f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White.copy(alpha = 0.4f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                ),
            )
            Spacer(Modifier.height(12.dp))
            SettingsAction("Login with GitHub", { onGitHubLogin(ghToken) })
            SettingsAction("Logout from GitHub", onGitHubLogout)
        }

        Column(
            Modifier.fillMaxWidth().clip(panel)
                .termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = 0.075f))
                .padding(18.dp),
        ) {
            Text("DEFAULT DISTRO", color = Color.White.copy(alpha = 0.54f), style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.25.sp)
            Spacer(Modifier.height(8.dp))
            Text("Opens when you start TermX", color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            distros.forEach { distro ->
                val isDefault = distro.distroId == defaultDistroId
                val isInstalled = distro.distroId in installedDistros
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
                        .background(if (isDefault) Color.White.copy(alpha = 0.09f) else Color.Transparent)
                        .clickable(enabled = isInstalled) { onSetDefault(distro.distroId) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(distro.distroName, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(if (isInstalled) distro.version else "Download to use",
                            color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        if (isDefault) "DEFAULT" else if (isInstalled) "SET" else "",
                        color = if (isDefault) Color(0xFFFFA5C2) else Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(panel)
                .termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = 0.075f))
                .padding(18.dp),
        ) {
            Text("LINUX WORLDS", color = Color.White.copy(alpha = 0.54f), style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.25.sp)
            Spacer(Modifier.height(5.dp))
            Text("Choose what to install", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            distros.forEach { distro ->
                val installed = distro.distroId in installedDistros
                val installing = installingDistroId == distro.distroId
                val accent = distroAccent(distro.distroId)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(9.dp).height(9.dp).background(accent, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(distro.distroName, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Text(if (installed) "Installed" else distro.version, color = Color.White.copy(alpha = 0.58f),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!installed && !installing) {
                        Text("Download", Modifier.clip(CircleShape).clickable { onInstall(distro) }
                            .border(1.dp, accent.copy(alpha = 0.55f), CircleShape)
                            .padding(horizontal = 13.dp, vertical = 8.dp), color = accent,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (installing) {
                    LinearProgressIndicator(
                        progress = { installProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(start = 21.dp),
                        color = accent,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                    installStatus?.let {
                        Text(it, Modifier.padding(start = 21.dp, top = 5.dp), color = Color.White.copy(alpha = 0.62f),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(panel)
                .termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = 0.075f))
                .clickable(onClick = onOpenStorage)
                .padding(horizontal = 18.dp, vertical = 15.dp),
        ) {
            Text("Storage health", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text("Manage shared folders and access", color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall)
        }
        }
    }
}

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(vertical = 12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
}

private fun distroAccent(id: String): Color = when (id) {
    "alpine" -> Color(0xFF83C7FF)
    "debian" -> Color(0xFFFF91AA)
    "void" -> Color(0xFF83E0BE)
    else -> Color(0xFFFFB17A)
}
