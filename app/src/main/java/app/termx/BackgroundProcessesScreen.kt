package app.termx

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * Full-screen list of TermX-tracked background jobs, grouped by process name.
 *
 * Each group header shows the running count and a force-kill (SIGKILL) action; expanding a group
 * lists its individual jobs with PID and a per-job kill action. The background blends with the
 * wallpaper exactly like the settings page.
 */
@Composable
internal fun BackgroundProcessesScreen(
    wallpaperUri: String?,
    amoledMode: Boolean,
    onBack: () -> Unit,
) {
    val panel = RoundedCornerShape(24.dp)
    val backdrop = rememberLayerBackdrop()
    val base = if (amoledMode) Color.Black else Color(0xFF0D0F10)
    // Bumping this counter re-reads ProcessRunner after every kill action.
    var refreshTick by remember { mutableIntStateOf(0) }
    val groups = remember(refreshTick) { ProcessRunner.grouped() }
    val total = remember(refreshTick) { ProcessRunner.list().size }

    Box(Modifier.fillMaxSize().background(if (wallpaperUri != null) base.copy(alpha = 0.55f) else base)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White, fontSize = 30.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Background Processes", color = Color.White,
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("$total job(s) in ${groups.size} group(s)", color = Color(0xFF9CE6D8),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (groups.isEmpty()) {
                    Text(
                        "No background processes running.\n\nProcesses started with & or via ProcessRunner appear here grouped by name.",
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                groups.forEach { (name, jobs) ->
                    Column(
                        Modifier.fillMaxWidth().clip(panel)
                            .termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = 0.075f))
                            .padding(18.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = Color.White, style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${jobs.size}", color = Color.White.copy(alpha = 0.62f),
                                style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Kill all",
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { ProcessRunner.stopGroup(name); refreshTick++ }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color(0xFFFF91AA), style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        jobs.forEach { job ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("PID ${job.pid}", color = Color.White,
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(job.command, color = Color.White.copy(alpha = 0.62f),
                                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                                Text(
                                    "Kill",
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .clickable { ProcessRunner.stop(job.pid); refreshTick++ }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = Color(0xFFFF91AA), style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
